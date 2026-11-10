package com.nanahoshi.audioplayer.data

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.room.withTransaction
import com.nanahoshi.audioplayer.asmr.WorkFacts
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.data.db.DlsiteOriginalEntryEntity
import com.nanahoshi.audioplayer.data.db.DlsiteOriginalWorkEntity
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.FileKind
import com.nanahoshi.audioplayer.data.db.LibraryFileEntity
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.dlsite.DlsiteClient
import com.nanahoshi.audioplayer.dlsite.PlayEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer

object DlsiteOriginalKind {
    const val FOLDER = "FOLDER"
    const val AUDIO = "AUDIO"
    const val IMAGE = "IMAGE"
    const val TEXT = "TEXT"
    const val OTHER = "OTHER"
}

data class DlsiteOriginalImportResult(
    val workno: String,
    val rootFolderId: Long,
    val audioCount: Int,
    val linkedCount: Int,
    /** 已关联其他曲目而保持不变的相对路径。 */
    val skipped: List<String> = emptyList(),
)

/** Publishes an already extracted generation without rebuilding existing track identities. */
class DlsiteOriginalStore(
    context: Context,
    private val db: AppDatabase,
    private val dlsite: DlsiteClient,
    private val library: LibraryRepository,
    private val loadPlayTree: suspend (String, String?, String?) -> List<PlayEntry> = dlsite::playTree,
) {
    private val filesDir = context.applicationContext.filesDir.canonicalFile

    suspend fun importDirectory(
        workno: String,
        directory: File,
        generation: String,
        beforeCommit: suspend () -> Unit = {},
        onPublish: suspend () -> Unit = {},
    ): DlsiteOriginalImportResult = withContext(Dispatchers.IO) {
        val code = DlsiteClient.canonicalWorkno(workno)
        val rootDirectory = directory.canonicalFile
        require(rootDirectory.isDirectory && rootDirectory.path.startsWith(filesDir.path + File.separator)) {
            "找不到已解压的完整原始包文件"
        }
        val disk = scanOriginalDirectory(rootDirectory)
        require(disk.any { it.kind != DlsiteOriginalKind.FOLDER }) { "完整原始包里没有文件" }
        val originalLeaves = disk.filter { it.kind != DlsiteOriginalKind.FOLDER }.map { it.path }
        val previousRoot = library.folderBySource(code)
        val shown = if (previousRoot == null) optional { dlsite.showcase(code) } else null
        val facts = WorkFacts.fromJson(previousRoot?.workMeta) ?: shown?.facts() ?: WorkFacts(
            title = previousRoot?.name ?: code,
            circle = "",
            vas = emptyList(),
            tags = emptyList(),
            release = "",
            sourceId = code,
        )
        val cover = previousRoot?.coverUri ?: shown?.coverUrl
        // Network access happens before opening the database transaction. Existing entries still
        // provide the Play identity if the optional catalogue refresh is temporarily unavailable.
        val sameGeneration = db.dlsiteOriginals().work(code)?.generation == generation
        val play = if (sameGeneration) emptyList() else optional { loadPlayTree(code, facts.circle.ifBlank { null }, cover) }
            ?.let(::flattenOriginalPlay).orEmpty()
        val playPaths = matchDlsiteOriginalPaths(originalLeaves, play.map { it.path })
        val playByPath = play.associateBy { it.path }
        val durations = disk.filter { it.kind == DlsiteOriginalKind.AUDIO }
            .associate { it.path to audioDuration(it.file) }
        db.withTransaction {
            beforeCommit()
            val now = System.currentTimeMillis()
            val root = library.folderBySource(code) ?: FolderEntity(
                parentId = null,
                name = sanitizeEntryName("$code ${facts.title}"),
                position = db.folders().maxPosition(null) + 1,
                addedAt = now,
                asmrWorkId = code.filter { it.isDigit() }.toLongOrNull() ?: 0L,
                asmrSourceId = code,
                coverUri = cover,
                workMeta = facts.copy(sourceId = code).toJson(),
            ).let { it.copy(id = db.folders().insert(it)) }
            val workId = root.asmrWorkId ?: code.filter { it.isDigit() }.toLongOrNull() ?: 0L
            val oldEntries = db.dlsiteOriginals().entries(code).associateBy { it.relativePath }
            val folders = db.folders().all()
            val byFolderId = folders.associateBy { it.id }
            val folderIds = originalFolderIds(folders, root.id)
            val oldTracks = db.tracks().inFolders(folderIds.toList()).filter { it.source == TrackSource.DLSITE }
            val oldFiles = folderIds.flatMap { id -> db.files().listIn(id) }
                .filter { libraryFileSource(it.fileHash) == TrackSource.DLSITE }
            val trackPaths = oldTracks.mapNotNull { track ->
                relativeLibraryPath(track.folderId, track.title, root.id, byFolderId)?.let { it to track }
            }
            val filePaths = oldFiles.mapNotNull { file ->
                relativeLibraryPath(file.folderId, file.name, root.id, byFolderId)?.let { it to file }
            }
            val oldLeafPaths = trackPaths.map { it.first } + filePaths.map { it.first }
            val matchedPaths = matchDlsiteOriginalPaths(originalLeaves, oldLeafPaths)
            val tracksByPath = trackPaths.groupBy({ it.first }, { it.second })
            val filesByPath = filePaths.groupBy({ it.first }, { it.second })
            val tracksByPlay = oldTracks.filter { !it.fileHash.isNullOrBlank() }.groupBy { rawFileKey(it.fileHash) }
            val directoryIds = mutableMapOf("" to root.id)
            val usedFolderIds = mutableSetOf(root.id)
            val usedTrackIds = mutableSetOf<Long>()
            val usedFileIds = mutableSetOf<Long>()
            val entries = mutableListOf<DlsiteOriginalEntryEntity>()
            var linked = 0
            val skipped = mutableListOf<String>()
            for (item in disk) {
                currentCoroutineContext().ensureActive()
                val parentPath = item.path.substringBeforeLast('/', "")
                val parentId = directoryIds.getValue(parentPath)
                val name = item.path.substringAfterLast('/')
                val previous = oldEntries[item.path]
                if (item.kind == DlsiteOriginalKind.FOLDER) {
                    val prior = previous?.folderId?.let { byFolderId[it] }?.takeIf {
                        it.id != root.id && it.contentSource == TrackSource.DLSITE.name && it.id !in usedFolderIds
                    }
                    val compatible = folders.filter {
                        it.parentId == parentId && it.contentSource == TrackSource.DLSITE.name &&
                            matchKey(it.name) == matchKey(name) && it.id !in usedFolderIds
                    }.singleOrNull()
                    val folder = (prior ?: compatible)?.copy(
                        parentId = parentId, name = name, asmrWorkId = workId, contentSource = TrackSource.DLSITE.name,
                    ) ?: FolderEntity(
                        parentId = parentId, name = name, position = db.folders().maxPosition(parentId) + 1,
                        addedAt = now, asmrWorkId = workId, contentSource = TrackSource.DLSITE.name,
                    )
                    val id = if (folder.id > 0) {
                        db.folders().update(folder)
                        folder.id
                    } else db.folders().insert(folder)
                    usedFolderIds += id
                    directoryIds[item.path] = id
                    entries += item.entry(code, folderId = id)
                    continue
                }
                val remote = playPaths[item.path]?.let { playByPath[it] }?.node
                if (item.kind == DlsiteOriginalKind.AUDIO) {
                    val previousTrack = previous?.trackId?.let { db.tracks().getById(it) }
                        ?.takeIf { it.source == TrackSource.DLSITE && it.id !in usedTrackIds }
                    val playKey = remote?.audio?.fileKey?.takeIf { it.isNotBlank() }
                    val byKey = playKey?.let { tracksByPlay[it]?.singleOrNull() }?.takeIf { it.id !in usedTrackIds }
                    val byPath = matchedPaths[item.path]?.let { tracksByPath[it]?.singleOrNull() }
                        ?.takeIf { it.id !in usedTrackIds }
                    val old = previousTrack ?: byKey ?: byPath
                    val track = old?.copy(
                        title = name, folderId = parentId, localUri = item.file.absolutePath,
                        durationMs = durations.getValue(item.path).takeIf { it > 0 } ?: old.durationMs,
                    ) ?: TrackEntity(
                        source = TrackSource.DLSITE, title = name, artist = facts.circle.ifBlank { code },
                        durationMs = durations.getValue(item.path), coverUri = cover, localUri = item.file.absolutePath,
                        bvid = null, cid = null, page = null, addedAt = now, folderId = parentId,
                        remoteUrl = remote?.audio?.remoteUrl, asmrWorkId = workId,
                        fileHash = playKey?.let { scopedFileHash(TrackSource.DLSITE, it) },
                    )
                    val owner = db.tracks().findByLocalUri(item.file.absolutePath)
                    if (owner != null && owner.id != track.id) {
                        // 文件已被别的曲目占用：保留原关联并跳过本条，不让整个发布回滚。
                        skipped += item.path
                        usedTrackIds += owner.id
                        entries += item.entry(code, trackId = owner.id, playFileKey = playKey
                            ?: rawFileKey(track.fileHash) ?: previous?.playFileKey)
                        continue
                    }
                    val id = if (track.id > 0) {
                        db.tracks().update(track)
                        linked++
                        track.id
                    } else db.tracks().insert(track)
                    usedTrackIds += id
                    entries += item.entry(code, trackId = id, playFileKey = playKey
                        ?: rawFileKey(track.fileHash) ?: previous?.playFileKey)
                } else {
                    val previousFile = previous?.fileId?.let { db.files().get(it) }
                        ?.takeIf { libraryFileSource(it.fileHash) == TrackSource.DLSITE && it.id !in usedFileIds }
                    val byPath = matchedPaths[item.path]?.let { filesByPath[it]?.singleOrNull() }
                        ?.takeIf { it.id !in usedFileIds }
                    val old = previousFile ?: byPath
                    val fileKind = if (item.kind == DlsiteOriginalKind.TEXT) FileKind.TEXT else previewKind(name)
                    val file = old?.copy(folderId = parentId, name = name, kind = fileKind,
                        localUri = item.file.absolutePath) ?: LibraryFileEntity(
                        folderId = parentId, name = name, kind = fileKind,
                        remoteUrl = remote?.preview?.url.orEmpty(), addedAt = now, asmrWorkId = workId,
                        fileHash = scopedFileHash(TrackSource.DLSITE, "original:${item.path}"),
                        localUri = item.file.absolutePath,
                    )
                    val id = if (file.id > 0) {
                        db.files().update(file)
                        file.id
                    } else db.files().insert(file)
                    usedFileIds += id
                    entries += item.entry(code, fileId = id)
                }
            }
            db.dlsiteOriginals().deleteEntries(code)
            db.dlsiteOriginals().upsertEntries(entries)
            db.dlsiteOriginals().upsertWork(DlsiteOriginalWorkEntity(code, root.id, rootDirectory.absolutePath, generation))
            beforeCommit()
            onPublish()
            DlsiteOriginalImportResult(code, root.id, usedTrackIds.size, linked, skipped)
        }
    }

    private suspend fun <T> optional(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

private data class OriginalDiskEntry(val path: String, val file: File, val kind: String) {
    fun entry(code: String, playFileKey: String? = null, trackId: Long? = null,
              fileId: Long? = null, folderId: Long? = null) = DlsiteOriginalEntryEntity(
        workno = code, relativePath = path, kind = kind, size = if (file.isFile) file.length() else 0L,
        localPath = file.absolutePath, playFileKey = playFileKey, trackId = trackId, fileId = fileId, folderId = folderId,
    )
}

private fun scanOriginalDirectory(root: File): List<OriginalDiskEntry> = root.walkTopDown().drop(1).map { file ->
    require(file.canonicalPath == file.absolutePath && file.canonicalPath.startsWith(root.path + File.separator)) {
        "完整原始包目录包含不安全的路径"
    }
    val path = file.relativeTo(root).invariantSeparatorsPath
    val kind = when {
        file.isDirectory -> DlsiteOriginalKind.FOLDER
        LocalAudio.isAudio(file.name) -> DlsiteOriginalKind.AUDIO
        previewKind(file.name) == com.nanahoshi.audioplayer.data.db.FileKind.IMAGE -> DlsiteOriginalKind.IMAGE
        previewKind(file.name) == com.nanahoshi.audioplayer.data.db.FileKind.TEXT || isSubtitleFile(file.name) -> DlsiteOriginalKind.TEXT
        else -> DlsiteOriginalKind.OTHER
    }
    OriginalDiskEntry(path, file, kind)
}.sortedWith(compareBy<OriginalDiskEntry> { it.path.count { char -> char == '/' } }
    .thenBy { if (it.kind == DlsiteOriginalKind.FOLDER) 0 else 1 }.thenBy { it.path }).toList()

private fun audioDuration(file: File): Long {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.coerceAtLeast(0) ?: 0
    } catch (_: Exception) {
        0
    } finally {
        runCatching { retriever.release() }
    }
}

private data class OriginalPlayPath(val path: String, val node: PlayEntry)

private fun originalFolderIds(folders: List<FolderEntity>, rootId: Long): Set<Long> {
    val children = folders.groupBy { it.parentId }
    val found = mutableSetOf<Long>()
    val pending = ArrayDeque<Long>()
    pending.add(rootId)
    while (pending.isNotEmpty()) {
        val id = pending.removeFirst()
        if (found.add(id)) children[id].orEmpty().forEach { pending.add(it.id) }
    }
    return found
}

private fun flattenOriginalPlay(nodes: List<PlayEntry>): List<OriginalPlayPath> = buildList {
    fun addNodes(level: List<PlayEntry>, prefix: String) {
        for (node in level) {
            val path = if (prefix.isEmpty()) node.title else "$prefix/${node.title}"
            if (node.children.isNotEmpty()) addNodes(node.children, path) else add(OriginalPlayPath(path, node))
        }
    }
    addNodes(nodes, "")
}

private fun relativeLibraryPath(folderId: Long?, name: String, rootId: Long,
                                folders: Map<Long, FolderEntity>): String? {
    val names = ArrayDeque<String>()
    var current = folderId
    val visited = mutableSetOf<Long>()
    while (current != rootId) {
        if (current == null || !visited.add(current)) return null
        val folder = folders[current] ?: return null
        names.addFirst(folder.name)
        current = folder.parentId
    }
    names.addLast(name)
    return names.joinToString("/")
}

/** Matches complete paths, optionally removing one unambiguous outer directory on either side. */
internal fun matchDlsiteOriginalPaths(originalPaths: List<String>, candidatePaths: List<String>): Map<String, String> {
    if (originalPaths.isEmpty() || candidatePaths.isEmpty()) return emptyMap()
    fun wrapper(paths: List<String>): String? {
        if (paths.any { '/' !in it }) return null
        return paths.map { it.substringBefore('/') }.distinct().singleOrNull()
    }
    fun variants(paths: List<String>): List<List<Pair<String, String>>> = buildList {
        add(paths.map { it to it })
        if (wrapper(paths) != null) add(paths.map { it to it.substringAfter('/') })
    }
    fun exact(path: String) = Normalizer.normalize(path, Normalizer.Form.NFC)
    fun legacy(path: String): String {
        // Old library names were sanitised and truncated. Never let that truncation erase
        // the distinction between a WAV and an MP3 with the same long prefix.
        val name = Normalizer.normalize(path.substringAfterLast('/'), Normalizer.Form.NFKC)
        val extension = name.substringAfterLast('.', "").lowercase()
        return path.split('/').joinToString("/") { matchKey(it) } + "\u0000" + extension
    }
    val matches = mutableListOf<Map<String, String>>()
    for (original in variants(originalPaths)) for (candidate in variants(candidatePaths)) {
        val found = linkedMapOf<String, String>()
        val used = mutableSetOf<String>()
        for (key: (String) -> String in listOf(::exact, ::legacy)) {
            val left = original.filter { it.first !in found }.groupBy { key(it.second) }
            val right = candidate.filter { it.first !in used }.groupBy { key(it.second) }
            for ((path, items) in left) {
                val other = right[path]
                if (items.size == 1 && other?.size == 1) {
                    found[items.single().first] = other.single().first
                    used += other.single().first
                }
            }
        }
        matches += found
    }
    val best = matches.filter { it.size == matches.maxOf { match -> match.size } }
    // Conflicting equally plausible wrapper interpretations never choose a track arbitrarily.
    return best.first().filter { (path, candidate) -> best.all { it[path] == candidate } }
}
