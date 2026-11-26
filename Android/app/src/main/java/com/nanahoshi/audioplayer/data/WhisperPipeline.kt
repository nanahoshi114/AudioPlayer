package com.nanahoshi.audioplayer.data

import androidx.room.withTransaction
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import com.nanahoshi.audioplayer.data.db.WhisperJobEntity
import com.nanahoshi.audioplayer.text.PersistedErrors
import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.UserFacingException
import com.nanahoshi.audioplayer.text.persistedErrorText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** 听写任务的入队、重试与去重准备。 */
internal class WhisperPipeline(private val center: WhisperCenter) {
    private val db get() = center.db
    private val downloads get() = center.downloads
    private val subtitles get() = center.subtitles

    internal suspend fun startWithFile(
        row: QueuedTrack,
        file: File,
        translate: Boolean,
        model: String,
        submission: WhisperCenter.Submission,
        forceRegenerate: Boolean,
        requestedAt: Long,
    ): WhisperStart {
        try {
            if (!file.isFile) return WhisperStart.Unavailable
            val fingerprint = sha256(file)
            return center.taskLock.withLock {
                startLocked(
                    title = row.title.ifBlank { center.appContext.getString(R.string.whisper_audio_label) },
                    file = file,
                    trackId = row.trackId?.takeIf { it > 0L },
                    remoteUrl = row.whisperRemoteIdentity(),
                    skipTranslate = !translate,
                    fingerprint = fingerprint,
                    model = model,
                    submission = submission,
                    forceRegenerate = forceRegenerate,
                    requestedAt = requestedAt,
                )
            }
        } finally {
            center.stagingOps.releaseStaged(file.absolutePath)
            withContext(NonCancellable) { center.stagingOps.sweepStaged() }
        }
    }

    internal suspend fun startAfterDownload(
        row: QueuedTrack,
        translate: Boolean,
        model: String,
        dlsiteMode: DlsiteDownloadMode,
        forceRegenerate: Boolean = false,
        requestedAt: Long = System.currentTimeMillis(),
    ): WhisperStart = center.submitStart(audio = true) { submission ->
        val track = downloads.libraryTrack(row) ?: downloads.prepareJapaneseTrack(row)
            ?: return@submitStart WhisperStart.BackgroundUnavailable(TextRef.Res(R.string.download_error_library_required))
        val savedRow = row.copy(trackId = track.id)
        val archive = track.source == TrackSource.DLSITE &&
            (dlsiteMode == DlsiteDownloadMode.ARCHIVE || track.fileHash.isNullOrBlank())
        val ready = if (archive) center.stagingOps.materialize(downloads.originalPathForTrack(track.id)) else center.matcher.existingFile(savedRow)
        if (ready != null) {
            return@submitStart startWithFile(savedRow, ready, translate, model, submission, forceRegenerate, requestedAt)
        }
        if (row.remoteUrl?.startsWith("japaneseasmr://file?") == true) {
            return@submitStart WhisperStart.BackgroundUnavailable(TextRef.Res(R.string.ja_error_work_file_missing))
        }
        center.taskLock.withLock {
            center.missingOfflineModel(model)?.let { return@withLock WhisperStart.NeedModelDownload(it) }
            if (db.whisper().activeForTrack(track.id) != null) return@withLock WhisperStart.AlreadyRunning
            val download = checkNotNull(submission.audio).run {
                if (archive) {
                    val workno = downloads.requestWorkno(track) ?: error(DOWNLOAD_LIBRARY_REQUIRED)
                    downloads.enqueueDlsiteArchive(workno, row.title).id
                } else downloads.enqueueTrack(track.id).id
            }
            if (download == null) {
                val file = center.matcher.existingFile(savedRow) ?: return@withLock WhisperStart.Unavailable
                try {
                    return@withLock startLocked(row.title, file, track.id, savedRow.whisperRemoteIdentity(),
                        !translate, sha256(file), model, submission, forceRegenerate, requestedAt)
                } finally {
                    center.stagingOps.releaseStaged(file.absolutePath)
                    center.stagingOps.sweepStaged()
                }
            }
            submission.enqueueTranscription {
                db.whisper().insert(waiting(row.title, track.id, savedRow.whisperRemoteIdentity(), !translate, model, requestedAt).copy(
                    awaitDownloadId = download, awaitFileKey = if (archive) rawFileKey(track.fileHash) else null,
                    forceRegenerate = true.takeIf { forceRegenerate },
                ))
            }
            WhisperStart.Started
        }
    }

