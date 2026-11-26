package com.nanahoshi.audioplayer.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.nanahoshi.audioplayer.data.TrackSource
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders WHERE biliBvid = :bvid LIMIT 1")
    suspend fun findBiliVideo(bvid: String): FolderEntity?

    @Update
    suspend fun update(value: FolderEntity)

    @Query("SELECT * FROM folders WHERE parentId IS :parentId ORDER BY position ASC, name ASC")
    suspend fun childrenOf(parentId: Long?): List<FolderEntity>

    @Query("SELECT * FROM folders ORDER BY position ASC, name ASC")
    suspend fun all(): List<FolderEntity>

    @Query("SELECT * FROM folders")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun get(id: Long): FolderEntity?

    @Query("SELECT * FROM folders WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :id")
    fun observe(id: Long): Flow<FolderEntity?>

    @Query("SELECT COALESCE(MAX(position), -1) FROM folders WHERE parentId IS :parentId")
    suspend fun maxPosition(parentId: Long?): Int

    @Query("SELECT * FROM folders WHERE asmrSourceId = :sourceId LIMIT 1")
    suspend fun findBySourceId(sourceId: String): FolderEntity?

    @Query("UPDATE folders SET workMeta = :workMeta WHERE id = :id")
    suspend fun setWorkMeta(id: Long, workMeta: String)

    @Query("UPDATE folders SET contentSource = :source WHERE id = :id")
    suspend fun setContentSource(id: Long, source: String)

    @Query(
        """
        UPDATE folders
        SET name = :name, asmrWorkId = :workId, asmrSourceId = :sourceId, coverUri = :cover, workMeta = :meta
        WHERE id = :id
        """,
    )
    suspend fun markAsWork(id: Long, name: String, workId: Long, sourceId: String, cover: String?, meta: String)

    @Query("UPDATE folders SET asmrWorkId = :workId, contentSource = :source WHERE id = :id")
    suspend fun markWorkChild(id: Long, workId: Long, source: String)

    @Insert
    suspend fun insert(folder: FolderEntity): Long

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks WHERE bvid = :bvid ORDER BY page ASC, id ASC")
    suspend fun forBvid(bvid: String): List<com.nanahoshi.audioplayer.data.db.TrackEntity>

    @Update
    suspend fun update(value: TrackEntity)

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

    @Query("SELECT EXISTS(SELECT 1 FROM tracks WHERE localUri = :uri OR substr(localUri, 1, length(:uri) + 1) = :uri || '/')")
    suspend fun usesLocalGrant(uri: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM tracks WHERE remoteUrl = :url AND id IS NOT :trackId)")
    suspend fun hasOtherRemoteUrl(url: String, trackId: Long?): Boolean

    @Query("SELECT id FROM tracks WHERE remoteUrl = :url")
    suspend fun idsForRemoteUrl(url: String): List<Long>

    @Query("SELECT * FROM tracks WHERE localUri LIKE '/%' ORDER BY id")
    suspend fun withAbsoluteLocalPaths(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE source IN ('ASMR', 'DLSITE', 'JAPANESE_ASMR') AND localUri IS NOT NULL")
    suspend fun onlineWithLocal(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE source = 'BILIBILI' AND localUri IS NOT NULL")
    suspend fun biliWithLocal(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE youtubeVideoId = :videoId LIMIT 1")
    suspend fun findYouTube(videoId: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE source = 'YOUTUBE' AND localUri IS NOT NULL")
    suspend fun youtubeWithLocal(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE bvid = :bvid AND cid = :cid LIMIT 1")
    suspend fun findByBvid(bvid: String, cid: Long): TrackEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(track: TrackEntity): Long

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE tracks SET durationMs = :duration WHERE id = :id AND durationMs = 0")
    suspend fun fillDuration(id: Long, duration: Long)

    @Query("UPDATE tracks SET folderId = :folderId WHERE id = :id")
    suspend fun setFolder(id: Long, folderId: Long?)

    @Query("SELECT * FROM tracks WHERE asmrWorkId = :workId AND fileHash = :hash LIMIT 1")
    suspend fun findAsmr(workId: Long, hash: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE asmrWorkId = :workId")
    suspend fun forAsmrWork(workId: Long): List<TrackEntity>

    @Query("UPDATE tracks SET remoteUrl = :url WHERE id = :id")
    suspend fun updateRemoteUrl(id: Long, url: String)

    @Query("UPDATE tracks SET localUri = :path WHERE id = :id")
    suspend fun updateLocalUri(id: Long, path: String)

    @Query("UPDATE tracks SET localUri = NULL WHERE id = :id")
    suspend fun clearLocalUri(id: Long)

    @Query(
        """
        UPDATE tracks
        SET source = :source, artist = :artist, coverUri = :cover, remoteUrl = :url,
            asmrWorkId = :workId, fileHash = :hash
        WHERE id = :id
        """,
    )
    suspend fun attachWork(
        id: Long,
        source: TrackSource,
        artist: String?,
        cover: String?,
        url: String?,
        workId: Long,
        hash: String,
    )
}

@Dao
interface LibraryFileDao {
    @Update
    suspend fun update(value: LibraryFileEntity)

    @Query("SELECT * FROM library_files WHERE folderId IS :folderId")
    fun observeIn(folderId: Long?): Flow<List<LibraryFileEntity>>

    @Query("SELECT * FROM library_files WHERE folderId IS :folderId")
    suspend fun listIn(folderId: Long?): List<LibraryFileEntity>

    @Query("SELECT * FROM library_files")
    fun observeAll(): Flow<List<LibraryFileEntity>>

    @Query("SELECT * FROM library_files WHERE id = :id")
    suspend fun get(id: Long): LibraryFileEntity?

    @Query("SELECT * FROM library_files WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<LibraryFileEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM library_files WHERE localUri = :uri OR substr(localUri, 1, length(:uri) + 1) = :uri || '/')")
    suspend fun usesLocalGrant(uri: String): Boolean

    @Query("UPDATE library_files SET localUri = NULL WHERE id = :id")
    suspend fun clearLocalUri(id: Long)

    @Query("UPDATE library_files SET localUri = NULL WHERE localUri = :path")
    suspend fun clearLocalUriByPath(path: String)

    @Query("SELECT * FROM library_files WHERE localUri IS NOT NULL")
    suspend fun withLocalUri(): List<LibraryFileEntity>

    @Query("SELECT * FROM library_files WHERE asmrWorkId = :workId AND fileHash = :hash LIMIT 1")
    suspend fun findAsmr(workId: Long, hash: String): LibraryFileEntity?

    @Query("SELECT * FROM library_files WHERE folderId IN (:folderIds)")
    suspend fun inFolders(folderIds: List<Long>): List<LibraryFileEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(file: LibraryFileEntity): Long

    @Query("DELETE FROM library_files WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        """
        UPDATE library_files
        SET asmrWorkId = :workId, fileHash = :hash, remoteUrl = :url
        WHERE id = :id
        """,
    )
    suspend fun attachWork(id: Long, workId: Long, hash: String, url: String)
}