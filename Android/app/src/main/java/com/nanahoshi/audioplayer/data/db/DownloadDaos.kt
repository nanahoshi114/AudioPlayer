package com.nanahoshi.audioplayer.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("UPDATE downloads SET progressSummary = :summary WHERE id = :id")
    suspend fun setProgressSummary(id: Long, summary: String?)

    @Query("UPDATE downloads SET status = 'FAILED', error = :error WHERE id = :id AND status = 'RUNNING'")
    suspend fun failRunning(id: Long, error: String)
    @Query("SELECT * FROM downloads WHERE kind = 'DLSITE_ARCHIVE' AND workKey = :workno ORDER BY id DESC LIMIT 1")
    suspend fun latestArchive(workno: String): DownloadEntity?

    @Query("""
        SELECT * FROM downloads WHERE kind = 'JAPANESE_ASMR_WORK' AND workKey = :workKey AND id != :excludeId
            AND (status IN ('QUEUED', 'RUNNING') OR (status = 'FAILED' AND phase = 'VERIFY')) ORDER BY id LIMIT 1
    """)
    suspend fun reserveWorkTask(workKey: String, excludeId: Long): DownloadEntity?

    @Query("UPDATE downloads SET status = 'FAILED', phase = 'VERIFY', error = '需要验证后继续' WHERE id = :id AND status = 'RUNNING'")
    suspend fun waitForJaVerification(id: Long)

    @Query("UPDATE downloads SET phase = NULL, error = '篇目已从音频库移除' WHERE kind = 'JAPANESE_ASMR_WORK' AND status = 'FAILED' AND phase = 'VERIFY' AND workFolderId IN (:folderIds)")
    suspend fun invalidateJaWorkVerification(folderIds: List<Long>)

    @Query("SELECT * FROM downloads WHERE kind = 'DLSITE_ARCHIVE' AND workKey = :workno AND status IN ('QUEUED', 'RUNNING') LIMIT 1")
    suspend fun activeArchive(workno: String): DownloadEntity?

    @Query("UPDATE downloads SET status = 'FAILED', error = :error WHERE id = :id AND status IN ('QUEUED', 'RUNNING')")
    suspend fun stopActive(id: Long, error: String): Int

    @Query("UPDATE downloads SET trackId = :trackId, workFolderId = :folderId, source = :source, workKey = :workKey, fileKey = :fileKey WHERE id = :id")
    suspend fun bindLibraryTarget(id: Long, trackId: Long, folderId: Long?, source: String?, workKey: String?, fileKey: String?)

    @Query("UPDATE downloads SET pendingBiliSubtitle = :pending WHERE id = :id")
    suspend fun setPendingBiliSubtitle(id: Long, pending: Boolean?)

    @Query("SELECT * FROM downloads WHERE pendingBiliSubtitle = 1 AND status = 'DONE' ORDER BY id")
    suspend fun pendingBiliSubtitles(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED', 'RUNNING')")
    suspend fun active(): List<DownloadEntity>

    @Query("UPDATE downloads SET phase = :phase WHERE id = :id AND status = 'RUNNING'")
    suspend fun setPhase(id: Long, phase: String)

    @Query("UPDATE downloads SET status = 'RUNNING' WHERE id = :id AND status = 'QUEUED'")
    suspend fun startQueued(id: Long): Int

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: Long): DownloadEntity?

    @Query("UPDATE downloads SET trackId = :trackId WHERE id = :id")
    suspend fun attachTrack(id: Long, trackId: Long)

    @Query("UPDATE downloads SET bytesDone = :done, bytesTotal = :total WHERE id = :id AND status = 'RUNNING'")
    suspend fun activeProgress(id: Long, done: Long, total: Long)

    @Query("SELECT * FROM downloads ORDER BY id DESC")
    fun observe(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE dismissedAt IS NULL ORDER BY id DESC")
    fun observeVisible(): Flow<List<DownloadEntity>>

    @Query("""
        UPDATE downloads SET dismissedAt = :at
        WHERE id = :id AND status = 'DONE' AND (pendingBiliSubtitle IS NULL OR pendingBiliSubtitle = 0) AND dismissedAt IS NULL
    """)
    suspend fun dismissDone(id: Long, at: Long): Int

    @Query("""
        UPDATE downloads SET dismissedAt = :at
        WHERE status = 'DONE' AND (pendingBiliSubtitle IS NULL OR pendingBiliSubtitle = 0) AND dismissedAt IS NULL
    """)
    suspend fun dismissAllDone(at: Long): Int

    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED', 'RUNNING') OR (status = 'DONE' AND pendingBiliSubtitle = 1) ORDER BY id ASC")
    fun observeActive(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status = 'QUEUED' ORDER BY id ASC LIMIT 1")
    suspend fun nextQueued(): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE trackId = :trackId AND status IN ('QUEUED', 'RUNNING') LIMIT 1")
    suspend fun activeForTrack(trackId: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE remoteUrl = :url AND status IN ('QUEUED', 'RUNNING') LIMIT 1")
    suspend fun activeForUrl(url: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE status = 'DONE' AND source = :source AND workKey = :workKey")
    suspend fun finishedForWork(source: String, workKey: String): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE kind = 'DLSITE_ARCHIVE'")
    suspend fun archives(): List<DownloadEntity>

    @Query("DELETE FROM downloads WHERE id = :id AND status = 'FAILED'")
    suspend fun deleteFailed(id: Long): Int

    @Query(
        "SELECT * FROM downloads WHERE source = :source AND workKey = :workKey AND fileKey = :fileKey " +
            "AND status IN ('QUEUED', 'RUNNING') LIMIT 1",
    )
    suspend fun activeForFile(source: String, workKey: String, fileKey: String): DownloadEntity?

    @Query("UPDATE downloads SET localPath = :path WHERE id = :id")
    suspend fun setLocalPath(id: Long, path: String)

    @Insert
    suspend fun insert(item: DownloadEntity): Long

    @Query("UPDATE downloads SET status = 'DONE', bytesDone = :done, bytesTotal = :total, error = NULL WHERE id = :id")
    suspend fun finish(id: Long, done: Long, total: Long)

    @Query("UPDATE downloads SET status = 'QUEUED', bytesDone = 0, bytesTotal = 0, error = NULL, phase = NULL, progressSummary = NULL WHERE id = :id AND status = 'FAILED'")
    suspend fun requeue(id: Long)

    @Query("DELETE FROM downloads WHERE trackId = :trackId AND status = 'DONE'")
    suspend fun deleteFinishedForTrack(trackId: Long)

    @Query("SELECT * FROM downloads WHERE status = 'DONE'")
    suspend fun finished(): List<DownloadEntity>

    @Query("DELETE FROM downloads WHERE status = 'DONE' AND localPath = :path")
    suspend fun deleteFinishedForPath(path: String)

    @Query("SELECT * FROM downloads WHERE trackId = :trackId ORDER BY id DESC LIMIT 1")
    suspend fun latestForTrack(trackId: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE source = :source AND workKey = :post AND fileKey = :key ORDER BY id DESC LIMIT 1")
    suspend fun latestForSourceFile(source: String, post: String, key: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE remoteUrl = :url ORDER BY id DESC LIMIT 1")
    suspend fun latestForUrl(url: String): DownloadEntity?
}