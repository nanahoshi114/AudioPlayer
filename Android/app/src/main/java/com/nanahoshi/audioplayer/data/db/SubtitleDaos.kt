package com.nanahoshi.audioplayer.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackSubtitleDao {
    @Query("SELECT * FROM track_subtitles WHERE trackId = :trackId")
    fun observeForTrack(trackId: Long): Flow<TrackSubtitleBindingEntity?>

    @Query("SELECT * FROM track_subtitles WHERE trackId = :trackId")
    suspend fun getForTrack(trackId: Long): TrackSubtitleBindingEntity?

    @Query("SELECT * FROM track_subtitles WHERE trackId IN (:trackIds)")
    suspend fun getForTracks(trackIds: List<Long>): List<TrackSubtitleBindingEntity>

    @Query("SELECT * FROM track_subtitles WHERE fileId IN (:fileIds)")
    suspend fun getForFileIds(fileIds: List<Long>): List<TrackSubtitleBindingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: TrackSubtitleBindingEntity): Long

    @Query("DELETE FROM track_subtitles WHERE trackId = :trackId")
    suspend fun deleteForTrack(trackId: Long)

    @Query("DELETE FROM track_subtitles WHERE kind = 'APP_FILE' AND uri = :path")
    suspend fun deleteByAppPath(path: String)

    @Query("SELECT COUNT(*) FROM track_subtitles WHERE kind = 'APP_FILE' AND uri = :path")
    suspend fun countByAppPath(path: String): Int

    @Query("SELECT COUNT(*) FROM track_subtitles WHERE kind = 'EXTERNAL_URI' AND COALESCE(treeUri, uri) = :uri")
    suspend fun countByUri(uri: String): Int

    @Query("SELECT * FROM track_subtitles WHERE kind = 'APP_FILE' AND uri IS NOT NULL ORDER BY trackId")
    suspend fun appFileBindings(): List<TrackSubtitleBindingEntity>

    @Query("SELECT DISTINCT uri FROM track_subtitles WHERE kind = 'APP_FILE' AND uri IS NOT NULL")
    suspend fun allAppPaths(): List<String>
}

@Dao
interface WhisperDao {
    @Query("UPDATE whisper_jobs SET progressSummary = :summary WHERE id = :id")
    suspend fun setProgressSummary(id: Long, summary: String?)

    @Query("SELECT * FROM whisper_jobs WHERE status IN ('QUEUED', 'RUNNING') OR translateStatus IN ('QUEUED', 'RUNNING') ORDER BY id")
    suspend fun pendingWork(): List<WhisperJobEntity>

    @Query("SELECT * FROM whisper_jobs WHERE fingerprint = :fingerprint AND translateStatus IN ('QUEUED', 'RUNNING') LIMIT 1")
    suspend fun activeTranslation(fingerprint: String): WhisperJobEntity?

    @Query("UPDATE whisper_jobs SET status = 'DONE', progress = 1, error = NULL, translateStatus = 'QUEUED' WHERE id = :id AND status IN ('QUEUED', 'RUNNING')")
    suspend fun queueTranslationIfActive(id: Long): Int

    @Query("UPDATE whisper_jobs SET translateStatus = 'RUNNING' WHERE id = :id AND translateStatus = 'QUEUED'")
    suspend fun startQueuedTranslation(id: Long): Int

    @Query("UPDATE whisper_jobs SET error = :error, translateStatus = 'FAILED' WHERE translateStatus IN ('QUEUED', 'RUNNING') AND NOT EXISTS (SELECT 1 FROM subtitle_publications WHERE whisperJobId = whisper_jobs.id)")
    suspend fun failActiveTranslations(error: String)

    @Query("UPDATE whisper_jobs SET awaitDownloadId = :downloadId, awaitFileKey = :fileKey WHERE id = :id")
    suspend fun setAwaitDownload(id: Long, downloadId: Long, fileKey: String?)

    @Query("SELECT * FROM whisper_jobs ORDER BY id DESC")
    fun observe(): Flow<List<WhisperJobEntity>>

