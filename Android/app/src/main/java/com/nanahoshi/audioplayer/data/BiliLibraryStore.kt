package com.nanahoshi.audioplayer.data

import android.content.Context
import androidx.room.withTransaction
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.bilibili.*
import com.nanahoshi.audioplayer.data.db.*
import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.UserFacingException

/** 保存作品分组及其分 P，沿用现有曲目编号。 */
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
        if (!(current == null || current == request.mode || request.conversionConfirmed)) {
            throw UserFacingException(TextRef.Res(R.string.bilibili_import_mode_changed))
        }
    }

    suspend fun save(request: BiliImportRequest): List<TrackEntity> = db.withTransaction {
        val checked = request.checked()
        validateConversion(checked)
        apply(checked)
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
                if (!(db.folders().childrenOf(oldGroup.id).isEmpty() && db.files().listIn(oldGroup.id).isEmpty() &&
                        db.tracks().inFolder(oldGroup.id).all { it.bvid == bvid })) {
                    throw UserFacingException(TextRef.Res(R.string.bilibili_import_group_not_empty))
                }
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

}