    internal suspend fun needsDlsiteDownloadChoice(job: WhisperJobEntity): Boolean = withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (!job.localPath.isNullOrBlank() && File(job.localPath).isFile) return@withContext false
        val track = job.trackId?.let { db.tracks().getById(it) }
        if (track != null && LocalAudio.hasStoredCopy(center.appContext, track.localUri)) return@withContext false
        if (track?.source == TrackSource.DLSITE) return@withContext true
        job.awaitDownloadId?.let { db.downloads().get(it) }?.let {
            return@withContext it.source == TrackSource.DLSITE.name
        }
        val url = job.awaitRemoteUrl ?: return@withContext false
        db.downloads().latestForUrl(url)?.let { it.source == TrackSource.DLSITE.name && !it.fileKey.isNullOrBlank() } ?: false
    }

    internal suspend fun retry(id: Long, dlsiteMode: DlsiteDownloadMode): TextRef? = try {
        center.submit(
            audio = true,
            prepare = { submission ->
                center.taskLock.withLock {
                    val job = db.whisper().get(id)?.takeIf { it.status == DownloadStatus.FAILED }
                        ?: return@withLock TextRef.Res(R.string.whisper_prepare_failed)
                    if (center.runners.containsKey(id) || center.translationRunners.containsKey(id)) return@withLock stoppingRetry()
                    center.missingOfflineModel(job.model)?.let { return@withLock missingOfflineWhisperModelMessage(it) }
                    if (retryLocked(job, dlsiteMode, submission)) null
                    else TextRef.Res(R.string.whisper_prepare_failed)
                }
            },
            queued = { it == null },
        )
    } catch (stopped: SubtitleTaskStopped) {
        stopped.userText
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: UserFacingException) {
        error.userText
    } catch (error: Exception) {
        preparationErrorText(error)
    }

    internal suspend fun retryTranslation(id: Long): TextRef? = try {
        center.submit(
            audio = false,
            prepare = { submission ->
                subtitles.generatedWriteLock.withLock {
                    if (center.translationRunners.containsKey(id)) return@withLock stoppingRetry()
                    currentCoroutineContext().ensureActive()
                    withContext(NonCancellable) {
                        when (center.translator.requeueGeneratedTranslation(id, System.currentTimeMillis())) {
                            GeneratedRetryResult.Queued -> {
                                submission.register(id, isTranslation = true)
                                null
                            }
                            GeneratedRetryResult.Duplicate -> TextRef.Res(R.string.translate_duplicate_notice)
                            GeneratedRetryResult.MissingConfig -> TextRef.Res(R.string.translate_config_required)
                            GeneratedRetryResult.MissingSource -> TextRef.Res(R.string.translate_source_missing)
                            GeneratedRetryResult.AlreadyTranslated -> TextRef.Res(R.string.translate_nothing)
                            GeneratedRetryResult.Unavailable -> TextRef.Res(R.string.whisper_translate_failed)
                        }
                    }
                }
            },
            queued = { it == null },
        )
    } catch (stopped: SubtitleTaskStopped) {
        stopped.userText
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        preparationErrorText(error)
    }

    private fun stoppingRetry() = TextRef.Res(R.string.subtitle_stopping_retry)

    internal fun preparationErrorText(error: Exception): TextRef = when (val persisted = error.message) {
        DOWNLOAD_LIBRARY_REQUIRED, AUDIO_DOWNLOAD_START_FAILED, AUDIO_DOWNLOAD_TIMEOUT,
        PersistedErrors.JA_WORK_FILE_MISSING -> requireNotNull(persistedErrorText(persisted))
        else -> TextRef.Res(R.string.whisper_prepare_failed)
    }

    private suspend fun retryLocked(job: WhisperJobEntity, dlsiteMode: DlsiteDownloadMode, submission: WhisperCenter.Submission): Boolean {
        requireRetryAvailable(job)
        val path = job.localPath
        if (dlsiteMode != DlsiteDownloadMode.ARCHIVE && !path.isNullOrBlank() && File(path).isFile) {
            if (center.stagingOps.isStagedPath(path)) {
                return center.stagingOps.stagingLock(path).withLock {
                    center.stagingOps.holdStaged(path)
                    try {
                        val file = File(path)
                        val fingerprint = sha256(file)
                        requireRetryAvailable(job, fingerprint)
                        requeueWithFile(job, fingerprint, path, submission, retry = true)
                    } finally {
                        center.stagingOps.releaseStaged(path)
                    }
                }
            }
            val snapshot = center.stagingOps.snapshotForRetry(File(path), job.id)
            if (snapshot != null) {
                val snapshotPath = snapshot.absolutePath
                return try {
                    val fingerprint = sha256(snapshot)
                    requireRetryAvailable(job, fingerprint)
                    requeueWithFile(job, fingerprint, snapshotPath, submission, retry = true)
                } finally {
                    center.stagingOps.releaseStaged(snapshotPath)
                    withContext(NonCancellable) { center.stagingOps.sweepStaged() }
                }
            }
        }
        val restored = if (dlsiteMode == DlsiteDownloadMode.ARCHIVE) null else center.stagingOps.restoreLocalCopy(job)
        if (restored != null) {
            return try {
                requeueWithFile(job, sha256(restored), restored.absolutePath, submission, retry = true)
            } finally {
                center.stagingOps.releaseStaged(restored.absolutePath)
                center.stagingOps.sweepStaged()
            }
        }
        val oldDownload = job.awaitDownloadId?.let { db.downloads().get(it) } ?: center.matcher.relatedDownload(job)
        val selectedTrack = job.trackId?.let { db.tracks().getById(it) }
            ?: db.dlsiteOriginals().let { originals ->
                val workno = oldDownload?.workKey
                val key = job.awaitFileKey?.let(::rawFileKey)
                if (oldDownload?.kind == "DLSITE_ARCHIVE" && workno != null && key != null) {
                    originals.entryForPlay(workno, key)?.trackId?.let { db.tracks().getById(it) }
                } else null
            }
        val track = selectedTrack ?: oldDownload?.let { downloads.retryTrack(it) } ?: error(DOWNLOAD_LIBRARY_REQUIRED)
        if (track.id != job.trackId) requireRetryAvailable(job, resolvedTrackId = track.id)
        val id = checkNotNull(submission.audio).run {
            if (track.source == TrackSource.DLSITE &&
                (dlsiteMode == DlsiteDownloadMode.ARCHIVE || track.fileHash.isNullOrBlank())) {
                downloads.enqueueDlsiteArchive(downloads.requestWorkno(track) ?: error(DOWNLOAD_LIBRARY_REQUIRED), job.title).id
            } else if (oldDownload?.status == DownloadStatus.FAILED && oldDownload.kind != "DLSITE_ARCHIVE") {
                downloads.retry(oldDownload.id)
            } else downloads.enqueueTrack(track.id).id
        }
        if (id == null) throw UserFacingException(TextRef.Res(R.string.whisper_audio_missing_playback))
        return submission.enqueueTranscription {
            requireRetryAvailable(job, resolvedTrackId = track.id)
            val queued = db.withTransaction {
                if (db.whisper().requeueForDownload(job.id) == 0) false
                else {
                    db.whisper().setAwaitDownload(job.id, id, job.awaitFileKey ?: selectedTrack?.fileHash?.let(::rawFileKey))
                    true
                }
            }
            if (!queued) return@enqueueTranscription null
            center.progress.reset(TaskKey(TaskKind.WHISPER, job.id))
            job.id
        }
    }

    private suspend fun requireRetryAvailable(
        job: WhisperJobEntity,
        fingerprint: String = job.fingerprint,
        resolvedTrackId: Long? = job.trackId,
    ) {
        val targets = WhisperMatchTargets(
            trackIds = listOfNotNull(job.trackId, resolvedTrackId).toSet(),
            remoteIdentity = job.awaitRemoteUrl?.takeIf { it.isNotBlank() },
            localPaths = emptySet(),
            generatedFingerprint = fingerprint.takeIf { it.isNotBlank() },
        )
        if (db.whisper().active().any { it.id != job.id && targets.matches(it) } ||
            (fingerprint.isNotBlank() && center.translator.hasActiveGenerated(fingerprint))
        ) {
            throw UserFacingException(TextRef.Res(R.string.player_subtitle_already_running))
        }
    }

    private suspend fun requeueWithFile(
        job: WhisperJobEntity,
        fingerprint: String,
        path: String,
        submission: WhisperCenter.Submission,
        retry: Boolean = false,
        createdAt: Long = System.currentTimeMillis(),
    ): Boolean = submission.enqueueTranscription {
        if (retry) requireRetryAvailable(job, fingerprint)
        val changed = db.whisper().requeueWithFile(
            job.id, job.title, fingerprint, job.trackId, path,
            job.awaitRemoteUrl, job.endpoint, job.skipTranslate, job.model, createdAt,
        )
        if (changed == 0) return@enqueueTranscription null
        center.progress.reset(TaskKey(TaskKind.WHISPER, job.id))
        job.id
    }

    private suspend fun startLocked(
        title: String,
        file: File,
        trackId: Long?,
        remoteUrl: String?,
        skipTranslate: Boolean,
        fingerprint: String,
        model: String,
        submission: WhisperCenter.Submission,
        forceRegenerate: Boolean,
        requestedAt: Long,
    ): WhisperStart {
        if (!file.isFile) return WhisperStart.Unavailable
        center.missingOfflineModel(model)?.let { return WhisperStart.NeedModelDownload(it) }
        if (trackId != null && db.whisper().activeForTrack(trackId) != null) return WhisperStart.AlreadyRunning
        if (trackId == null && remoteUrl != null && db.whisper().activeForUrl(remoteUrl) != null) {
            return WhisperStart.AlreadyRunning
        }
        if (center.translator.hasActiveGenerated(fingerprint)) return WhisperStart.AlreadyRunning
        val endpoint = effectiveWhisperBase(model, center.credentials.whisperBaseUrl(model))
        val prior = db.whisper().forFingerprint(fingerprint)
        val busy = prior.filter {
            (it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING) &&
                sameWhisperEndpoint(it.endpoint, endpoint, model) &&
                whisperModelsMatch(it.model, model)
        }
        val latestDone = prior.lastOrNull { it.status == DownloadStatus.DONE }
        if (!forceRegenerate && latestDone != null && center.jobRunner.canReuseCues(fingerprint, model, endpoint, latestDone)) {
            if (com.nanahoshi.audioplayer.data.db.TranslateMark.isActive(latestDone.translateStatus)) return WhisperStart.AlreadyRunning
            if (!skipTranslate && center.translator.readyToTranslate(fingerprint) && busy.isNotEmpty()) {
                return WhisperStart.AlreadyRunning
            }
            rememberDone(title, fingerprint, file, trackId, remoteUrl, listOf(latestDone), endpoint, model)
            return WhisperStart.AlreadyDone
        }
        if (busy.isNotEmpty()) {
            if (forceRegenerate) return WhisperStart.AlreadyRunning
            return if (rememberPending(title, fingerprint, file, trackId, remoteUrl, busy, endpoint, skipTranslate, model, submission, requestedAt)) {
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
        if (failed != null && !forceRegenerate && failed.forceRegenerate != true) {
            if (center.runners.containsKey(failed.id) || center.translationRunners.containsKey(failed.id)) return WhisperStart.AlreadyRunning
            val requeued = requeueWithFile(
                failed.copy(title = title, trackId = trackId ?: failed.trackId, awaitRemoteUrl = remoteUrl ?: failed.awaitRemoteUrl,
                    endpoint = endpoint, skipTranslate = skipTranslate, model = model),
                fingerprint, file.absolutePath, submission, createdAt = requestedAt,
            )
            return if (requeued) WhisperStart.Started else WhisperStart.AlreadyRunning
        }
        submission.enqueueTranscription {
            db.whisper().insert(uploadJob(title, fingerprint, trackId, file.absolutePath, remoteUrl, endpoint, skipTranslate, model, requestedAt)
                .copy(forceRegenerate = true.takeIf { forceRegenerate }))
        }
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
                createdAt = System.currentTimeMillis(),
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
        submission: WhisperCenter.Submission,
        requestedAt: Long,
    ): Boolean {
        val covered = prior.any {
            it.status != DownloadStatus.FAILED && covers(it, trackId, remoteUrl, file.absolutePath)
        }
        if (covered) return false
        submission.enqueueTranscription {
            db.whisper().insert(uploadJob(title, fingerprint, trackId, file.absolutePath, remoteUrl, endpoint, skipTranslate, model, requestedAt))
        }
        return true
    }

    private fun covers(job: WhisperJobEntity, trackId: Long?, remoteUrl: String?, path: String): Boolean {
        if (trackId != null && job.trackId == trackId) return true
        if (trackId == null && remoteUrl != null && job.awaitRemoteUrl == remoteUrl) return true
        return job.localPath == path
    }

    private fun waiting(
        title: String,
        trackId: Long?,
        url: String?,
        skipTranslate: Boolean,
        model: String,
        createdAt: Long = System.currentTimeMillis(),
    ): WhisperJobEntity =
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
            endpoint = effectiveWhisperBase(model, center.credentials.whisperBaseUrl(model)),
            skipTranslate = skipTranslate,
            model = model,
            createdAt = createdAt,
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
        createdAt: Long = System.currentTimeMillis(),
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
        createdAt = createdAt,
    )

    internal suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }
}