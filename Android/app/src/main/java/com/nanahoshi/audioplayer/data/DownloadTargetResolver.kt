package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.data.db.DownloadEntity
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.japaneseasmr.resolveQueuedJaMatch
import com.nanahoshi.audioplayer.japaneseasmr.resolveStoredJaMatches
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 下载任务与音频库曲目的身份解析。 */
internal class DownloadTargetResolver(private val center: DownloadCenter) {
    private val db get() = center.db
    private val library get() = center.library

    internal data class LibraryTarget(val trackId: Long, val folderId: Long? = null)

    suspend fun libraryTrack(row: QueuedTrack): TrackEntity? {
        row.trackId?.let { id ->
            db.tracks().getById(id)?.takeIf { it.source == row.source }?.let { return it }
        }
        return when (row.source) {
            TrackSource.BILIBILI -> {
                val bvid = row.bvid ?: row.workno ?: return null
                val cid = row.cid ?: row.fileKey?.toLongOrNull() ?: return null
                db.tracks().findByBvid(bvid, cid)
            }
            TrackSource.YOUTUBE -> (row.youtubeVideoId ?: row.workno)?.let { db.tracks().findYouTube(it) }
            TrackSource.ASMR, TrackSource.DLSITE -> onlineTrack(row.source, row.workno, row.fileKey)
            TrackSource.JAPANESE_ASMR -> jaTrack(row.workno, row.fileKey)
            TrackSource.LOCAL -> null
        }
    }

    suspend fun japaneseRoot(row: QueuedTrack): Long? {
        if (row.source != TrackSource.JAPANESE_ASMR) return null
        val postId = row.workno ?: return null
        val post = db.japaneseAsmr().post(postId)
        val rj = post?.rj ?: library.japaneseAsmr.cachedArticle(postId)?.post?.rj ?: return null
        return library.folderBySource(rj)?.id
    }

    suspend fun prepareJapaneseTrack(row: QueuedTrack): TrackEntity? {
        val root = japaneseRoot(row) ?: return null
        val postId = row.workno ?: return null
        val expected = db.japaneseAsmr().post(postId)?.rj
            ?: library.japaneseAsmr.cachedArticle(postId)?.post?.rj ?: return null
        val article = library.japaneseAsmr.article(postId, expected)
        val key = row.fileKey ?: return null
        val stored = resolveStoredJaMatches(article, postId, db.japaneseAsmr())
        val match = resolveQueuedJaMatch(article, key, row.mediaPathHash, stored, db.queue().snapshot())
        val media = match.media ?: return null
        library.importJapaneseAsmrPost(article, root)
        val resolved = resolveStoredJaMatches(article, postId, db.japaneseAsmr())
            .entries.firstOrNull { it.value.media?.key == media.key }?.key ?: return null
        return jaTrack(postId, resolved)
    }

    private suspend fun jaTrack(postId: String?, key: String?): TrackEntity? =
        if (postId == null || key == null) null else db.japaneseAsmr().track(postId, key)

    private suspend fun onlineTrack(source: TrackSource, workno: String?, key: String?): TrackEntity? {
        val raw = rawFileKey(key) ?: return null
        val numericAsmrId = workno?.toLongOrNull()?.takeIf { source == TrackSource.ASMR }
        val work = if (numericAsmrId == null) workno?.let { library.folderBySource(it) } else null
        val workId = numericAsmrId ?: work?.asmrWorkId ?: return null
        return (db.tracks().findAsmr(workId, scopedFileHash(source, raw))
            ?: db.tracks().findAsmr(workId, raw))?.takeIf { track ->
            track.source == source && (work == null ||
                worknoOf(track)?.let(::canonicalWorkKey) == work.asmrSourceId?.let(::canonicalWorkKey))
        }
    }

