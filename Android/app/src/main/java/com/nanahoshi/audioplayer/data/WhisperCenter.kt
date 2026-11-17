package com.nanahoshi.audioplayer.data

import androidx.room.withTransaction

import com.nanahoshi.audioplayer.youtube.readyYouTubeAudio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.data.db.DownloadEntity
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import com.nanahoshi.audioplayer.data.db.TranslateMark
import com.nanahoshi.audioplayer.data.db.WhisperJobEntity
import com.nanahoshi.audioplayer.playback.SubtitleCue
import com.nanahoshi.audioplayer.playback.SubtitleOrigin
import com.nanahoshi.audioplayer.playback.SubtitleSource
import com.nanahoshi.audioplayer.playback.parseGeneratedSubtitles
import com.nanahoshi.audioplayer.playback.encodeGeneratedCues
import com.nanahoshi.audioplayer.playback.writeTextAtomically
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

internal sealed interface WhisperStart {
    data object Started : WhisperStart
    data object AlreadyRunning : WhisperStart
    data object AlreadyDone : WhisperStart
    data object Unavailable : WhisperStart
    data class BackgroundUnavailable(val message: String) : WhisperStart
    data class NeedModelDownload(val model: String) : WhisperStart
}

class WhisperCenter internal constructor(
    context: Context,
    private val db: AppDatabase,
    private val credentials: CredentialStore,
    private val downloads: DownloadCenter,
    private val translator: TranslateCenter,
    private val subtitles: SubtitleSource,
    private val offlineModels: Map<OfflineWhisperModel, OfflineWhisperModelManager>,
    private val requests: ForegroundTaskRequests<SubtitleTask>,
    private val recovery: Job,
) {
    private val appContext = context.applicationContext
    private val calls = InflightCalls()
    private val client = WhisperClient(credentials, calls)
    private val qwenFile = QwenFileTranscriber(calls)
    private val transcriber = OfflineWhisperTranscriber(appContext)
    private val taskLock = Mutex()
    private val workerLock = Mutex()
    private val checkingPending = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val kick = Channel<Unit>(Channel.CONFLATED)
    private val stagedRoot = File(appContext.filesDir, "whisper-src")
    private val staging = ConcurrentHashMap<String, Int>()
    private val runners = ConcurrentHashMap<Long, Job>()

    fun observe(): Flow<List<WhisperJobEntity>> = db.whisper().observe()

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
            downloads.observe().collect { kick.trySend(Unit) }
        }
        scope.launch { sweepStaged() }
    }

    internal fun resumePending() {
        if (!checkingPending.compareAndSet(false, true)) return
        scope.launch {
            try {
                recovery.join()
                if (workerLock.isLocked || db.whisper().active().isEmpty()) return@launch
                requests.submit(SubtitleTask.WHISPER) { runForegroundQueue() }.result.await()
            } catch (_: SubtitleTaskStopped) {
                // 只有应用重新进入前台时，才再次尝试接手数据库中的排队任务。
            } finally {
                checkingPending.set(false)
            }
        }
    }

    private suspend fun runForegroundQueue() = workerLock.withLock {
        try {
            downloads.awaitStartup()
            do {
                adoptFinishedDownloads()
                drain()
                if (db.whisper().active().isEmpty()) break
                kick.receive()
            } while (true)
        } catch (stopped: SubtitleTaskStopped) {
            failQueue(stopped.message ?: SUBTITLE_INTERRUPTED)
            throw stopped
        } catch (cancelled: CancellationException) {
            val stopped = coroutineContext.cancellationCause() as? SubtitleTaskStopped
            if (stopped != null) failQueue(stopped.message ?: SUBTITLE_INTERRUPTED)
            throw cancelled
        } catch (_: Exception) {
            failQueue(SUBTITLE_INTERRUPTED)
        }
    }

    private suspend fun failQueue(message: String) = withContext(NonCancellable) {
        taskLock.withLock {
            db.whisper().failActive(message)
            sweepStaged()
        }
    }

    private suspend fun existingFile(row: QueuedTrack): File? = withContext(Dispatchers.IO) {
        val track = downloads.libraryTrack(row)
        materialize(track?.localUri)?.let { return@withContext it }
        materialize(row.localUri)?.let { return@withContext it }
        // 从下载管理播放的 B 站下载音轨没有 trackId，本地路径放在 remoteUrl。
        materialize(row.remoteUrl?.takeIf { it.startsWith("/") })?.let { return@withContext it }
        if (row.source == TrackSource.BILIBILI) {
            readyBiliAudio(appContext.filesDir, row.bvid ?: row.workno, row.cid ?: row.fileKey?.toLongOrNull())
                ?.let { return@withContext it }
        }
        if (row.source == TrackSource.YOUTUBE) {
            readyYouTubeAudio(appContext.filesDir, row.youtubeVideoId ?: row.workno)?.let { return@withContext it }
        }
        val url = row.remoteUrl
        if (!url.isNullOrBlank()) {
            val done = db.downloads().latestForUrl(url)
            if (done?.status == DownloadStatus.DONE) {
                materialize(done.localPath)?.let { return@withContext it }
            }
        }
        val workKey = row.workno
        if ((row.source == TrackSource.ASMR || row.source == TrackSource.DLSITE) && !workKey.isNullOrBlank()) {
            materialize(downloads.savedRemotePath(row.source, workKey, row.fileKey))
        } else {
            null
        }
    }

    internal suspend fun startIfLocal(row: QueuedTrack, translate: Boolean, model: String): WhisperStart =
        submitStart {
            val file = existingFile(row) ?: return@submitStart WhisperStart.Unavailable
            startWithFile(row, file, translate, model)
        }

    private suspend fun submitStart(
        audio: Boolean = false,
        prepare: suspend (AudioPreparation?) -> WhisperStart,
    ): WhisperStart = try {
        submit(audio, prepare) { it == WhisperStart.Started }
    } catch (stopped: SubtitleTaskStopped) {
        WhisperStart.BackgroundUnavailable(stopped.message ?: SUBTITLE_INTERRUPTED)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        WhisperStart.BackgroundUnavailable(error.message?.takeIf {
            it == DOWNLOAD_LIBRARY_REQUIRED || it == AUDIO_DOWNLOAD_START_FAILED || it == AUDIO_DOWNLOAD_TIMEOUT
        } ?: "无法准备字幕任务，请重试")
    }

    private class AudioPreparation(downloads: DownloadCenter) {
        private val command = CompletableDeferred<suspend () -> Long?>()
        private val request = downloads.submitPreparation { command.await()() }

        suspend fun run(block: suspend () -> Long?): Long? {
            check(command.complete(block))
            try {
                return request.result.await()
            } catch (stopped: AudioDownloadStopped) {
                coroutineContext.ensureActive()
                throw IllegalStateException(stopped.message)
            }
        }

        fun close() {
            // 未使用预留时让服务立即退出。已经交给下载服务的操作独立完成。
            command.complete { null }
        }
    }

    private suspend fun <T> submit(audio: Boolean, prepare: suspend (AudioPreparation?) -> T, queued: (T) -> Boolean): T {
        val prepared = CompletableDeferred<T>()
        // 界面只等到准备完成，服务继续持有听写队列直到音频、听写和自动翻译全部结束。
        val download = if (audio) AudioPreparation(downloads) else null
        val request = requests.submit(SubtitleTask.WHISPER) {
            val result = try {
                recovery.join()
                prepare(download)
            } finally {
                download?.close()
            }
            prepared.complete(result)
            if (queued(result)) runForegroundQueue()
        }
        request.result.invokeOnCompletion { error ->
            download?.close()
            if (error != null) prepared.completeExceptionally(error)
        }
        return prepared.await()
    }

    private suspend fun startWithFile(row: QueuedTrack, file: File, translate: Boolean, model: String): WhisperStart {
        try {
            if (!file.isFile) return WhisperStart.Unavailable
            val fingerprint = sha256(file)
            return taskLock.withLock {
                startLocked(
                    title = row.title.ifBlank { "音频" },
                    file = file,
                    trackId = row.trackId?.takeIf { it > 0L },
                    remoteUrl = row.whisperRemoteIdentity(),
                    skipTranslate = !translate,
                    fingerprint = fingerprint,
                    model = model,
                )
            }
        } finally {
            releaseStaged(file.absolutePath)
            withContext(NonCancellable) { sweepStaged() }
        }
    }

    internal suspend fun startAfterDownload(
        row: QueuedTrack,
        translate: Boolean,
        model: String,
        dlsiteMode: DlsiteDownloadMode,
    ): WhisperStart = submitStart(audio = true) { preparation ->
        val track = downloads.libraryTrack(row) ?: return@submitStart WhisperStart.BackgroundUnavailable(DOWNLOAD_LIBRARY_REQUIRED)
        val savedRow = row.copy(trackId = track.id)
        val archive = track.source == TrackSource.DLSITE &&
            (dlsiteMode == DlsiteDownloadMode.ARCHIVE || track.fileHash.isNullOrBlank())
        val ready = if (archive) materialize(downloads.originalPathForTrack(track.id)) else existingFile(savedRow)
        if (ready != null) return@submitStart startWithFile(savedRow, ready, translate, model)
        taskLock.withLock {
            missingOfflineModel(model)?.let { return@withLock WhisperStart.NeedModelDownload(it) }
            if (db.whisper().activeForTrack(track.id) != null) return@withLock WhisperStart.AlreadyRunning
            val download = checkNotNull(preparation).run {
                if (archive) {
                    val workno = downloads.requestWorkno(track) ?: error(DOWNLOAD_LIBRARY_REQUIRED)
                    downloads.enqueueDlsiteArchive(workno, row.title).id
                } else downloads.enqueueTrack(track.id).id
            }
            if (download == null) {
                val file = existingFile(savedRow) ?: return@withLock WhisperStart.Unavailable
                try {
                    return@withLock startLocked(row.title, file, track.id, savedRow.whisperRemoteIdentity(), !translate, sha256(file), model)
                } finally {
                    releaseStaged(file.absolutePath)
                    sweepStaged()
                }
            }
            db.whisper().insert(waiting(row.title, track.id, savedRow.whisperRemoteIdentity(), !translate, model).copy(
                awaitDownloadId = download, awaitFileKey = if (archive) rawFileKey(track.fileHash) else null,
            ))
            kick.trySend(Unit)
            WhisperStart.Started
        }
    }

    suspend fun needsDlsiteDownloadChoice(job: WhisperJobEntity): Boolean = withContext(Dispatchers.IO) {
        if (!job.localPath.isNullOrBlank() && File(job.localPath).isFile) return@withContext false
        val track = job.trackId?.let { db.tracks().getById(it) }
        if (track != null && LocalAudio.hasStoredCopy(track.localUri)) return@withContext false
        if (track?.source == TrackSource.DLSITE) return@withContext true
        job.awaitDownloadId?.let { db.downloads().get(it) }?.let {
            return@withContext it.source == TrackSource.DLSITE.name
        }
        val url = job.awaitRemoteUrl ?: return@withContext false
        db.downloads().latestForUrl(url)?.let { it.source == TrackSource.DLSITE.name && !it.fileKey.isNullOrBlank() } ?: false
    }

    internal suspend fun retry(id: Long, dlsiteMode: DlsiteDownloadMode): String? = try {
        submit(
            audio = true,
            prepare = { preparation ->
                taskLock.withLock {
                    val job = db.whisper().get(id) ?: return@withLock null
                    if (job.status != DownloadStatus.FAILED) return@withLock null
                    missingOfflineModel(job.model)?.let { return@withLock missingOfflineWhisperModelMessage(it) }
                    retryLocked(job, dlsiteMode, checkNotNull(preparation))
                    null
                }
            },
            queued = { it == null },
        )
    } catch (stopped: SubtitleTaskStopped) {
        stopped.message ?: SUBTITLE_INTERRUPTED
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        error.message?.takeIf { it == DOWNLOAD_LIBRARY_REQUIRED || it == AUDIO_DOWNLOAD_START_FAILED || it == AUDIO_DOWNLOAD_TIMEOUT }
            ?: "无法准备字幕任务，请重试"
    }

    private suspend fun retryLocked(job: WhisperJobEntity, dlsiteMode: DlsiteDownloadMode, preparation: AudioPreparation) {
        val path = job.localPath
        if (dlsiteMode != DlsiteDownloadMode.ARCHIVE && !path.isNullOrBlank() && File(path).isFile && job.fingerprint.isNotBlank()) {
            val staged = isStagedPath(path)
            if (staged) holdStaged(path)
            try {
                db.whisper().requeueWithFile(
                    job.id,
                    job.title,
                    job.fingerprint,
                    job.trackId,
                    path,
                    job.awaitRemoteUrl,
                    job.endpoint,
                    job.skipTranslate,
                    job.model,
                )
            } finally {
                if (staged) releaseStaged(path)
            }
            kick.trySend(Unit)
            return
        }
        val restored = if (dlsiteMode == DlsiteDownloadMode.ARCHIVE) null else restoreLocalCopy(job)
        if (restored != null) {
            try {
                db.whisper().requeueWithFile(
                    job.id,
                    job.title,
                    sha256(restored),
                    job.trackId,
                    restored.absolutePath,
                    job.awaitRemoteUrl,
                    job.endpoint,
                    job.skipTranslate,
                    job.model,
                )
            } finally {
                releaseStaged(restored.absolutePath)
                sweepStaged()
            }
            kick.trySend(Unit)
            return
        }
        val oldDownload = job.awaitDownloadId?.let { db.downloads().get(it) } ?: relatedDownload(job)
        val selectedTrack = job.trackId?.let { db.tracks().getById(it) }
            ?: db.dlsiteOriginals().let { originals ->
                val workno = oldDownload?.workKey
                val key = job.awaitFileKey?.let(::rawFileKey)
                if (oldDownload?.kind == "DLSITE_ARCHIVE" && workno != null && key != null) {
                    originals.entryForPlay(workno, key)?.trackId?.let { db.tracks().getById(it) }
                } else null
            }
        val id = preparation.run {
            val track = selectedTrack ?: oldDownload?.let { downloads.retryTrack(it) }
                ?: error(DOWNLOAD_LIBRARY_REQUIRED)
            if (track.source == TrackSource.DLSITE &&
                (dlsiteMode == DlsiteDownloadMode.ARCHIVE || track.fileHash.isNullOrBlank())) {
                downloads.enqueueDlsiteArchive(downloads.requestWorkno(track) ?: error(DOWNLOAD_LIBRARY_REQUIRED), job.title).id
            } else if (oldDownload?.status == DownloadStatus.FAILED && oldDownload.kind != "DLSITE_ARCHIVE") {
                downloads.retry(oldDownload.id)
            } else downloads.enqueueTrack(track.id).id
        }
        if (id == null) {
            db.whisper().fail(job.id, "找不到音频文件，请回播放页重试")
        } else {
            db.withTransaction {
                db.whisper().setAwaitDownload(job.id, id, job.awaitFileKey ?: selectedTrack?.fileHash?.let(::rawFileKey))
                db.whisper().requeueForDownload(job.id)
            }
        }
        kick.trySend(Unit)
    }

    fun cancel(id: Long) {
        scope.launch {
            subtitles.generatedWriteLock.withLock {
                val job = db.whisper().get(id) ?: return@launch
                val translating = job.translateStatus == TranslateMark.RUNNING
                val active = job.status == DownloadStatus.QUEUED || job.status == DownloadStatus.RUNNING
                if (!translating && !active) return@launch
                if (translating) {
                    if (db.whisper().failTranslateIfUnpublished(id, "已中止") == 0) return@launch
                } else db.whisper().fail(id, "已中止")
                calls.cancel("job-$id")
                translator.stopForWhisper(id)
                runners[id]?.cancel()
            }
            kick.trySend(Unit)
            sweepStaged()
        }
    }

    private suspend fun startLocked(
        title: String,
        file: File,
        trackId: Long?,
        remoteUrl: String?,
        skipTranslate: Boolean,
        fingerprint: String,
        model: String,
    ): WhisperStart {
        if (!file.isFile) return WhisperStart.Unavailable
        missingOfflineModel(model)?.let { return WhisperStart.NeedModelDownload(it) }
        if (trackId != null && db.whisper().activeForTrack(trackId) != null) return WhisperStart.AlreadyRunning
        if (trackId == null && remoteUrl != null && db.whisper().activeForUrl(remoteUrl) != null) {
            return WhisperStart.AlreadyRunning
        }
        val endpoint = effectiveWhisperBase(model, credentials.whisperBaseUrl(model))
        val prior = db.whisper().forFingerprint(fingerprint)
        val busy = prior.filter {
            (it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING) &&
                sameWhisperEndpoint(it.endpoint, endpoint, model) &&
                whisperModelsMatch(it.model, model)
        }
        val latestDone = prior.lastOrNull { it.status == DownloadStatus.DONE }
        if (latestDone != null && canReuseCues(fingerprint, model, endpoint, latestDone)) {
            if (latestDone.translateStatus == TranslateMark.RUNNING) return WhisperStart.AlreadyRunning
            if (!skipTranslate && translator.readyToTranslate(fingerprint) && busy.isNotEmpty()) {
                return WhisperStart.AlreadyRunning
            }
            rememberDone(title, fingerprint, file, trackId, remoteUrl, listOf(latestDone), endpoint, model)
            return WhisperStart.AlreadyDone
        }
        if (busy.isNotEmpty()) {
            return if (rememberPending(title, fingerprint, file, trackId, remoteUrl, busy, endpoint, skipTranslate, model)) {
                WhisperStart.Started
            } else {
                WhisperStart.AlreadyRunning
            }
        }
        val failed = prior.lastOrNull {
            it.status == DownloadStatus.FAILED && sameWhisperEndpoint(it.endpoint, endpoint, model) &&
                whisperModelsMatch(it.model, model) &&
                (trackId == null || it.trackId == trackId)
        }
        if (failed != null) {
            db.whisper().requeueWithFile(
                failed.id,
                title,
                fingerprint,
                trackId ?: failed.trackId,
                file.absolutePath,
                remoteUrl ?: failed.awaitRemoteUrl,
                endpoint,
                skipTranslate,
                model,
            )
            kick.trySend(Unit)
            return WhisperStart.Started
        }
        db.whisper().insert(uploadJob(title, fingerprint, trackId, file.absolutePath, remoteUrl, endpoint, skipTranslate, model))
        kick.trySend(Unit)
        return WhisperStart.Started
    }

    private suspend fun rememberDone(
        title: String,
        fingerprint: String,
        file: File,
        trackId: Long?,
        remoteUrl: String?,
        prior: List<WhisperJobEntity>,
        endpoint: String?,
        model: String,
    ) {
        val done = prior.filter { it.status == DownloadStatus.DONE }
        val covered = done.any { covers(it, trackId, remoteUrl, file.absolutePath) }
        if (covered) return
        val sample = done.last()
        db.whisper().insert(
            WhisperJobEntity(
                title = title,
                fingerprint = fingerprint,
                trackId = trackId,
                localPath = file.absolutePath,
                remoteJobId = sample.remoteJobId,
                status = DownloadStatus.DONE,
                progress = 1f,
                error = null,
                awaitRemoteUrl = remoteUrl,
                endpoint = endpoint,
                model = model,
            ),
        )
    }

    private suspend fun rememberPending(
        title: String,
        fingerprint: String,
        file: File,
        trackId: Long?,
        remoteUrl: String?,
        prior: List<WhisperJobEntity>,
        endpoint: String?,
        skipTranslate: Boolean,
        model: String,
    ): Boolean {
        val covered = prior.any {
            it.status != DownloadStatus.FAILED && covers(it, trackId, remoteUrl, file.absolutePath)
        }
        if (covered) return false
        db.whisper().insert(uploadJob(title, fingerprint, trackId, file.absolutePath, remoteUrl, endpoint, skipTranslate, model))
        kick.trySend(Unit)
        return true
    }

    private fun covers(job: WhisperJobEntity, trackId: Long?, remoteUrl: String?, path: String): Boolean {
        if (trackId != null && job.trackId == trackId) return true
        if (trackId == null && remoteUrl != null && job.awaitRemoteUrl == remoteUrl) return true
        return job.localPath == path
    }

    private suspend fun adoptFinishedDownloads() {
        taskLock.withLock {
            for (job in db.whisper().waitingForFile()) {
                val path = waitingPath(job)
                if (path != null) {
                    val fingerprint = try {
                        sha256(File(path))
                    } catch (_: java.io.IOException) {
                        db.whisper().failWaiting(job.id, "无法读取下载的音频")
                        continue
                    }
                    db.whisper().attachFile(job.id, fingerprint, path)
                } else if (downloadGaveUp(job)) {
                    db.whisper().failWaiting(job.id, downloadError(job) ?: "下载任务已不存在或音频文件不可用")
                }
            }
        }
    }

    private suspend fun waitingPath(job: WhisperJobEntity): String? {
        job.awaitDownloadId?.let { id ->
            val download = db.downloads().get(id) ?: return null
            if (download.status != DownloadStatus.DONE) return null
            if (download.kind != "DLSITE_ARCHIVE") {
                return download.localPath?.takeIf(::isFile)
                    ?: db.tracks().getById(download.trackId)?.localUri?.takeIf(::isFile)
            }
            job.trackId?.let { downloads.originalPathForTrack(it) }?.let { return it }
            val workno = download.workKey ?: return null
            val key = job.awaitFileKey ?: return null
            return db.dlsiteOriginals().entryForPlay(
                com.nanahoshi.audioplayer.dlsite.DlsiteClient.canonicalWorkno(workno), rawFileKey(key) ?: key,
            )?.localPath?.takeIf { File(it).isFile }
        }
        val trackId = job.trackId
        if (trackId != null && trackId > 0L) {
            val track = db.tracks().getById(trackId)
            if (isFile(track?.localUri)) return track?.localUri
        }
        val download = relatedDownload(job)
        if (download?.status == DownloadStatus.DONE && isFile(download.localPath)) return download.localPath
        return null
    }

    private suspend fun relatedDownload(job: WhisperJobEntity): DownloadEntity? {
        val trackId = job.trackId?.takeIf { it > 0L }
        val url = job.awaitRemoteUrl
        val byTrack = trackId?.let { db.downloads().latestForTrack(it) }
        val byUrl = url?.takeIf { it.isNotBlank() }?.let { db.downloads().latestForUrl(it) }
        return when {
            byTrack == null -> byUrl
            byUrl == null || byTrack.id >= byUrl.id -> byTrack
            else -> byUrl
        }
    }

    private suspend fun downloadGaveUp(job: WhisperJobEntity): Boolean {
        job.awaitDownloadId?.let { id ->
            val row = db.downloads().get(id) ?: return true
            return row.status == DownloadStatus.FAILED || row.status == DownloadStatus.DONE
        }
        if (job.trackId?.takeIf { it > 0L }?.let { db.downloads().activeForTrack(it) } != null) return false
        if (job.awaitRemoteUrl?.let { db.downloads().activeForUrl(it) } != null) return false
        val latest = relatedDownload(job) ?: return true
        return latest.status == DownloadStatus.FAILED || latest.status == DownloadStatus.DONE
    }

    private suspend fun downloadError(job: WhisperJobEntity): String? {
        job.awaitDownloadId?.let { id ->
            val row = db.downloads().get(id)
            return if (row?.status == DownloadStatus.DONE) {
                if (row.kind == "DLSITE_ARCHIVE") "完整原始包中未找到这条音频" else "音频文件不可用"
            } else row?.error ?: "下载任务已不存在"
        }
        return relatedDownload(job)?.error
    }

    private suspend fun drain() {
        while (true) {
            val job = db.whisper().nextUploadable() ?: return
            if (!stillActive(job.id)) continue
            coroutineScope {
                launch {
                    val self = coroutineContext[Job]!!
                    runners[job.id] = self
                    try {
                        runJob(job)
                    } catch (cancelled: CancellationException) {
                        val reason = coroutineContext.cancellationCause() ?: cancelled
                        withContext(NonCancellable) {
                            val message = (reason as? SubtitleTaskStopped)?.message ?: "已中止"
                            subtitles.generatedWriteLock.withLock { abandonIfNeeded(job.id, message) }
                        }
                        throw cancelled
                    } catch (_: Exception) {
                        subtitles.generatedWriteLock.withLock { abandonIfNeeded(job.id, "生成字幕失败，请重试") }
                    } finally {
                        runners.remove(job.id, self)
                        withContext(NonCancellable) { sweepStaged() }
                    }
                }
            }
        }
    }

    private suspend fun stillActive(id: Long): Boolean {
        coroutineContext.ensureActive()
        val current = db.whisper().get(id) ?: return false
        return current.status == DownloadStatus.QUEUED || current.status == DownloadStatus.RUNNING
    }

    private suspend fun abandonIfNeeded(id: Long, message: String) {
        val current = db.whisper().get(id) ?: return
        when {
            current.status == DownloadStatus.QUEUED || current.status == DownloadStatus.RUNNING ->
                db.whisper().fail(id, message)
            current.translateStatus == TranslateMark.RUNNING ->
                db.whisper().failTranslateIfUnpublished(id, message)
        }
    }

    private suspend fun runJob(job: WhisperJobEntity) {
        if (!stillActive(job.id)) return
        val ticket = calls.prepare("job-${job.id}")
        calls.cancelWithCoroutine(ticket) { runJob(job, ticket) }
    }

    private suspend fun runJob(job: WhisperJobEntity, ticket: InflightCalls.Ticket) {
        if (!stillActive(job.id)) return
        if (finishFromExisting(job)) {
            finishWritten(job)
            return
        }
        val modelId = jobCredentialModel(job)
        val resumingUpload = modelId == WHISPER_MODEL_QWEN && job.remoteJobId != null
        val file = job.localPath?.let(::File)
        if (file == null || (!file.isFile && !resumingUpload)) {
            db.whisper().fail(job.id, "找不到音频文件")
            return
        }
        val base = effectiveWhisperBase(modelId, job.endpoint ?: credentials.whisperBaseUrl(modelId))
        when (modelId) {
            WHISPER_MODEL_QWEN -> runQwen(job, file, base, ticket)
            WHISPER_MODEL_QWEN3 -> {
                if (base.isNullOrBlank()) {
                    db.whisper().fail(job.id, "没有听写地址")
                    return
                }
                runQwen3(job, file, whisperTranscriptionBase(base), ticket)
            }
            SENSE_VOICE_MODEL -> runOffline(job, file, offlineModels.getValue(OfflineWhisperModel.SenseVoice))
            PARAKEET_MODEL -> runOffline(job, file, offlineModels.getValue(OfflineWhisperModel.Parakeet))
            else -> db.whisper().fail(job.id, "该听写模型已移除，请换用其他模型重新生成")
        }
    }

    private suspend fun runQwen3(job: WhisperJobEntity, file: File, base: String, ticket: InflightCalls.Ticket) {
        try {
            if (db.whisper().markRunningIfActive(job.id, 0f) == 0) return
            val cues = client.transcribeQwen3(file, base, audioDurationMs(job, file), ticket, jobCredentialModel(job))
            if (!stillActive(job.id)) return
            publishCues(job, cues, base)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: WhisperException) {
            if (!stillActive(job.id)) return
            db.whisper().fail(job.id, error.message ?: "生成失败")
        } catch (error: Exception) {
            if (!stillActive(job.id)) return
            db.whisper().fail(job.id, error.message?.take(80)?.ifBlank { null } ?: "生成失败")
        }
    }

    private suspend fun runOffline(job: WhisperJobEntity, file: File, manager: OfflineWhisperModelManager) {
        try {
            if (db.whisper().markRunningIfActive(job.id, 0f) == 0) return
            val cues = transcriber.transcribe(manager, file, audioDurationMs(job, file)) { progress ->
                if (stillActive(job.id)) db.whisper().setProgress(job.id, progress.coerceIn(0f, 1f))
            }
            if (!stillActive(job.id)) return
            publishCues(job, cues, null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: WhisperException) {
            if (!stillActive(job.id)) return
            db.whisper().fail(job.id, error.message ?: "生成失败")
        } catch (error: Exception) {
            if (!stillActive(job.id)) return
            db.whisper().fail(job.id, error.message?.take(80)?.ifBlank { null } ?: "生成失败")
        }
    }

    private suspend fun audioDurationMs(job: WhisperJobEntity, file: File): Long {
        val stored = job.trackId?.takeIf { it > 0L }?.let { db.tracks().getById(it)?.durationMs }?.takeIf { it > 0L }
        return stored ?: fileDurationMs(file)
    }

    private fun fileDurationMs(file: File): Long = try {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    } catch (_: Exception) {
        0L
    }

    private suspend fun runQwen(
        job: WhisperJobEntity,
        file: File,
        base: String?,
        ticket: InflightCalls.Ticket,
    ) {
        try {
            if (base.isNullOrBlank()) {
                db.whisper().fail(job.id, "没有听写地址")
                return
            }
            val token = credentials.whisperToken(jobCredentialModel(job))
            if (token.isNullOrBlank()) {
                db.whisper().fail(job.id, "没有听写口令")
                return
            }
            if (db.whisper().markRunningIfActive(job.id, 0f) == 0) return
            val cues = qwenFile.transcribe(file, base, ticket, token, job.remoteJobId, job.remoteSubmittedAt) { id, at ->
                withContext(NonCancellable) { db.whisper().setRemoteId(job.id, id, at) }
            }
            if (!stillActive(job.id)) return
            publishCues(job, cues, base)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: WhisperException) {
            if (!stillActive(job.id)) return
            db.whisper().fail(job.id, error.message ?: "生成失败")
        } catch (error: Exception) {
            if (!stillActive(job.id)) return
            db.whisper().fail(job.id, error.message?.take(80)?.ifBlank { null } ?: "生成失败")
        }
    }

    private fun jobCredentialModel(job: WhisperJobEntity): String? = knownWhisperModel(job.model)

    private suspend fun finishFromExisting(job: WhisperJobEntity): Boolean {
        val requested = knownWhisperModel(job.model) ?: return false
        val latestDone = db.whisper().forFingerprint(job.fingerprint).lastOrNull {
            it.id != job.id && it.status == DownloadStatus.DONE
        } ?: return false
        val endpoint = effectiveWhisperBase(requested, job.endpoint ?: credentials.whisperBaseUrl(requested))
        return canReuseCues(job.fingerprint, requested, endpoint, latestDone)
    }

    private fun canReuseCues(
        fingerprint: String,
        model: String,
        endpoint: String?,
        latestDone: WhisperJobEntity,
    ): Boolean = runCatching {
        val document = parseGeneratedSubtitles(cueFile(fingerprint).readText()) ?: return false
        generatedSubtitlesMatch(document, model, endpoint, latestDone)
    }.getOrDefault(false)

    private suspend fun finishWritten(job: WhisperJobEntity) {
        val translate = subtitles.generatedWriteLock.withLock { finishTranscription(job) }
        if (translate) translateWritten(job)
    }

    /** 在字幕写入锁内登记识别完成，随后在锁外执行网络翻译。 */
    private suspend fun finishTranscription(job: WhisperJobEntity): Boolean {
        if (!stillActive(job.id)) return false
        if (job.skipTranslate == true || !translator.readyToTranslate(job.fingerprint)) {
            db.whisper().finishIfActive(job.id)
            return false
        }
        return db.whisper().markTranslatingIfActive(job.id) > 0
    }

    private suspend fun translateWritten(job: WhisperJobEntity) {
        val failure = translator.applyGenerated(job.id, job.fingerprint)
        if (failure == null) return
        if (db.whisper().get(job.id)?.translateStatus == TranslateMark.RUNNING) {
            db.whisper().failTranslateIfUnpublished(job.id, failure)
        }
    }

    private suspend fun publishCues(job: WhisperJobEntity, cues: List<WhisperCue>, endpoint: String?) {
        val model = requireNotNull(jobCredentialModel(job))
        val origin = SubtitleOrigin(model, canonicalWhisperEndpoint(endpoint, model))
        val payload = encodeGeneratedCues(cues.map { SubtitleCue(it.startMs, it.endMs, it.text) }, origin)
        val translate = subtitles.generatedWriteLock.withLock {
            if (!stillActive(job.id)) return
            cueFile(job.fingerprint).writeTextAtomically(payload)
            val translate = finishTranscription(job)
            subtitles.invalidate()
            translate
        }
        if (translate) translateWritten(job)
    }

    private fun cueFile(fingerprint: String): File {
        val safe = fingerprint.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }
        return File(File(appContext.filesDir, "subtitles"), "$safe.json")
    }

    private fun QueuedTrack.whisperRemoteIdentity(): String? =
        remoteUrl?.takeIf { it.isNotBlank() && (source != TrackSource.BILIBILI || it.startsWith("/")) }

    private suspend fun materialize(path: String?): File? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("content:")) return stageContent(path)
        val file = when {
            path.startsWith("/") -> File(path)
            path.startsWith("file:") -> Uri.parse(path).path?.let(::File)
            else -> null
        }
        return file?.takeIf { it.isFile }
    }

    private suspend fun stageContent(path: String): File? {
        val dest = File(stagedRoot, path.hashCode().toUInt().toString(16))
        val key = dest.absolutePath
        holdStaged(key)
        var rewriting = false
        try {
            val ready = dest.isFile && dest.length() > 0L
            val shared = (staging[key] ?: 0) > 1
            if (shared) {
                if (ready) return dest
                releaseStaged(key)
                return null
            }
            if (ready && stagedInUse(dest)) return dest
            val input = appContext.contentResolver.openInputStream(Uri.parse(path))
            if (input == null) {
                releaseStaged(key)
                return null
            }
            rewriting = true
            dest.parentFile?.mkdirs()
            input.use { source ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (!dest.isFile || dest.length() <= 0L) {
                dest.delete()
                releaseStaged(key)
                return null
            }
            return dest
        } catch (cancelled: CancellationException) {
            if (rewriting) dest.delete()
            releaseStaged(key)
            throw cancelled
        } catch (error: Exception) {
            if (rewriting) dest.delete()
            releaseStaged(key)
            throw error
        }
    }

    private suspend fun restoreLocalCopy(job: WhisperJobEntity): File? {
        val trackId = job.trackId ?: return null
        if (trackId <= 0L) return null
        val track = db.tracks().getById(trackId) ?: return null
        return materialize(track.localUri)
    }

    private suspend fun sweepStaged() {
        val kept = buildSet {
            addAll(db.whisper().activeLocalPaths())
            addAll(staging.keys)
        }
        val files = stagedRoot.listFiles() ?: return
        for (file in files) {
            if (shouldDeleteStagedCopy(file, stagedRoot, kept)) file.delete()
        }
    }

    private suspend fun stagedInUse(dest: File): Boolean {
        val canonical = runCatching { dest.canonicalPath }.getOrDefault(dest.absolutePath)
        return db.whisper().activeLocalPaths().any { raw ->
            raw == dest.absolutePath || raw == canonical ||
                runCatching { File(raw).canonicalPath }.getOrNull() == canonical
        }
    }

    private fun isStagedPath(path: String): Boolean {
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return false
        val root = runCatching { stagedRoot.canonicalFile }.getOrNull() ?: return false
        return file.parentFile == root
    }

    private fun holdStaged(path: String) {
        staging.compute(path) { _, count -> (count ?: 0) + 1 }
    }

    private fun releaseStaged(path: String) {
        staging.compute(path) { _, count ->
            val next = (count ?: 0) - 1
            if (next <= 0) null else next
        }
    }

    private fun isFile(path: String?): Boolean =
        !path.isNullOrBlank() && path.startsWith("/") && File(path).isFile

    private fun waiting(title: String, trackId: Long?, url: String?, skipTranslate: Boolean, model: String): WhisperJobEntity =
        WhisperJobEntity(
            title = title,
            fingerprint = "",
            trackId = trackId,
            localPath = null,
            remoteJobId = null,
            status = DownloadStatus.QUEUED,
            progress = 0f,
            error = null,
            awaitRemoteUrl = url,
            endpoint = effectiveWhisperBase(model, credentials.whisperBaseUrl(model)),
            skipTranslate = skipTranslate,
            model = model,
        )

    private fun uploadJob(
        title: String,
        fingerprint: String,
        trackId: Long?,
        path: String,
        url: String?,
        endpoint: String?,
        skipTranslate: Boolean,
        model: String,
    ) = WhisperJobEntity(
        title = title,
        fingerprint = fingerprint,
        trackId = trackId,
        localPath = path,
        remoteJobId = null,
        status = DownloadStatus.QUEUED,
        progress = 0f,
        error = null,
        awaitRemoteUrl = url,
        endpoint = endpoint,
        skipTranslate = skipTranslate,
        model = model,
    )

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

internal fun shouldDeleteStagedCopy(file: File, root: File, kept: Set<String>): Boolean {
    val path = runCatching { file.canonicalFile }.getOrNull() ?: return false
    val base = runCatching { root.canonicalFile }.getOrNull() ?: return false
    if (!path.isFile) return false
    if (path.parentFile != base) return false
    return kept.none { raw ->
        val other = runCatching { File(raw).canonicalFile }.getOrNull() ?: return@none false
        other == path
    }
}
