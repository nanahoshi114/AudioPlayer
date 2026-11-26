package com.nanahoshi.audioplayer.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id NOT IN (SELECT playlistId FROM bili_collections) ORDER BY updatedAt DESC")
    fun observeEditable(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observe(id: Long): Flow<PlaylistEntity?>

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: Long, name: String, updatedAt: Long)

    @Query("UPDATE playlists SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: Long, updatedAt: Long)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        """
        SELECT playlist_items.id AS itemId, playlist_items.position AS position,
            tracks.id AS trackId, tracks.source AS source, tracks.title AS title,
            tracks.artist AS artist, tracks.durationMs AS durationMs, tracks.coverUri AS coverUri,
            tracks.localUri AS localUri, tracks.bvid AS bvid, tracks.cid AS cid, tracks.page AS page,
            tracks.youtubeVideoId AS youtubeVideoId, tracks.folderId AS folderId
        FROM playlist_items
        INNER JOIN tracks ON tracks.id = playlist_items.trackId
        WHERE playlist_items.playlistId = :playlistId
        ORDER BY playlist_items.position ASC
        """,
    )
    fun observeTracks(playlistId: Long): Flow<List<PlaylistTrack>>

    @Query(
        """
        SELECT playlist_items.id AS itemId, playlist_items.position AS position,
            tracks.id AS trackId, tracks.source AS source, tracks.title AS title,
            tracks.artist AS artist, tracks.durationMs AS durationMs, tracks.coverUri AS coverUri,
            tracks.localUri AS localUri, tracks.bvid AS bvid, tracks.cid AS cid, tracks.page AS page,
            tracks.youtubeVideoId AS youtubeVideoId, tracks.folderId AS folderId
        FROM playlist_items
        INNER JOIN tracks ON tracks.id = playlist_items.trackId
        WHERE playlist_items.playlistId = :playlistId
        ORDER BY playlist_items.position ASC
        """,
    )
    suspend fun tracks(playlistId: Long): List<PlaylistTrack>

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int

    @Query("SELECT * FROM playlist_items WHERE trackId = :trackId")
    suspend fun itemsForTrack(trackId: Long): List<PlaylistItemEntity>

    @Query("SELECT trackId FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun trackIds(playlistId: Long): List<Long>

    @Insert
    suspend fun insertItem(item: PlaylistItemEntity): Long

    @Query("DELETE FROM playlist_items WHERE id = :itemId AND playlistId NOT IN (SELECT playlistId FROM bili_collections)")
    suspend fun deleteItem(itemId: Long)

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun items(playlistId: Long): List<PlaylistItemEntity>

    @Query("UPDATE playlist_items SET position = :position WHERE id = :id")
    suspend fun updatePosition(id: Long, position: Int)
}