    suspend fun libraryTarget(job: DownloadEntity): LibraryTarget? {
        if (job.kind == com.nanahoshi.audioplayer.japaneseasmr.JA_WORK_DOWNLOAD) {
            val rj = job.workKey ?: return null
            val post = job.fileKey?.let { db.japaneseAsmr().post(it) } ?: return null
            val root = library.folderBySource(rj) ?: return null
            if (post.rootFolderId != root.id || !post.rj.equals(rj, ignoreCase = true) ||
                job.workFolderId?.let { it != root.id } == true) return null
            return LibraryTarget(0L, root.id)
        }
        if (job.kind == "DLSITE_ARCHIVE") {
            val folder = job.workKey?.let { library.folderBySource(it) } ?: return null
            if (job.workFolderId != null && job.workFolderId != folder.id) return null
            return LibraryTarget(0L, folder.id)
        }
        val source = TrackSource.entries.firstOrNull { it.name == job.source }
        if (source == TrackSource.JAPANESE_ASMR) {
            val track = jaTrack(job.workKey, job.fileKey) ?: return null
            val post = db.japaneseAsmr().post(job.workKey ?: return null) ?: return null
            val root = post.rootFolderId?.let { db.folders().get(it) } ?: return null
            if (track.folderId != (post.articleFolderId ?: root.id) ||
                root.asmrSourceId?.let(::canonicalWorkKey) != canonicalWorkKey(post.rj)
            ) return null
            return LibraryTarget(track.id, root.id)
        }
        val bound = job.trackId.takeIf { it > 0 }?.let { db.tracks().getById(it) }
        val track = when {
            bound != null -> bound
            source == TrackSource.BILIBILI -> {
                val bvid = job.workKey ?: return null
                val cid = job.fileKey?.toLongOrNull() ?: return null
                db.tracks().findByBvid(bvid, cid)
            }
            source == TrackSource.YOUTUBE -> job.workKey?.let { db.tracks().findYouTube(it) }
            source == TrackSource.ASMR || source == TrackSource.DLSITE -> onlineTrack(source, job.workKey, job.fileKey)
            else -> null
        } ?: return null
        if (track.source == TrackSource.LOCAL || (source != null && source != track.source)) return null
        if (source == TrackSource.BILIBILI && (track.bvid != job.workKey || track.cid?.toString() != job.fileKey)) return null
        if (source == TrackSource.YOUTUBE && track.youtubeVideoId != job.workKey) return null
        if (source in setOf(TrackSource.ASMR, TrackSource.DLSITE) && job.workKey != null) {
            val numericAsmrId = job.workKey.toLongOrNull()?.takeIf { source == TrackSource.ASMR }
            val matchesWork = if (numericAsmrId != null) track.asmrWorkId == numericAsmrId
                else worknoOf(track)?.let(::canonicalWorkKey) == canonicalWorkKey(job.workKey)
            if (!matchesWork) return null
        }
        if (source in setOf(TrackSource.ASMR, TrackSource.DLSITE) && job.fileKey != null &&
            rawFileKey(track.fileHash) != rawFileKey(job.fileKey)
        ) return null
        return LibraryTarget(track.id)
    }

    suspend fun retryTrack(job: DownloadEntity): TrackEntity? =
        libraryTarget(job.copy(workFolderId = null))?.trackId?.takeIf { it > 0 }?.let { db.tracks().getById(it) }

    suspend fun finishTrackDownload(job: DownloadEntity, file: File) {
        db.tracks().updateLocalUri(job.trackId, file.absolutePath)
        db.downloads().setLocalPath(job.id, file.absolutePath)
        db.downloads().finish(job.id, file.length(), file.length())
    }

