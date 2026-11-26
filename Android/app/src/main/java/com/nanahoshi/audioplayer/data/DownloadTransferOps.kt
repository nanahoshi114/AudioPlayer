package com.nanahoshi.audioplayer.data

import android.net.Uri
import androidx.room.withTransaction
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.bilibili.BiliSubtitle
import com.nanahoshi.audioplayer.bilibili.BilibiliClient
import com.nanahoshi.audioplayer.data.db.DownloadEntity
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.dlsite.DlsiteArchiveException
import com.nanahoshi.audioplayer.dlsite.DlsiteArchiveFailureKind
import com.nanahoshi.audioplayer.dlsite.DlsiteClient
import com.nanahoshi.audioplayer.japaneseasmr.JA_WORK_DOWNLOAD
import com.nanahoshi.audioplayer.japaneseasmr.JaFailure
import com.nanahoshi.audioplayer.japaneseasmr.JaMediaKind
import com.nanahoshi.audioplayer.japaneseasmr.JapaneseAsmrClient
import com.nanahoshi.audioplayer.japaneseasmr.JapaneseAsmrClient.Companion.isChallenge
import com.nanahoshi.audioplayer.japaneseasmr.JapaneseAsmrException
import com.nanahoshi.audioplayer.japaneseasmr.jaHash
import com.nanahoshi.audioplayer.japaneseasmr.resolveStoredJaMatches
import com.nanahoshi.audioplayer.text.PersistedErrors
import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.UserFacingException
import com.nanahoshi.audioplayer.text.resolveNoUrl
import com.nanahoshi.audioplayer.text.resolveUserError
import com.nanahoshi.audioplayer.text.userTextOr
import com.nanahoshi.audioplayer.youtube.YouTubeException
import com.nanahoshi.audioplayer.youtube.parseMediaRange
import com.nanahoshi.audioplayer.youtube.readyYouTubeAudio
import com.nanahoshi.audioplayer.youtube.youtubeAudioFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.io.File
import java.io.IOException

/** 各来源的音轨传输、保存与 B 站字幕缓存。 */
internal class DownloadTransferOps(private val center: DownloadCenter) {
    private val db get() = center.db
    private val appContext get() = center.appContext
    private val filesDir get() = center.filesDir

    suspend fun downloadOne(snapshot: DownloadEntity) = supervisorScope {
        val job = db.withTransaction {
            val target = center.targetResolver.libraryTarget(snapshot)
            if (snapshot.kind == JA_WORK_DOWNLOAD && snapshot.workKey != null &&
                db.downloads().reserveWorkTask(snapshot.workKey, snapshot.id)?.let {
                    it.id < snapshot.id || it.status != DownloadStatus.QUEUED
                } == true
            ) {
                db.downloads().stopActive(snapshot.id, appContext.getString(R.string.download_already_has_task))
                return@withTransaction null
            }
            if (target == null) {
                db.downloads().stopActive(snapshot.id,
                    if (snapshot.kind == JA_WORK_DOWNLOAD) PersistedErrors.JA_POST_REMOVED else DOWNLOAD_LIBRARY_REQUIRED)
                return@withTransaction null
            }
            center.targetResolver.bindLibraryTarget(snapshot, target)
            if (db.downloads().startQueued(snapshot.id) == 0) return@withTransaction null
            db.downloads().get(snapshot.id)
        } ?: return@supervisorScope
        val report = center.progress.begin(TaskKey(TaskKind.DOWNLOAD, job.id), ProgressPart.DOWNLOAD)
        var transferEntered = false
        val runner = async(start = CoroutineStart.LAZY) {
            transferEntered = true
            transfer(job, report)
        }
        val active = DownloadCenter.ActiveRun(center.nextAttemptId.incrementAndGet(), runner)
        center.runners[job.id] = active
        try {
            if (db.downloads().get(job.id)?.status == DownloadStatus.RUNNING) runner.await() else runner.cancel()
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
        } finally {
            center.runners.remove(job.id, active)
            val queueActive = currentCoroutineContext().isActive
            val stopped = currentCoroutineContext().cancellationCause()
            withContext(NonCancellable) {
                if (!transferEntered && db.downloads().get(job.id)?.status == DownloadStatus.RUNNING) {
                    val message = (stopped as? AudioDownloadStopped)?.persistedError
                        ?: if (queueActive) PersistedErrors.CANCELLED else AUDIO_DOWNLOAD_INTERRUPTED
                    db.downloads().failRunning(job.id, message)
                }
                center.progress.end(report)
            }
        }
    }

