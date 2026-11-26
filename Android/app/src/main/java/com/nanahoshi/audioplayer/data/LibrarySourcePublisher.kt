package com.nanahoshi.audioplayer.data

import androidx.room.withTransaction
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.bilibili.BiliPart
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.UserFacingException
import com.nanahoshi.audioplayer.youtube.YouTubeVideo
import com.nanahoshi.audioplayer.youtube.readyYouTubeAudio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** B 站分 P、YouTube 音轨的入库与下载完成写入。 */
internal class LibrarySourcePublisher(private val repo: LibraryRepository) {
    private val db get() = repo.db
    private val context get() = repo.context

    suspend fun saveYouTube(video: YouTubeVideo): TrackEntity = withContext(Dispatchers.IO) {
        repo.youtubeLock.withLock {
            db.withTransaction {
                db.tracks().findYouTube(video.id)?.let { return@withTransaction it }
                val track = TrackEntity(
                    source = TrackSource.YOUTUBE, title = video.title, artist = video.author,
                    durationMs = video.durationMs, coverUri = video.coverUrl,
                    localUri = readyYouTubeAudio(context.filesDir, video.id)?.absolutePath,
                    bvid = null, cid = null, page = null, addedAt = System.currentTimeMillis(),
                    remoteUrl = video.watchUrl, youtubeVideoId = video.id,
                )
                track.copy(id = db.tracks().insert(track))
            }
        }
    }

    suspend fun importParts(parts: List<BiliPart>, folderId: Long? = null): List<TrackEntity> =
        withContext(Dispatchers.IO) {
            db.withTransaction { parts.map { upsertBili(it, folderId) } }
        }

    suspend fun completeYouTubeDownload(jobId: Long, videoId: String, path: String) = db.withTransaction {
        val job = db.downloads().get(jobId) ?: throw UserFacingException(TextRef.Res(R.string.library_download_task_removed))
        if (job.status != com.nanahoshi.audioplayer.data.db.DownloadStatus.RUNNING) throw CancellationException()
        val track = db.tracks().findYouTube(videoId)?.takeIf { it.id == job.trackId }
            ?: error(DOWNLOAD_LIBRARY_REQUIRED)
        val size = File(path).length()
        if (size <= 0) throw UserFacingException(TextRef.Res(R.string.library_track_file_missing))
        db.tracks().updateLocalUri(track.id, path)
        db.downloads().setLocalPath(jobId, path)
        db.downloads().finish(jobId, size, size)
    }

    suspend fun completeBiliDownload(jobId: Long, bvid: String, cid: Long, path: String) = db.withTransaction {
        val job = db.downloads().get(jobId) ?: throw UserFacingException(TextRef.Res(R.string.library_download_task_removed))
        if (job.status != com.nanahoshi.audioplayer.data.db.DownloadStatus.RUNNING) throw UserFacingException(TextRef.Res(R.string.library_download_task_ended))
        val track = db.tracks().findByBvid(bvid, cid)?.takeIf { it.id == job.trackId }
            ?: error(DOWNLOAD_LIBRARY_REQUIRED)
        val size = File(path).length()
        if (size <= 0) throw UserFacingException(TextRef.Res(R.string.library_track_file_missing))
        db.tracks().updateLocalUri(track.id, path)
        db.downloads().setLocalPath(jobId, path)
        db.downloads().setPendingBiliSubtitle(jobId, true)
        db.downloads().finish(jobId, size, size)
    }

    suspend fun upsertBili(part: BiliPart, folderId: Long?): TrackEntity {
        val group = db.folders().findBiliVideo(part.bvid)
        db.tracks().findByBvid(part.bvid, part.cid)?.let { old ->
            val existing = if (group != null && old.folderId != group.id) {
                db.tracks().setFolder(old.id, group.id)
                old.copy(folderId = group.id)
            } else old
            val cached = readyBiliAudio(context.filesDir, part.bvid, part.cid)
            if (cached != null && existing.localUri != cached.absolutePath) {
                db.tracks().updateLocalUri(existing.id, cached.absolutePath)
                return existing.copy(localUri = cached.absolutePath)
            }
            return existing
        }
        val cached = readyBiliAudio(context.filesDir, part.bvid, part.cid)
        val id = db.tracks().insert(
            TrackEntity(
                source = TrackSource.BILIBILI,
                title = part.title,
                artist = part.artist,
                durationMs = part.durationMs,
                coverUri = part.coverUrl,
                localUri = cached?.absolutePath,
                bvid = part.bvid,
                cid = part.cid,
                page = part.page,
                addedAt = System.currentTimeMillis(),
                folderId = group?.id ?: folderId,
            ),
        )
        return db.tracks().getById(id) ?: throw UserFacingException(TextRef.Res(R.string.library_save_audio_failed))
    }
}