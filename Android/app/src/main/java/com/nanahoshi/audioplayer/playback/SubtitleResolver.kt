package com.nanahoshi.audioplayer.playback

import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.bilibili.BiliSubtitle
import com.nanahoshi.audioplayer.data.LocalAudio
import com.nanahoshi.audioplayer.data.libraryFilePreview
import com.nanahoshi.audioplayer.data.loadWorkSubtitleCatalog
import com.nanahoshi.audioplayer.data.readFullText
import com.nanahoshi.audioplayer.data.readLocalTextFull
import com.nanahoshi.audioplayer.data.resolveLibraryFileSource
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.LibraryFileEntity
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.data.db.TrackSubtitleBindingEntity
import com.nanahoshi.audioplayer.data.db.TrackSubtitleKind
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.readBiliSubtitle
import com.nanahoshi.audioplayer.data.biliSubtitleLanguage
import com.nanahoshi.audioplayer.data.writeBiliSubtitle
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.InputStream

internal suspend fun SubtitleSource.boundCues(binding: TrackSubtitleBindingEntity): TrackSubtitles {
    val kind = when (binding.kind) {
        TrackSubtitleKind.LIBRARY_FILE -> SubtitleSourceKind.LIBRARY_FILE
        TrackSubtitleKind.EXTERNAL_URI -> SubtitleSourceKind.EXTERNAL_URI
        TrackSubtitleKind.APP_FILE -> SubtitleSourceKind.APP_FILE
        else -> SubtitleSourceKind.NONE
    }
    var name = binding.displayName
    val cues = try {
        val text = when (kind) {
            SubtitleSourceKind.LIBRARY_FILE -> binding.fileId?.let { db.files().get(it) }?.let { file ->
                name = name ?: file.name
                val folders = db.folders().all().associateBy { it.id }
                librarySubtitleText(file, folders, db.jaDownloads().fileIds().toSet())
            }
            SubtitleSourceKind.EXTERNAL_URI -> binding.uri?.let { uri ->
                val grant = binding.treeUri ?: uri
                if (context.contentResolver.persistedUriPermissions.none { it.isReadPermission && it.uri.toString() == grant }) null
                else readLocalTextFull(context, uri)
            }
            SubtitleSourceKind.APP_FILE -> binding.uri?.let { readLocalTextFull(context, it) }
            else -> null
        }
        parseSubtitleDocument(text.orEmpty())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyList()
    }
    val notice = if (cues.isEmpty()) {
        context.getString(if (kind == SubtitleSourceKind.EXTERNAL_URI) {
            R.string.player_error_bound_subtitle_uri
        } else R.string.player_error_bound_subtitle_file)
    } else null
    return TrackSubtitles(cues, notice, sourceKind = kind, sourceLabel = name)
}

internal fun SubtitleSource.loadedAttachment(row: QueuedTrack, attachment: TrackSubtitles, binding: TrackSubtitleBindingEntity?): LoadedSubtitles {
    val (cues, outputName) = withOverlay(row, attachment.cues, binding == null && attachment.allowLegacyOverlay)
    val offset = binding?.offsetMs ?: 0L
    return LoadedSubtitles(
        offsetCues(cues, offset), false, false, attachment.notice, attachment.language,
        sourceCues = attachment.cues, sourceKind = attachment.sourceKind, sourceLabel = attachment.sourceLabel,
        hasManualBinding = binding != null, appliedOffsetMs = offset, overlayOutputName = outputName, unshiftedCues = cues,
    )
}

internal fun SubtitleSource.offsetCues(cues: List<SubtitleCue>, offset: Long): List<SubtitleCue> {
    if (offset == 0L) return cues
    fun shifted(time: Long) = (time.coerceIn(0L, Long.MAX_VALUE - offset.coerceAtLeast(0)) + offset).coerceAtLeast(0L)
    return cues.mapNotNull { cue ->
        val start = shifted(cue.startMs)
        val end = shifted(cue.endMs)
        if (end > start) cue.copy(startMs = start, endMs = end) else null
    }
}

