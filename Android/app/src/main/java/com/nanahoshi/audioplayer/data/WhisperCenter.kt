package com.nanahoshi.audioplayer.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.data.db.DownloadEntity
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import com.nanahoshi.audioplayer.data.db.TranslateMark
import com.nanahoshi.audioplayer.data.db.WhisperJobEntity
import com.nanahoshi.audioplayer.playback.LoadedSubtitles
import com.nanahoshi.audioplayer.playback.SubtitleSource
import com.nanahoshi.audioplayer.text.PersistedErrors
import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.persistedErrorText
import com.nanahoshi.audioplayer.text.resolveNoUrl
import com.nanahoshi.audioplayer.text.userTextOr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import okhttp3.OkHttpClient

internal sealed interface WhisperStart {
    data object Started : WhisperStart
    data object AlreadyRunning : WhisperStart
    data object AlreadyDone : WhisperStart
    data object Unavailable : WhisperStart
    data class BackgroundUnavailable(val message: TextRef) : WhisperStart
    data class NeedModelDownload(val model: String) : WhisperStart
}

class WhisperCenter internal constructor(
    context: Context,
    internal val db: AppDatabase,
    internal val credentials: CredentialStore,
    internal val downloads: DownloadCenter,
    internal val translator: TranslateCenter,
    internal val subtitles: SubtitleSource,
    internal val offlineModels: Map<OfflineWhisperModel, OfflineWhisperModelManager>,
    internal val requests: ForegroundTaskRequests<SubtitleTask>,
    internal val recovery: Job,
    internal val subtitleBindings: SubtitleBindingStore? = null,
    internal val progress: TaskProgressCenter = translator.progress,
    http: OkHttpClient = HttpRoots.shared,
) {
    internal val appContext = context.applicationContext
    internal val calls = InflightCalls()
    internal val client = WhisperClient(credentials, calls, http)
    internal val qwenFile = QwenFileTranscriber(calls, http)
    internal val transcriber = OfflineWhisperTranscriber(appContext)
    internal val taskLock = Mutex()
    internal val workerLock = Mutex()
    internal val checkingPending = AtomicBoolean(false)
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val kick = Channel<Unit>(Channel.CONFLATED)
    internal val runners = ConcurrentHashMap<Long, Job>()
    internal val reporters = ConcurrentHashMap<Long, TaskReporter>()
    internal val translationRunners = ConcurrentHashMap<Long, Job>()
    // The write lock protects handoff and attempt changes. When needed, taskLock comes first.
    internal val attemptCounter = AtomicLong()
    internal val activeTranslationAttempt = mutableMapOf<Long, Long>()
    internal val pendingHandoff = mutableMapOf<Long, PendingHandoff>()

    internal data class PendingHandoff(val id: Long, val attempt: Long, val isTranslation: Boolean)

    internal val stagingOps = WhisperStaging(this)
    internal val matcher = WhisperSubtitleMatcher(this)
    internal val pipeline = WhisperPipeline(this)
    internal val jobRunner = WhisperJobRunner(this)

    fun observe(): Flow<List<WhisperJobEntity>> = db.whisper().observe()

    fun observeVisible(): Flow<List<WhisperJobEntity>> = db.whisper().observeVisible()

    suspend fun deleteTerminal(id: Long) {
        taskLock.withLock {
            subtitles.generatedWriteLock.withLock {
                if (runners.containsKey(id) || translationRunners.containsKey(id)) return
                val dao = db.whisper()
                if (dao.deleteFailed(id) > 0) {
                    stagingOps.sweepStaged()
                    return
                }
                dao.dismissTerminal(id, System.currentTimeMillis())
            }
        }
    }

    suspend fun clearCompletedSubtitles() {
        subtitles.generatedWriteLock.withLock {
            val now = System.currentTimeMillis()
            db.withTransaction {
                db.whisper().dismissAllCompleted(now)
                db.translations().dismissAllCompleted(now)
            }
        }
    }

    internal fun missingOfflineModel(modelId: String?): String? =
        OfflineWhisperModel.fromId(modelId)?.let { model ->
            model.id.takeUnless { offlineModels.getValue(model).isReady() }
        }

    internal suspend fun deleteOfflineModel(model: OfflineWhisperModel): Boolean = withContext(Dispatchers.IO) {
        taskLock.withLock {
            !hasActiveOfflineWhisperJob(db.whisper().active(), model) && offlineModels.getValue(model).deleteModel()
        }
    }

    /** 重试沿用任务自己的地址；只有局域网地址才需要先拿到本地网络权限。 */
    fun needsLocalNetwork(job: WhisperJobEntity): Boolean {
        val model = jobCredentialModel(job)
        if (isOnDeviceWhisperModel(model)) return false
        val base = job.endpoint ?: effectiveWhisperBase(model, credentials.whisperBaseUrl(model))
        return isLocalNetworkAddress(base)
    }

    init {
        scope.launch {
            downloads.observe().map { jobs -> jobs.map { it.id to it.status } }
                .distinctUntilChanged().collect { kick.trySend(Unit) }
        }
        scope.launch {
            observe().map { jobs -> jobs.map { it.copy(progress = 0f, error = null, translatedAt = null) } }
                .distinctUntilChanged().collect { kick.trySend(Unit) }
        }
        scope.launch { stagingOps.sweepStaged() }
    }

    internal fun resumePending() {
        if (!checkingPending.compareAndSet(false, true)) return
        scope.launch {
            try {
                recovery.join()
                if (workerLock.isLocked || db.whisper().pendingWork().isEmpty()) return@launch
                requests.submit(SubtitleTask.WHISPER) { runForegroundQueue() }.result.await()
            } catch (_: SubtitleTaskStopped) {
                // 只有应用重新进入前台时，才再次尝试接手数据库中的排队任务。
            } finally {
                checkingPending.set(false)
            }
        }
    }

    private suspend fun runForegroundQueue() = workerLock.withLock {
        coroutineContext.ensureActive()
        try {
            subtitles.generatedWriteLock.withLock { pendingHandoff.clear() }
            downloads.awaitStartup()
            drain()
        } catch (stopped: SubtitleTaskStopped) {
            failQueue(stopped.persistedError)
            throw stopped
        } catch (cancelled: CancellationException) {
            val stopped = coroutineContext.cancellationCause() as? SubtitleTaskStopped
            failQueue(stopped?.persistedError ?: SUBTITLE_INTERRUPTED)
            throw cancelled
        } catch (_: Exception) {
            failQueue(SUBTITLE_INTERRUPTED)
        }
    }

    private suspend fun failQueue(message: String) = withContext(NonCancellable) {
        taskLock.withLock {
            subtitles.generatedWriteLock.withLock {
                val pending = db.whisper().pendingWork()
                db.whisper().failActive(message)
                db.whisper().failActiveTranslations(message)
                pendingHandoff.clear()
                activeTranslationAttempt.clear()
                pending.forEach { job ->
                    if (job.status != DownloadStatus.DONE) progress.finishFromDb(TaskKey(TaskKind.WHISPER, job.id), ProgressPart.TRANSCRIPTION, true)
                    if (TranslateMark.isActive(job.translateStatus)) progress.finishFromDb(TaskKey(TaskKind.WHISPER, job.id), ProgressPart.TRANSLATION, true)
                }
            }
            stagingOps.sweepStaged()
        }
    }

    internal suspend fun resolveReadOnlyMatchTargets(
        row: QueuedTrack,
        loaded: LoadedSubtitles?,
        loadedQueueItemId: Long?,
    ): WhisperMatchTargets = matcher.resolveReadOnlyMatchTargets(row, loaded, loadedQueueItemId)

    internal suspend fun existingFile(row: QueuedTrack): File? = matcher.existingFile(row)

    internal suspend fun progressDependency(job: WhisperJobEntity): Long? = matcher.progressDependency(job)

    internal suspend fun startIfLocal(
        row: QueuedTrack,
        translate: Boolean,
        model: String,
        forceRegenerate: Boolean = false,
        requestedAt: Long = System.currentTimeMillis(),
    ): WhisperStart =
        submitStart { submission ->
            val file = existingFile(row) ?: return@submitStart WhisperStart.Unavailable
            pipeline.startWithFile(row, file, translate, model, submission, forceRegenerate, requestedAt)
        }

    internal suspend fun submitStart(
        audio: Boolean = false,
        prepare: suspend (Submission) -> WhisperStart,
    ): WhisperStart = try {
        submit(audio, prepare) { it == WhisperStart.Started }
    } catch (stopped: SubtitleTaskStopped) {
        WhisperStart.BackgroundUnavailable(stopped.userText)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        WhisperStart.BackgroundUnavailable(pipeline.preparationErrorText(error))
    }

    internal class AudioPreparation(downloads: DownloadCenter) {
        private val command = CompletableDeferred<suspend () -> Long?>()
        private val request = downloads.submitPreparation { command.await()() }

        suspend fun run(block: suspend () -> Long?): Long? {
            check(command.complete(block))
            try {
                return request.result.await()
            } catch (stopped: AudioDownloadStopped) {
                coroutineContext.ensureActive()
                throw IllegalStateException(stopped.persistedError)
            }
        }

        fun close() {
            // 未使用预留时让服务立即退出。已经交给下载服务的操作独立完成。
            command.complete { null }
        }
    }

    internal inner class Submission(val audio: AudioPreparation?) {
        private var handoff: PendingHandoff? = null

        suspend fun enqueueTranscription(write: suspend () -> Long?): Boolean =
            subtitles.generatedWriteLock.withLock {
                coroutineContext.ensureActive()
                withContext(NonCancellable) {
                    val id = write() ?: return@withContext false
                    register(id, isTranslation = false)
                    true
                }
            }

        // Called with generatedWriteLock held, in the same non-cancellable block as the queue write.
        fun register(id: Long, isTranslation: Boolean) {
            val entry = PendingHandoff(id, attemptCounter.incrementAndGet(), isTranslation)
            handoff = entry
            pendingHandoff[id] = entry
            if (isTranslation) activeTranslationAttempt[id] = entry.attempt
            kick.trySend(Unit)
        }

        suspend fun failIfUnclaimed(message: String) {
            val entry = handoff ?: return
            subtitles.generatedWriteLock.withLock {
                if (!pendingHandoff.remove(entry.id, entry)) return
                val dao = db.whisper()
                val part = if (entry.isTranslation) ProgressPart.TRANSLATION else ProgressPart.TRANSCRIPTION
                val changed = if (entry.isTranslation) {
                    if (!activeTranslationAttempt.remove(entry.id, entry.attempt)) return
                    dao.failQueuedTranslationIfUnpublished(entry.id, message)
                } else dao.failQueuedIfUnpublished(entry.id, message)
                if (changed > 0) {
                    progress.finishFromDb(TaskKey(TaskKind.WHISPER, entry.id), part, force = true)
                    if (!entry.isTranslation) stagingOps.sweepStaged()
                }
            }
        }
    }

    internal suspend fun <T> submit(audio: Boolean, prepare: suspend (Submission) -> T, queued: (T) -> Boolean): T {
        val prepared = CompletableDeferred<T>()
        // 界面只等到准备完成，服务继续持有听写队列直到音频、听写和自动翻译全部结束。
        val download = if (audio) AudioPreparation(downloads) else null
        val request = requests.submit(SubtitleTask.WHISPER) {
            val submission = Submission(download)
            var failure: Throwable? = null
            try {
                val result = try {
                    recovery.join()
                    prepare(submission)
                } finally {
                    download?.close()
                }
                prepared.complete(result)
                if (queued(result)) runForegroundQueue()
            } catch (error: Exception) {
                failure = coroutineContext.cancellationCause() ?: error
                throw error
            } finally {
                withContext(NonCancellable) {
                    submission.failIfUnclaimed((failure as? SubtitleTaskStopped)?.persistedError ?: SUBTITLE_INTERRUPTED)
                }
            }
        }
        request.result.invokeOnCompletion { error ->
            download?.close()
            if (error != null) prepared.completeExceptionally(error)
        }
        return prepared.await()
    }

    internal suspend fun startAfterDownload(
        row: QueuedTrack,
        translate: Boolean,
        model: String,
        dlsiteMode: DlsiteDownloadMode,
        forceRegenerate: Boolean = false,
        requestedAt: Long = System.currentTimeMillis(),
    ): WhisperStart = pipeline.startAfterDownload(row, translate, model, dlsiteMode, forceRegenerate, requestedAt)

    suspend fun needsDlsiteDownloadChoice(job: WhisperJobEntity): Boolean = pipeline.needsDlsiteDownloadChoice(job)

    internal suspend fun retry(id: Long, dlsiteMode: DlsiteDownloadMode): TextRef? = pipeline.retry(id, dlsiteMode)

    internal suspend fun retryTranslation(id: Long): TextRef? = pipeline.retryTranslation(id)

    fun cancel(id: Long) {
        scope.launch {
            subtitles.generatedWriteLock.withLock {
                val job = db.whisper().get(id) ?: return@launch
                val translating = TranslateMark.isActive(job.translateStatus)
                val active = job.status == DownloadStatus.QUEUED || job.status == DownloadStatus.RUNNING
                if (!translating && !active) return@launch
                if (translating) {
                    if (db.whisper().failTranslateIfUnpublished(id, PersistedErrors.CANCELLED) == 0) return@launch
                } else db.whisper().fail(id, PersistedErrors.CANCELLED)
                calls.cancel("job-$id")
                translator.stopForWhisper(id)
                runners[id]?.cancel()
                translationRunners[id]?.cancel()
                pendingHandoff.remove(id)
                if (!translationRunners.containsKey(id)) activeTranslationAttempt.remove(id)
                progress.finishFromDb(TaskKey(TaskKind.WHISPER, id), if (translating) ProgressPart.TRANSLATION else ProgressPart.TRANSCRIPTION, true)
            }
            kick.trySend(Unit)
            stagingOps.sweepStaged()
        }
    }

    internal data class SubtitleWork(val job: WhisperJobEntity, val translate: Boolean)

    private suspend fun drain() = pumpTasks(kick,
        load = {
            matcher.adoptFinishedDownloads()
            subtitles.generatedWriteLock.withLock {
                // An existing worker can take over submissions made while it is draining.
                val pending = db.whisper().pendingWork()
                pendingHandoff.clear()
                pending.mapNotNull { job ->
                    when {
                        job.status == DownloadStatus.QUEUED || job.status == DownloadStatus.RUNNING -> SubtitleWork(job, false)
                        job.translateStatus == TranslateMark.QUEUED -> SubtitleWork(job, true)
                        else -> null
                    }
                }
            }
        },
        key = { it.job.id to it.translate },
        canStart = { work, pending, running ->
            running.none { it.translate == work.translate } &&
                (work.translate || (work.job.fingerprint.isNotBlank() && work.job.localPath != null &&
                    pending.none { it.translate && it.job.fingerprint == work.job.fingerprint } &&
                    running.none { it.translate && it.job.fingerprint == work.job.fingerprint }))
        },
        execute = { work -> jobRunner.runWork(work) },
    )

    internal fun jobCredentialModel(job: WhisperJobEntity): String? = knownWhisperModel(job.model)

    internal fun stagedFilePath(contentUri: String): String = stagingOps.stagedFilePath(contentUri)

    internal suspend fun stageContent(path: String): File? = stagingOps.stageContent(path)
}