    @Query("SELECT * FROM whisper_jobs WHERE dismissedAt IS NULL ORDER BY id DESC")
    fun observeVisible(): Flow<List<WhisperJobEntity>>

    @Query("""
        DELETE FROM whisper_jobs
        WHERE id = :id AND status = 'FAILED' AND (translateStatus IS NULL OR translateStatus NOT IN ('QUEUED', 'RUNNING'))
            AND NOT EXISTS (SELECT 1 FROM subtitle_publications WHERE whisperJobId = :id)
    """)
    suspend fun deleteFailed(id: Long): Int

    @Query("""
        UPDATE whisper_jobs SET dismissedAt = :at
        WHERE id = :id AND status = 'DONE' AND (translateStatus IS NULL OR translateStatus IN ('DONE', 'FAILED'))
            AND dismissedAt IS NULL AND NOT EXISTS (SELECT 1 FROM subtitle_publications WHERE whisperJobId = :id)
    """)
    suspend fun dismissTerminal(id: Long, at: Long): Int

    @Query("""
        UPDATE whisper_jobs SET dismissedAt = :at
        WHERE status = 'DONE' AND (translateStatus IS NULL OR translateStatus IN ('DONE', 'FAILED')) AND dismissedAt IS NULL
            AND NOT EXISTS (SELECT 1 FROM subtitle_publications WHERE whisperJobId = whisper_jobs.id)
    """)
    suspend fun dismissAllCompleted(at: Long): Int

    @Query("""
        UPDATE whisper_jobs
        SET translateStatus = 'QUEUED', error = NULL, createdAt = :createdAt, dismissedAt = NULL, progressSummary = :progressSummary
        WHERE id = :id AND status = 'DONE' AND translateStatus = 'FAILED'
            AND NOT EXISTS (SELECT 1 FROM subtitle_publications WHERE whisperJobId = :id)
    """)
    suspend fun requeueTranslation(id: Long, createdAt: Long, progressSummary: String?): Int

    @Query("SELECT * FROM whisper_jobs WHERE id = :id")
    suspend fun get(id: Long): WhisperJobEntity?

    @Query("SELECT * FROM whisper_jobs WHERE fingerprint = :fingerprint ORDER BY id ASC")
    suspend fun forFingerprint(fingerprint: String): List<WhisperJobEntity>

    @Query("SELECT * FROM whisper_jobs WHERE trackId = :trackId AND status IN ('QUEUED', 'RUNNING') LIMIT 1")
    suspend fun activeForTrack(trackId: Long): WhisperJobEntity?

    @Query("SELECT * FROM whisper_jobs WHERE awaitRemoteUrl = :url AND status IN ('QUEUED', 'RUNNING') LIMIT 1")
    suspend fun activeForUrl(url: String): WhisperJobEntity?

    @Query("SELECT * FROM whisper_jobs WHERE status IN ('QUEUED', 'RUNNING')")
    suspend fun active(): List<WhisperJobEntity>

    @Query("SELECT localPath FROM whisper_jobs WHERE status IN ('QUEUED', 'RUNNING') AND localPath IS NOT NULL")
    suspend fun activeLocalPaths(): List<String>

    @Query("SELECT * FROM whisper_jobs WHERE status = 'QUEUED' AND fingerprint = '' ORDER BY id ASC")
    suspend fun waitingForFile(): List<WhisperJobEntity>

    @Query("SELECT * FROM whisper_jobs WHERE status = 'DONE'")
    suspend fun done(): List<WhisperJobEntity>

    @Query("SELECT * FROM whisper_jobs WHERE status = 'DONE' AND trackId = :trackId")
    suspend fun doneForTrack(trackId: Long): List<WhisperJobEntity>

    @Query("SELECT * FROM whisper_jobs WHERE status = 'DONE' AND awaitRemoteUrl = :url")
    suspend fun doneForUrl(url: String): List<WhisperJobEntity>

    @Query("SELECT * FROM whisper_jobs WHERE status = 'DONE' AND localPath = :path")
    suspend fun doneForPath(path: String): List<WhisperJobEntity>

