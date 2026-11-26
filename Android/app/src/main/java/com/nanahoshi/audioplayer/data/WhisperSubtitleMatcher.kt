package com.nanahoshi.audioplayer.data

import android.net.Uri
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.data.db.DownloadEntity
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import com.nanahoshi.audioplayer.data.db.WhisperJobEntity
import com.nanahoshi.audioplayer.playback.LoadedSubtitles
import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.resolveNoUrl
import com.nanahoshi.audioplayer.youtube.readyYouTubeAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** 听写输入音频、字幕目标与下载依赖的解析。 */
internal class WhisperSubtitleMatcher(private val center: WhisperCenter) {
    private val db get() = center.db
    private val downloads get() = center.downloads

    internal suspend fun resolveReadOnlyMatchTargets(
        row: QueuedTrack,
        loaded: LoadedSubtitles?,
        loadedQueueItemId: Long?,
    ): WhisperMatchTargets = withContext(Dispatchers.IO) {
        val track = downloads.libraryTrack(row)
        val byUrl = if (row.source == TrackSource.JAPANESE_ASMR) null
            else completedAudioPath(row.whisperRemoteIdentity())
        com.nanahoshi.audioplayer.data.resolveReadOnlyMatchTargets(
            row, loaded, loadedQueueItemId, center.appContext.filesDir, track,
            savedPaths = listOfNotNull(byUrl, savedWorkAudioPath(row)),
        )
    }

    private suspend fun completedAudioPath(url: String?): String? =
        url?.takeIf { it.isNotBlank() }?.let { db.downloads().latestForUrl(it) }
            ?.takeIf { it.status == DownloadStatus.DONE }?.localPath

    internal suspend fun savedWorkAudioPath(row: QueuedTrack): String? {
        val work = row.workno ?: return null
        return when (row.source) {
            TrackSource.JAPANESE_ASMR -> {
                if (row.remoteUrl?.startsWith("japaneseasmr://file?") == true) {
                    val path = row.fileKey ?: return null
                    db.jaDownloads().entry(work, path)?.localPath
                } else downloads.savedRemotePath(row.source, work, row.fileKey)
            }
            TrackSource.ASMR, TrackSource.DLSITE ->
                if (work.isNotBlank()) downloads.savedRemotePath(row.source, work, row.fileKey) else null
            else -> null
        }
    }

    internal suspend fun existingFile(row: QueuedTrack): File? = withContext(Dispatchers.IO) {
        val track = downloads.libraryTrack(row)
        center.stagingOps.materialize(track?.localUri)?.let { return@withContext it }
        center.stagingOps.materialize(row.localUri)?.let { return@withContext it }
        // 从下载管理播放的 B 站下载音轨没有 trackId，本地路径放在 remoteUrl。
        center.stagingOps.materialize(row.remoteUrl?.takeIf { it.startsWith("/") })?.let { return@withContext it }
        if (row.source == TrackSource.BILIBILI) {
            readyBiliAudio(center.appContext.filesDir, row.bvid ?: row.workno, row.cid ?: row.fileKey?.toLongOrNull())
                ?.let { return@withContext it }
        }
        if (row.source == TrackSource.YOUTUBE) {
            readyYouTubeAudio(center.appContext.filesDir, row.youtubeVideoId ?: row.workno)?.let { return@withContext it }
        }
        if (row.source == TrackSource.JAPANESE_ASMR) {
            return@withContext center.stagingOps.materialize(savedWorkAudioPath(row))
        }
        center.stagingOps.materialize(completedAudioPath(row.remoteUrl))?.let { return@withContext it }
        center.stagingOps.materialize(savedWorkAudioPath(row))
    }

    internal suspend fun adoptFinishedDownloads() {
        center.taskLock.withLock {
            for (job in db.whisper().waitingForFile()) {
                val path = waitingPath(job)
                if (path != null) {
                    val fingerprint = try {
                        center.pipeline.sha256(File(path))
                    } catch (_: java.io.IOException) {
                        db.whisper().failWaiting(job.id, center.appContext.getString(R.string.whisper_download_audio_missing))
                        continue
                    }
                    db.whisper().attachFile(job.id, fingerprint, path)
                } else if (downloadGaveUp(job)) {
                    db.whisper().failWaiting(job.id, downloadError(job) ?: center.appContext.getString(R.string.whisper_download_missing))
                }
            }
        }
    }

    private suspend fun waitingPath(job: WhisperJobEntity): String? {
        job.awaitDownloadId?.let { id ->
            val download = db.downloads().get(id) ?: return null
            if (download.status != DownloadStatus.DONE) return null
            if (download.kind != "DLSITE_ARCHIVE") {
                return download.localPath?.takeIf(center.stagingOps::isFile)
                    ?: db.tracks().getById(download.trackId)?.localUri?.takeIf(center.stagingOps::isFile)
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
            if (center.stagingOps.isFile(track?.localUri)) return track?.localUri
        }
        val download = relatedDownload(job)
        if (download?.status == DownloadStatus.DONE && center.stagingOps.isFile(download.localPath)) return download.localPath
        return null
    }

    internal suspend fun progressDependency(job: WhisperJobEntity): Long? =
        job.awaitDownloadId ?: relatedDownload(job)?.takeIf {
            it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING
        }?.id

    internal suspend fun relatedDownload(job: WhisperJobEntity): DownloadEntity? {
        val trackId = job.trackId?.takeIf { it > 0L }
        val url = job.awaitRemoteUrl
        val byTrack = trackId?.let { db.downloads().latestForTrack(it) }
        if (url?.startsWith("japaneseasmr://audio?") == true) {
            val uri = Uri.parse(url)
            val post = uri.getQueryParameter("post") ?: return null
            val key = uri.getQueryParameter("media") ?: return null
            return db.downloads().latestForSourceFile(TrackSource.JAPANESE_ASMR.name, post, key)
        }
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
        val error = if (job.awaitDownloadId != null) {
            val row = db.downloads().get(job.awaitDownloadId)
            if (row?.status == DownloadStatus.DONE) {
                return center.appContext.getString(
                    if (row.kind == "DLSITE_ARCHIVE") R.string.whisper_archive_track_missing
                    else R.string.whisper_audio_unavailable,
                )
            }
            row?.error ?: return center.appContext.getString(R.string.whisper_download_gone)
        } else relatedDownload(job)?.error
        return error?.let { center.appContext.resolveNoUrl(TextRef.Raw(it), R.string.whisper_download_missing) }
    }
}