    private suspend fun transfer(job: DownloadEntity, report: TaskReporter) {
        var partialFile: File? = null
        try {
            center.targetResolver.requireRunningTarget(job)
            if (job.kind == "DLSITE_ARCHIVE") return center.archiveRunner.downloadArchive(job, report)
            if (job.kind == JA_WORK_DOWNLOAD) return center.jaRunner.downloadJaWork(job, report)
            if (job.source == TrackSource.YOUTUBE.name) return downloadYouTube(job, report)
            if (job.source == TrackSource.BILIBILI.name) return downloadBili(job, report)
            if (job.source == TrackSource.JAPANESE_ASMR.name) return downloadJapaneseAsmr(job, report)
            val track = db.tracks().getById(job.trackId) ?: throw UserFacingException(TextRef.Res(R.string.download_track_removed))
            if (LocalAudio.hasStoredCopy(appContext, track.localUri)) {
                report.reused()
                db.withTransaction {
                    center.targetResolver.requireRunningTarget(job)
                    val length = when {
                        localFileReady(track.localUri) -> File(track.localUri!!).length()
                        track.localUri?.startsWith("file:", ignoreCase = true) == true ->
                            Uri.parse(track.localUri).path?.let(::File)?.takeIf { it.isFile }?.length() ?: 0L
                        else -> 0L
                    }
                    db.downloads().finish(job.id, length, length)
                }
                return
            }
            report.phase(ProgressPhase.RESOLVING)
            val dlsiteTrack = track.source == TrackSource.DLSITE
            var signed = if (dlsiteTrack) signedTrack(track) else null
            var url = signed?.url ?: resolveUrl(track, refresh = false)
            val dest = destination(track)
            dest.parentFile?.mkdirs()
            val partial = File(dest.absolutePath + ".${job.id}.part").also { partialFile = it }
            var refreshed = false
            while (true) {
                if (partial.exists()) partial.delete()
                val request = Request.Builder()
                    .url(url)
                    .taskProgress(report)
                    .header("User-Agent", if (dlsiteTrack) DlsiteClient.USER_AGENT else com.nanahoshi.audioplayer.asmr.AsmrClient.USER_AGENT)
                    .header("Referer", if (dlsiteTrack) DlsiteClient.PLAY_REFERER else com.nanahoshi.audioplayer.asmr.AsmrClient.REFERER)
                    .apply {
                        if (dlsiteTrack) {
                            signed?.cookie?.let { header("Cookie", it) }
                        } else {
                            header("Origin", com.nanahoshi.audioplayer.asmr.AsmrClient.ORIGIN)
                        }
                    }
                    .build()
                val expired = center.transferHttp.newCall(request).withResponse { response ->
                    if (response.code == 401) throw UserFacingException(TextRef.Res(R.string.download_login_expired))
                    if ((response.code == 403 || response.code == 404) && !refreshed) return@withResponse true
                    if (!response.isSuccessful) throw UserFacingException(TextRef.Res(R.string.download_failed_generic))
                    val body = response.body ?: throw UserFacingException(TextRef.Res(R.string.download_failed_generic))
                    val expectedTotal = fullGetExpectedTotal(response, body, R.string.download_incomplete_audio)
                    val total = expectedTotal ?: 0L
                    val transfer = report.phase(ProgressPhase.DOWNLOADING)
                    body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            var reported = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                done += read
                                transfer.bytes(read.toLong(), done, total.takeIf { it > 0 })
                                if (done - reported >= 256 * 1024) {
                                    db.downloads().activeProgress(job.id, done, total)
                                    reported = done
                                }
                            }
                            if (done == 0L || (expectedTotal != null && done != expectedTotal)) {
                                throw UserFacingException(TextRef.Res(R.string.download_incomplete_audio))
                            }
                        }
                    }
                    false
                }
                if (!expired) break
                refreshed = true
                report.retry()
                report.phase(ProgressPhase.RESOLVING)
                if (dlsiteTrack) {
                    signed = signedTrack(track)
                    url = signed.url
                } else {
                    url = resolveUrl(track, refresh = true)
                }
            }
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                db.withTransaction {
                    center.targetResolver.requireRunningTarget(job)
                    report.phase(ProgressPhase.SAVING)
                    if (!partial.renameTo(dest)) throw UserFacingException(TextRef.Res(R.string.download_save_file_failed))
                    report.phase(ProgressPhase.IMPORTING)
                    center.targetResolver.finishTrackDownload(job, dest)
                }
            }
        } catch (cancelled: CancellationException) {
            val queueActive = currentCoroutineContext().isActive
            val stopped = currentCoroutineContext().cancellationCause()
            val message = ((stopped ?: cancelled) as? AudioDownloadStopped)?.persistedError
                ?: if (queueActive) PersistedErrors.CANCELLED else AUDIO_DOWNLOAD_INTERRUPTED
            withContext(NonCancellable) { db.downloads().failRunning(job.id, message) }
            if (!queueActive) throw cancelled
        } catch (error: Exception) {
            val stopped = currentCoroutineContext().cancellationCause()
            val message = when {
                stopped != null -> (stopped as? AudioDownloadStopped)?.persistedError ?: AUDIO_DOWNLOAD_INTERRUPTED
                error.message == DOWNLOAD_LIBRARY_REQUIRED -> DOWNLOAD_LIBRARY_REQUIRED
                error is DlsiteArchiveException && error.archiveKind == DlsiteArchiveFailureKind.INTERRUPTED ->
                    PersistedErrors.DLSITE_ARCHIVE_INTERRUPTED
                error is DlsiteArchiveException -> appContext.resolveNoUrl(error.userText, R.string.download_archive_failed)
                job.kind == "DLSITE_ARCHIVE" -> appContext.resolveUserError(error, R.string.download_archive_or_extract_failed)
                error is com.nanahoshi.audioplayer.japaneseasmr.JaWorkException -> error.persistedError
                    ?: appContext.resolveNoUrl(error.userText, R.string.download_ja_work_failed)
                error is JapaneseAsmrException && error.failure == JaFailure.WORK_FILE_MISSING ->
                    PersistedErrors.JA_WORK_FILE_MISSING
                error is JapaneseAsmrException && error.failure == JaFailure.UNSAFE ->
                    appContext.getString(error.failure.messageRes)
                job.kind == JA_WORK_DOWNLOAD -> error.message?.takeIf {
                    // 「文章…」兼容旧版本已落库的错误文本；新任务写入「篇目…」。
                    it in setOf(PersistedErrors.JA_POST_REMOVED_LEGACY, PersistedErrors.JA_POST_REMOVED, PersistedErrors.JA_ENTRY_CHANGED)
                }
                    ?: appContext.getString(R.string.download_ja_work_failed)
                error is JapaneseAsmrException -> appContext.resolveNoUrl(error.userText, R.string.download_failed_generic)
                error is YouTubeException -> appContext.resolveNoUrl(error.userTextOr(R.string.download_youtube_audio_failed), R.string.download_youtube_audio_failed)
                job.source == TrackSource.YOUTUBE.name -> appContext.getString(R.string.download_youtube_audio_failed)
                error is IOException -> appContext.resolveNoUrl(error.userTextOr(R.string.download_save_audio_failed), R.string.download_save_audio_failed)
                else -> appContext.resolveNoUrl(error.userTextOr(R.string.download_failed_generic), R.string.download_failed_generic)
            }
            withContext(NonCancellable) { db.downloads().failRunning(job.id, message) }
            if (stopped != null) throw stopped
        } finally {
            partialFile?.delete()
        }
    }

    internal fun hlsStaging(job: DownloadEntity): File? {
        val postId = job.workKey?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return null
        val key = job.fileKey ?: return null
        return File(center.jaRoot, "$postId/hls-${jaHash(key).take(16)}")
    }

    private suspend fun downloadYouTube(job: DownloadEntity, report: TaskReporter) {
        val videoId = job.workKey ?: throw YouTubeException(appContext.getString(R.string.download_no_video_id))
        val destination = youtubeAudioFile(filesDir, videoId)
            ?: throw YouTubeException(appContext.getString(R.string.download_invalid_video_id))
        destination.parentFile?.mkdirs()
        // 每次运行独立暂存，旧请求取消后的清理不会碰到新一次重试。
        val partial = File(destination.path + ".${job.id}.${java.util.UUID.randomUUID()}.part")
        try {
            val cached = readyYouTubeAudio(filesDir, videoId)
            if (cached == null) {
                report.phase(ProgressPhase.RESOLVING)
                val runner = currentCoroutineContext()[kotlinx.coroutines.Job]!!
                center.youtube.downloadAudio(videoId, partial, taskProgress = report) { done, total ->
                    center.scope.launch { if (runner.isActive) db.downloads().activeProgress(job.id, done, total) }
                }
            }
            currentCoroutineContext().ensureActive()
            if (cached != null) report.reused() else report.phase(ProgressPhase.SAVING)
            withContext(NonCancellable) {
                db.withTransaction {
                    center.targetResolver.requireRunningTarget(job)
                    if (cached == null && !partial.renameTo(destination)) {
                        throw YouTubeException(appContext.getString(R.string.download_save_youtube_failed))
                    }
                    center.library.completeYouTubeDownload(job.id, videoId, destination.absolutePath)
                }
            }
        } finally {
            partial.delete()
        }
    }

    private suspend fun downloadJapaneseAsmr(job: DownloadEntity, report: TaskReporter) {
        val postId = job.workKey ?: error(DOWNLOAD_LIBRARY_REQUIRED)
        val key = job.fileKey ?: error(DOWNLOAD_LIBRARY_REQUIRED)
        val track = db.tracks().getById(job.trackId) ?: error(DOWNLOAD_LIBRARY_REQUIRED)
        val post = db.japaneseAsmr().post(postId) ?: error(DOWNLOAD_LIBRARY_REQUIRED)
        val directory = File(center.jaRoot, "$postId/japaneseasmr-${track.id}").apply { mkdirs() }
        val partial = File(directory, ".${job.id}.part")
        val staging = requireNotNull(hlsStaging(job))
        try {
            var extension: String? = null
            var hlsLength = 0L
            for (attempt in 0..1) {
                report.phase(ProgressPhase.RESOLVING)
                val article = center.library.japaneseAsmr.article(postId, post.rj, refresh = attempt != 0)
                val media = resolveStoredJaMatches(article, postId, db.japaneseAsmr())[key]?.media
                    ?: throw JapaneseAsmrException(JaFailure.MEDIA)
                if (media.kind == JaMediaKind.HLS) {
                    val candidate = media.candidates.firstOrNull { it.kind == JaMediaKind.HLS }
                        ?: throw JapaneseAsmrException(JaFailure.MEDIA)
                    hlsLength = center.jaHls.download(candidate.url, staging, partial, report) ?: continue
                    extension = "m4a"
                } else {
                    extension = downloadJaDirect(job, media.candidates.filter { it.kind == JaMediaKind.DIRECT }, partial, report)
                }
                if (extension != null) break
                report.retry()
            }
            if (extension == null) throw JapaneseAsmrException(JaFailure.MEDIA)
            currentCoroutineContext().ensureActive()
            report.phase(ProgressPhase.CHECKING)
            val probe = if (hlsLength > 0L) null else LocalAudio.probeAudio(partial.absolutePath)
            val hasAudio = probe?.hasAudio ?: (hlsLength > 0L)
            val duration = probe?.durationMs
            if (!hasAudio && (duration == null || duration <= 0L)) {
                throw JapaneseAsmrException(JaFailure.MEDIA)
            }
            val finalDuration = duration ?: hlsLength
            val safeTitle = sanitizeEntryName(track.title)
            val name = if (safeTitle.endsWith(".$extension", ignoreCase = true)) safeTitle else "$safeTitle.$extension"
            val dest = File(directory, name)
            withContext(NonCancellable) {
                var renamed = false
                try {
                    db.withTransaction {
                        center.targetResolver.requireRunningTarget(job)
                        report.phase(ProgressPhase.SAVING)
                        if (!partial.renameTo(dest)) throw UserFacingException(TextRef.Res(R.string.download_save_file_failed))
                        renamed = true
                        report.phase(ProgressPhase.IMPORTING)
                        center.targetResolver.finishTrackDownload(job, dest)
                        if (finalDuration > 0) {
                            db.tracks().fillDuration(track.id, finalDuration)
                            db.japaneseAsmr().fillDuration(track.id, finalDuration)
                        }
                    }
                    staging.deleteRecursively()
                } catch (failure: Exception) {
                    if (renamed) dest.delete()
                    throw failure
                }
            }
        } finally {
            partial.delete()
        }
    }

    private suspend fun downloadJaDirect(
        job: DownloadEntity,
        candidates: List<com.nanahoshi.audioplayer.japaneseasmr.JaCandidate>,
        partial: File,
        report: TaskReporter,
    ): String? {
        for (candidate in candidates) {
            val req = JapaneseAsmrClient.mediaRequest(candidate.url)
                .header("Accept-Encoding", "identity")
            val probe = try {
                center.jaProbeHttp.newCall(req.header("Range", "bytes=0-1023").build()).withResponse { response ->
                    if (response.isChallenge(if (response.code == 403) response.peekBody(4096).string() else "")) {
                        throw JapaneseAsmrException(JaFailure.VERIFY)
                    }
                    (response.code == 200 || response.code == 206) &&
                        response.body?.contentType()?.subtype != "html" && response.peekBody(1).bytes().isNotEmpty()
                }
            } catch (failure: JapaneseAsmrException) {
                throw failure
            } catch (_: IOException) {
                false
            }
            if (!probe) continue
            val result = center.jaTransferHttp.newCall(req.removeHeader("Range").taskProgress(report).build()).withResponse { response ->
                if (response.isChallenge(if (response.code == 403) response.peekBody(4096).string() else "")) {
                    throw JapaneseAsmrException(JaFailure.VERIFY)
                }
                if (response.code == 403 || response.code == 404) return@withResponse null
                if (response.code != 200) throw JapaneseAsmrException(if (response.code >= 500) JaFailure.SERVER else JaFailure.MEDIA)
                val body = response.body ?: throw JapaneseAsmrException(JaFailure.MEDIA)
                val subtype = body.contentType()?.subtype?.lowercase()
                if (subtype == "html" || subtype == "xhtml+xml") return@withResponse null
                val total = body.contentLength().coerceAtLeast(0)
                val transfer = report.phase(ProgressPhase.DOWNLOADING)
                var done = 0L
                var reported = 0L
                var firstChunk = true
                var htmlBody = false
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (firstChunk && read > 0) {
                                firstChunk = false
                                if (looksLikeHtml(buffer, read)) {
                                    htmlBody = true
                                    break
                                }
                            }
                            output.write(buffer, 0, read)
                            done += read
                            transfer.bytes(read.toLong(), done, total.takeIf { it > 0 })
                            if (done - reported >= 256 * 1024) {
                                db.downloads().activeProgress(job.id, done, total)
                                reported = done
                            }
                        }
                    }
                }
                if (htmlBody) {
                    partial.delete()
                    return@withResponse null
                }
                if (done <= 0L || !(total == 0L || done == total)) throw UserFacingException(TextRef.Res(R.string.download_incomplete_audio))
                val ext = response.request.url.encodedPath.substringAfterLast('.', "").lowercase()
                if (ext in setOf("mp3", "m4a", "aac", "mp4", "wav", "flac", "ogg", "opus")) ext
                else if ((body.contentType()?.toString() ?: candidate.mime)?.startsWith("audio/mpeg") == true) "mp3" else "m4a"
            }
            if (result != null) return result
        }
        return null
    }

    private suspend fun downloadBili(job: DownloadEntity, report: TaskReporter) {
        val track = job.trackId.takeIf { it > 0L }?.let { db.tracks().getById(it) }
        val bvid = job.workKey?.trim().orEmpty().ifBlank { track?.bvid?.trim().orEmpty() }
        val cid = job.fileKey?.toLongOrNull() ?: track?.cid ?: throw UserFacingException(TextRef.Res(R.string.download_no_bili_episode))
        val dest = biliAudioFile(filesDir, bvid, cid) ?: throw UserFacingException(TextRef.Res(R.string.download_no_bili_episode))
        if (dest.isFile && dest.length() > 0L) {
            report.reused()
            center.library.completeBiliDownload(job.id, bvid, cid, dest.absolutePath)
            return
        }
        dest.parentFile?.mkdirs()
        val partial = File(dest.absolutePath + ".${job.id}.part")
        try {
            var refreshed = false
            while (true) {
                if (partial.exists()) partial.delete()
                report.phase(ProgressPhase.RESOLVING)
                val audio = center.bilibili.resolveAudio(bvid, cid)
                val request = Request.Builder()
                    .taskProgress(report)
                    .url(audio.url.replace("%2C", ",").replace("%2c", ","))
                    .header("Referer", "https://www.bilibili.com/video/$bvid")
                    .header("User-Agent", BilibiliClient.USER_AGENT)
                    .build()
                val expired = center.biliHttp.newCall(request).withResponse { response ->
                    if ((response.code == 403 || response.code == 401) && !refreshed) return@withResponse true
                    if (!response.isSuccessful) throw UserFacingException(TextRef.Res(R.string.download_failed_generic))
                    val body = response.body ?: throw UserFacingException(TextRef.Res(R.string.download_failed_generic))
                    val expectedTotal = fullGetExpectedTotal(response, body, R.string.download_bili_incomplete)
                    val total = expectedTotal ?: 0L
                    val transfer = report.phase(ProgressPhase.DOWNLOADING)
                    body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            var reported = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                done += read
                                transfer.bytes(read.toLong(), done, total.takeIf { it > 0 })
                                if (done - reported >= 256 * 1024) {
                                    db.downloads().activeProgress(job.id, done, total)
                                    reported = done
                                }
                            }
                            if (done <= 0 || (expectedTotal != null && done != expectedTotal)) {
                                throw UserFacingException(TextRef.Res(R.string.download_bili_incomplete))
                            }
                        }
                    }
                    false
                }
                if (!expired) break
                refreshed = true
                report.retry()
            }
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                db.withTransaction {
                    center.targetResolver.requireRunningTarget(job)
                    report.phase(ProgressPhase.SAVING)
                    if (!partial.renameTo(dest) || dest.length() <= 0L) throw UserFacingException(TextRef.Res(R.string.download_save_track_failed))
                    center.library.completeBiliDownload(job.id, bvid, cid, dest.absolutePath)
                }
            }
        } finally { partial.delete() }
    }

    internal suspend fun cacheBiliSubtitle(job: DownloadEntity) {
        val report = center.progress.begin(TaskKey(TaskKind.DOWNLOAD, job.id, ProgressPart.BILI_SUBTITLE),
            ProgressPart.BILI_SUBTITLE, ProgressPhase.FETCHING_SUBTITLE)
        var outcome = ProgressResult.RETRY_LATER
        try {
            val bvid = job.workKey
            val cid = job.fileKey?.toLongOrNull()
            if (bvid != null && cid != null && center.targetResolver.libraryTarget(job) != null &&
                readyBiliAudio(filesDir, bvid, cid) != null && readBiliSubtitle(filesDir, bvid, cid) == null
            ) {
                val result = withTimeoutOrNull(30_000L) { center.bilibili.subtitleDocument(bvid, cid) }
                if (result is BiliSubtitle.NeedsLogin) center.notice(appContext.getString(R.string.download_bili_subtitle_login))
                db.withTransaction {
                    if (db.downloads().get(job.id)?.pendingBiliSubtitle != true) return@withTransaction
                    val targetReady = center.targetResolver.libraryTarget(job) != null && readyBiliAudio(filesDir, bvid, cid) != null
                    val cached = readBiliSubtitle(filesDir, bvid, cid) != null
                    val written = if (result is BiliSubtitle.Ready && targetReady && !cached) {
                        writeBiliSubtitle(filesDir, bvid, cid, result.document, result.language)
                    } else null
                    outcome = when {
                        !targetReady -> ProgressResult.CANCELLED
                        cached || written == true -> ProgressResult.COMPLETED
                        result is BiliSubtitle.NeedsLogin -> ProgressResult.NEEDS_LOGIN
                        result is BiliSubtitle.Missing -> ProgressResult.NO_SUBTITLE
                        else -> ProgressResult.RETRY_LATER
                    }
                    // 写盘失败、需登录、读取失败或超时都保留标记，待下次打开应用接手。
                    if (shouldClearPendingBiliSubtitle(result, targetReady, cached, written)) {
                        db.downloads().setPendingBiliSubtitle(job.id, null)
                    }
                }
            } else {
                outcome = if (bvid != null && cid != null && readBiliSubtitle(filesDir, bvid, cid) != null) ProgressResult.COMPLETED else ProgressResult.CANCELLED
                db.downloads().setPendingBiliSubtitle(job.id, null)
            }
        } catch (cancelled: CancellationException) {
            outcome = ProgressResult.INTERRUPTED
            throw cancelled
        } finally {
            report.finish(outcome)
        }
    }

    private suspend fun signedTrack(track: TrackEntity): com.nanahoshi.audioplayer.dlsite.SignedPlayFile {
        val workno = center.targetResolver.worknoOf(track) ?: throw UserFacingException(TextRef.Res(R.string.download_no_address))
        val fileKey = rawFileKey(track.fileHash) ?: throw UserFacingException(TextRef.Res(R.string.download_no_address))
        return center.dlsite.signedPlayFile(workno, fileKey)
    }

    private suspend fun resolveUrl(track: TrackEntity, refresh: Boolean): String {
        val current = track.remoteUrl
        if (!refresh && !current.isNullOrBlank()) return current
        val workId = track.asmrWorkId ?: throw UserFacingException(TextRef.Res(R.string.download_no_address))
        val fresh = com.nanahoshi.audioplayer.asmr.AsmrClient.findStream(
            center.asmr.trackTree(workId), rawFileKey(track.fileHash), track.title,
        ) ?: throw UserFacingException(TextRef.Res(R.string.download_no_address))
        db.tracks().updateRemoteUrl(track.id, fresh)
        return fresh
    }

    private suspend fun destination(track: TrackEntity): File {
        val workDir = File(center.root, (track.asmrWorkId ?: 0L).toString())
        val relative = relativeDirs(track.folderId)
        val fileName = withExtension(sanitizeEntryName(track.title), track.remoteUrl)
        val unique = trackDownloadRelativePath(track.source, track.id, fileName)
        val dest = if (relative.isEmpty()) File(workDir, unique) else File(workDir, "$relative/$unique")
        val workRoot = workDir.canonicalFile.path
        val destPath = dest.canonicalFile.path
        if (!destPath.startsWith(workRoot + File.separator)) {
            throw UserFacingException(TextRef.Res(R.string.download_save_file_failed))
        }
        return dest
    }

    private suspend fun relativeDirs(folderId: Long?): String {
        if (folderId == null) return ""
        val names = ArrayDeque<String>()
        var current = db.folders().get(folderId)
        while (current != null && current.asmrSourceId == null && current.asmrWorkId != null) {
            names.addFirst(sanitizeEntryName(current.name))
            current = current.parentId?.let { db.folders().get(it) }
        }
        return names.joinToString("/")
    }

    private fun withExtension(name: String, url: String?): String {
        if (fileExtension(name).isNotEmpty()) return name
        val remoteExt = url?.substringBefore('?')?.substringAfterLast('.', "").orEmpty()
        if (remoteExt.isBlank() || remoteExt.length > 5 || remoteExt.contains('/')) return name
        return "$name.$remoteExt"
    }

    private fun fullGetExpectedTotal(
        response: okhttp3.Response,
        body: okhttp3.ResponseBody,
        incompleteRes: Int,
    ): Long? {
        if (response.code == 200) return body.contentLength().takeIf { it >= 0L }
        if (response.code != 206) throw UserFacingException(TextRef.Res(R.string.download_failed_generic))
        val range = parseMediaRange(response.header("Content-Range"))
            ?: throw UserFacingException(TextRef.Res(incompleteRes))
        if (range.start != 0L || range.end != range.total - 1L) {
            throw UserFacingException(TextRef.Res(incompleteRes))
        }
        val length = body.contentLength()
        if (length >= 0L && length != range.total) {
            throw UserFacingException(TextRef.Res(incompleteRes))
        }
        return range.total
    }

    private fun looksLikeHtml(bytes: ByteArray, count: Int): Boolean {
        val text = bytes.copyOf(count.coerceAtMost(512))
            .toString(Charsets.UTF_8)
            .trimStart('﻿', ' ', '\n', '\r', '\t')
            .lowercase()
        return text.startsWith("<!doctype") || text.startsWith("<html")
    }

    private fun localFileReady(path: String?): Boolean =
        !path.isNullOrBlank() && path.startsWith("/") && File(path).isFile
}