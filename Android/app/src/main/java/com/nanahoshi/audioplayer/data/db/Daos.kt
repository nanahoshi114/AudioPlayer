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

    @Query("UPDATE folders SET parentId = :parentId WHERE id = :id")
    suspend fun setParent(id: Long, parentId: Long?)

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

    @Query("SELECT COUNT(*) FROM tracks WHERE asmrWorkId = :workId AND source = :source")
    suspend fun countByWorkSource(workId: Long, source: TrackSource): Int

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

    @Query("SELECT * FROM library_files WHERE asmrWorkId = :workId AND fileHash = :hash LIMIT 1")
    suspend fun findAsmr(workId: Long, hash: String): LibraryFileEntity?

    @Query("SELECT * FROM library_files WHERE folderId IN (:folderIds)")
    suspend fun inFolders(folderIds: List<Long>): List<LibraryFileEntity>

    @Query("SELECT * FROM library_files WHERE asmrWorkId = :workId")
    suspend fun forWork(workId: Long): List<LibraryFileEntity>

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

    @Query("SELECT * FROM downloads WHERE source = :source AND workKey = :workKey AND localPath IS NOT NULL")
    suspend fun locatedForWork(source: String, workKey: String): List<DownloadEntity>

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

    @Query("UPDATE whisper_jobs SET awaitDownloadId = NULL, awaitFileKey = NULL WHERE id = :id")
    suspend fun clearAwaitDownload(id: Long)

    @Query("UPDATE whisper_jobs SET awaitDownloadId = :downloadId, awaitFileKey = :fileKey WHERE id = :id")
    suspend fun setAwaitDownload(id: Long, downloadId: Long, fileKey: String?)

    @Query("SELECT * FROM whisper_jobs ORDER BY id DESC")
    fun observe(): Flow<List<WhisperJobEntity>>

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

    @Query(
        """
        SELECT * FROM whisper_jobs
        WHERE status IN ('QUEUED', 'RUNNING') AND fingerprint != '' AND localPath IS NOT NULL
        ORDER BY CASE status WHEN 'RUNNING' THEN 0 ELSE 1 END, id ASC
        LIMIT 1
        """,
    )
    suspend fun nextUploadable(): WhisperJobEntity?

    @Query("SELECT * FROM whisper_jobs WHERE status = 'DONE'")
    suspend fun done(): List<WhisperJobEntity>

    @Query("SELECT * FROM whisper_jobs WHERE status = 'DONE' AND trackId = :trackId")
    suspend fun doneForTrack(trackId: Long): List<WhisperJobEntity>

    @Query("SELECT * FROM whisper_jobs WHERE status = 'DONE' AND awaitRemoteUrl = :url")
    suspend fun doneForUrl(url: String): List<WhisperJobEntity>

    @Query("SELECT * FROM whisper_jobs WHERE status = 'DONE' AND localPath = :path")
    suspend fun doneForPath(path: String): List<WhisperJobEntity>

    @Query("DELETE FROM whisper_jobs WHERE status = 'DONE' AND fingerprint = :fingerprint")
    suspend fun deleteDoneForFingerprint(fingerprint: String)

    @Query("DELETE FROM whisper_jobs WHERE status = 'DONE' AND trackId = :trackId")
    suspend fun deleteDoneForTrack(trackId: Long)

    @Query("DELETE FROM whisper_jobs WHERE status = 'DONE' AND awaitRemoteUrl = :url")
    suspend fun deleteDoneForUrl(url: String)

    @Query("DELETE FROM whisper_jobs WHERE status = 'DONE' AND localPath = :path")
    suspend fun deleteDoneForPath(path: String)

    @Query("SELECT * FROM whisper_jobs WHERE status = 'DONE' AND trackId = :trackId ORDER BY id DESC LIMIT 1")
    suspend fun latestDoneForTrack(trackId: Long): WhisperJobEntity?

    @Query("SELECT * FROM whisper_jobs WHERE status = 'DONE' AND localPath = :path ORDER BY id DESC LIMIT 1")
    suspend fun latestDoneForPath(path: String): WhisperJobEntity?

    @Query("SELECT * FROM whisper_jobs WHERE status = 'DONE' AND awaitRemoteUrl = :url ORDER BY id DESC LIMIT 1")
    suspend fun latestDoneForUrl(url: String): WhisperJobEntity?

    @Insert
    suspend fun insert(job: WhisperJobEntity): Long

    @Query("UPDATE whisper_jobs SET status = 'RUNNING', progress = :progress, error = NULL, translateStatus = NULL WHERE id = :id")
    suspend fun markRunning(id: Long, progress: Float)

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

    @Query("UPDATE whisper_jobs SET status = 'DONE', progress = 1, error = NULL, translateStatus = NULL WHERE id = :id")
    suspend fun finish(id: Long)

    @Query(
        """
        UPDATE whisper_jobs
        SET status = 'DONE', progress = 1, error = NULL, translateStatus = NULL
        WHERE id = :id AND status IN ('QUEUED', 'RUNNING')
        """,
    )
    suspend fun finishIfActive(id: Long): Int

    @Query("UPDATE whisper_jobs SET status = 'DONE', progress = 1, error = NULL, translateStatus = 'RUNNING' WHERE id = :id")
    suspend fun markTranslating(id: Long)

    @Query(
        """
        UPDATE whisper_jobs
        SET status = 'DONE', progress = 1, error = NULL, translateStatus = 'RUNNING'
        WHERE id = :id AND status IN ('QUEUED', 'RUNNING')
        """,
    )
    suspend fun markTranslatingIfActive(id: Long): Int

    @Query("UPDATE whisper_jobs SET status = 'DONE', progress = 1, error = NULL, translateStatus = 'DONE', translatedAt = :at WHERE id = :id")
    suspend fun markTranslated(id: Long, at: Long)

    @Query(
        """
        UPDATE whisper_jobs
        SET status = 'DONE', progress = 1, error = NULL, translateStatus = 'DONE', translatedAt = :at
        WHERE id = :id AND translateStatus = 'RUNNING'
        """,
    )
    suspend fun markTranslatedIfRunning(id: Long, at: Long): Int

    @Query("UPDATE whisper_jobs SET status = 'DONE', progress = 1, error = :error, translateStatus = 'FAILED' WHERE id = :id")
    suspend fun markTranslateFailed(id: Long, error: String)

    @Query("UPDATE whisper_jobs SET error = :error, translateStatus = 'FAILED' WHERE id = :id AND translateStatus IN ('QUEUED', 'RUNNING') AND NOT EXISTS (SELECT 1 FROM subtitle_publications WHERE whisperJobId = :id)")
    suspend fun failTranslateIfUnpublished(id: Long, error: String): Int

    @Query("UPDATE whisper_jobs SET error = :error, translateStatus = 'FAILED' WHERE translateStatus = 'RUNNING' AND NOT EXISTS (SELECT 1 FROM subtitle_publications WHERE whisperJobId = whisper_jobs.id)")
    suspend fun failInterruptedTranslations(error: String)

    @Query("UPDATE whisper_jobs SET translatedAt = :at WHERE id = :id")
    suspend fun setTranslatedAt(id: Long, at: Long)

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

    @Query(
        """
        UPDATE whisper_jobs
        SET title = :title, fingerprint = :fingerprint, trackId = :trackId, localPath = :path,
            awaitRemoteUrl = :url, endpoint = :endpoint, skipTranslate = :skipTranslate, model = :model,
            status = 'QUEUED', remoteJobId = NULL, remoteSubmittedAt = NULL, progress = 0, error = NULL, translateStatus = NULL, progressSummary = NULL
        WHERE id = :id
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
    )

    @Query(
        """
        UPDATE whisper_jobs
        SET status = 'QUEUED', fingerprint = '', localPath = NULL, remoteJobId = NULL, remoteSubmittedAt = NULL, progress = 0, error = NULL,
            translateStatus = NULL, progressSummary = NULL
        WHERE id = :id
        """,
    )
    suspend fun requeueForDownload(id: Long)
}

@Dao
interface TranslationDao {
    @Query("UPDATE translation_jobs SET progressSummary = :summary WHERE id = :id")
    suspend fun setProgressSummary(id: Long, summary: String?)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun stagePublication(item: SubtitlePublicationEntity)

    @Query("SELECT * FROM subtitle_publications")
    suspend fun pendingPublications(): List<SubtitlePublicationEntity>

    @Query("DELETE FROM subtitle_publications WHERE filename = :filename")
    suspend fun clearPublication(filename: String)

    @Query("SELECT * FROM translation_jobs WHERE status IN ('QUEUED', 'RUNNING') ORDER BY id")
    suspend fun active(): List<TranslationJobEntity>

    @Query("UPDATE translation_jobs SET status = 'QUEUED', error = NULL, progressSummary = NULL WHERE id = :id AND status = 'FAILED'")
    suspend fun requeue(id: Long): Int

    @Query("UPDATE translation_jobs SET status = 'RUNNING', error = NULL WHERE id = :id AND status = 'QUEUED'")
    suspend fun startQueued(id: Long): Int

    @Query("UPDATE translation_jobs SET status = 'FAILED', error = :error WHERE id = :id AND status IN ('QUEUED', 'RUNNING') AND NOT EXISTS (SELECT 1 FROM subtitle_publications WHERE translationJobId = :id)")
    suspend fun failIfActive(id: Long, error: String): Int

    @Query("DELETE FROM translation_jobs WHERE generatedFingerprint = :fingerprint AND status IN ('DONE', 'FAILED')")
    suspend fun deleteInactiveForFingerprint(fingerprint: String)

    @Query("UPDATE translation_jobs SET sourceFingerprint = :value WHERE id = :id AND status = 'RUNNING'")
    suspend fun setSourceFingerprint(id: Long, value: String)

    @Query("SELECT * FROM translation_jobs ORDER BY id DESC")
    fun observe(): Flow<List<TranslationJobEntity>>

    @Query("SELECT * FROM translation_jobs WHERE trackId = :trackId ORDER BY id DESC LIMIT 1")
    suspend fun latestForTrack(trackId: Long): TranslationJobEntity?

    @Query("SELECT * FROM translation_jobs WHERE remoteUrl = :url ORDER BY id DESC LIMIT 1")
    suspend fun latestForUrl(url: String): TranslationJobEntity?

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

    @Query("UPDATE translation_jobs SET status = 'DONE', error = NULL WHERE id = :id")
    suspend fun finish(id: Long)

    @Query(
        """
        UPDATE translation_jobs
        SET status = 'DONE', error = NULL
        WHERE id = :id AND status IN ('QUEUED', 'RUNNING')
        """,
    )
    suspend fun finishIfActive(id: Long): Int

    @Query("UPDATE translation_jobs SET status = 'FAILED', error = :error WHERE id = :id")
    suspend fun fail(id: Long, error: String)

    @Query("UPDATE translation_jobs SET status = 'FAILED', error = :error WHERE status IN ('QUEUED', 'RUNNING') AND NOT EXISTS (SELECT 1 FROM subtitle_publications WHERE translationJobId = translation_jobs.id)")
    suspend fun failInterrupted(error: String)

    @Query("DELETE FROM translation_jobs WHERE id = :id AND status IN ('DONE', 'FAILED')")
    suspend fun deleteInactive(id: Long)

    /** 只删终态记录；进行中的翻译保留，与听写侧「保留进行中的听写」一致。 */
    @Query("UPDATE translation_jobs SET status = 'FAILED', error = :error WHERE (trackId = :trackId OR queueItemId IN (:queueIds)) AND status IN ('QUEUED', 'RUNNING') AND NOT EXISTS (SELECT 1 FROM subtitle_publications WHERE translationJobId = translation_jobs.id)")
    suspend fun failForRemovedAudio(trackId: Long?, queueIds: List<Long>, error: String)

    @Query("DELETE FROM translation_jobs WHERE status IN ('DONE', 'FAILED') AND trackId = :trackId")
    suspend fun deleteInactiveForTrack(trackId: Long)

    @Query("DELETE FROM translation_jobs WHERE status IN ('DONE', 'FAILED') AND remoteUrl = :url")
    suspend fun deleteInactiveForUrl(url: String)

    @Query("DELETE FROM translation_jobs WHERE status IN ('DONE', 'FAILED') AND bvid = :bvid AND cid = :cid")
    suspend fun deleteInactiveForBili(bvid: String, cid: Long)

    @Query("DELETE FROM translation_jobs WHERE status IN ('DONE', 'FAILED') AND queueItemId = :queueItemId")
    suspend fun deleteInactiveForQueue(queueItemId: Long)
}

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


@Dao
interface DlsiteOriginalDao {
    @Query("SELECT * FROM dlsite_original_works WHERE workno = :workno")
    suspend fun work(workno: String): DlsiteOriginalWorkEntity?

    @Query("SELECT * FROM dlsite_original_works")
    suspend fun works(): List<DlsiteOriginalWorkEntity>

    @Query("SELECT * FROM dlsite_original_entries WHERE workno = :workno")
    suspend fun entries(workno: String): List<DlsiteOriginalEntryEntity>

    @Query("SELECT * FROM dlsite_original_entries")
    suspend fun allEntries(): List<DlsiteOriginalEntryEntity>

    @Query("SELECT * FROM dlsite_original_entries")
    fun observeEntries(): Flow<List<DlsiteOriginalEntryEntity>>

    @Query("SELECT * FROM dlsite_original_entries WHERE trackId = :trackId LIMIT 1")
    suspend fun entryForTrack(trackId: Long): DlsiteOriginalEntryEntity?

    @Query("SELECT * FROM dlsite_original_entries WHERE workno = :workno AND playFileKey = :key LIMIT 1")
    suspend fun entryForPlay(workno: String, key: String): DlsiteOriginalEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWork(work: DlsiteOriginalWorkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntries(entries: List<DlsiteOriginalEntryEntity>)

    @Query("DELETE FROM dlsite_original_entries WHERE workno = :workno")
    suspend fun deleteEntries(workno: String)
}
