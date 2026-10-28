package com.nanahoshi.audioplayer.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import com.nanahoshi.audioplayer.data.db.TranslateMark
import com.nanahoshi.audioplayer.data.db.WhisperJobEntity
import com.nanahoshi.audioplayer.dlsite.RemotePlayable
import com.nanahoshi.audioplayer.playback.SubtitleCue
import com.nanahoshi.audioplayer.playback.encodeGeneratedCues
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

enum class WhisperStart {
    Started,
    AlreadyRunning,
    AlreadyDone,
    Unavailable,
}

class WhisperCenter(
    context: Context,
    private val db: AppDatabase,
    private val credentials: CredentialStore,
    private val downloads: DownloadCenter,
    private val translator: TranslateCenter,
) {
    private val appContext = context.applicationContext
    private val calls = InflightCalls()
    private val client = WhisperClient(credentials, calls)
    private val qwenFile = QwenFileTranscriber(calls)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val kick = Channel<Unit>(Channel.CONFLATED)
    private val stagedRoot = File(appContext.filesDir, "whisper-src")
    private val staging = ConcurrentHashMap<String, Int>()
    private val runners = ConcurrentHashMap<Long, Job>()
    private val sizeAnswers = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()
    private val sizeConfirm = MutableStateFlow<Long?>(null)
    val sizeConfirmJob: StateFlow<Long?> = sizeConfirm.asStateFlow()

    fun observe(): Flow<List<WhisperJobEntity>> = db.whisper().observe()

    fun acceptSizeConfirm() {
        val id = sizeConfirm.value ?: return
        sizeAnswers[id]?.complete(true)
        if (sizeConfirm.value == id) sizeConfirm.value = null
    }

    fun declineSizeConfirm() {
        val id = sizeConfirm.value ?: return
        sizeAnswers[id]?.complete(false)
        if (sizeConfirm.value == id) sizeConfirm.value = null
    }

    init {
        scope.launch {
            downloads.observe().collect { kick.trySend(Unit) }
        }
        scope.launch {
            for (ignored in kick) {
                adoptFinishedDownloads()
                drain()
            }
        }
        scope.launch { sweepStaged() }
        kick.trySend(Unit)
    }

    suspend fun existingFile(row: QueuedTrack): File? = withContext(Dispatchers.IO) {
        val track = row.trackId?.let { db.tracks().getById(it) }
        materialize(track?.localUri)?.let { return@withContext it }
        materialize(row.localUri)?.let { return@withContext it }
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

    suspend fun start(row: QueuedTrack, file: File, translate: Boolean): WhisperStart = withContext(Dispatchers.IO) {
        try {
            startLocked(
                title = row.title.ifBlank { "音频" },
                file = file,
                trackId = row.trackId?.takeIf { it > 0L },
                remoteUrl = row.remoteUrl?.takeIf { it.isNotBlank() },
                skipTranslate = !translate,
            )
        } finally {
            releaseStaged(file.absolutePath)
            sweepStaged()
        }
    }

    suspend fun startAfterDownload(row: QueuedTrack, translate: Boolean): WhisperStart = withContext(Dispatchers.IO) {
        val ready = existingFile(row)
        if (ready != null) {
            return@withContext start(row, ready, translate)
        }
        val title = row.title.ifBlank { "音频" }
        val trackId = row.trackId?.takeIf { it > 0L }
        val skipTranslate = !translate
        if (trackId != null) {
            if (db.whisper().activeForTrack(trackId) != null) return@withContext WhisperStart.AlreadyRunning
            val track = db.tracks().getById(trackId) ?: return@withContext WhisperStart.Unavailable
            downloads.enqueue(listOf(track))
            db.whisper().insert(waiting(title, trackId, row.remoteUrl?.takeIf { it.isNotBlank() }, skipTranslate))
            kick.trySend(Unit)
            return@withContext WhisperStart.Started
        }
        val url = row.remoteUrl?.takeIf { it.isNotBlank() } ?: return@withContext WhisperStart.Unavailable
        if (db.whisper().activeForUrl(url) != null) return@withContext WhisperStart.AlreadyRunning
        val workKey = row.workno?.takeIf { it.isNotBlank() } ?: "remote"
        downloads.enqueueRemote(
            RemotePlayable(
                title = title,
                artist = row.artist,
                coverUri = row.coverUri,
                remoteUrl = url,
                source = row.source,
                referer = row.referer.orEmpty(),
                durationMs = row.durationMs,
                workno = row.workno,
                fileKey = row.fileKey,
            ),
            workKey,
        )
        db.whisper().insert(waiting(title, null, url, skipTranslate))
        kick.trySend(Unit)
        WhisperStart.Started
    }

    fun retry(id: Long) {
        scope.launch {
            val job = db.whisper().get(id) ?: return@launch
            if (job.status != DownloadStatus.FAILED) return@launch
            val path = job.localPath
            if (!path.isNullOrBlank() && File(path).isFile && job.fingerprint.isNotBlank()) {
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
                return@launch
            }
            val restored = restoreLocalCopy(job)
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
                return@launch
            }
            db.whisper().requeueForDownload(job.id)
            val trackId = job.trackId
            if (trackId != null && trackId > 0L) {
                val latest = db.downloads().latestForTrack(trackId)
                if (latest?.status == DownloadStatus.FAILED) {
                    downloads.retry(latest.id)
                } else {
                    val track = db.tracks().getById(trackId)
                    if (track != null) downloads.enqueue(listOf(track))
                }
            } else {
                val url = job.awaitRemoteUrl
                if (!url.isNullOrBlank()) {
                    val latest = db.downloads().latestForUrl(url)
                    if (latest?.status == DownloadStatus.FAILED) downloads.retry(latest.id)
                }
            }
            kick.trySend(Unit)
        }
    }

    fun cancel(id: Long) {
        scope.launch {
            val job = db.whisper().get(id) ?: return@launch
            val translating = job.translateStatus == TranslateMark.RUNNING
            val active = job.status == DownloadStatus.QUEUED || job.status == DownloadStatus.RUNNING
            if (!translating && !active) return@launch
            if (translating) db.whisper().markTranslateFailed(id, "已中止")
            else db.whisper().fail(id, "已中止")
            calls.cancel("job-$id")
            if (sizeConfirm.value == id) sizeConfirm.value = null
            sizeAnswers[id]?.cancel()
            translator.stopForWhisper(id)
            runners[id]?.cancel()
            sweepStaged()
        }
    }

    private suspend fun startLocked(
        title: String,
        file: File,
        trackId: Long?,
        remoteUrl: String?,
        skipTranslate: Boolean,
    ): WhisperStart {
        if (!file.isFile) return WhisperStart.Unavailable
        if (trackId != null && db.whisper().activeForTrack(trackId) != null) return WhisperStart.AlreadyRunning
        if (trackId == null && remoteUrl != null && db.whisper().activeForUrl(remoteUrl) != null) {
            return WhisperStart.AlreadyRunning
        }
        val fingerprint = sha256(file)
        val model = credentials.resolvedWhisperModel()
        val endpoint = effectiveWhisperBase(model, credentials.whisperBaseUrl(model))
        val prior = db.whisper().forFingerprint(fingerprint)
        val latestDone = prior.lastOrNull { it.status == DownloadStatus.DONE }
        if (
            latestDone != null &&
            latestDone.endpoint == endpoint &&
            whisperModelsMatch(latestDone.model, model, endpoint) &&
            cueFile(fingerprint).isFile
        ) {
            rememberDone(title, fingerprint, file, trackId, remoteUrl, listOf(latestDone), endpoint, model)
            return WhisperStart.AlreadyDone
        }
        val busy = prior.filter {
            (it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING) &&
                it.endpoint == endpoint &&
                whisperModelsMatch(it.model, model, endpoint)
        }
        if (busy.isNotEmpty()) {
            return if (rememberPending(title, fingerprint, file, trackId, remoteUrl, busy, endpoint, skipTranslate, model)) {
                WhisperStart.Started
            } else {
                WhisperStart.AlreadyRunning
            }
        }
        val failed = prior.lastOrNull {
            it.status == DownloadStatus.FAILED && it.endpoint == endpoint &&
                whisperModelsMatch(it.model, model, endpoint) &&
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
        for (job in db.whisper().waitingForFile()) {
            val path = waitingPath(job)
            if (path != null) {
                db.whisper().attachFile(job.id, sha256(File(path)), path)
                continue
            }
            if (downloadGaveUp(job)) {
                db.whisper().fail(job.id, downloadError(job) ?: "下载失败")
            }
        }
    }

    private suspend fun waitingPath(job: WhisperJobEntity): String? {
        val trackId = job.trackId
        if (trackId != null && trackId > 0L) {
            val track = db.tracks().getById(trackId)
            if (isFile(track?.localUri)) return track?.localUri
        }
        val url = job.awaitRemoteUrl
        if (!url.isNullOrBlank()) {
            val done = db.downloads().latestForUrl(url)
            if (done?.status == DownloadStatus.DONE && isFile(done.localPath)) return done.localPath
        }
        return null
    }

    private suspend fun downloadGaveUp(job: WhisperJobEntity): Boolean {
        val trackId = job.trackId
        if (trackId != null && trackId > 0L) {
            if (db.downloads().activeForTrack(trackId) != null) return false
            val latest = db.downloads().latestForTrack(trackId) ?: return false
            return latest.status == DownloadStatus.FAILED || latest.status == DownloadStatus.DONE
        }
        val url = job.awaitRemoteUrl ?: return false
        if (db.downloads().activeForUrl(url) != null) return false
        val latest = db.downloads().latestForUrl(url) ?: return false
        return latest.status == DownloadStatus.FAILED || latest.status == DownloadStatus.DONE
    }

    private suspend fun downloadError(job: WhisperJobEntity): String? {
        val trackId = job.trackId
        val latest = if (trackId != null && trackId > 0L) {
            db.downloads().latestForTrack(trackId)
        } else {
            job.awaitRemoteUrl?.let { db.downloads().latestForUrl(it) }
        }
        return latest?.error
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
                } catch (_: CancellationException) {
                    abandonIfNeeded(job.id)
                } finally {
                    runners.remove(job.id, self)
                    sweepStaged()
                }
                }
            }
        }
    }

    private suspend fun stillActive(id: Long): Boolean {
        val current = db.whisper().get(id) ?: return false
        return current.status == DownloadStatus.QUEUED || current.status == DownloadStatus.RUNNING
    }

    private suspend fun abandonIfNeeded(id: Long) {
        val current = db.whisper().get(id) ?: return
        when {
            current.status == DownloadStatus.QUEUED || current.status == DownloadStatus.RUNNING ->
                db.whisper().fail(id, "已中止")
            current.translateStatus == TranslateMark.RUNNING ->
                db.whisper().markTranslateFailed(id, "已中止")
        }
    }

    private suspend fun runJob(job: WhisperJobEntity) {
        if (!stillActive(job.id)) return
        val ticket = calls.prepare("job-${job.id}")
        if (!stillActive(job.id)) return
        if (finishFromExisting(job)) {
            finishWritten(job)
            return
        }
        val file = job.localPath?.let(::File)
        if (file == null || !file.isFile) {
            db.whisper().fail(job.id, "找不到音频文件")
            return
        }
        val modelId = jobCredentialModel(job)
        val saved = job.endpoint ?: credentials.whisperBaseUrl(modelId)
        val route = when (job.model) {
            WHISPER_MODEL_1 -> WHISPER_MODEL_1
            WHISPER_MODEL_LOCAL -> WHISPER_MODEL_LOCAL
            WHISPER_MODEL_QWEN -> WHISPER_MODEL_QWEN
            WHISPER_MODEL_QWEN3 -> WHISPER_MODEL_QWEN3
            WHISPER_MODEL_JOBS -> WHISPER_MODEL_JOBS
            null -> inferredWhisperModel(saved)
            else -> null
        }
        val base = if (isCloudWhisperModel(knownWhisperModel(job.model))) {
            officialWhisperBase(job.model)
        } else {
            saved?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        }
        when (route) {
            null -> {
                db.whisper().fail(job.id, "生成失败")
                return
            }
            WHISPER_MODEL_QWEN -> {
                runQwen(job, file, base, ticket)
                return
            }
            WHISPER_MODEL_QWEN3 -> {
                if (base.isNullOrBlank()) {
                    db.whisper().fail(job.id, "没有听写地址")
                    return
                }
                runQwen3(job, file, whisperTranscriptionBase(base), ticket)
                return
            }
            WHISPER_MODEL_LOCAL -> {
                if (base.isNullOrBlank()) {
                    db.whisper().fail(job.id, "没有听写地址")
                    return
                }
                runOnline(job, file, whisperTranscriptionBase(base), ticket)
                return
            }
            WHISPER_MODEL_1 -> {
                if (base.isNullOrBlank()) {
                    db.whisper().fail(job.id, "没有听写地址")
                    return
                }
                if (needsOnlineSizeConfirm(WHISPER_MODEL_1, base, file.length())) {
                    val send = confirmOversized(job.id)
                    if (!send || !stillActive(job.id)) {
                        if (stillActive(job.id)) db.whisper().fail(job.id, "已中止")
                        return
                    }
                }
                runOnline(job, file, whisperTranscriptionBase(base), ticket)
                return
            }
        }
        try {
            if (db.whisper().markRunningIfActive(job.id, job.progress.coerceIn(0f, 1f)) == 0) return
            var remoteId = job.remoteJobId
            if (remoteId.isNullOrBlank()) {
                val created = client.create(file, job.fingerprint, job.trackId, base, ticket, modelId)
                if (!stillActive(job.id)) return
                remoteId = created.id
                db.whisper().setRemoteId(job.id, remoteId)
                when (created.status) {
                    "done" -> {
                        storeCues(job, remoteId, base, ticket)
                        return
                    }
                    "failed" -> {
                        db.whisper().fail(job.id, "生成失败")
                        return
                    }
                }
            }
            poll(job, remoteId, base, ticket)
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

    private suspend fun runOnline(job: WhisperJobEntity, file: File, base: String, ticket: InflightCalls.Ticket) {
        try {
            if (db.whisper().markRunningIfActive(job.id, 0f) == 0) return
            val cues = client.transcribeOnline(file, base, ticket, jobCredentialModel(job))
            if (!stillActive(job.id)) return
            writeCues(job.fingerprint, cues)
            finishWritten(job)
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

    private suspend fun confirmOversized(jobId: Long): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        sizeAnswers[jobId] = deferred
        sizeConfirm.value = jobId
        return try {
            deferred.await()
        } finally {
            sizeAnswers.remove(jobId)
            if (sizeConfirm.value == jobId) sizeConfirm.value = null
        }
    }

    private suspend fun runQwen3(job: WhisperJobEntity, file: File, base: String, ticket: InflightCalls.Ticket) {
        try {
            if (db.whisper().markRunningIfActive(job.id, 0f) == 0) return
            val cues = client.transcribeQwen3(file, base, audioDurationMs(job, file), ticket, jobCredentialModel(job))
            if (!stillActive(job.id)) return
            writeCues(job.fingerprint, cues)
            finishWritten(job)
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
            val cues = qwenFile.transcribe(file, base, ticket, token)
            if (!stillActive(job.id)) return
            writeCues(job.fingerprint, cues)
            finishWritten(job)
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

    private fun jobCredentialModel(job: WhisperJobEntity): String =
        knownWhisperModel(job.model) ?: inferredWhisperModel(job.endpoint)

    private suspend fun finishFromExisting(job: WhisperJobEntity): Boolean {
        val requested = knownWhisperModel(job.model) ?: inferredWhisperModel(job.endpoint)
        val latestDone = db.whisper().forFingerprint(job.fingerprint).lastOrNull {
            it.id != job.id &&
                it.status == DownloadStatus.DONE &&
                it.endpoint == job.endpoint &&
                whisperModelsMatch(it.model, requested, it.endpoint)
        }
        if (latestDone == null || !cueFile(job.fingerprint).isFile) return false
        return true
    }

    private suspend fun finishWritten(job: WhisperJobEntity) {
        if (!stillActive(job.id)) return
        if (job.skipTranslate == true || !translator.readyToTranslate(job.fingerprint)) {
            db.whisper().finishIfActive(job.id)
            return
        }
        if (db.whisper().markTranslatingIfActive(job.id) == 0) return
        val failure = translator.applyGenerated(job.id, job.fingerprint)
        if (failure == null) return
        if (db.whisper().get(job.id)?.translateStatus == TranslateMark.RUNNING) {
            db.whisper().markTranslateFailed(job.id, failure)
        }
    }

    private suspend fun poll(job: WhisperJobEntity, remoteId: String, base: String?, ticket: InflightCalls.Ticket) {
        while (true) {
            if (!stillActive(job.id)) return
            val state = client.job(remoteId, base, ticket, jobCredentialModel(job))
            if (!stillActive(job.id)) return
            when (state.status) {
                "queued" -> db.whisper().setProgress(job.id, 0f)
                "running" -> db.whisper().setProgress(job.id, state.progress.coerceIn(0f, 1f))
                "done" -> if (storeCues(job, remoteId, base, ticket)) return
                "failed" -> {
                    db.whisper().fail(job.id, state.error ?: "生成失败")
                    return
                }
                else -> {
                    db.whisper().fail(job.id, state.error ?: "生成失败")
                    return
                }
            }
            delay(2_000)
        }
    }

    private suspend fun storeCues(job: WhisperJobEntity, remoteId: String, base: String?, ticket: InflightCalls.Ticket): Boolean {
        if (!stillActive(job.id)) return false
        val fetched = client.cues(remoteId, base, ticket, jobCredentialModel(job))
        if (!stillActive(job.id)) return false
        if (fetched is WhisperCues.NotReady) return false
        val cues = (fetched as WhisperCues.Ready).cues
        writeCues(job.fingerprint, cues)
        finishWritten(job)
        return true
    }

    private fun writeCues(fingerprint: String, cues: List<WhisperCue>) {
        val file = cueFile(fingerprint)
        file.parentFile?.mkdirs()
        file.writeText(encodeGeneratedCues(cues.map { SubtitleCue(it.startMs, it.endMs, it.text) }))
    }

    private fun cueFile(fingerprint: String): File {
        val safe = fingerprint.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }
        return File(File(appContext.filesDir, "subtitles"), "$safe.json")
    }

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
            input.use { source -> dest.outputStream().use { source.copyTo(it) } }
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

    private fun waiting(title: String, trackId: Long?, url: String?, skipTranslate: Boolean): WhisperJobEntity {
        val model = credentials.resolvedWhisperModel()
        return WhisperJobEntity(
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
    }

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

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
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
