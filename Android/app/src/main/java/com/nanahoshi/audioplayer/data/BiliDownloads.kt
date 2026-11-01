package com.nanahoshi.audioplayer.data

import androidx.room.withTransaction
import com.nanahoshi.audioplayer.bilibili.BiliPart
import com.nanahoshi.audioplayer.bilibili.importMetadata
import com.nanahoshi.audioplayer.data.db.*
import java.io.File

/** 持久化下载和入库意图；与 HTTP 工作者分开，重启重试和已有任务都使用相同规则。 */
internal class BiliDownloads(private val filesDir: File, private val db: AppDatabase, private val library: LibraryRepository) {
    suspend fun enqueue(parts: List<BiliPart>, autoImport: Boolean = false): Int {
        var added = 0
        for (part in parts.distinctBy { it.bvid to it.cid }) {
            if (biliAudioFile(filesDir, part.bvid, part.cid) == null) continue
            val queued = db.withTransaction {
                val metadata = if (autoImport) part.importMetadata() else null
                val active = db.downloads().activeForFile(TrackSource.BILIBILI.name, part.bvid, part.cid.toString())
                if (active != null) {
                    if (metadata != null) db.downloads().setBiliImportPart(active.id, metadata)
                    return@withTransaction false
                }
                val track = db.tracks().findByBvid(part.bvid, part.cid)
                val cached = readyBiliAudio(filesDir, part.bvid, part.cid)
                if (cached != null) {
                    val saved = if (autoImport) library.importParts(listOf(part)).single() else track
                    if (saved != null) {
                        db.tracks().updateLocalUri(saved.id, cached.absolutePath)
                        db.downloads().finishedForWork(TrackSource.BILIBILI.name, part.bvid)
                            .filter { it.fileKey == part.cid.toString() }.forEach { db.downloads().attachTrack(it.id, saved.id) }
                    }
                    return@withTransaction false
                }
                // 用户识别挂上的本地文件同样可以直接使用，不再额外下载。
                if (track != null && LocalAudio.hasStoredCopy(track.localUri) &&
                    (track.localUri?.startsWith('/') != true || File(track.localUri).length() > 0)) return@withTransaction false
                db.downloads().insert(DownloadEntity(
                    trackId = track?.id ?: 0, title = part.title.ifBlank { part.bvid }, bytesDone = 0, bytesTotal = 0,
                    status = DownloadStatus.QUEUED, error = null, source = TrackSource.BILIBILI.name,
                    workKey = part.bvid, fileKey = part.cid.toString(), biliImportPart = metadata,
                ))
                true
            }
            if (queued) added++
        }
        return added
    }
}