    @Query("""
        DELETE FROM whisper_jobs WHERE status = 'DONE' AND fingerprint = :fingerprint
            AND (translateStatus IS NULL OR translateStatus NOT IN ('QUEUED', 'RUNNING'))
            AND NOT EXISTS (SELECT 1 FROM subtitle_publications WHERE whisperJobId = whisper_jobs.id)
    """)
    suspend fun deleteDoneForFingerprint(fingerprint: String)

    @Query("""
        DELETE FROM whisper_jobs WHERE status = 'DONE' AND id = :id
            AND (translateStatus IS NULL OR translateStatus NOT IN ('QUEUED', 'RUNNING'))
            AND NOT EXISTS (SELECT 1 FROM subtitle_publications WHERE whisperJobId = whisper_jobs.id)
    """)
    suspend fun deleteDoneForId(id: Long)

    @Query("SELECT * FROM whisper_jobs WHERE status = 'DONE' AND trackId = :trackId ORDER BY id DESC LIMIT 1")
    suspend fun latestDoneForTrack(trackId: Long): WhisperJobEntity?

    @Query("SELECT * FROM whisper_jobs WHERE status = 'DONE' AND localPath = :path ORDER BY id DESC LIMIT 1")
    suspend fun latestDoneForPath(path: String): WhisperJobEntity?

    @Query("SELECT * FROM whisper_jobs WHERE status = 'DONE' AND awaitRemoteUrl = :url ORDER BY id DESC LIMIT 1")
    suspend fun latestDoneForUrl(url: String): WhisperJobEntity?

    @Insert
    suspend fun insert(job: WhisperJobEntity): Long

    @Query(
        """
        UPDATE whisper_jobs
        SET status = 'RUNNING', progress = :progress, error = NULL, translateStatus = NULL
        WHERE id = :id AND status IN ('QUEUED', 'RUNNING')
        """,
    )
    suspend fun markRunningIfActive(id: Long, progress: Float): Int

    @Query("UPDATE whisper_jobs SET progress = :progress WHERE id = :id")
    suspend fun setProgress(id: Long, progress: Float)

    @Query("UPDATE whisper_jobs SET remoteJobId = :remoteId, remoteSubmittedAt = :submittedAt WHERE id = :id AND status = 'RUNNING'")
    suspend fun setRemoteId(id: Long, remoteId: String, submittedAt: Long)

    @Query("UPDATE whisper_jobs SET fingerprint = :fingerprint, localPath = :path WHERE id = :id AND status = 'QUEUED'")
    suspend fun attachFile(id: Long, fingerprint: String, path: String)

    @Query(
        """
        UPDATE whisper_jobs
        SET status = 'DONE', progress = 1, error = NULL, translateStatus = NULL
        WHERE id = :id AND status IN ('QUEUED', 'RUNNING')
        """,
    )
    suspend fun finishIfActive(id: Long): Int

    @Query("UPDATE whisper_jobs SET status = 'DONE', progress = 1, error = NULL, translateStatus = 'RUNNING' WHERE id = :id AND status IN ('QUEUED', 'RUNNING')")
    suspend fun markTranslatingIfActive(id: Long): Int

    @Query("UPDATE whisper_jobs SET status = 'DONE', progress = 1, error = NULL, translateStatus = 'DONE', translatedAt = :at WHERE id = :id")
    suspend fun markTranslated(id: Long, at: Long)

    @Query("""
        UPDATE whisper_jobs
        SET translateStatus = NULL, translatedAt = NULL, error = NULL, progressSummary = :summary
        WHERE id = :id AND status = 'DONE'
            AND (translateStatus IS NULL OR translateStatus NOT IN ('QUEUED', 'RUNNING'))
            AND NOT EXISTS (SELECT 1 FROM subtitle_publications WHERE whisperJobId = :id)
    """)
    suspend fun clearTranslationForId(id: Long, summary: String?): Int

    @Query("UPDATE whisper_jobs SET status = 'DONE', progress = 1, error = :error, translateStatus = 'FAILED' WHERE id = :id")
    suspend fun markTranslateFailed(id: Long, error: String)

