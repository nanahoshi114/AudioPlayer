package com.nanahoshi.audioplayer.ui.components

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 串行化每个曲目的字幕时间轴微调写入，同时保持界面乐观更新。
 *
 * 字幕选择弹层不能用自身的 composition scope 调用 `setOffsetMs`：弹层一关闭写入就被取消，
 * 而每次点击都从数据库重读已落盘值会在 Room 回流前丢掉增量。这里按曲目保存一个待写入值，
 * 用长生命周期 scope 串行写盘，并始终以最新值收尾。
 *
 * 纯 Kotlin + kotlinx.coroutines 实现，不依赖 Android UI，便于 JVM 单测。
 */
internal class SubtitleOffsetWriter(
    private val scope: CoroutineScope,
    private val persist: suspend (trackId: Long, offsetMs: Long?) -> Unit,
    private val onError: (Throwable) -> Unit = {},
) {
    private class TrackState(settled: Long) {
        /** 用户最后一次要求显示的值。 */
        var desired: Long = settled

        /** 每次用户操作递增。 */
        var version: Long = 0L

        /** 已成功落盘的最高版本。 */
        var writtenVersion: Long = 0L

        /** 在 Room 追上之前覆盖已落盘值的待显示值；null 表示直接显示已落盘值。 */
        var pending: Long? = null

        /** 最近一次成功写入、但界面尚未观察到的目标值。 */
        var awaiting: Long? = null

        /** 界面最近一次提供的已落盘值。 */
        var lastSettled: Long = settled

        /** 本写入器落盘过的值，用于识别 Room 的中间态回流。 */
        val writtenTargets = mutableSetOf<Long>()

        /** 曲目换绑/解除关联时递增，使更早发起的写入收尾不再回填旧状态。 */
        var epoch: Long = 0L

        var starting: Boolean = false
        var worker: Job? = null
    }

    private val lock = Any()
    private val tracks = mutableMapOf<Long, TrackState>()
    private val _pendingOffsets = MutableStateFlow<Map<Long, Long>>(emptyMap())

    /** 尚未被已落盘值确认的乐观偏移；key 为曲目 id。 */
    val pendingOffsets: StateFlow<Map<Long, Long>> = _pendingOffsets.asStateFlow()

    /** 在已落盘值基础上叠加一次增量，立即返回新的乐观值。 */
    fun step(trackId: Long, settledOffsetMs: Long, deltaMs: Long): Long =
        change(trackId, settledOffsetMs) { current -> current + deltaMs }

    /** 归零（落盘为 null，与字幕关联表的既有约定一致）。 */
    fun reset(trackId: Long, settledOffsetMs: Long): Long = change(trackId, settledOffsetMs) { 0L }

    /**
     * 在切换或解除字幕关联时放弃尚未完成的时间轴微调，避免旧偏移写回新关联或残留在界面上。
     */
    fun clear(trackId: Long) {
        val jobToCancel = synchronized(lock) {
            val state = tracks[trackId] ?: return
            state.epoch += 1
            state.desired = 0L
            state.version = 0L
            state.writtenVersion = 0L
            state.pending = null
            state.awaiting = null
            state.lastSettled = 0L
            state.writtenTargets.clear()
            state.starting = false
            state.worker.also { state.worker = null }
        }
        jobToCancel?.cancel()
        publish()
    }

    /** 界面收到新的已落盘值时调用，用于在 Room 追上后清除覆盖。 */
    fun sync(trackId: Long, settledOffsetMs: Long) {
        synchronized(lock) {
            val state = tracks[trackId] ?: return
            reconcile(state, settledOffsetMs)
        }
        publish()
    }

    /** 返回当前应显示的偏移：有乐观值时优先，否则使用已落盘值。 */
    fun displayedOffset(trackId: Long, settledOffsetMs: Long): Long {
        val pending = synchronized(lock) {
            val state = tracks.getOrPut(trackId) { TrackState(settledOffsetMs) }
            reconcile(state, settledOffsetMs)
            state.pending
        }
        publish()
        return pending ?: settledOffsetMs
    }

    private fun change(trackId: Long, settled: Long, transform: (Long) -> Long): Long {
        val (next, start) = synchronized(lock) {
            val state = tracks.getOrPut(trackId) { TrackState(settled) }
            reconcile(state, settled)
            val value = transform(state.pending ?: settled)
            state.desired = value
            state.version += 1
            state.pending = value
            state.lastSettled = settled
            value to (state.worker?.isActive != true && !state.starting)
        }
        publish()
        if (start) launchWriter(trackId)
        return next
    }

    /**
     * 已落盘值追上、或选择被外部改掉时清除覆盖。
     * Room 回流可能先给出本次写入之前的旧值或中间值，这些值不会被视为外部变化。
     */
    private fun reconcile(state: TrackState, settled: Long) {
        val pending = state.pending
        if (pending == null) {
            state.lastSettled = settled
            return
        }
        if (settled == pending || settled == state.awaiting) {
            if (state.writtenVersion == state.version) {
                state.pending = null
                state.awaiting = null
                state.writtenTargets.clear()
            }
            state.lastSettled = settled
            return
        }
        if (settled != state.lastSettled && settled !in state.writtenTargets) {
            // 曲目重新绑定或外部重置：放弃旧覆盖，让新值生效；若仍有写入在途，
            // 递增版本使其完成后补写新的已落盘值，避免旧目标覆盖新选择。
            state.pending = null
            state.awaiting = null
            state.writtenTargets.clear()
            if (state.desired != settled) {
                state.desired = settled
                state.version += 1
            }
        }
        state.lastSettled = settled
    }

    private fun launchWriter(trackId: Long) {
        val state = synchronized(lock) {
            val current = tracks[trackId] ?: return
            if (current.starting || current.worker?.isActive == true) return
            current.starting = true
            current
        }
        val job = scope.launch { runWriter(trackId) }
        synchronized(lock) {
            state.starting = false
            if (job.isActive) state.worker = job else if (state.worker?.isActive != true) state.worker = null
        }
    }

    /** 取出下一次要写入的目标；已写完时清除 worker 并返回 null。 */
    private fun nextWrite(trackId: Long): Triple<Long, Long, Long>? = synchronized(lock) {
        val state = tracks[trackId] ?: return@synchronized null
        if (state.writtenVersion == state.version) {
            state.worker = null
            return@synchronized null
        }
        Triple(state.desired, state.version, state.epoch)
    }

    /** 记录失败；返回 (是否属于当前 epoch, 是否还有更新版本需要继续尝试)。 */
    private fun recordFailure(trackId: Long, version: Long, epoch: Long): Pair<Boolean, Boolean> = synchronized(lock) {
        val state = tracks[trackId] ?: return@synchronized false to false
        if (state.epoch != epoch) return@synchronized false to false
        state.writtenVersion = version
        val retryNewer = state.version > version
        if (!retryNewer) {
            // 回滚到界面已知的已落盘值；后续点击重新从该值起算。
            state.desired = state.lastSettled
            state.pending = null
            state.awaiting = null
            state.writtenTargets.clear()
            state.worker = null
        }
        true to retryNewer
    }

    private suspend fun runWriter(trackId: Long) {
        while (true) {
            val (target, version, epoch) = nextWrite(trackId) ?: return
            try {
                persist(trackId, target.takeIf { it != 0L })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val (currentEpoch, retryNewer) = recordFailure(trackId, version, epoch)
                if (!currentEpoch) return
                publish()
                onError(error)
                if (!retryNewer) return
                continue
            }
            val stale = synchronized(lock) {
                val state = tracks[trackId] ?: return
                if (state.epoch != epoch) return@synchronized true
                state.writtenVersion = version
                state.writtenTargets += target
                // 只有在界面仍显示本次写入的值时才等待 Room 确认，避免覆盖外部重置。
                if (state.version == version && state.pending != null) state.awaiting = target
                false
            }
            if (stale) return
            publish()
        }
    }

    private fun publish() {
        val snapshot = synchronized(lock) {
            tracks.mapNotNull { (id, state) -> state.pending?.let { id to it } }.toMap()
        }
        _pendingOffsets.value = snapshot
    }
}