internal suspend fun SubtitleSource.bilibiliCues(track: TrackEntity): TrackSubtitles {
    val bvid = track.bvid ?: return TrackSubtitles(emptyList())
    val cid = track.cid ?: return TrackSubtitles(emptyList())
    // 下载音轨时顺带存下的字幕，播放时优先读本地，不再依赖登录和网络。
    readBiliSubtitle(context.filesDir, bvid, cid)?.let { document ->
        val cues = parseSubtitleDocument(document)
        if (cues.isNotEmpty()) return TrackSubtitles(
            cues, language = biliSubtitleLanguage(document), sourceKind = SubtitleSourceKind.BILIBILI,
            allowLegacyOverlay = true,
        )
    }
    return when (val result = bilibili.subtitleDocument(bvid, cid)) {
        is BiliSubtitle.Ready -> {
            val cues = parseSubtitleDocument(result.document)
            // 音轨已经在本地时，顺手把这次拿到的字幕补存下来。
            if (cues.isNotEmpty() && LocalAudio.hasStoredCopy(context, track.localUri)) {
                generatedWriteLock.withLock {
                    val path = if (track.id > 0) db.tracks().getById(track.id)?.localUri else track.localUri
                    if (LocalAudio.hasStoredCopy(context, path)) {
                        writeBiliSubtitle(context.filesDir, bvid, cid, result.document, result.language)
                    }
                }
            }
            TrackSubtitles(cues, language = result.language, sourceKind = SubtitleSourceKind.BILIBILI, allowLegacyOverlay = true)
        }
        BiliSubtitle.NeedsLogin -> TrackSubtitles(emptyList(), context.getString(R.string.player_error_bili_subtitle_login))
        BiliSubtitle.Failed -> TrackSubtitles(emptyList(), context.getString(R.string.player_error_bili_subtitle_load))
        BiliSubtitle.Missing -> TrackSubtitles(emptyList())
    }
}

