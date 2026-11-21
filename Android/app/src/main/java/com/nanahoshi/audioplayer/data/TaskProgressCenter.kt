package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.text.PersistedErrors
import androidx.room.withTransaction
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.TranslateMark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import java.util.concurrent.atomic.AtomicBoolean

/** AppGraph 共享实例；数据库仍然是任务是否活跃的唯一依据。 */
class TaskProgressCenter(private val db: AppDatabase) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checkpoints = Channel<ProgressCheckpoint>(Channel.UNLIMITED)
    val tracker = TaskProgressTracker(checkpoint = { key, token, part, summary ->
        checkpoints.trySend(ProgressCheckpoint(key, token, part, summary))
    })
    val snapshots = tracker.snapshots
    private val downloadWatch = AtomicBoolean()
    private val subtitleWatch = AtomicBoolean()
    private val tick = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            snapshots.collect { if (tracker.hasActive()) tick.trySend(Unit) }
        }
        scope.launch {
            for (signal in tick) {
                while (isActive && tracker.hasActive()) { delay(1000); tracker.sample() }
            }
        }
        scope.launch {
            for (item in checkpoints) {
                if (tracker.isCurrent(item.key, item.attempt)) {
                    persist(item.key, item.part, item.summary) { tracker.isCurrent(item.key, item.attempt) }
                }
            }
        }
    }
    fun begin(key: TaskKey, part: ProgressPart, phase: ProgressPhase = ProgressPhase.PREPARING,
              model: String? = null, platform: String? = null) = tracker.begin(key, part, phase, model, platform)
    fun reset(key: TaskKey) = tracker.reset(key)
    internal suspend fun close() { scope.coroutineContext[Job]?.cancelAndJoin() }

    private suspend fun persist(key: TaskKey, part: ProgressPart, value: TaskSegmentSummary, valid: () -> Boolean = { true }) {
        db.withTransaction {
            if (!valid()) return@withTransaction
            val old = when (key.kind) {
                TaskKind.DOWNLOAD -> db.downloads().get(key.id)?.progressSummary
                TaskKind.WHISPER -> db.whisper().get(key.id)?.progressSummary
                TaskKind.TRANSLATION -> db.translations().get(key.id)?.progressSummary
            }
            val encoded = decodeProgressSummary(old).merge(part, value).encode()
            when (key.kind) {
                TaskKind.DOWNLOAD -> db.downloads().setProgressSummary(key.id, encoded)
                TaskKind.WHISPER -> db.whisper().setProgressSummary(key.id, encoded)
                TaskKind.TRANSLATION -> db.translations().setProgressSummary(key.id, encoded)
            }
        }
    }
    suspend fun interrupted(key: TaskKey, part: ProgressPart, phase: ProgressPhase? = null) {
        tracker.reset(key)
        val raw = when (key.kind) {
            TaskKind.DOWNLOAD -> db.downloads().get(key.id)?.progressSummary
            TaskKind.WHISPER -> db.whisper().get(key.id)?.progressSummary
            TaskKind.TRANSLATION -> db.translations().get(key.id)?.progressSummary
        }
        val previous = decodeProgressSummary(raw).parts[part]
        persist(key, part, TaskSegmentSummary(phase = previous?.phase ?: phase,
            result = ProgressResult.INTERRUPTED, failurePhase = previous?.phase ?: phase,
            model = previous?.model, platform = previous?.platform))
    }
    suspend fun end(reporter: TaskReporter) = finishFromDb(reporter.key, reporter.part)

    suspend fun finishFromDb(key: TaskKey, part: ProgressPart, force: Boolean = false) {
        val terminal = when (key.kind) {
            TaskKind.DOWNLOAD -> db.downloads().get(key.id)?.let { job ->
                if (part == ProgressPart.BILI_SUBTITLE) null
                else terminalResult(job.status, job.error)
            }
            TaskKind.WHISPER -> db.whisper().get(key.id)?.let { job ->
                if (part == ProgressPart.TRANSLATION) when (job.translateStatus) {
                    TranslateMark.DONE -> ProgressResult.COMPLETED
                    TranslateMark.FAILED -> failureResult(job.error)
                    else -> null
                } else terminalResult(job.status, job.error)
            }
            TaskKind.TRANSLATION -> db.translations().get(key.id)?.let { terminalResult(it.status, it.error) }
        } ?: return
        val current = tracker.currentPart(key, part)
        if (current != null) {
            if (force) tracker.reconcile(key, part, terminal, true)
            else tracker.finishPart(key, part, terminal)
        } else if (force) persist(key, part, TaskSegmentSummary(result = terminal))
    }

    private data class Record(val key: TaskKey, val part: ProgressPart, val status: DownloadStatus,
                              val error: String?, val phase: ProgressPhase)
    private suspend fun watch(records: kotlinx.coroutines.flow.Flow<List<Record>>) {
        var previous = emptyMap<Pair<TaskKey, ProgressPart>, Record>()
        records.distinctUntilChanged().collect { rows ->
            val next = rows.associateBy { it.key to it.part }
            for ((id, row) in next) {
                if (previous[id] == row) continue
                if (row.status == DownloadStatus.QUEUED || row.status == DownloadStatus.RUNNING) {
                    tracker.queued(row.key, row.part, row.phase)
                } else terminalResult(row.status, row.error)?.let { tracker.reconcile(row.key, row.part, it) }
            }
            for (removed in previous.keys - next.keys) {
                if (next.keys.none { it.first.parent() == removed.first.parent() }) tracker.reset(removed.first)
                else tracker.reconcile(removed.first, removed.second, ProgressResult.CANCELLED)
            }
            previous = next
        }
    }
    fun watchDownloads() {
        if (!downloadWatch.compareAndSet(false, true)) return
        scope.launch {
            watch(db.downloads().observe().map { jobs -> jobs.flatMap { job ->
                buildList {
                    add(Record(TaskKey(TaskKind.DOWNLOAD, job.id), ProgressPart.DOWNLOAD, job.status, job.error, ProgressPhase.WAITING_SLOT))
                    if (job.status == DownloadStatus.DONE && job.pendingBiliSubtitle == true)
                        add(Record(TaskKey(TaskKind.DOWNLOAD, job.id, ProgressPart.BILI_SUBTITLE), ProgressPart.BILI_SUBTITLE,
                            DownloadStatus.QUEUED, null, ProgressPhase.WAITING_SUBTITLE))
                }
            } })
        }
    }
    fun watchSubtitles(recovery: Job) {
        if (!subtitleWatch.compareAndSet(false, true)) return
        scope.launch {
            recovery.join()
            watch(db.whisper().observe().map { jobs -> jobs.flatMap { job ->
                buildList {
                    add(Record(TaskKey(TaskKind.WHISPER, job.id), ProgressPart.TRANSCRIPTION, job.status, job.error,
                        if (job.fingerprint.isEmpty()) ProgressPhase.WAITING_AUDIO else ProgressPhase.WAITING_TRANSCRIPTION))
                    job.translateStatus?.let { state ->
                        val status = when (state) {
                            TranslateMark.QUEUED -> DownloadStatus.QUEUED
                            TranslateMark.RUNNING -> DownloadStatus.RUNNING
                            TranslateMark.DONE -> DownloadStatus.DONE
                            else -> DownloadStatus.FAILED
                        }
                        add(Record(TaskKey(TaskKind.WHISPER, job.id), ProgressPart.TRANSLATION, status, job.error,
                            ProgressPhase.WAITING_TRANSLATION))
                    }
                }
            } })
        }
        scope.launch {
            recovery.join()
            watch(db.translations().observe().map { rows -> rows.map { job ->
                Record(TaskKey(TaskKind.TRANSLATION, job.id), ProgressPart.TRANSLATION, job.status, job.error,
                    if (job.kind == SubtitleTask.POLISH.name) ProgressPhase.WAITING_POLISH else ProgressPhase.WAITING_TRANSLATION)
            } })
        }
    }
}
internal fun failureResult(error: String?): ProgressResult = when {
    PersistedErrors.isCancelled(error) -> ProgressResult.CANCELLED
    PersistedErrors.isInterrupted(error) -> ProgressResult.INTERRUPTED
    else -> ProgressResult.FAILED
}
private fun terminalResult(status: DownloadStatus, error: String?): ProgressResult? = when (status) {
    DownloadStatus.DONE -> ProgressResult.COMPLETED
    DownloadStatus.FAILED -> failureResult(error)
    else -> null
}