    @Query("UPDATE whisper_jobs SET error = :error, translateStatus = 'FAILED' WHERE id = :id AND translateStatus IN ('QUEUED', 'RUNNING') AND NOT EXISTS (SELECT 1 FROM subtitle_publications WHERE whisperJobId = :id)")
    suspend fun failTranslateIfUnpublished(id: Long, error: String): Int

    @Query("UPDATE whisper_jobs SET error = :error, translateStatus = 'FAILED' WHERE translateStatus = 'RUNNING' AND NOT EXISTS (SELECT 1 FROM subtitle_publications WHERE whisperJobId = whisper_jobs.id)")
    suspend fun failInterruptedTranslations(error: String)

    @Query("UPDATE whisper_jobs SET status = 'FAILED', error = :error WHERE id = :id")
    suspend fun fail(id: Long, error: String)

    @Query("UPDATE whisper_jobs SET status = 'FAILED', error = :error WHERE status IN ('QUEUED', 'RUNNING')")
    suspend fun failActive(error: String)

    @Query("UPDATE whisper_jobs SET status = 'FAILED', error = :error WHERE trackId = :trackId AND status IN ('QUEUED', 'RUNNING')")
    suspend fun failActiveForTrack(trackId: Long, error: String)

    @Query("UPDATE whisper_jobs SET error = :error, translateStatus = 'FAILED' WHERE trackId = :trackId AND translateStatus IN ('QUEUED', 'RUNNING') AND NOT EXISTS (SELECT 1 FROM subtitle_publications WHERE whisperJobId = whisper_jobs.id)")
    suspend fun failTranslationForTrack(trackId: Long, error: String)

    @Query("UPDATE whisper_jobs SET status = 'FAILED', error = :error WHERE id = :id AND status = 'QUEUED'")
    suspend fun failWaiting(id: Long, error: String)

    @Query("""
        UPDATE whisper_jobs SET status = 'FAILED', error = :error
        WHERE id = :id AND status = 'QUEUED'
            AND NOT EXISTS (SELECT 1 FROM subtitle_publications WHERE whisperJobId = :id)
    """)
    suspend fun failQueuedIfUnpublished(id: Long, error: String): Int

    @Query("""
        UPDATE whisper_jobs SET translateStatus = 'FAILED', error = :error
        WHERE id = :id AND status = 'DONE' AND translateStatus = 'QUEUED'
            AND NOT EXISTS (SELECT 1 FROM subtitle_publications WHERE whisperJobId = :id)
    """)
    suspend fun failQueuedTranslationIfUnpublished(id: Long, error: String): Int

    @Query(
        """
        UPDATE whisper_jobs
        SET title = :title, fingerprint = :fingerprint, trackId = :trackId, localPath = :path,
            awaitRemoteUrl = :url, endpoint = :endpoint, skipTranslate = :skipTranslate, model = :model,
            status = 'QUEUED', remoteJobId = NULL, remoteSubmittedAt = NULL, progress = 0, error = NULL,
            translateStatus = NULL, progressSummary = NULL, createdAt = :createdAt, dismissedAt = NULL
        WHERE id = :id AND status = 'FAILED'
            AND NOT EXISTS (SELECT 1 FROM subtitle_publications WHERE whisperJobId = :id)
        """,
    )
    suspend fun requeueWithFile(
        id: Long,
        title: String,
        fingerprint: String,
        trackId: Long?,
        path: String,
        url: String?,
        endpoint: String?,
        skipTranslate: Boolean?,
        model: String?,
        createdAt: Long = System.currentTimeMillis(),
    ): Int

    @Query(
        """
        UPDATE whisper_jobs
        SET status = 'QUEUED', fingerprint = '', localPath = NULL, remoteJobId = NULL, remoteSubmittedAt = NULL, progress = 0, error = NULL,
            translateStatus = NULL, progressSummary = NULL, createdAt = :createdAt, dismissedAt = NULL
        WHERE id = :id AND status = 'FAILED'
            AND NOT EXISTS (SELECT 1 FROM subtitle_publications WHERE whisperJobId = :id)
        """,
    )
    suspend fun requeueForDownload(id: Long, createdAt: Long = System.currentTimeMillis()): Int
}

