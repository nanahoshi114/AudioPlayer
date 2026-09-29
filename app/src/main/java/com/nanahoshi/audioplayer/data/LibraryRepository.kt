package com.nanahoshi.audioplayer.data

import android.content.Context
import android.net.Uri
import com.nanahoshi.audioplayer.bilibili.BiliPart
import com.nanahoshi.audioplayer.bilibili.BilibiliClient
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.PlaylistEntity
import com.nanahoshi.audioplayer.data.db.PlaylistItemEntity
import com.nanahoshi.audioplayer.data.db.PlaylistTrack
import com.nanahoshi.audioplayer.data.db.QueueItemEntity
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import com.nanahoshi.audioplayer.data.db.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class LibraryRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val bilibili: BilibiliClient,
) {
    fun observeTracks(): Flow<List<TrackEntity>> = db.tracks().observeAll()

    fun observeTracksIn(folderId: Long?): Flow<List<TrackEntity>> = db.tracks().observeIn(folderId)

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
        db.folders().insert(FolderEntity(parentId = parentId, name = trimmed, position = position))
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
    ): List<TrackEntity> = withContext(Dispatchers.IO) {
        val folders = db.folders().all()
        val byParent = folders.groupBy { it.parentId }
        fun children(parentId: Long?) = byParent[parentId].orEmpty().sortedWith(compareBy({ it.position }, { it.name }))
        val orderedIds = mutableListOf<Long>()
        val seen = mutableSetOf<Long>()
        fun addTrack(id: Long) {
            if (seen.add(id)) orderedIds += id
        }
        suspend fun expand(folderId: Long) {
            for (child in children(folderId)) expand(child.id)
            for (track in db.tracks().inFolder(folderId)) addTrack(track.id)
        }
        for (folder in children(currentFolderId)) {
            if (folder.id in selectedFolderIds) expand(folder.id)
        }
        db.tracks().inFolder(currentFolderId).forEach { track ->
            if (track.id in selectedTrackIds) addTrack(track.id)
        }
        if (orderedIds.isEmpty()) return@withContext emptyList()
        val byId = db.tracks().getByIds(orderedIds).associateBy { it.id }
        orderedIds.mapNotNull { byId[it] }
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
        val id = db.folders().insert(FolderEntity(parentId = parentId, name = node.name, position = position))
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
