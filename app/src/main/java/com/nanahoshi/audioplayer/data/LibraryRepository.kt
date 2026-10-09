package com.nanahoshi.audioplayer.data

import android.content.Context
import android.net.Uri
import com.nanahoshi.audioplayer.asmr.AsmrClient
import com.nanahoshi.audioplayer.asmr.AsmrNode
import com.nanahoshi.audioplayer.asmr.AsmrWork
import com.nanahoshi.audioplayer.asmr.WorkFacts
import com.nanahoshi.audioplayer.bilibili.BiliPart
import com.nanahoshi.audioplayer.dlsite.DlsiteClient
import com.nanahoshi.audioplayer.dlsite.PlayEntry
import com.nanahoshi.audioplayer.dlsite.RemotePlayable
import com.nanahoshi.audioplayer.bilibili.BilibiliClient
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.LibraryFileEntity
import com.nanahoshi.audioplayer.data.db.PlaylistEntity
import com.nanahoshi.audioplayer.data.db.PlaylistItemEntity
import com.nanahoshi.audioplayer.data.db.PlaylistTrack
import com.nanahoshi.audioplayer.data.db.QueueItemEntity
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import com.nanahoshi.audioplayer.data.db.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

enum class WorkFill {
    UNCHANGED,
    FILLED,
    EMPTY,
}

class LibraryRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val bilibili: BilibiliClient,
    private val asmr: AsmrClient,
    private val dlsite: DlsiteClient,
) {
    fun observeTracks(): Flow<List<TrackEntity>> = db.tracks().observeAll()

    fun observeAllFolders(): Flow<List<FolderEntity>> = db.folders().observeAll()

    suspend fun allFolders(): List<FolderEntity> = withContext(Dispatchers.IO) {
        db.folders().all()
    }

    fun observeAllFiles(): Flow<List<LibraryFileEntity>> = db.files().observeAll()

    fun observeTracksIn(folderId: Long?): Flow<List<TrackEntity>> = db.tracks().observeIn(folderId)

    suspend fun track(id: Long): TrackEntity? = withContext(Dispatchers.IO) {
        db.tracks().getById(id)
    }

    suspend fun tracksIn(folderId: Long?): List<TrackEntity> = withContext(Dispatchers.IO) {
        db.tracks().inFolder(folderId)
    }

    suspend fun ensureWorkFiles(folderId: Long): WorkFill = withContext(Dispatchers.IO) {
        val folder = db.folders().get(folderId) ?: return@withContext WorkFill.UNCHANGED
        val sourceId = folder.asmrSourceId ?: return@withContext WorkFill.UNCHANGED
        if (db.folders().childrenOf(folderId).isNotEmpty()) return@withContext WorkFill.UNCHANGED
        if (db.tracks().inFolder(folderId).isNotEmpty()) return@withContext WorkFill.UNCHANGED
        if (db.files().listIn(folderId).isNotEmpty()) return@withContext WorkFill.UNCHANGED
        val facts = WorkFacts.fromJson(folder.workMeta) ?: WorkFacts(
            title = folder.name,
            circle = "",
            vas = emptyList(),
            tags = emptyList(),
            release = "",
            sourceId = sourceId,
        )
        val workId = folder.asmrWorkId ?: sourceId.filter { it.isDigit() }.toLongOrNull() ?: 0L
        val purchased = try {
            dlsite.isPurchased(sourceId)
        } catch (_: Exception) {
            false
        }
        if (purchased) {
            val nodes = try {
                dlsite.playTree(sourceId, facts.circle.ifBlank { null }, folder.coverUri)
            } catch (_: Exception) {
                emptyList()
            }
            if (nodes.isNotEmpty()) {
                importPlayNodes(nodes, folderId, workId, facts, folder.coverUri, "")
                return@withContext WorkFill.FILLED
            }
        }
        val loaded = try {
            asmr.loadWork(sourceId)
        } catch (_: Exception) {
            null
        }
        if (loaded != null && loaded.nodes.isNotEmpty()) {
            importAsmrNodes(loaded.nodes, folderId, loaded, "")
            return@withContext WorkFill.FILLED
        }
        WorkFill.EMPTY
    }

    suspend fun ensureWorkMeta(folderId: Long) = withContext(Dispatchers.IO) {
        val folder = db.folders().get(folderId) ?: return@withContext
        val sourceId = folder.asmrSourceId ?: return@withContext
        if (!folder.workMeta.isNullOrBlank()) return@withContext
        db.folders().setWorkMeta(folderId, asmr.facts(sourceId).toJson())
    }

    suspend fun hasAsmrWork(sourceId: String): Boolean = withContext(Dispatchers.IO) {
        db.folders().findBySourceId(sourceId) != null
    }

    fun observeFilesIn(folderId: Long?): Flow<List<LibraryFileEntity>> = db.files().observeIn(folderId)

    fun observeChildFolders(parentId: Long?): Flow<List<FolderEntity>> = db.folders().observeChildren(parentId)

    fun observeFolder(id: Long): Flow<FolderEntity?> = db.folders().observe(id)

    fun observeQueue(): Flow<List<QueuedTrack>> = db.queue().observe()

    fun observePlaylists(): Flow<List<PlaylistEntity>> = db.playlists().observeAll()

    fun observePlaylist(id: Long): Flow<PlaylistEntity?> = db.playlists().observe(id)

    fun observePlaylistTracks(id: Long): Flow<List<PlaylistTrack>> = db.playlists().observeTracks(id)

    suspend fun importFile(uri: Uri, folderId: Long? = null): TrackEntity = withContext(Dispatchers.IO) {
        upsertLocal(uri, folderId)
    }

    suspend fun importTree(uri: Uri, folderId: Long?, keepStructure: Boolean): Int = withContext(Dispatchers.IO) {
        val root = LocalAudio.readTree(context, uri) ?: return@withContext 0
        if (keepStructure) {
            importNode(root, folderId)
        } else {
            root.allFiles().map { upsertLocal(it, folderId) }.size
        }
    }

    suspend fun createFolder(parentId: Long?, name: String): Long = withContext(Dispatchers.IO) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) error("请填写文件夹名称")
        val position = db.folders().maxPosition(parentId) + 1
        db.folders().insert(
            FolderEntity(
                parentId = parentId,
                name = trimmed,
                position = position,
                addedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun folder(id: Long): FolderEntity? = withContext(Dispatchers.IO) {
        db.folders().get(id)
    }

    suspend fun moveChoices(excludedFolderIds: Set<Long>): List<FolderChoice> = withContext(Dispatchers.IO) {
        val folders = db.folders().all()
        val hidden = descendantFolderIds(folders, excludedFolderIds)
        val byId = folders.associateBy { it.id }
        buildList {
            add(FolderChoice(id = null, label = "音频库"))
            folders.filter { it.id !in hidden }
                .map { folder -> FolderChoice(folder.id, folderPath(folder, byId)) }
                .sortedBy { it.label }
                .forEach(::add)
        }
    }

    suspend fun moveItems(trackIds: List<Long>, folderIds: List<Long>, targetFolderId: Long?) = withContext(Dispatchers.IO) {
        val folders = db.folders().all()
        val movedFolders = descendantFolderIds(folders, folderIds.toSet())
        if (targetFolderId != null && targetFolderId in movedFolders) {
            error("不能移动到自己里面")
        }
        folderIds.forEach { db.folders().setParent(it, targetFolderId) }
        val carried = trackIdsIn(movedFolders)
        trackIds.filterNot { it in carried }.forEach { db.tracks().setFolder(it, targetFolderId) }
    }

    suspend fun affectedTrackIds(trackIds: List<Long>, folderIds: List<Long>): Set<Long> = withContext(Dispatchers.IO) {
        val folders = db.folders().all()
        val doomedFolders = descendantFolderIds(folders, folderIds.toSet())
        trackIdsIn(doomedFolders) + trackIds
    }

    suspend fun deleteItems(trackIds: List<Long>, folderIds: List<Long>) = withContext(Dispatchers.IO) {
        folderIds.forEach { db.folders().delete(it) }
        trackIds.forEach { db.tracks().delete(it) }
        compactQueue()
    }

    suspend fun orderedSelection(
        currentFolderId: Long?,
        selectedFolderIds: Set<Long>,
        selectedTrackIds: Set<Long>,
        sort: LibrarySort,
    ): List<TrackEntity> = withContext(Dispatchers.IO) {
        val folders = db.folders().all()
        val byParent = folders.groupBy { it.parentId }
        fun children(parentId: Long?) = byParent[parentId].orEmpty().sortedForLibrary(sort)
        val orderedIds = mutableListOf<Long>()
        val seen = mutableSetOf<Long>()
        fun addTrack(id: Long) {
            if (seen.add(id)) orderedIds += id
        }
        suspend fun expand(folderId: Long) {
            for (child in children(folderId)) expand(child.id)
            for (track in db.tracks().inFolder(folderId).sortedForLibrary(sort)) addTrack(track.id)
        }
        for (folder in children(currentFolderId)) {
            if (folder.id in selectedFolderIds) expand(folder.id)
        }
        db.tracks().inFolder(currentFolderId).sortedForLibrary(sort).forEach { track ->
            if (track.id in selectedTrackIds) addTrack(track.id)
        }
        if (orderedIds.isEmpty()) return@withContext emptyList()
        val byId = db.tracks().getByIds(orderedIds).associateBy { it.id }
        orderedIds.mapNotNull { byId[it] }
    }

    suspend fun importAsmr(input: String, parentId: Long?) = withContext(Dispatchers.IO) {
        val work = asmr.loadWork(input)
        if (db.folders().findBySourceId(work.sourceId) != null) error("已经在音频库")
        insertAsmrWork(work, parentId)
    }

    suspend fun folderBySource(sourceId: String): FolderEntity? = withContext(Dispatchers.IO) {
        db.folders().findBySourceId(sourceId)?.let { return@withContext it }
        val key = DlsiteClient.canonicalWorkno(sourceId)
        db.folders().all().firstOrNull { folder ->
            val stored = folder.asmrSourceId ?: return@firstOrNull false
            DlsiteClient.canonicalWorkno(stored) == key
        }
    }

    suspend fun importPlayWork(facts: WorkFacts, nodes: List<PlayEntry>, coverUrl: String?) =
        withContext(Dispatchers.IO) {
            if (folderBySource(facts.sourceId) != null) error("已经在音频库")
            val workId = facts.sourceId.filter { it.isDigit() }.toLongOrNull() ?: 0L
            val rootId = db.folders().insert(
                FolderEntity(
                    parentId = null,
                    name = sanitizeEntryName("${facts.sourceId} ${facts.title}"),
                    position = db.folders().maxPosition(null) + 1,
                    addedAt = System.currentTimeMillis(),
                    asmrWorkId = workId,
                    asmrSourceId = facts.sourceId,
                    coverUri = coverUrl,
                    workMeta = facts.toJson(),
                ),
            )
            importPlayNodes(nodes, rootId, workId, facts, coverUrl, "")
        }

    suspend fun childFolders(parentId: Long?): List<FolderEntity> = withContext(Dispatchers.IO) {
        db.folders().childrenOf(parentId)
    }

    suspend fun filesIn(folderId: Long?): List<LibraryFileEntity> = withContext(Dispatchers.IO) {
        db.files().listIn(folderId)
    }

    suspend fun asmrTrack(workId: Long, hash: String): TrackEntity? = withContext(Dispatchers.IO) {
        db.tracks().findAsmr(workId, hash)
    }

    suspend fun asmrFile(workId: Long, hash: String): LibraryFileEntity? = withContext(Dispatchers.IO) {
        db.files().findAsmr(workId, hash)
    }

    /** Returns the work's root folder. Inserts it under the library root when it is not there yet. */
    suspend fun ensureAsmrWork(work: AsmrWork): Long = withContext(Dispatchers.IO) {
        db.folders().findBySourceId(work.sourceId)?.id ?: insertAsmrWork(work, null)
    }

    private suspend fun insertAsmrWork(work: AsmrWork, parentId: Long?): Long {
        val position = db.folders().maxPosition(parentId) + 1
        val rootId = db.folders().insert(
            FolderEntity(
                parentId = parentId,
                name = sanitizeEntryName("${work.sourceId} ${work.title}"),
                position = position,
                addedAt = System.currentTimeMillis(),
                asmrWorkId = work.id,
                asmrSourceId = work.sourceId,
                coverUri = work.coverUrl,
                workMeta = work.facts().toJson(),
            ),
        )
        importAsmrNodes(work.nodes, rootId, work, "")
        return rootId
    }

    suspend fun asmrTracksInTree(folderId: Long): List<TrackEntity> = withContext(Dispatchers.IO) {
        val folders = db.folders().all()
        val ids = descendantFolderIds(folders, setOf(folderId))
        if (ids.isEmpty()) return@withContext emptyList()
        db.tracks().inFolders(ids.toList()).filter { it.source == TrackSource.ASMR || it.source == TrackSource.DLSITE }
    }

    suspend fun libraryFile(id: Long): LibraryFileEntity? = withContext(Dispatchers.IO) {
        db.files().get(id)
    }

    suspend fun deleteFile(id: Long) = withContext(Dispatchers.IO) {
        db.files().delete(id)
    }

    suspend fun lookupBvid(input: String): List<BiliPart> = withContext(Dispatchers.IO) {
        bilibili.lookup(input)
    }

    suspend fun importParts(parts: List<BiliPart>, folderId: Long? = null): List<TrackEntity> = withContext(Dispatchers.IO) {
        parts.map { upsertBili(it, folderId) }
    }

    suspend fun deleteTrack(id: Long) = withContext(Dispatchers.IO) {
        db.tracks().delete(id)
        compactQueue()
    }

    suspend fun createPlaylist(name: String): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        db.playlists().insert(PlaylistEntity(name = name.trim(), createdAt = now, updatedAt = now))
    }

    suspend fun renamePlaylist(id: Long, name: String) = withContext(Dispatchers.IO) {
        db.playlists().rename(id, name.trim(), System.currentTimeMillis())
    }

    suspend fun deletePlaylist(id: Long) = withContext(Dispatchers.IO) {
        db.playlists().delete(id)
    }

    suspend fun addTracksToPlaylist(playlistId: Long, trackIds: List<Long>) = withContext(Dispatchers.IO) {
        val existing = db.playlists().trackIds(playlistId).toSet()
        var position = db.playlists().maxPosition(playlistId)
        trackIds.filterNot { it in existing }.forEach { trackId ->
            position += 1
            db.playlists().insertItem(
                PlaylistItemEntity(playlistId = playlistId, trackId = trackId, position = position),
            )
        }
        db.playlists().touch(playlistId, System.currentTimeMillis())
    }

    suspend fun removePlaylistItem(playlistId: Long, itemId: Long) = withContext(Dispatchers.IO) {
        db.playlists().deleteItem(itemId)
        compactPlaylist(playlistId)
        db.playlists().touch(playlistId, System.currentTimeMillis())
    }

    suspend fun movePlaylistItem(playlistId: Long, from: Int, to: Int) = withContext(Dispatchers.IO) {
        reorder(db.playlists().items(playlistId), from, to) { id, position ->
            db.playlists().updatePosition(id, position)
        }
        db.playlists().touch(playlistId, System.currentTimeMillis())
    }

    suspend fun playlistTracks(playlistId: Long): List<TrackEntity> = withContext(Dispatchers.IO) {
        db.playlists().tracks(playlistId).map { it.toTrack() }
    }

    suspend fun insertQueueAfter(anchorQueueItemId: Long?, trackIds: List<Long>): Pair<Int, List<Long>> =
        withContext(Dispatchers.IO) {
            if (trackIds.isEmpty()) return@withContext 0 to emptyList()
            val existing = db.queue().items()
            val anchorIndex = existing.indexOfFirst { it.id == anchorQueueItemId }
            val insertAt = if (anchorIndex < 0) 0 else anchorIndex + 1
            val newIds = trackIds.map { trackId ->
                db.queue().insert(QueueItemEntity(trackId = trackId, position = insertAt))
            }
            val ordered = existing.map { it.id }.toMutableList()
            ordered.addAll(insertAt, newIds)
            ordered.forEachIndexed { index, id -> db.queue().updatePosition(id, index) }
            insertAt to newIds
        }

    suspend fun appendQueue(trackIds: List<Long>): List<Long> = withContext(Dispatchers.IO) {
        var position = db.queue().maxPosition()
        trackIds.map { trackId ->
            position += 1
            db.queue().insert(QueueItemEntity(trackId = trackId, position = position))
        }
    }

    suspend fun replaceQueue(trackIds: List<Long>): List<Long> = withContext(Dispatchers.IO) {
        db.queue().clear()
        trackIds.mapIndexed { index, trackId ->
            db.queue().insert(QueueItemEntity(trackId = trackId, position = index))
        }
    }

    suspend fun replaceRemoteQueue(items: List<RemotePlayable>): List<Long> = withContext(Dispatchers.IO) {
        db.queue().clear()
        items.mapIndexed { index, item ->
            db.queue().insert(
                QueueItemEntity(
                    position = index,
                    title = item.title,
                    artist = item.artist,
                    coverUri = item.coverUri,
                    remoteUrl = item.remoteUrl,
                    source = item.source.name,
                    referer = item.referer,
                    workno = item.workno,
                    fileKey = item.fileKey,
                ),
            )
        }
    }

    suspend fun queueSnapshot(): List<QueuedTrack> = withContext(Dispatchers.IO) {
        db.queue().snapshot()
    }

    suspend fun firstQueueItemId(trackId: Long): Long? = withContext(Dispatchers.IO) {
        db.queue().firstIdForTrack(trackId)
    }

    suspend fun moveQueueItem(from: Int, to: Int) = withContext(Dispatchers.IO) {
        reorder(db.queue().items(), from, to) { id, position ->
            db.queue().updatePosition(id, position)
        }
    }

    suspend fun removeQueueItem(queueItemId: Long) = withContext(Dispatchers.IO) {
        db.queue().delete(queueItemId)
        compactQueue()
    }

    private suspend fun importNode(node: AudioNode, parentId: Long?): Int {
        val position = db.folders().maxPosition(parentId) + 1
        val id = db.folders().insert(
            FolderEntity(
                parentId = parentId,
                name = node.name,
                position = position,
                addedAt = System.currentTimeMillis(),
            ),
        )
        var count = node.files.map { upsertLocal(it, id) }.size
        node.children.forEach { count += importNode(it, id) }
        return count
    }

    private suspend fun upsertLocal(uri: Uri, folderId: Long?): TrackEntity {
        val key = uri.toString()
        db.tracks().findByLocalUri(key)?.let { return it }
        val metadata = LocalAudio.readMetadata(context, uri)
        val id = db.tracks().insert(
            TrackEntity(
                source = TrackSource.LOCAL,
                title = metadata.title,
                artist = metadata.artist,
                durationMs = metadata.durationMs,
                coverUri = metadata.coverPath,
                localUri = key,
                bvid = null,
                cid = null,
                page = null,
                addedAt = System.currentTimeMillis(),
                folderId = folderId,
            ),
        )
        return db.tracks().getById(id) ?: error("保存音频失败")
    }

    private suspend fun importPlayNodes(
        nodes: List<PlayEntry>,
        parentId: Long,
        workId: Long,
        facts: WorkFacts,
        coverUrl: String?,
        path: String,
    ) {
        for (node in nodes) {
            val name = sanitizeEntryName(node.title)
            val here = if (path.isEmpty()) name else "$path/$name"
            if (node.children.isNotEmpty()) {
                val id = db.folders().insert(
                    FolderEntity(
                        parentId = parentId,
                        name = name,
                        position = db.folders().maxPosition(parentId) + 1,
                        addedAt = System.currentTimeMillis(),
                        asmrWorkId = workId,
                    ),
                )
                importPlayNodes(node.children, id, workId, facts, coverUrl, here)
                continue
            }
            val hash = scopedFileHash(TrackSource.DLSITE, node.audio?.fileKey?.ifBlank { null } ?: here)
            val audio = node.audio
            if (audio != null) {
                if (db.tracks().findAsmr(workId, hash) != null) continue
                db.tracks().insert(
                    TrackEntity(
                        source = TrackSource.DLSITE,
                        title = name,
                        artist = facts.circle.ifBlank { facts.sourceId },
                        durationMs = audio.durationMs,
                        coverUri = coverUrl,
                        localUri = null,
                        bvid = null,
                        cid = null,
                        page = null,
                        addedAt = System.currentTimeMillis(),
                        folderId = parentId,
                        remoteUrl = audio.remoteUrl,
                        asmrWorkId = workId,
                        fileHash = hash,
                    ),
                )
            } else {
                val preview = node.preview ?: continue
                if (db.files().findAsmr(workId, hash) != null) continue
                db.files().insert(
                    LibraryFileEntity(
                        folderId = parentId,
                        name = name,
                        kind = preview.kind,
                        remoteUrl = preview.url,
                        addedAt = System.currentTimeMillis(),
                        asmrWorkId = workId,
                        fileHash = hash,
                    ),
                )
            }
        }
    }

    private suspend fun importAsmrNodes(
        nodes: List<AsmrNode>,
        parentId: Long,
        work: AsmrWork,
        path: String,
    ) {
        for (node in nodes) {
            val name = sanitizeEntryName(node.title)
            val here = if (path.isEmpty()) name else "$path/$name"
            if (node.isContainer()) {
                val position = db.folders().maxPosition(parentId) + 1
                val id = db.folders().insert(
                    FolderEntity(
                        parentId = parentId,
                        name = name,
                        position = position,
                        addedAt = System.currentTimeMillis(),
                        asmrWorkId = work.id,
                    ),
                )
                importAsmrNodes(node.children, id, work, here)
                continue
            }
            val hash = scopedFileHash(TrackSource.ASMR, node.storageKey(path))
            if (isAsmrAudio(node.type, node.title)) {
                if (db.tracks().findAsmr(work.id, hash) != null) continue
                db.tracks().insert(
                    TrackEntity(
                        source = TrackSource.ASMR,
                        title = name,
                        artist = work.circle.ifBlank { work.sourceId },
                        durationMs = node.durationMs,
                        coverUri = work.coverUrl,
                        localUri = null,
                        bvid = null,
                        cid = null,
                        page = null,
                        addedAt = System.currentTimeMillis(),
                        folderId = parentId,
                        remoteUrl = node.streamUrl,
                        asmrWorkId = work.id,
                        fileHash = hash,
                    ),
                )
            } else {
                if (db.files().findAsmr(work.id, hash) != null) continue
                db.files().insert(
                    LibraryFileEntity(
                        folderId = parentId,
                        name = name,
                        kind = previewKind(node.title),
                        remoteUrl = node.streamUrl.orEmpty(),
                        addedAt = System.currentTimeMillis(),
                        asmrWorkId = work.id,
                        fileHash = hash,
                    ),
                )
            }
        }
    }

    private suspend fun upsertBili(part: BiliPart, folderId: Long?): TrackEntity {
        db.tracks().findByBvid(part.bvid, part.cid)?.let { return it }
        val id = db.tracks().insert(
            TrackEntity(
                source = TrackSource.BILIBILI,
                title = part.title,
                artist = part.artist,
                durationMs = part.durationMs,
                coverUri = part.coverUrl,
                localUri = null,
                bvid = part.bvid,
                cid = part.cid,
                page = part.page,
                addedAt = System.currentTimeMillis(),
                folderId = folderId,
            ),
        )
        return db.tracks().getById(id) ?: error("保存音频失败")
    }

    private suspend fun trackIdsIn(folderIds: Set<Long>): Set<Long> {
        if (folderIds.isEmpty()) return emptySet()
        return db.tracks().inFolders(folderIds.toList()).map { it.id }.toSet()
    }

    private fun descendantFolderIds(folders: List<FolderEntity>, roots: Set<Long>): Set<Long> {
        if (roots.isEmpty()) return emptySet()
        val children = folders.groupBy { it.parentId }
        val result = mutableSetOf<Long>()
        val stack = ArrayDeque(roots)
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (!result.add(id)) continue
            children[id].orEmpty().forEach { stack.add(it.id) }
        }
        return result
    }

    private fun folderPath(folder: FolderEntity, byId: Map<Long, FolderEntity>): String {
        val names = ArrayDeque<String>()
        var current: FolderEntity? = folder
        while (current != null) {
            names.addFirst(current.name)
            current = current.parentId?.let { byId[it] }
        }
        return names.joinToString(" / ")
    }

    private suspend fun compactQueue() {
        db.queue().items().forEachIndexed { index, item ->
            db.queue().updatePosition(item.id, index)
        }
    }

    private suspend fun compactPlaylist(playlistId: Long) {
        db.playlists().items(playlistId).forEachIndexed { index, item ->
            db.playlists().updatePosition(item.id, index)
        }
    }

    private suspend fun reorder(
        items: List<Any>,
        from: Int,
        to: Int,
        update: suspend (Long, Int) -> Unit,
    ) {
        if (from !in items.indices || to !in items.indices || from == to) return
        val ids = items.map { item ->
            when (item) {
                is QueueItemEntity -> item.id
                is PlaylistItemEntity -> item.id
                else -> error("未知列表项")
            }
        }.toMutableList()
        val moved = ids.removeAt(from)
        ids.add(to, moved)
        ids.forEachIndexed { index, id -> update(id, index) }
    }
}

data class FolderChoice(
    val id: Long?,
    val label: String,
)