data class SubtitlePublicationMeta(
    val filename: String,
    val whisperJobId: Long?,
    val translationJobId: Long?,
    val translatedAt: Long,
    val payloadLength: Int,
)

@Dao
interface TranslationDao {
    @Query("UPDATE translation_jobs SET progressSummary = :summary WHERE id = :id")
    suspend fun setProgressSummary(id: Long, summary: String?)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun stagePublication(item: SubtitlePublicationEntity)

    @Query("SELECT filename, whisperJobId, translationJobId, translatedAt, length(payload) AS payloadLength FROM subtitle_publications")
    suspend fun pendingPublications(): List<SubtitlePublicationMeta>

    @Query("SELECT EXISTS(SELECT 1 FROM subtitle_publications WHERE filename = :filename)")
    suspend fun hasPendingPublication(filename: String): Boolean

    @Query("SELECT filename FROM subtitle_publications")
    suspend fun pendingPublicationFilenames(): List<String>

    @Query("SELECT substr(payload, :start, :length) FROM subtitle_publications WHERE filename = :filename")
    suspend fun payloadChunk(filename: String, start: Int, length: Int): String?

    @Query("SELECT DISTINCT whisperJobId FROM subtitle_publications WHERE whisperJobId IS NOT NULL")
    fun observeWhisperIds(): Flow<List<Long>>

    @Query("SELECT DISTINCT translationJobId FROM subtitle_publications WHERE translationJobId IS NOT NULL")
    fun observeTranslationIds(): Flow<List<Long>>

    @Query("DELETE FROM subtitle_publications WHERE filename = :filename")
    suspend fun clearPublication(filename: String)

    @Query("SELECT * FROM translation_jobs WHERE status IN ('QUEUED', 'RUNNING') ORDER BY id")
    suspend fun active(): List<TranslationJobEntity>

    @Query("""
        UPDATE translation_jobs
        SET status = 'QUEUED', error = NULL, progressSummary = NULL, createdAt = :createdAt, dismissedAt = NULL
        WHERE id = :id AND status = 'FAILED'
            AND NOT EXISTS (SELECT 1 FROM subtitle_publications WHERE translationJobId = :id)
    """)
    suspend fun requeue(id: Long, createdAt: Long = System.currentTimeMillis()): Int

    @Query("UPDATE translation_jobs SET status = 'RUNNING', error = NULL WHERE id = :id AND status = 'QUEUED'")
    suspend fun startQueued(id: Long): Int

    @Query("UPDATE translation_jobs SET status = 'FAILED', error = :error WHERE id = :id AND status IN ('QUEUED', 'RUNNING') AND NOT EXISTS (SELECT 1 FROM subtitle_publications WHERE translationJobId = :id)")
    suspend fun failIfActive(id: Long, error: String): Int

    @Query("DELETE FROM translation_jobs WHERE generatedFingerprint = :fingerprint AND status IN ('DONE', 'FAILED')")
    suspend fun deleteInactiveForFingerprint(fingerprint: String)

    @Query("""
        UPDATE translation_jobs SET sourceFingerprint = :fingerprint, targetKind = :targetKind, outputFilename = :filename,
            generatedFingerprint = :generatedFingerprint
        WHERE id = :id AND status = 'RUNNING'
    """)
    suspend fun setTarget(id: Long, fingerprint: String, targetKind: String, filename: String, generatedFingerprint: String?): Int

    @Query("SELECT * FROM translation_jobs ORDER BY id DESC")
    fun observe(): Flow<List<TranslationJobEntity>>

    @Query("SELECT * FROM translation_jobs WHERE dismissedAt IS NULL ORDER BY id DESC")
    fun observeVisible(): Flow<List<TranslationJobEntity>>

    @Query("""
        DELETE FROM translation_jobs WHERE id = :id AND status = 'FAILED'
            AND NOT EXISTS (SELECT 1 FROM subtitle_publications WHERE translationJobId = :id)
    """)
    suspend fun deleteFailed(id: Long): Int

    @Query("""
        UPDATE translation_jobs SET dismissedAt = :at
        WHERE id = :id AND status = 'DONE' AND dismissedAt IS NULL
            AND NOT EXISTS (SELECT 1 FROM subtitle_publications WHERE translationJobId = :id)
    """)
    suspend fun dismissDone(id: Long, at: Long): Int