    suspend fun bindLibraryTarget(job: DownloadEntity, target: LibraryTarget) {
        val track = target.trackId.takeIf { it > 0L }?.let { db.tracks().getById(it) }
        val source = track?.source?.name ?: job.source
        val work = when (track?.source) {
            TrackSource.BILIBILI -> track.bvid
            TrackSource.YOUTUBE -> track.youtubeVideoId
            else -> job.workKey
        }
        val file = when (track?.source) {
            TrackSource.BILIBILI -> track.cid?.toString()
            TrackSource.YOUTUBE -> "audio"
            else -> job.fileKey
        }
        db.downloads().bindLibraryTarget(job.id, target.trackId, target.folderId, source, work, file)
    }

    suspend fun requireRunningTarget(job: DownloadEntity) {
        if (db.downloads().get(job.id)?.status != DownloadStatus.RUNNING) throw CancellationException()
        if (job.trackId > 0) check(db.tracks().getById(job.trackId) != null) { DOWNLOAD_LIBRARY_REQUIRED }
        check(libraryTarget(job) == LibraryTarget(job.trackId, job.workFolderId)) {
            if (job.kind == com.nanahoshi.audioplayer.japaneseasmr.JA_WORK_DOWNLOAD) com.nanahoshi.audioplayer.text.PersistedErrors.JA_POST_REMOVED else DOWNLOAD_LIBRARY_REQUIRED
        }
    }

    suspend fun worknoOf(track: TrackEntity): String? {
        var folderId = track.folderId
        while (folderId != null) {
            val folder = db.folders().get(folderId) ?: break
            if (!folder.asmrSourceId.isNullOrBlank()) return folder.asmrSourceId
            folderId = folder.parentId
        }
        return null
    }

    suspend fun savedRemotePath(source: TrackSource, workKey: String, fileKey: String?): String? = withContext(Dispatchers.IO) {
        if (source == TrackSource.JAPANESE_ASMR) return@withContext localCopy(source.name, workKey, fileKey)
        val key = canonicalWorkKey(workKey)
        if (source == TrackSource.DLSITE && !fileKey.isNullOrBlank()) {
            db.dlsiteOriginals().entryForPlay(key, rawFileKey(fileKey) ?: fileKey)?.localPath
                ?.takeIf { File(it).isFile }?.let { return@withContext it }
        }
        workKeys(workKey).firstNotNullOfOrNull { candidate -> localCopy(source.name, candidate, fileKey) }
    }

    suspend fun downloadedKeys(source: TrackSource, workKey: String): Set<String> = withContext(Dispatchers.IO) {
        val keys = if (source == TrackSource.JAPANESE_ASMR) listOf(workKey) else workKeys(workKey)
        buildSet {
            if (source == TrackSource.DLSITE) {
                db.dlsiteOriginals().entries(canonicalWorkKey(workKey)).forEach { entry ->
                    if (File(entry.localPath).isFile) entry.playFileKey?.let { add(it) }
                }
            }
            keys.forEach { key ->
                db.downloads().finishedForWork(source.name, key).forEach { row ->
                    val path = row.localPath
                    val fileKey = rawFileKey(row.fileKey) ?: return@forEach
                    if (!path.isNullOrBlank() && File(path).isFile) add(fileKey)
                }
            }
        }
    }

    private suspend fun localCopy(source: String, workKey: String, fileKey: String?): String? {
        if (fileKey.isNullOrBlank()) return null
        return db.downloads().finishedForWork(source, workKey)
            .firstOrNull { (if (source == TrackSource.JAPANESE_ASMR.name) it.fileKey else rawFileKey(it.fileKey)) == fileKey && File(it.localPath.orEmpty()).length() > 0L && !it.localPath.isNullOrBlank() && File(it.localPath).isFile }
            ?.localPath
    }

    fun canonicalWorkKey(workKey: String): String = try {
        com.nanahoshi.audioplayer.dlsite.DlsiteClient.canonicalWorkno(workKey)
    } catch (_: Exception) {
        workKey
    }

    private fun workKeys(workKey: String): List<String> {
        val canonical = canonicalWorkKey(workKey)
        return listOf(workKey, canonical).distinct()
    }
}