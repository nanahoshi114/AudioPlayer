package com.nanahoshi.audioplayer.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.nanahoshi.audioplayer.bilibili.*
import com.nanahoshi.audioplayer.data.db.*
import java.io.File
import java.util.UUID

/** Library organization and delayed download publication share the same Room transaction. */
internal class BiliLibraryStore(
    private val context: Context,
    private val db: AppDatabase,
    private val savePart: suspend (BiliPart, Long?) -> TrackEntity,
) {
    suspend fun mode(bvid: String): BiliImportMode? = when {
        db.folders().findBiliVideo(bvid) != null -> BiliImportMode.GROUPED
        db.tracks().forBvid(bvid).isNotEmpty() -> BiliImportMode.SPLIT
        else -> null
    }

    private suspend fun validateConversion(request: BiliImportRequest) {
        val current = mode(request.video.bvid)
        check(current == null || current == request.mode || request.conversionConfirmed) {
            "这个视频的导入方式已经改变，请重新导入并确认。"
        }
    }

    suspend fun save(request: BiliImportRequest): List<TrackEntity> = db.withTransaction {
        val checked = request.checked()
        validateConversion(checked)
        db.biliImports().delete(checked.video.bvid)
        db.downloads().clearBiliImportMetadata(checked.video.bvid)
        apply(checked)
    }

    suspend fun beginDownload(request: BiliImportRequest): String = db.withTransaction {
        val checked = request.checked()
        validateConversion(checked)
        val id = UUID.randomUUID().toString()
        db.downloads().clearBiliImportMetadata(checked.video.bvid)
        for (part in checked.parts) {
            val active = db.downloads().activeForFile(TrackSource.BILIBILI.name, part.bvid, part.cid.toString())
            if (active != null) db.downloads().setBiliImportPart(active.id,
                biliLibraryJson.encodeToString(BiliDownloadImport.serializer(), BiliDownloadImport(part, id)))
        }
        db.biliImports().put(BiliImportIntentEntity(checked.video.bvid, id,
            biliLibraryJson.encodeToString(BiliImportRequest.serializer(), checked)))
        id
    }

    private suspend fun apply(request: BiliImportRequest): List<TrackEntity> {
        validateConversion(request)
        val bvid = request.video.bvid
        val oldGroup = db.folders().findBiliVideo(bvid)
        val existing = db.tracks().forBvid(bvid)
        val target = if (request.mode == BiliImportMode.GROUPED) {
            val group = oldGroup ?: FolderEntity(name = request.video.title, position = db.folders().maxPosition(null) + 1,
                addedAt = System.currentTimeMillis(), coverUri = request.video.coverUrl, biliBvid = bvid).let {
                it.copy(id = db.folders().insert(it))
            }
            existing.forEach { db.tracks().setFolder(it.id, group.id) }
            group.id
        } else {
            if (oldGroup != null) {
                check(db.folders().childrenOf(oldGroup.id).isEmpty() && db.files().listIn(oldGroup.id).isEmpty() &&
                    db.tracks().inFolder(oldGroup.id).all { it.bvid == bvid }) { "这个分组里还有其他视频，无法直接更改导入方式" }
                existing.filter { it.folderId == oldGroup.id }.forEach { db.tracks().setFolder(it.id, oldGroup.parentId) }
                db.folders().delete(oldGroup.id)
            }
            oldGroup?.parentId
        }
        request.parts.forEach { savePart(it, target) }
        val saved = db.tracks().forBvid(bvid)
        for (track in saved) {
            val cid = track.cid ?: continue
            readyBiliAudio(context.filesDir, bvid, cid)?.let { file -> db.tracks().updateLocalUri(track.id, file.absolutePath) }
            db.downloads().finishedForWork(TrackSource.BILIBILI.name, bvid)
                .filter { it.fileKey == cid.toString() }.forEach { db.downloads().attachTrack(it.id, track.id) }
        }
        return db.tracks().forBvid(bvid)
    }

    internal suspend fun hasLocalPart(part: BiliPart): Boolean {
        if (readyBiliAudio(context.filesDir, part.bvid, part.cid) != null) return true
        val uri = db.tracks().findByBvid(part.bvid, part.cid)?.localUri ?: return false
        return runCatching {
            when {
                uri.startsWith("content:") -> context.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { true } == true
                else -> File(if (uri.startsWith("file:")) Uri.parse(uri).path.orEmpty() else uri).let { it.isFile && it.length() > 0 }
            }
        }.getOrDefault(false)
    }

    suspend fun publish(bvid: String? = null) {
        var failure: Exception? = null
        for (snapshot in db.biliImports().all().filter { bvid == null || it.bvid == bvid }) {
            try {
                db.withTransaction {
                    val intent = db.biliImports().get(snapshot.bvid)?.takeIf { it.batchId == snapshot.batchId } ?: return@withTransaction
                    val request = biliLibraryJson.decodeFromString(BiliImportRequest.serializer(), intent.requestJson).checked()
                    val ready = request.parts.filter { hasLocalPart(it) }
                    val complete = ready.size == request.parts.size
                    // New split imports publish successful parts individually. Conversions and
                    // grouped imports wait for every requested part before publishing.
                    if (complete || (request.mode == BiliImportMode.SPLIT && db.folders().findBiliVideo(intent.bvid) == null && ready.isNotEmpty())) {
                        apply(request.copy(parts = ready))
                        if (complete) db.biliImports().delete(intent.bvid)
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { if (failure == null) failure = error }
        }
        failure?.let { throw it }
    }
}
