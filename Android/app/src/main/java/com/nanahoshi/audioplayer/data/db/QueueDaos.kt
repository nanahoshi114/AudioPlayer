package com.nanahoshi.audioplayer.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface QueueDao {
    @Query(
        """
        SELECT queue_items.id AS queueItemId, queue_items.position AS position,
            tracks.id AS trackId,
            COALESCE(tracks.source, queue_items.source) AS source,
            COALESCE(tracks.title, queue_items.title, '') AS title,
            COALESCE(tracks.artist, queue_items.artist) AS artist,
            COALESCE(tracks.durationMs, 0) AS durationMs,
            COALESCE(tracks.coverUri, queue_items.coverUri) AS coverUri,
            tracks.localUri AS localUri, tracks.bvid AS bvid, tracks.cid AS cid, tracks.page AS page,
            COALESCE(queue_items.remoteUrl, CASE WHEN tracks.remoteUrl LIKE 'japaneseasmr://file?%' THEN tracks.remoteUrl END) AS remoteUrl,
            queue_items.referer AS referer,
            COALESCE(japaneseasmr_media.postId, queue_items.workno) AS workno,
            COALESCE(japaneseasmr_media.mediaKey, queue_items.fileKey) AS fileKey,
            COALESCE(japaneseasmr_media.kind, queue_items.mediaKind) AS mediaKind,
            COALESCE(japaneseasmr_media.pathHash, queue_items.mediaPathHash) AS mediaPathHash,
            COALESCE(tracks.youtubeVideoId, CASE WHEN queue_items.source = 'YOUTUBE' THEN queue_items.workno END) AS youtubeVideoId
        FROM queue_items
        LEFT JOIN tracks ON tracks.id = queue_items.trackId
        LEFT JOIN japaneseasmr_media ON japaneseasmr_media.trackId = tracks.id AND tracks.source = 'JAPANESE_ASMR'
        ORDER BY queue_items.position ASC
        """,
    )
    fun observe(): Flow<List<QueuedTrack>>

    @Query(
        """
        SELECT queue_items.id AS queueItemId, queue_items.position AS position,
            tracks.id AS trackId,
            COALESCE(tracks.source, queue_items.source) AS source,
            COALESCE(tracks.title, queue_items.title, '') AS title,
            COALESCE(tracks.artist, queue_items.artist) AS artist,
            COALESCE(tracks.durationMs, 0) AS durationMs,
            COALESCE(tracks.coverUri, queue_items.coverUri) AS coverUri,
            tracks.localUri AS localUri, tracks.bvid AS bvid, tracks.cid AS cid, tracks.page AS page,
            COALESCE(queue_items.remoteUrl, CASE WHEN tracks.remoteUrl LIKE 'japaneseasmr://file?%' THEN tracks.remoteUrl END) AS remoteUrl,
            queue_items.referer AS referer,
            COALESCE(japaneseasmr_media.postId, queue_items.workno) AS workno,
            COALESCE(japaneseasmr_media.mediaKey, queue_items.fileKey) AS fileKey,
            COALESCE(japaneseasmr_media.kind, queue_items.mediaKind) AS mediaKind,
            COALESCE(japaneseasmr_media.pathHash, queue_items.mediaPathHash) AS mediaPathHash,
            COALESCE(tracks.youtubeVideoId, CASE WHEN queue_items.source = 'YOUTUBE' THEN queue_items.workno END) AS youtubeVideoId
        FROM queue_items
        LEFT JOIN tracks ON tracks.id = queue_items.trackId
        LEFT JOIN japaneseasmr_media ON japaneseasmr_media.trackId = tracks.id AND tracks.source = 'JAPANESE_ASMR'
        ORDER BY queue_items.position ASC
        """,
    )
    suspend fun snapshot(): List<QueuedTrack>

    @Query("SELECT id FROM queue_items WHERE trackId = :trackId ORDER BY position ASC LIMIT 1")
    suspend fun firstIdForTrack(trackId: Long): Long?

    @Query("SELECT COALESCE(MAX(position), -1) FROM queue_items")
    suspend fun maxPosition(): Int

    @Insert
    suspend fun insert(item: QueueItemEntity): Long

    @Query("DELETE FROM queue_items")
    suspend fun clear()

    @Query("DELETE FROM queue_items WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM queue_items ORDER BY position ASC")
    suspend fun items(): List<QueueItemEntity>

    @Query("UPDATE queue_items SET mediaKind = :kind WHERE id = :id AND trackId IS NULL AND source = 'JAPANESE_ASMR'")
    suspend fun setJaMediaKind(id: Long, kind: String)

    @Query("UPDATE queue_items SET position = :position WHERE id = :id")
    suspend fun updatePosition(id: Long, position: Int)

    @Query("SELECT * FROM playback_state WHERE id = 0")
    suspend fun playbackState(): PlaybackStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePlayback(state: PlaybackStateEntity)
}