internal suspend fun SubtitleSource.workCues(track: TrackEntity): TrackSubtitles {
    val catalog = loadWorkSubtitleCatalog(db, track.folderId) ?: return TrackSubtitles(emptyList())
    val match = catalog.match(track, allowsCrossSourceFallback())
    val candidates = WorkSubtitleMatcher.automaticCandidates(match, track, catalog.folders)
    for (candidate in candidates) {
        val cues = try {
            parseSubtitleDocument(librarySubtitleText(candidate.file, catalog.folders, catalog.jaFileIds).orEmpty())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        if (cues.isNotEmpty()) {
            val historyName = if (track.source == TrackSource.ASMR) pickSubtitleName(track.title,
                catalog.files.filter {
                    it.folderId == track.folderId && resolveLibraryFileSource(it, catalog.jaFileIds) == TrackSource.ASMR
                }.map { it.name },
            ) else null
            return TrackSubtitles(
                cues, sourceKind = SubtitleSourceKind.LIBRARY_FILE, sourceLabel = candidate.file.name,
                allowLegacyOverlay = candidate.scope == WorkSubtitleScope.SAME_FOLDER && candidate.file.name == historyName,
            )
        }
    }
    return TrackSubtitles(
        emptyList(), if (candidates.isNotEmpty()) context.getString(R.string.player_error_bound_subtitle_file) else null,
    )
}

private suspend fun SubtitleSource.librarySubtitleText(
    file: LibraryFileEntity, folders: Map<Long, FolderEntity>, jaFileIds: Set<Long>,
): String? {
    try {
        file.localUri?.let { readLocalTextFull(context, it) }?.let { return it }
    } catch (_: java.io.IOException) {
        // A removed local copy can still have a valid online source.
    } catch (_: SecurityException) {
        // A revoked local association can still have a valid online source.
    }
    if (file.remoteUrl.isBlank()) return null
    return when (val source = resolveLibraryFileSource(file, jaFileIds)) {
        TrackSource.ASMR -> asmr.readFullText(file.remoteUrl)
        TrackSource.DLSITE -> dlsite?.readFullText(libraryFilePreview(file, folders, source))
        else -> null
    }
}

internal fun SubtitleSource.localCues(track: TrackEntity): TrackSubtitles {
    val uri = track.localUri ?: return TrackSubtitles(emptyList())
    val siblings = siblingsOf(uri)
    val audioName = audioFileName(uri).ifBlank { track.title }
    val historyName = pickSubtitleName(audioName, siblings.map { it.name })
    val matches = siblings.mapNotNull { candidate ->
        val tier = subtitleNameTier(audioName, candidate.name) ?: return@mapNotNull null
        candidate to (tier.ordinal * 3 + checkNotNull(subtitleFormatRank(candidate.name)))
    }.groupBy { it.second }.toSortedMap()
    for (group in matches.values) {
        val candidate = group.singleOrNull()?.first ?: break
        val cues = parseSubtitleDocument(runCatching { candidate.read() }.getOrNull().orEmpty())
        if (cues.isNotEmpty()) return TrackSubtitles(
            cues, sourceKind = SubtitleSourceKind.LOCAL_FILE, sourceLabel = candidate.name,
            allowLegacyOverlay = candidate.name == historyName,
        )
    }
    return TrackSubtitles(emptyList())
}

private fun SubtitleSource.audioFileName(localUri: String): String {
    val uri = localUri.toUri()
    return if (uri.scheme == "content") {
        LocalAudio.displayName(context, uri)
    } else {
        File(uri.path ?: localUri).name
    }
}

private fun SubtitleSource.siblingsOf(localUri: String): List<NamedBytes> {
    val uri = localUri.toUri()
    if (uri.scheme == "content") return documentSiblings(uri)
    val file = File(if (uri.scheme == "file") uri.path.orEmpty() else localUri)
    val parent = file.parentFile ?: return emptyList()
    return parent.listFiles().orEmpty().filter { it.isFile }.map { child ->
        NamedBytes(child.name) { readLimited(child.inputStream()) }
    }
}

private fun SubtitleSource.documentSiblings(uri: Uri): List<NamedBytes> {
    return try {
        if (!DocumentsContract.isDocumentUri(context, uri)) return emptyList()
        val documentId = DocumentsContract.getDocumentId(uri)
        val isTree = DocumentsContract.isTreeUri(uri)
        val authority = uri.authority ?: return emptyList()
        val slash = documentId.lastIndexOf('/')
        if (slash > 0) {
            val parentId = documentId.substring(0, slash)
            val rows = queryDocumentSiblings(uri, authority, parentId, isTree)
            if (rows != null && (!isTree || rows.any { it.first == documentId })) {
                return rows.map { it.second }
            }
        }
        if (!isTree) return emptyList()
        val treeId = DocumentsContract.getTreeDocumentId(uri)
        findTreeParentSiblings(uri, authority, treeId, documentId).orEmpty()
    } catch (_: Exception) {
        emptyList()
    }
}

private fun SubtitleSource.queryDocumentSiblings(
    scopeUri: Uri,
    authority: String,
    parentId: String,
    isTree: Boolean,
    subfolders: MutableList<String>? = null,
): List<Pair<String, NamedBytes>>? {
    val childrenUri = if (isTree) {
        DocumentsContract.buildChildDocumentsUriUsingTree(scopeUri, parentId)
    } else {
        DocumentsContract.buildChildDocumentsUri(authority, parentId)
    }
    val rows = mutableListOf<Pair<String, NamedBytes>>()
    val cursor = context.contentResolver.query(
        childrenUri,
        arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        ),
        null,
        null,
        null,
    ) ?: return null
    cursor.use {
        val nameColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val idColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val mimeColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
        while (it.moveToNext()) {
            if (nameColumn < 0 || idColumn < 0) continue
            val name = it.getString(nameColumn) ?: continue
            val id = it.getString(idColumn) ?: continue
            val mime = if (mimeColumn >= 0) it.getString(mimeColumn) else null
            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                subfolders?.add(id)
                continue
            }
            val child = if (isTree) {
                DocumentsContract.buildDocumentUriUsingTree(scopeUri, id)
            } else {
                DocumentsContract.buildDocumentUri(authority, id)
            }
            rows += id to NamedBytes(name) { readLimited(context.contentResolver.openInputStream(child)) }
        }
    }
    return rows
}

private fun SubtitleSource.findTreeParentSiblings(
    treeUri: Uri,
    authority: String,
    treeId: String,
    targetDocumentId: String,
): List<NamedBytes>? {
    val visited = hashSetOf(treeId)
    val queue = ArrayDeque<String>().apply { addLast(treeId) }
    var scanned = 0
    while (queue.isNotEmpty() && scanned < MAX_SAF_TREE_FOLDERS) {
        val folderId = queue.removeFirst()
        scanned++
        val subfolders = mutableListOf<String>()
        val rows = queryDocumentSiblings(treeUri, authority, folderId, isTree = true, subfolders = subfolders)
        if (rows != null && rows.any { it.first == targetDocumentId }) {
            return rows.map { it.second }
        }
        for (childFolder in subfolders) {
            if (visited.add(childFolder)) queue.addLast(childFolder)
        }
    }
    return null
}

private fun SubtitleSource.readLimited(input: InputStream?): String? =
    input?.use { it.readFullText() }

private fun String.toUri(): Uri = when {
    startsWith("content:") || startsWith("file:") -> Uri.parse(this)
    else -> Uri.EMPTY
}

private class NamedBytes(val name: String, val read: () -> String?)

private const val MAX_SAF_TREE_FOLDERS = 500