    @Query("""
        UPDATE translation_jobs SET dismissedAt = :at
        WHERE status = 'DONE' AND dismissedAt IS NULL
            AND NOT EXISTS (SELECT 1 FROM subtitle_publications WHERE translationJobId = translation_jobs.id)
    """)
    suspend fun dismissAllCompleted(at: Long): Int

    @Query("SELECT * FROM translation_jobs WHERE trackId = :trackId ORDER BY id DESC LIMIT 1")
    suspend fun latestForTrack(trackId: Long): TranslationJobEntity?

    @Query("SELECT * FROM translation_jobs WHERE queueItemId = :queueItemId ORDER BY id DESC LIMIT 1")
    suspend fun latestForQueue(queueItemId: Long): TranslationJobEntity?

    @Query("SELECT * FROM translation_jobs WHERE id = :id")
    suspend fun get(id: Long): TranslationJobEntity?

    @Query("SELECT * FROM translation_jobs")
    suspend fun all(): List<TranslationJobEntity>

    /** 覆盖文件只可能由完成或仍在执行的任务写出；失败历史不参与命名，避免整表加载。 */
    @Query("SELECT * FROM translation_jobs WHERE status <> 'FAILED' AND generatedFingerprint IS NULL")
    suspend fun overlayCandidates(): List<TranslationJobEntity>

    @Insert
    suspend fun insert(job: TranslationJobEntity): Long

    @Query("UPDATE translation_jobs SET status = 'RUNNING', title = :title, error = NULL WHERE id = :id")
    suspend fun markRunning(id: Long, title: String)

    @Query(
        """
        UPDATE translation_jobs
        SET status = 'DONE', error = NULL
        WHERE id = :id AND status IN ('QUEUED', 'RUNNING')
        """,
    )
    suspend fun finishIfActive(id: Long): Int

    @Query("UPDATE translation_jobs SET status = 'FAILED', error = :error WHERE status IN ('QUEUED', 'RUNNING') AND NOT EXISTS (SELECT 1 FROM subtitle_publications WHERE translationJobId = translation_jobs.id)")
    suspend fun failInterrupted(error: String)

    @Query("DELETE FROM translation_jobs WHERE id = :id AND status IN ('DONE', 'FAILED')")
    suspend fun deleteInactive(id: Long)

    /** 只删终态记录；进行中的翻译保留，与听写侧「保留进行中的听写」一致。 */
    @Query("UPDATE translation_jobs SET status = 'FAILED', error = :error WHERE (trackId = :trackId OR queueItemId IN (:queueIds)) AND status IN ('QUEUED', 'RUNNING') AND NOT EXISTS (SELECT 1 FROM subtitle_publications WHERE translationJobId = translation_jobs.id)")
    suspend fun failForRemovedAudio(trackId: Long?, queueIds: List<Long>, error: String)

    @Query("DELETE FROM translation_jobs WHERE status IN ('DONE', 'FAILED') AND trackId = :trackId AND NOT EXISTS (SELECT 1 FROM subtitle_publications WHERE translationJobId = translation_jobs.id)")
    suspend fun deleteInactiveForTrack(trackId: Long)

    @Query("DELETE FROM translation_jobs WHERE status IN ('DONE', 'FAILED') AND remoteUrl = :url AND NOT EXISTS (SELECT 1 FROM subtitle_publications WHERE translationJobId = translation_jobs.id)")
    suspend fun deleteInactiveForUrl(url: String)

    @Query("DELETE FROM translation_jobs WHERE status IN ('DONE', 'FAILED') AND bvid = :bvid AND cid = :cid AND NOT EXISTS (SELECT 1 FROM subtitle_publications WHERE translationJobId = translation_jobs.id)")
    suspend fun deleteInactiveForBili(bvid: String, cid: Long)

    @Query("DELETE FROM translation_jobs WHERE status IN ('DONE', 'FAILED') AND queueItemId = :queueItemId")
    suspend fun deleteInactiveForQueue(queueItemId: Long)
}