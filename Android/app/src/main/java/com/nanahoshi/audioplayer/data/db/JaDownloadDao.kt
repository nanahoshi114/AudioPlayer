package com.nanahoshi.audioplayer.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

@Entity(tableName = "japaneseasmr_download_works")
data class JaDownloadWorkEntity(
    @PrimaryKey val rj: String,
    val rootFolderId: Long?,
    val postId: String?,
    val directory: String?,
    val updatedAt: Long?,
    val entryKey: String? = null,
)

@Entity(
    tableName = "japaneseasmr_download_entries",
    primaryKeys = ["rj", "relativePath"],
    indices = [
        Index("trackId"), Index("fileId"), Index("folderId"),
        Index(value = ["rj", "packageKey"], name = "index_japaneseasmr_download_entries_packageKey"),
    ],
)
data class JaDownloadEntryEntity(
    val rj: String,
    val postId: String,
    val relativePath: String,
    val kind: String,
    val size: Long,
    val sha256: String?,
    val localPath: String?,
    val packageKey: String?,
    val trackId: Long? = null,
    val fileId: Long? = null,
    val folderId: Long? = null,
    val packageRevision: String? = null,
)

@Entity(tableName = "japaneseasmr_download_packages", primaryKeys = ["rj", "packageKey"])
data class JaDownloadPackageEntity(
    val rj: String,
    val packageKey: String,
    val relativePath: String,
    val size: Long,
    val sha256: String?,
    val localPath: String?,
    val extractedAt: Long?,
    val publishedAt: Long?,
    val revision: String? = null,
)

@Dao
interface JaDownloadDao {
    @Query("SELECT * FROM japaneseasmr_download_works WHERE rj = :rj")
    suspend fun work(rj: String): JaDownloadWorkEntity?

    @Query("SELECT * FROM japaneseasmr_download_entries WHERE rj = :rj ORDER BY relativePath")
    suspend fun entries(rj: String): List<JaDownloadEntryEntity>

    @Query("SELECT * FROM japaneseasmr_download_entries WHERE rj = :rj AND relativePath = :path")
    suspend fun entry(rj: String, path: String): JaDownloadEntryEntity?

    @Query("SELECT * FROM japaneseasmr_download_entries WHERE localPath IS NOT NULL")
    suspend fun storedEntries(): List<JaDownloadEntryEntity>

    @Query("SELECT * FROM japaneseasmr_download_entries WHERE localPath = :path LIMIT 1")
    suspend fun entryForPath(path: String): JaDownloadEntryEntity?

    @Query("UPDATE japaneseasmr_download_entries SET localPath = NULL WHERE localPath = :path")
    suspend fun clearPath(path: String)

    @Query("SELECT * FROM japaneseasmr_download_entries WHERE trackId = :trackId LIMIT 1")
    suspend fun entryForTrack(trackId: Long): JaDownloadEntryEntity?

    @Query("SELECT DISTINCT localPath FROM japaneseasmr_download_entries WHERE localPath IS NOT NULL")
    suspend fun paths(): List<String>

    @Query("UPDATE japaneseasmr_download_entries SET localPath = NULL WHERE trackId = :trackId")
    suspend fun clearTrackPath(trackId: Long)

    @Query("SELECT * FROM japaneseasmr_download_packages WHERE rj = :rj")
    suspend fun packages(rj: String): List<JaDownloadPackageEntity>

    @Upsert
    suspend fun saveWork(work: JaDownloadWorkEntity)

    @Upsert
    suspend fun saveEntries(entries: List<JaDownloadEntryEntity>)

    @Upsert
    suspend fun savePackages(packages: List<JaDownloadPackageEntity>)
}
