package com.nanahoshi.audioplayer.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders WHERE parentId IS :parentId ORDER BY position ASC, name ASC")
    fun observeChildren(parentId: Long?): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE parentId IS :parentId ORDER BY position ASC, name ASC")
    suspend fun childrenOf(parentId: Long?): List<FolderEntity>

    @Query("SELECT * FROM folders ORDER BY position ASC, name ASC")
    suspend fun all(): List<FolderEntity>

    @Query("SELECT * FROM folders")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun get(id: Long): FolderEntity?

    @Query("SELECT * FROM folders WHERE id = :id")
    fun observe(id: Long): Flow<FolderEntity?>

    @Query("SELECT COALESCE(MAX(position), -1) FROM folders WHERE parentId IS :parentId")
    suspend fun maxPosition(parentId: Long?): Int

    @Query("SELECT * FROM folders WHERE asmrSourceId = :sourceId LIMIT 1")
    suspend fun findBySourceId(sourceId: String): FolderEntity?

    @Query("UPDATE folders SET workMeta = :workMeta WHERE id = :id")
    suspend fun setWorkMeta(id: Long, workMeta: String)

    @Insert
    suspend fun insert(folder: FolderEntity): Long

    @Query("UPDATE folders SET parentId = :parentId WHERE id = :id")
    suspend fun setParent(id: Long, parentId: Long?)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE folderId IS :folderId ORDER BY addedAt DESC")
    fun observeIn(folderId: Long?): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE folderId IS :folderId ORDER BY addedAt DESC")
    suspend fun inFolder(folderId: Long?): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE folderId IN (:folderIds)")
    suspend fun inFolders(folderIds: List<Long>): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getById(id: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE localUri = :uri LIMIT 1")
    suspend fun findByLocalUri(uri: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE bvid = :bvid AND cid = :cid LIMIT 1")
    suspend fun findByBvid(bvid: String, cid: Long): TrackEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(track: TrackEntity): Long

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE tracks SET folderId = :folderId WHERE id = :id")
    suspend fun setFolder(id: Long, folderId: Long?)

    @Query("SELECT * FROM tracks WHERE asmrWorkId = :workId AND fileHash = :hash LIMIT 1")
    suspend fun findAsmr(workId: Long, hash: String): TrackEntity?

    @Query("UPDATE tracks SET remoteUrl = :url WHERE id = :id")
    suspend fun updateRemoteUrl(id: Long, url: String)

    @Query("UPDATE tracks SET localUri = :path WHERE id = :id")
    suspend fun updateLocalUri(id: Long, path: String)
}

@Dao
interface LibraryFileDao {
    @Query("SELECT * FROM library_files WHERE folderId IS :folderId")
    fun observeIn(folderId: Long?): Flow<List<LibraryFileEntity>>

    @Query("SELECT * FROM library_files WHERE folderId IS :folderId")
    suspend fun listIn(folderId: Long?): List<LibraryFileEntity>

    @Query("SELECT * FROM library_files")
    fun observeAll(): Flow<List<LibraryFileEntity>>

    @Query("SELECT * FROM library_files WHERE id = :id")
    suspend fun get(id: Long): LibraryFileEntity?

    @Query("SELECT * FROM library_files WHERE asmrWorkId = :workId AND fileHash = :hash LIMIT 1")
    suspend fun findAsmr(workId: Long, hash: String): LibraryFileEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(file: LibraryFileEntity): Long

    @Query("DELETE FROM library_files WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY id DESC")
    fun observe(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status = 'QUEUED' ORDER BY id ASC LIMIT 1")
    suspend fun nextQueued(): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE trackId = :trackId AND status IN ('QUEUED', 'RUNNING') LIMIT 1")
    suspend fun activeForTrack(trackId: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE remoteUrl = :url AND status IN ('QUEUED', 'RUNNING') LIMIT 1")
    suspend fun activeForUrl(url: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE status = 'DONE' AND source = :source AND workKey = :workKey")
    suspend fun finishedForWork(source: String, workKey: String): List<DownloadEntity>

    @Query(
        "SELECT * FROM downloads WHERE source = :source AND workKey = :workKey AND fileKey = :fileKey " +
            "AND status IN ('QUEUED', 'RUNNING') LIMIT 1",
    )
    suspend fun activeForFile(source: String, workKey: String, fileKey: String): DownloadEntity?

    @Query("UPDATE downloads SET localPath = :path WHERE id = :id")
    suspend fun setLocalPath(id: Long, path: String)

    @Insert
    suspend fun insert(item: DownloadEntity): Long

    @Query("UPDATE downloads SET status = 'RUNNING' WHERE id = :id")
    suspend fun markRunning(id: Long)

    @Query("UPDATE downloads SET bytesDone = :done, bytesTotal = :total WHERE id = :id")
    suspend fun progress(id: Long, done: Long, total: Long)

    @Query("UPDATE downloads SET status = 'DONE', bytesDone = :done, bytesTotal = :total, error = NULL WHERE id = :id")
    suspend fun finish(id: Long, done: Long, total: Long)

    @Query("UPDATE downloads SET status = 'FAILED', error = :error WHERE id = :id")
    suspend fun fail(id: Long, error: String)

    @Query("UPDATE downloads SET status = 'FAILED', error = :error WHERE status IN ('QUEUED', 'RUNNING')")
    suspend fun failActive(error: String)

    @Query("UPDATE downloads SET status = 'QUEUED', bytesDone = 0, bytesTotal = 0, error = NULL WHERE id = :id")
    suspend fun requeue(id: Long)
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<PlaylistEntity>>

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
            tracks.localUri AS localUri, tracks.bvid AS bvid, tracks.cid AS cid, tracks.page AS page
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
            tracks.localUri AS localUri, tracks.bvid AS bvid, tracks.cid AS cid, tracks.page AS page
        FROM playlist_items
        INNER JOIN tracks ON tracks.id = playlist_items.trackId
        WHERE playlist_items.playlistId = :playlistId
        ORDER BY playlist_items.position ASC
        """,
    )
    suspend fun tracks(playlistId: Long): List<PlaylistTrack>

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int

    @Query("SELECT trackId FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun trackIds(playlistId: Long): List<Long>

    @Insert
    suspend fun insertItem(item: PlaylistItemEntity): Long

    @Query("DELETE FROM playlist_items WHERE id = :itemId")
    suspend fun deleteItem(itemId: Long)

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun items(playlistId: Long): List<PlaylistItemEntity>

    @Query("UPDATE playlist_items SET position = :position WHERE id = :id")
    suspend fun updatePosition(id: Long, position: Int)
}

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
            queue_items.remoteUrl AS remoteUrl, queue_items.referer AS referer,
            queue_items.workno AS workno, queue_items.fileKey AS fileKey
        FROM queue_items
        LEFT JOIN tracks ON tracks.id = queue_items.trackId
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
            queue_items.remoteUrl AS remoteUrl, queue_items.referer AS referer,
            queue_items.workno AS workno, queue_items.fileKey AS fileKey
        FROM queue_items
        LEFT JOIN tracks ON tracks.id = queue_items.trackId
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

    @Query("UPDATE queue_items SET position = :position WHERE id = :id")
    suspend fun updatePosition(id: Long, position: Int)

    @Query("SELECT * FROM playback_state WHERE id = 0")
    suspend fun playbackState(): PlaybackStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePlayback(state: PlaybackStateEntity)
}
