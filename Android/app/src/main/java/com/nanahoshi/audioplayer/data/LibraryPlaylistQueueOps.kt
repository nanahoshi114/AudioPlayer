package com.nanahoshi.audioplayer.data

import androidx.room.withTransaction
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.bilibili.BiliPart
import com.nanahoshi.audioplayer.data.db.PlaylistEntity
import com.nanahoshi.audioplayer.data.db.PlaylistItemEntity
import com.nanahoshi.audioplayer.data.db.QueueItemEntity
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.dlsite.RemotePlayable
import com.nanahoshi.audioplayer.playback.QueuePlayable
import com.nanahoshi.audioplayer.playback.queueRow
import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.UserFacingException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 自建播放列表与播放队列的读写。 */
internal class LibraryPlaylistQueueOps(private val repo: LibraryRepository) {
    private val db get() = repo.db

    suspend fun createPlaylist(name: String): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        db.playlists().insert(PlaylistEntity(name = name.trim(), createdAt = now, updatedAt = now))
    }

    suspend fun publishBiliPlaylist(name: String, parts: List<BiliPart>): Long? = withContext(Dispatchers.IO) {
        db.withTransaction {
            val ids = parts.distinctBy { it.bvid to it.cid }.map { part ->
                (db.tracks().findByBvid(part.bvid, part.cid)
                    ?: throw UserFacingException(TextRef.Res(R.string.library_bili_part_track_missing))).id
            }
            if (ids.isEmpty()) return@withTransaction null
            val id = createPlaylist(name)
            addTracksToPlaylist(id, ids)
            id
        }
    }

    suspend fun renamePlaylist(id: Long, name: String) = withContext(Dispatchers.IO) {
        if (db.collections().get(id) != null) throw UserFacingException(TextRef.Res(R.string.library_online_playlist_readonly))
        db.playlists().rename(id, name.trim(), System.currentTimeMillis())
    }

    suspend fun deletePlaylist(id: Long) = withContext(Dispatchers.IO) {
        db.playlists().delete(id)
    }

    suspend fun addTracksToPlaylist(playlistId: Long, trackIds: List<Long>) = withContext(Dispatchers.IO) {
        if (db.collections().get(playlistId) != null) throw UserFacingException(TextRef.Res(R.string.library_online_playlist_readonly))
        db.withTransaction {
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
    }

    suspend fun removePlaylistItem(playlistId: Long, itemId: Long) = withContext(Dispatchers.IO) {
        if (db.collections().get(playlistId) != null) throw UserFacingException(TextRef.Res(R.string.library_online_playlist_readonly))
        db.withTransaction {
            db.playlists().deleteItem(itemId)
            repo.compactPlaylist(playlistId)
            db.playlists().touch(playlistId, System.currentTimeMillis())
        }
    }

    suspend fun movePlaylistItem(playlistId: Long, from: Int, to: Int) = withContext(Dispatchers.IO) {
        if (db.collections().get(playlistId) != null) throw UserFacingException(TextRef.Res(R.string.library_online_playlist_readonly))
        db.withTransaction {
            reorder(db.playlists().items(playlistId), from, to) { id, position ->
                db.playlists().updatePosition(id, position)
            }
            db.playlists().touch(playlistId, System.currentTimeMillis())
        }
    }

    suspend fun playlistTracks(playlistId: Long): List<TrackEntity> = withContext(Dispatchers.IO) {
        db.playlists().tracks(playlistId).map { it.toTrack() }
    }

    suspend fun insertQueueAfter(anchorQueueItemId: Long?, trackIds: List<Long>): Pair<Int, List<Long>> =
        withContext(Dispatchers.IO) {
            if (trackIds.isEmpty()) return@withContext 0 to emptyList()
            db.withTransaction {
                val existing = db.queue().items()
                val anchorIndex = existing.indexOfFirst { it.id == anchorQueueItemId }
                val insertAt = if (anchorIndex < 0) 0 else anchorIndex + 1
                val newIds = trackIds.mapIndexed { offset, trackId ->
                    db.queue().insert(QueueItemEntity(trackId = trackId, position = insertAt + offset))
                }
                val ordered = existing.map { it.id }.toMutableList()
                ordered.addAll(insertAt, newIds)
                ordered.forEachIndexed { index, id -> db.queue().updatePosition(id, index) }
                insertAt to newIds
            }
        }

    suspend fun appendQueue(trackIds: List<Long>): List<Long> = withContext(Dispatchers.IO) {
        db.withTransaction {
            var position = db.queue().maxPosition()
            trackIds.map { trackId ->
                position += 1
                db.queue().insert(QueueItemEntity(trackId = trackId, position = position))
            }
        }
    }

    suspend fun replaceQueue(trackIds: List<Long>): List<Long> = withContext(Dispatchers.IO) {
        db.withTransaction {
            db.queue().clear()
            trackIds.mapIndexed { index, trackId ->
                db.queue().insert(QueueItemEntity(trackId = trackId, position = index))
            }
        }
    }

    suspend fun writeMixedQueue(items: List<QueuePlayable>, replace: Boolean): List<Long> = withContext(Dispatchers.IO) {
        db.withTransaction {
            if (replace) db.queue().clear()
            val start = if (replace) 0 else db.queue().maxPosition() + 1
            items.mapIndexed { index, item -> db.queue().insert(item.queueRow(start + index)) }
        }
    }

    suspend fun replaceRemoteQueue(items: List<RemotePlayable>): List<Long> = withContext(Dispatchers.IO) {
        db.withTransaction {
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
                        mediaKind = item.mediaKind,
                        mediaPathHash = item.mediaPathHash,
                    ),
                )
            }
        }
    }

    suspend fun queueSnapshot(): List<com.nanahoshi.audioplayer.data.db.QueuedTrack> = withContext(Dispatchers.IO) {
        db.queue().snapshot()
    }

    suspend fun firstQueueItemId(trackId: Long): Long? = withContext(Dispatchers.IO) {
        db.queue().firstIdForTrack(trackId)
    }

    suspend fun moveQueueItem(from: Int, to: Int) = withContext(Dispatchers.IO) {
        db.withTransaction {
            reorder(db.queue().items(), from, to) { id, position ->
                db.queue().updatePosition(id, position)
            }
        }
    }

    suspend fun removeQueueItem(queueItemId: Long) = withContext(Dispatchers.IO) {
        db.withTransaction {
            db.queue().delete(queueItemId)
            repo.compactQueue()
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
                else -> throw UserFacingException(TextRef.Res(R.string.library_unknown_list_item))
            }
        }.toMutableList()
        val moved = ids.removeAt(from)
        ids.add(to, moved)
        ids.forEachIndexed { index, id -> update(id, index) }
    }
}