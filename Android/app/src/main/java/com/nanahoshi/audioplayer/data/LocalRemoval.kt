package com.nanahoshi.audioplayer.data

import androidx.room.withTransaction
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.playback.SubtitleSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

data class DeleteResult(val deleted: Int, val unlinked: Int, val changed: Int = 0) {
    val total: Int get() = deleted + unlinked
}

internal enum class LocalRemoval { DOWNLOAD, ASSOCIATION }

internal fun TrackEntity.localRemoval(filesDir: File): LocalRemoval? {
    if (source == TrackSource.LOCAL) return null
    val path = localUri ?: return null
    if (path.startsWith("/")) {
        val file = File(path)
        val owned = runCatching { file.canonicalFile.startsWith(filesDir.canonicalFile) }.getOrDefault(false)
        return LocalRemoval.DOWNLOAD.takeIf { owned && file.isFile }
    }
    val userFile = path.startsWith("content:", ignoreCase = true) || path.startsWith("file:", ignoreCase = true)
    return LocalRemoval.ASSOCIATION.takeIf { userFile && source != TrackSource.BILIBILI }
}

/** 删 App 下载的文件；关联得到的曲目（本地文件是用户自己的）只取消关联，不动磁盘上的文件。 */
internal suspend fun deleteLocalCopies(
    db: AppDatabase,
    filesDir: File,
    subtitles: SubtitleSource,
    tracks: List<TrackEntity>,
    deleteSubtitles: Boolean,
): DeleteResult = withContext(Dispatchers.IO) {
    var deleted = 0
    var unlinked = 0
    var changed = 0
    for (expected in tracks) {
        val path = expected.localUri ?: continue
        if (expected.source == TrackSource.LOCAL) continue
        currentCoroutineContext().ensureActive()
        // Do not clear an association published by a download while confirmation was open.
        withContext(NonCancellable) {
            db.withTransaction {
                val track = db.tracks().getById(expected.id)
                if (track == null || track.localUri != path || track.source != expected.source) {
                    changed++
                    return@withTransaction
                }
                when (track.localRemoval(filesDir)) {
                    LocalRemoval.DOWNLOAD -> {
                        if (!File(path).delete()) return@withTransaction
                        if (deleteSubtitles) subtitles.discardStored(track.id, path, track.remoteUrl)
                        db.downloads().deleteFinishedForPath(path)
                        if (track.source == TrackSource.BILIBILI) deleteBiliSubtitle(filesDir, track.bvid, track.cid)
                        deleted++
                    }
                    LocalRemoval.ASSOCIATION -> unlinked++
                    null -> return@withTransaction
                }
                db.tracks().clearLocalUri(track.id)
                db.downloads().deleteFinishedForTrack(track.id)
            }
        }
    }
    DeleteResult(deleted, unlinked, changed)
}
