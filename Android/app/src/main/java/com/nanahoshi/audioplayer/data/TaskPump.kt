package com.nanahoshi.audioplayer.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope

/** 一个调度器分配执行位置；完成后的清理也计入占用，状态变更不会提前释放位置。 */
internal suspend fun <T, K> pumpTasks(
    wake: Channel<Unit>,
    load: suspend () -> List<T>,
    key: (T) -> K,
    canStart: (T, List<T>, List<T>) -> Boolean,
    onFailure: suspend (T, Exception) -> Unit = { _, error -> throw error },
    execute: suspend (T) -> Unit,
) = supervisorScope {
    val running = linkedMapOf<K, Pair<T, Deferred<Unit>>>()
    while (true) {
        currentCoroutineContext().ensureActive()
        for ((id, entry) in running.toMap()) {
            if (!entry.second.isCompleted) continue
            try {
                entry.second.await()
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
            }
            running.remove(id)
        }
        val pending = load()
        val active = running.values.map { it.first }
        val next = pending.firstOrNull { key(it) !in running && canStart(it, pending, active) }
        if (next != null) {
            val worker = async(start = CoroutineStart.LAZY) {
                try {
                    execute(next)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    onFailure(next, error)
                }
            }
            running[key(next)] = next to worker
            worker.invokeOnCompletion { wake.trySend(Unit) }
            worker.start()
        } else {
            if (pending.isEmpty() && running.isEmpty()) break
            wake.receive()
        }
    }
}

internal data class DownloadSlot(val id: Long, val work: String?, val archive: Boolean, val exclusiveWork: Boolean = false)

internal sealed interface DownloadStartDecision {
    data object Ready : DownloadStartDecision
    data object WaitingSlot : DownloadStartDecision
    data object WaitingWork : DownloadStartDecision
}

internal fun canStartDownload(
    candidate: DownloadSlot,
    queued: List<DownloadSlot>,
    running: List<DownloadSlot>,
    limit: Int,
): Boolean = downloadStartDecision(candidate, queued, running, limit) == DownloadStartDecision.Ready

internal fun downloadStartDecision(candidate: DownloadSlot, queued: List<DownloadSlot>, running: List<DownloadSlot>, limit: Int): DownloadStartDecision {
    val work = candidate.work
    if (work != null && !candidate.exclusiveWork &&
        (running.any { it.work == work && !it.exclusiveWork && (it.archive || candidate.archive) } ||
            (!candidate.archive && queued.any { it.id < candidate.id && it.work == work && it.archive })))
        return DownloadStartDecision.WaitingWork
    if (work != null && candidate.exclusiveWork &&
        (running.any { it.work == work && it.exclusiveWork } ||
            queued.any { it.id < candidate.id && it.work == work && it.exclusiveWork })
    ) return DownloadStartDecision.WaitingWork
    if (running.size >= limit || (candidate.archive && running.any { it.archive })) return DownloadStartDecision.WaitingSlot
    return DownloadStartDecision.Ready
}
