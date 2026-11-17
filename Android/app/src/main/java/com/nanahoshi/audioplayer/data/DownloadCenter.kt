package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.youtube.YouTubeClient
import com.nanahoshi.audioplayer.youtube.YouTubeException
import com.nanahoshi.audioplayer.youtube.readyYouTubeAudio
import com.nanahoshi.audioplayer.youtube.youtubeAudioFile

import androidx.room.withTransaction
import android.content.Context
import com.nanahoshi.audioplayer.asmr.AsmrClient
import com.nanahoshi.audioplayer.bilibili.BiliSubtitle
import com.nanahoshi.audioplayer.bilibili.BilibiliClient
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.dlsite.DlsiteClient
import com.nanahoshi.audioplayer.playback.SubtitleSource
import com.nanahoshi.audioplayer.data.db.DownloadEntity
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.TrackEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

internal const val DOWNLOAD_LIBRARY_REQUIRED = "请先加入音频库"

private class DownloadPreparation : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    val jobs = ConcurrentHashMap.newKeySet<Long>()

    companion object Key : CoroutineContext.Key<DownloadPreparation>
}

class DownloadCenter internal constructor(
    context: Context,
    private val db: AppDatabase,
    private val asmr: AsmrClient,
    private val dlsite: DlsiteClient,
    private val bilibili: BilibiliClient,
    private val youtube: YouTubeClient,
    private val library: LibraryRepository,
    private val subtitles: SubtitleSource,
    private val startService: (Context, Long) -> Boolean = AudioDownloadService::start,
    private val notice: (String) -> Unit = {},
) {
    private val appContext = context.applicationContext
    internal val requests = ForegroundTaskRequests<Unit>({ startService(appContext, it) }) {
        AudioDownloadStopped(AUDIO_DOWNLOAD_START_FAILED)
    }
    private val runners = ConcurrentHashMap<Long, Job>()
    private val filesDir = context.filesDir
    private val root = File(filesDir, "asmr")
    private val dlsiteRoot = File(filesDir, "dlsite")
    private val biliHttp = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workerLock = Mutex()

    private val archiveLock = Mutex()
    private val archiveClient = com.nanahoshi.audioplayer.dlsite.DlsiteArchiveClient { dlsite.cookieHeader() }
    private val originals = DlsiteOriginalStore(appContext, db, dlsite, library)

    suspend fun requestWorkno(track: TrackEntity): String? =
        db.dlsiteOriginals().entryForTrack(track.id)?.workno ?: worknoOf(track)

    /** 复用已活跃或已完成的整包时 added=false，但 id 仍携带（听写等待绑定依赖它）。 */
    internal suspend fun enqueueDlsiteArchive(
        workno: String,
        title: String = workno,
        expectedFolderId: Long? = null,
    ): Enqueued = prepare {
        archiveLock.withLock {
            val key = DlsiteClient.canonicalWorkno(workno)
            val folder = library.folderBySource(key) ?: error(DOWNLOAD_LIBRARY_REQUIRED)
            check(expectedFolderId == null || folder.id == expectedFolderId) { DOWNLOAD_LIBRARY_REQUIRED }
            db.downloads().activeArchive(key)?.takeIf { it.workFolderId == folder.id }?.let { return@withLock Enqueued(it.id, false) }
            val saved = db.dlsiteOriginals().work(key)
            val entries = db.dlsiteOriginals().entries(key)
            if (saved != null && entries.any { it.kind == "AUDIO" } &&
                entries.all { if (it.kind == "FOLDER") File(it.localPath).isDirectory else File(it.localPath).isFile }) {
                val needsRelink = saved.rootFolderId == null || db.folders().get(saved.rootFolderId) == null ||
                    entries.any { entry ->
                        when (entry.kind) {
                            "AUDIO" -> entry.trackId?.let { db.tracks().getById(it) }?.localUri != entry.localPath
                            "FOLDER" -> entry.folderId?.let { db.folders().get(it) } == null
                            else -> entry.fileId?.let { db.files().get(it) }?.localUri != entry.localPath
                        }
                    }
                if (needsRelink) {
                    reportSkipped(originals.importDirectory(key, File(saved.directory), saved.generation, beforeCommit = {
                        check(library.folderBySource(key)?.id == folder.id) { DOWNLOAD_LIBRARY_REQUIRED }
                    }))
                }
                check(library.folderBySource(key)?.id == folder.id) { DOWNLOAD_LIBRARY_REQUIRED }
                db.downloads().latestArchive(key)?.takeIf { it.status == DownloadStatus.DONE }?.let { return@withLock Enqueued(it.id, false) }
            }
            db.withTransaction {
                check(library.folderBySource(key)?.id == folder.id) { DOWNLOAD_LIBRARY_REQUIRED }
                val id = db.downloads().insert(DownloadEntity(
                    trackId = 0, title = "$title · 完整原始包", bytesDone = 0, bytesTotal = 0,
                    status = DownloadStatus.QUEUED, error = null, source = TrackSource.DLSITE.name,
                    workKey = key, kind = "DLSITE_ARCHIVE", workFolderId = folder.id,
                ))
                registerJob(id)
                Enqueued(id, true)
            }
        }
    }

    fun cancel(id: Long) {
        scope.launch {
            if (db.downloads().stopActive(id, "已中止") > 0) runners[id]?.cancel()
        }
    }

    suspend fun originalPathForTrack(trackId: Long): String? =
        db.dlsiteOriginals().entryForTrack(trackId)?.localPath?.takeIf { File(it).isFile }

    private suspend fun downloadArchive(job: DownloadEntity) = kotlinx.coroutines.coroutineScope {
        val workno = job.workKey ?: error("这个作品缺少编号")
        val self = currentCoroutineContext()[kotlinx.coroutines.Job]!!
        val generation = java.util.UUID.randomUUID().toString()
        val directory = File(dlsiteRoot, "$workno/original/$generation")
        val staging = File(filesDir, "dlsite-archives/$workno/${job.id}")
        var published = false
        val oldCopies = db.tracks().onlineWithLocal().filter { track ->
            track.source == TrackSource.DLSITE && requestWorkno(track)?.let { DlsiteClient.canonicalWorkno(it) } == workno
        }.mapNotNull { track -> track.localUri?.takeIf { path -> path.startsWith("/") }?.let { track.id to it } }
        val oldDirectory = db.dlsiteOriginals().work(workno)?.directory
        try {
            requireRunningTarget(job)
            db.downloads().setPhase(job.id, "RESOLVING")
            db.downloads().setPhase(job.id, "DOWNLOADING")
            var lastProgress = 0L
            val packages = archiveClient.download(workno, staging, onProgress = { done, total ->
                if (done - lastProgress >= 256 * 1024 || total == done) {
                    lastProgress = done
                    scope.launch {
                        if (self.isActive) db.downloads().activeProgress(job.id, done, total ?: 0L)
                    }
                }
            }, checkCancelled = { self.ensureActive() })
            self.ensureActive()
            db.downloads().setPhase(job.id, "EXTRACTING")
            com.nanahoshi.audioplayer.dlsite.DlsiteArchiveExtractor.extract(packages, directory) { self.ensureActive() }
            self.ensureActive()
            db.downloads().setPhase(job.id, "IMPORTING")
            val imported = originals.importDirectory(
                workno, directory, generation,
                beforeCommit = {
                    self.ensureActive()
                    requireRunningTarget(job)
                },
                onPublish = {
                    db.downloads().setLocalPath(job.id, directory.absolutePath)
                    val bytes = packages.sumOf { it.length() }
                    db.downloads().finish(job.id, bytes, bytes)
                },
                onCommitted = { published = true },
            )
            reportSkipped(imported)
            // 清单发布后不再响应中止；完整原始包只在发布成功后回收。
            staging.deleteRecursively()
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                retireOriginalCopies(oldCopies, oldDirectory)
            }
        } catch (error: com.nanahoshi.audioplayer.dlsite.DlsiteArchiveIntegrityException) {
            // 已确认损坏的包无法通过再次解压修复；仅清理这个任务的暂存。
            staging.deleteRecursively()
            throw error
        } finally {
            if (!published) directory.deleteRecursively()
        }
    }

    private fun reportSkipped(result: DlsiteOriginalImportResult) {
        if (result.skipped.isEmpty()) return
        notice("原始文件已解压并加入作品；${result.skipped.size} 个文件已关联其他曲目，保持不变")
    }

    private suspend fun retireOriginalCopies(paths: List<Pair<Long, String>>, oldDirectory: String?) {
        val active = db.whisper().activeLocalPaths().toSet()
        val tracks = db.tracks().onlineWithLocal().mapNotNull { it.localUri }.toSet()
        val files = db.files().observeAll().first().mapNotNull { it.localUri }.toSet()
        val queue = db.queue().snapshot()
        val queued = queue.flatMap { listOfNotNull(it.localUri, it.remoteUrl) }.toSet()
        // 已创建的MediaItem可能仍持有切换前的本地路径，队列里的曲目暂不回收旧副本。
        val queuedTrackIds = queue.mapNotNull { it.trackId }.toSet()
        val protected = active + tracks + files + queued + paths.filter { it.first in queuedTrackIds }.map { it.second }
        for (path in paths.map { it.second }.distinct()) {
            val file = File(path)
            val canonical = runCatching { file.canonicalPath }.getOrNull() ?: continue
            val managed = listOf(root, dlsiteRoot).any { canonical.startsWith(it.canonicalPath + File.separator) }
            if (managed && path !in protected && file.isFile && file.delete()) db.downloads().deleteFinishedForPath(path)
        }
        val old = oldDirectory?.let(::File) ?: return
        if (old.canonicalPath.startsWith(dlsiteRoot.canonicalPath + File.separator) &&
            protected.none { it == old.path || it.startsWith(old.path + File.separator) }) {
            old.deleteRecursively()
        }
    }

    /** 回收进程中断后未发布的解压代次；失败任务的完整压缩包保留供重试。 */
    private suspend fun cleanUnpublishedOriginals() {
        val works = db.dlsiteOriginals().works()
        val references = works.map { it.directory } +
            db.tracks().onlineWithLocal().mapNotNull { it.localUri } +
            db.files().observeAll().first().mapNotNull { it.localUri } +
            db.queue().snapshot().flatMap { listOfNotNull(it.localUri, it.remoteUrl) } +
            db.whisper().activeLocalPaths()
        val protected = references.filter { it.startsWith("/") }.mapNotNull {
            runCatching { File(it).canonicalPath }.getOrNull()
        }
        val rootPath = dlsiteRoot.canonicalPath + File.separator
        for (workDirectory in dlsiteRoot.listFiles().orEmpty()) {
            if (!Regex("(?:RJ|BJ|VJ)[0-9]+").matches(workDirectory.name)) continue
            for (generation in File(workDirectory, "original").listFiles().orEmpty()) {
                if (runCatching { java.util.UUID.fromString(generation.name) }.isFailure) continue
                val path = generation.canonicalPath
                if (!path.startsWith(rootPath) || protected.any { it == path || it.startsWith(path + File.separator) }) continue
                generation.deleteRecursively()
            }
        }
        // 未完成的任务保留暂存供断点续传；已完成和数据库里已无对应任务的孤儿目录一律回收。
        val live = db.downloads().archives()
            .filter { it.status != DownloadStatus.DONE }
            .mapNotNull { job -> job.workKey?.let { "$it/${job.id}" } }
            .toSet()
        val archivesRoot = File(filesDir, "dlsite-archives")
        for (workDirectory in archivesRoot.listFiles().orEmpty()) {
            if (!workDirectory.isDirectory || !Regex("(?:RJ|BJ|VJ)[0-9]+").matches(workDirectory.name)) continue
            for (jobDirectory in workDirectory.listFiles().orEmpty()) {
                if (!jobDirectory.isDirectory || jobDirectory.name.toLongOrNull() == null) continue
                if ("${workDirectory.name}/${jobDirectory.name}" in live) continue
                jobDirectory.deleteRecursively()
            }
            if (workDirectory.listFiles().isNullOrEmpty()) workDirectory.delete()
        }
    }

    fun observe(): Flow<List<DownloadEntity>> = db.downloads().observe()

    internal fun observeActive(): Flow<List<DownloadEntity>> = db.downloads().observeActive()

    private val startup = scope.async {
        for (directory in listOf(root, dlsiteRoot, File(filesDir, "youtube"), File(filesDir, "bilibili"))) {
            directory.walkTopDown()
                .onEnter { it.parentFile?.parentFile != dlsiteRoot || it.name != "original" }
                .filter { it.isFile && it.name.endsWith(".part") }
                .forEach { it.delete() }
        }
        db.withTransaction {
            for (job in db.downloads().active()) {
                db.downloads().stopActive(job.id, if (libraryTarget(job) == null) DOWNLOAD_LIBRARY_REQUIRED else AUDIO_DOWNLOAD_INTERRUPTED)
            }
        }
        cleanUnpublishedOriginals()
    }

    init {
        scope.launch {
            observe().collect { jobs ->
                jobs.filter { it.status == DownloadStatus.FAILED }.forEach { runners[it.id]?.cancel() }
            }
        }
    }

    internal fun resumePendingSubtitles() {
        scope.launch {
            try {
                startup.await()
                if (db.downloads().pendingBiliSubtitles().isNotEmpty()) startService(appContext, 0L)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice("无法恢复后台下载任务，请重试")
            }
        }
    }

    internal suspend fun drainQueue(onActive: (List<Long>) -> Unit) = withContext(Dispatchers.IO) {
        startup.await()
        workerLock.withLock {
            var activeIds = emptyList<Long>()
            // 保留待补标记的任务本轮只尝试一次，避免 drain 内无限重试；下次启动再接手。
            val triedSubtitles = mutableSetOf<Long>()
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    activeIds = db.downloads().active().map { it.id }
                    onActive(activeIds)
                    val job = db.downloads().nextQueued()
                    if (job == null) {
                        val subtitle = db.downloads().pendingBiliSubtitles().firstOrNull { it.id !in triedSubtitles } ?: break
                        triedSubtitles += subtitle.id
                        cacheBiliSubtitle(subtitle)
                    } else {
                        downloadOne(job)
                        db.downloads().get(job.id)?.takeIf { it.pendingBiliSubtitle == true }?.let {
                            triedSubtitles += it.id
                            cacheBiliSubtitle(it)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                val stopped = (currentCoroutineContext().cancellationCause() ?: cancelled) as? AudioDownloadStopped
                if (stopped != null) {
                    withContext(NonCancellable) {
                        stopJobs(activeIds, stopped.message ?: AUDIO_DOWNLOAD_INTERRUPTED)
                    }
                }
                throw cancelled
            }
        }
    }

    internal fun failAfterServiceStop(ids: List<Long>, message: String) {
        scope.launch { stopJobs(ids, message) }
    }

    private suspend fun stopJobs(ids: Collection<Long>, message: String) {
        db.withTransaction { ids.forEach { db.downloads().stopActive(it, message) } }
    }

    internal suspend fun awaitStartup() = startup.await()

    internal fun <T> submitPreparation(block: suspend () -> T) = requests.submit(Unit) {
        startup.await()
        val preparation = DownloadPreparation()
        try {
            withContext(preparation) { block() }
        } catch (stopped: AudioDownloadStopped) {
            withContext(NonCancellable) {
                stopJobs(preparation.jobs, stopped.message ?: AUDIO_DOWNLOAD_INTERRUPTED)
            }
            throw stopped
        }
    }

    private suspend fun registerJob(id: Long) {
        currentCoroutineContext()[DownloadPreparation]?.jobs?.add(id)
    }

    internal suspend fun <T> prepare(block: suspend () -> T): T {
        if (currentCoroutineContext()[DownloadPreparation.Key] != null) return block()
        try {
            return submitPreparation(block).result.await()
        } catch (stopped: AudioDownloadStopped) {
            currentCoroutineContext().ensureActive()
            throw IllegalStateException(stopped.message ?: AUDIO_DOWNLOAD_INTERRUPTED)
        }
    }

    suspend fun deleteDownloaded(tracks: List<TrackEntity>, deleteSubtitles: Boolean): DeleteResult =
        deleteLocalCopies(db, filesDir, subtitles, tracks, deleteSubtitles)

    suspend fun enqueue(tracks: List<TrackEntity>): Int = prepare {
        tracks.count { enqueueTrack(it.id).added }
    }

    internal data class Enqueued(val id: Long?, val added: Boolean)

    internal suspend fun enqueueTrack(trackId: Long): Enqueued = prepare {
        val track = db.tracks().getById(trackId) ?: error(DOWNLOAD_LIBRARY_REQUIRED)
        if (track.source == TrackSource.LOCAL || LocalAudio.hasStoredCopy(track.localUri)) return@prepare Enqueued(null, false)
        if (track.source == TrackSource.DLSITE && track.fileHash.isNullOrBlank()) {
            val workno = requestWorkno(track) ?: error(DOWNLOAD_LIBRARY_REQUIRED)
            val folderId = library.folderBySource(workno)?.id ?: error(DOWNLOAD_LIBRARY_REQUIRED)
            check(db.tracks().getById(track.id) != null) { DOWNLOAD_LIBRARY_REQUIRED }
            return@prepare enqueueDlsiteArchive(workno, expectedFolderId = folderId)
        }
        db.withTransaction {
            val current = db.tracks().getById(trackId) ?: error(DOWNLOAD_LIBRARY_REQUIRED)
            db.downloads().activeForTrack(trackId)?.let { return@withTransaction Enqueued(it.id, false) }
            val work = when (current.source) {
                TrackSource.BILIBILI -> checkNotNull(current.bvid)
                TrackSource.YOUTUBE -> checkNotNull(current.youtubeVideoId)
                else -> worknoOf(current)
            }
            val key = when (current.source) {
                TrackSource.BILIBILI -> checkNotNull(current.cid).toString()
                TrackSource.YOUTUBE -> "audio"
                else -> rawFileKey(current.fileHash)
            }
            val active = if (work != null && key != null) {
                db.downloads().activeForFile(current.source.name, work, key)
            } else null
            if (active != null) return@withTransaction Enqueued(active.id, false)
            val id = db.downloads().insert(DownloadEntity(
                trackId = current.id, title = current.title, bytesDone = 0, bytesTotal = 0,
                status = DownloadStatus.QUEUED, error = null, source = current.source.name,
                workKey = work, fileKey = key, remoteUrl = current.remoteUrl.takeIf { current.source == TrackSource.YOUTUBE },
            ))
            registerJob(id)
            Enqueued(id, true)
        }
    }

    internal suspend fun libraryTrack(row: com.nanahoshi.audioplayer.data.db.QueuedTrack): TrackEntity? {
        row.trackId?.let { id ->
            db.tracks().getById(id)?.takeIf { it.source == row.source }?.let { return it }
        }
        return when (row.source) {
            TrackSource.BILIBILI -> {
                val bvid = row.bvid ?: row.workno ?: return null
                val cid = row.cid ?: row.fileKey?.toLongOrNull() ?: return null
                db.tracks().findByBvid(bvid, cid)
            }
            TrackSource.YOUTUBE -> (row.youtubeVideoId ?: row.workno)?.let { db.tracks().findYouTube(it) }
            TrackSource.ASMR, TrackSource.DLSITE -> onlineTrack(row.source, row.workno, row.fileKey)
            TrackSource.LOCAL -> null
        }
    }

    private suspend fun onlineTrack(source: TrackSource, workno: String?, key: String?): TrackEntity? {
        val raw = rawFileKey(key) ?: return null
        val numericAsmrId = workno?.toLongOrNull()?.takeIf { source == TrackSource.ASMR }
        val work = if (numericAsmrId == null) workno?.let { library.folderBySource(it) } else null
        val workId = numericAsmrId ?: work?.asmrWorkId ?: return null
        return (db.tracks().findAsmr(workId, scopedFileHash(source, raw))
            ?: db.tracks().findAsmr(workId, raw))?.takeIf { track ->
            track.source == source && (work == null ||
                worknoOf(track)?.let(::canonicalWorkKey) == work.asmrSourceId?.let(::canonicalWorkKey))
        }
    }

    private data class LibraryTarget(val trackId: Long, val folderId: Long? = null)

    private suspend fun libraryTarget(job: DownloadEntity): LibraryTarget? {
        if (job.kind == "DLSITE_ARCHIVE") {
            val folder = job.workKey?.let { library.folderBySource(it) } ?: return null
            if (job.workFolderId != null && job.workFolderId != folder.id) return null
            return LibraryTarget(0L, folder.id)
        }
        val source = TrackSource.entries.firstOrNull { it.name == job.source }
        val bound = job.trackId.takeIf { it > 0 }?.let { db.tracks().getById(it) }
        val track = when {
            bound != null -> bound
            source == TrackSource.BILIBILI -> {
                val bvid = job.workKey ?: return null
                val cid = job.fileKey?.toLongOrNull() ?: return null
                db.tracks().findByBvid(bvid, cid)
            }
            source == TrackSource.YOUTUBE -> job.workKey?.let { db.tracks().findYouTube(it) }
            source == TrackSource.ASMR || source == TrackSource.DLSITE -> onlineTrack(source, job.workKey, job.fileKey)
            else -> null
        } ?: return null
        if (track.source == TrackSource.LOCAL || (source != null && source != track.source)) return null
        if (source == TrackSource.BILIBILI && (track.bvid != job.workKey || track.cid?.toString() != job.fileKey)) return null
        if (source == TrackSource.YOUTUBE && track.youtubeVideoId != job.workKey) return null
        if (source in setOf(TrackSource.ASMR, TrackSource.DLSITE) && job.workKey != null) {
            val numericAsmrId = job.workKey.toLongOrNull()?.takeIf { source == TrackSource.ASMR }
            val matchesWork = if (numericAsmrId != null) track.asmrWorkId == numericAsmrId
                else worknoOf(track)?.let(::canonicalWorkKey) == canonicalWorkKey(job.workKey)
            if (!matchesWork) return null
        }
        if (source in setOf(TrackSource.ASMR, TrackSource.DLSITE) && job.fileKey != null &&
            rawFileKey(track.fileHash) != rawFileKey(job.fileKey)
        ) return null
        return LibraryTarget(track.id)
    }

    internal suspend fun retryTrack(job: DownloadEntity): TrackEntity? =
        libraryTarget(job.copy(workFolderId = null))?.trackId?.takeIf { it > 0 }?.let { db.tracks().getById(it) }

    private suspend fun finishTrackDownload(job: DownloadEntity, file: File) {
        db.tracks().updateLocalUri(job.trackId, file.absolutePath)
        db.downloads().setLocalPath(job.id, file.absolutePath)
        db.downloads().finish(job.id, file.length(), file.length())
    }

    private suspend fun bindLibraryTarget(job: DownloadEntity, target: LibraryTarget) {
        val track = target.trackId.takeIf { it > 0L }?.let { db.tracks().getById(it) }
        val source = track?.source?.name ?: job.source
        val work = when (track?.source) {
            TrackSource.BILIBILI -> track.bvid
            TrackSource.YOUTUBE -> track.youtubeVideoId
            else -> job.workKey
        }
        val file = when (track?.source) {
            TrackSource.BILIBILI -> track.cid?.toString()
            TrackSource.YOUTUBE -> "audio"
            else -> job.fileKey
        }
        db.downloads().bindLibraryTarget(job.id, target.trackId, target.folderId, source, work, file)
    }

    private suspend fun requireRunningTarget(job: DownloadEntity) {
        if (db.downloads().get(job.id)?.status != DownloadStatus.RUNNING) throw CancellationException()
        if (job.trackId > 0) check(db.tracks().getById(job.trackId) != null) { DOWNLOAD_LIBRARY_REQUIRED }
        check(libraryTarget(job) == LibraryTarget(job.trackId, job.workFolderId)) { DOWNLOAD_LIBRARY_REQUIRED }
    }

    private suspend fun downloadYouTube(job: DownloadEntity) {
        val videoId = job.workKey ?: throw YouTubeException("没有视频编号")
        val destination = youtubeAudioFile(filesDir, videoId)
            ?: throw YouTubeException("视频编号不正确")
        destination.parentFile?.mkdirs()
        // 每次运行独立暂存，旧请求取消后的清理不会碰到新一次重试。
        val partial = File(destination.path + ".${job.id}.part")
        try {
            val cached = readyYouTubeAudio(filesDir, videoId)
            if (cached == null) {
                val runner = currentCoroutineContext()[Job]!!
                youtube.downloadAudio(videoId, partial) { done, total ->
                    scope.launch { if (runner.isActive) db.downloads().activeProgress(job.id, done, total) }
                }
            }
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                db.withTransaction {
                    requireRunningTarget(job)
                    if (cached == null && !partial.renameTo(destination)) {
                        throw YouTubeException("无法保存 YouTube 音轨")
                    }
                    library.completeYouTubeDownload(job.id, videoId, destination.absolutePath)
                }
            }
        } finally {
            partial.delete()
        }
    }

    suspend fun retry(id: Long): Long? = prepare {
        val job = db.downloads().get(id) ?: return@prepare null
        if (job.status != DownloadStatus.FAILED) return@prepare job.id
        if (runners[id] != null) error("下载正在中止，请稍后重试")
        db.withTransaction {
            val candidate = job.copy(workFolderId = null)
            val target = libraryTarget(candidate) ?: error(DOWNLOAD_LIBRARY_REQUIRED)
            val active = if (target.trackId > 0L) db.downloads().activeForTrack(target.trackId)
                else job.workKey?.let { db.downloads().activeArchive(it) }
            if (active != null) return@withTransaction active.id
            bindLibraryTarget(job, target)
            db.downloads().requeue(id)
            registerJob(id)
            id
        }
    }

    /** 删除失败任务并回收它的压缩包暂存；排队、下载中和已完成的任务不允许删。 */
    suspend fun deleteFailed(id: Long) = withContext(Dispatchers.IO) {
        val job = db.downloads().get(id) ?: return@withContext
        if (runners[id] != null || db.downloads().deleteFailed(id) == 0) return@withContext
        if (job.kind == "DLSITE_ARCHIVE") {
            job.workKey?.let { File(filesDir, "dlsite-archives/$it/$id").deleteRecursively() }
        }
    }

    private suspend fun downloadOne(snapshot: DownloadEntity) = supervisorScope {
        val job = db.withTransaction {
            val target = libraryTarget(snapshot)
            if (target == null) {
                db.downloads().stopActive(snapshot.id, DOWNLOAD_LIBRARY_REQUIRED)
                return@withTransaction null
            }
            bindLibraryTarget(snapshot, target)
            if (db.downloads().startQueued(snapshot.id) == 0) return@withTransaction null
            db.downloads().get(snapshot.id)
        } ?: return@supervisorScope
        val runner = async(start = kotlinx.coroutines.CoroutineStart.LAZY) { transfer(job) }
        runners[job.id] = runner
        try {
            if (db.downloads().get(job.id)?.status == DownloadStatus.RUNNING) runner.await() else runner.cancel()
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
        } finally {
            runners.remove(job.id, runner)
        }
    }

    private suspend fun transfer(job: DownloadEntity) {
        var partialFile: File? = null
        try {
            requireRunningTarget(job)
            if (job.kind == "DLSITE_ARCHIVE") return downloadArchive(job)
            if (job.source == TrackSource.YOUTUBE.name) return downloadYouTube(job)
            if (job.source == TrackSource.BILIBILI.name) return downloadBili(job)
            val track = db.tracks().getById(job.trackId) ?: error("音频已经不在库里")
            if (LocalAudio.hasStoredCopy(track.localUri)) {
                db.withTransaction {
                    requireRunningTarget(job)
                    val length = if (localFileReady(track.localUri)) File(track.localUri!!).length() else 0L
                    db.downloads().finish(job.id, length, length)
                }
                return
            }
            val dlsiteTrack = track.source == TrackSource.DLSITE
            var signed = if (dlsiteTrack) signedTrack(track) else null
            var url = signed?.url ?: resolveUrl(track, refresh = false)
            val dest = destination(track)
            dest.parentFile?.mkdirs()
            val partial = File(dest.absolutePath + ".${job.id}.part").also { partialFile = it }
            var refreshed = false
            while (true) {
                if (partial.exists()) partial.delete()
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", if (dlsiteTrack) DlsiteClient.USER_AGENT else AsmrClient.USER_AGENT)
                    .header("Referer", if (dlsiteTrack) DlsiteClient.PLAY_REFERER else AsmrClient.REFERER)
                    .apply {
                        if (dlsiteTrack) {
                            signed?.cookie?.let { header("Cookie", it) }
                        } else {
                            header("Origin", AsmrClient.ORIGIN)
                        }
                    }
                    .build()
                val expired = asmr.httpClient().newCall(request).withResponse { response ->
                    if (response.code == 401) error("登录已失效，请重新登录")
                    if ((response.code == 403 || response.code == 404) && !refreshed) return@withResponse true
                    if (!response.isSuccessful) error("下载失败")
                    val body = response.body ?: error("下载失败")
                    val total = body.contentLength().coerceAtLeast(0)
                    body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            var reported = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                done += read
                                if (done - reported >= 256 * 1024) {
                                    db.downloads().activeProgress(job.id, done, total)
                                    reported = done
                                }
                            }
                            if (done == 0L || (total > 0L && done != total)) error("音频下载不完整，请重试")
                        }
                    }
                    false
                }
                if (!expired) break
                refreshed = true
                if (dlsiteTrack) {
                    signed = signedTrack(track)
                    url = signed.url
                } else {
                    url = resolveUrl(track, refresh = true)
                }
            }
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                db.withTransaction {
                    requireRunningTarget(job)
                    if (!partial.renameTo(dest)) error("无法保存文件")
                    finishTrackDownload(job, dest)
                }
            }
        } catch (cancelled: CancellationException) {
            val queueActive = currentCoroutineContext().isActive
            val stopped = currentCoroutineContext().cancellationCause()
            val message = ((stopped ?: cancelled) as? AudioDownloadStopped)?.message
                ?: if (queueActive) "已中止" else AUDIO_DOWNLOAD_INTERRUPTED
            withContext(NonCancellable) { db.downloads().failRunning(job.id, message) }
            if (!queueActive) throw cancelled
        } catch (error: Exception) {
            val stopped = currentCoroutineContext().cancellationCause()
            val message = when {
                stopped != null -> (stopped as? AudioDownloadStopped)?.message ?: AUDIO_DOWNLOAD_INTERRUPTED
                error.message == DOWNLOAD_LIBRARY_REQUIRED -> DOWNLOAD_LIBRARY_REQUIRED
                error is com.nanahoshi.audioplayer.dlsite.DlsiteArchiveException -> error.message ?: "完整原始包下载失败"
                job.kind == "DLSITE_ARCHIVE" -> "完整原始包处理失败，请重试"
                error is YouTubeException -> error.message ?: "YouTube 下载失败"
                job.source == TrackSource.YOUTUBE.name -> "YouTube 下载失败"
                error is IOException -> "无法下载或保存音频，请重试"
                else -> error.message?.takeUnless { "://" in it } ?: "下载失败"
            }
            withContext(NonCancellable) { db.downloads().failRunning(job.id, message) }
            if (stopped != null) throw stopped
        } finally {
            partialFile?.delete()
        }
    }

    private suspend fun worknoOf(track: TrackEntity): String? {
        var folderId = track.folderId
        while (folderId != null) {
            val folder = db.folders().get(folderId) ?: break
            if (!folder.asmrSourceId.isNullOrBlank()) return folder.asmrSourceId
            folderId = folder.parentId
        }
        return null
    }

    private suspend fun resolveUrl(track: TrackEntity, refresh: Boolean): String {
        val current = track.remoteUrl
        if (!refresh && !current.isNullOrBlank()) return current
        val workId = track.asmrWorkId ?: error("这个文件没有可用的下载地址")
        val fresh = AsmrClient.findStream(asmr.trackTree(workId), rawFileKey(track.fileHash), track.title)
            ?: error("这个文件没有可用的下载地址")
        db.tracks().updateRemoteUrl(track.id, fresh)
        return fresh
    }

    suspend fun savedRemotePath(source: TrackSource, workKey: String, fileKey: String?): String? = withContext(Dispatchers.IO) {
        val key = canonicalWorkKey(workKey)
        if (source == TrackSource.DLSITE && !fileKey.isNullOrBlank()) {
            db.dlsiteOriginals().entryForPlay(key, rawFileKey(fileKey) ?: fileKey)?.localPath
                ?.takeIf { File(it).isFile }?.let { return@withContext it }
        }
        workKeys(workKey).firstNotNullOfOrNull { candidate -> localCopy(source.name, candidate, fileKey) }
    }

    suspend fun downloadedKeys(source: TrackSource, workKey: String): Set<String> = withContext(Dispatchers.IO) {
        val keys = workKeys(workKey)
        buildSet {
            if (source == TrackSource.DLSITE) {
                db.dlsiteOriginals().entries(canonicalWorkKey(workKey)).forEach { entry ->
                    if (File(entry.localPath).isFile) entry.playFileKey?.let { add(it) }
                }
            }
            keys.forEach { key ->
                db.downloads().finishedForWork(source.name, key).forEach { row ->
                    val path = row.localPath
                    val fileKey = rawFileKey(row.fileKey) ?: return@forEach
                    if (!path.isNullOrBlank() && File(path).isFile) add(fileKey)
                }
            }
        }
    }

    private suspend fun signedTrack(track: TrackEntity): com.nanahoshi.audioplayer.dlsite.SignedPlayFile {
        val workno = worknoOf(track) ?: error("这个文件没有可用的下载地址")
        val fileKey = rawFileKey(track.fileHash) ?: error("这个文件没有可用的下载地址")
        return dlsite.signedPlayFile(workno, fileKey)
    }

    private suspend fun localCopy(source: String, workKey: String, fileKey: String?): String? {
        if (fileKey.isNullOrBlank()) return null
        return db.downloads().finishedForWork(source, workKey)
            .firstOrNull { rawFileKey(it.fileKey) == fileKey && !it.localPath.isNullOrBlank() && File(it.localPath).isFile }
            ?.localPath
    }

    private fun canonicalWorkKey(workKey: String): String = try {
        com.nanahoshi.audioplayer.dlsite.DlsiteClient.canonicalWorkno(workKey)
    } catch (_: Exception) {
        workKey
    }

    private fun workKeys(workKey: String): List<String> {
        val canonical = canonicalWorkKey(workKey)
        return listOf(workKey, canonical).distinct()
    }

    private suspend fun destination(track: TrackEntity): File {
        val workDir = File(root, (track.asmrWorkId ?: 0L).toString())
        val relative = relativeDirs(track.folderId)
        val fileName = withExtension(sanitizeEntryName(track.title), track.remoteUrl)
        return if (relative.isEmpty()) File(workDir, fileName) else File(workDir, "$relative/$fileName")
    }

    private suspend fun relativeDirs(folderId: Long?): String {
        if (folderId == null) return ""
        val names = ArrayDeque<String>()
        var current = db.folders().get(folderId)
        while (current != null && current.asmrSourceId == null && current.asmrWorkId != null) {
            names.addFirst(sanitizeEntryName(current.name))
            current = current.parentId?.let { db.folders().get(it) }
        }
        return names.joinToString("/")
    }

    private fun withExtension(name: String, url: String?): String {
        if (fileExtension(name).isNotEmpty()) return name
        val remoteExt = url?.substringBefore('?')?.substringAfterLast('.', "").orEmpty()
        if (remoteExt.isBlank() || remoteExt.length > 5 || remoteExt.contains('/')) return name
        return "$name.$remoteExt"
    }

    private suspend fun downloadBili(job: DownloadEntity) {
        val track = job.trackId.takeIf { it > 0L }?.let { db.tracks().getById(it) }
        val bvid = job.workKey?.trim().orEmpty().ifBlank { track?.bvid?.trim().orEmpty() }
        val cid = job.fileKey?.toLongOrNull() ?: track?.cid ?: error("没有可下载的分集")
        val dest = biliAudioFile(filesDir, bvid, cid) ?: error("没有可下载的分集")
        if (dest.isFile && dest.length() > 0L) {
            library.completeBiliDownload(job.id, bvid, cid, dest.absolutePath)
            return
        }
        dest.parentFile?.mkdirs()
        val partial = File(dest.absolutePath + ".${job.id}.part")
        try {
            var refreshed = false
            while (true) {
                if (partial.exists()) partial.delete()
                val audio = bilibili.resolveAudio(bvid, cid)
                val request = Request.Builder()
                    .url(audio.url.replace("%2C", ",").replace("%2c", ","))
                    .header("Referer", "https://www.bilibili.com/video/$bvid")
                    .header("User-Agent", BilibiliClient.USER_AGENT)
                    .build()
                val expired = biliHttp.newCall(request).withResponse { response ->
                    if ((response.code == 403 || response.code == 401) && !refreshed) return@withResponse true
                    if (!response.isSuccessful) error("下载失败")
                    val body = response.body ?: error("下载失败")
                    val total = body.contentLength().coerceAtLeast(0)
                    body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            var reported = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                done += read
                                if (done - reported >= 256 * 1024) {
                                    db.downloads().activeProgress(job.id, done, total)
                                    reported = done
                                }
                            }
                            if (done <= 0 || (total > 0 && done != total)) error("B 站音轨下载不完整，请重试")
                        }
                    }
                    false
                }
                if (!expired) break
                refreshed = true
            }
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                db.withTransaction {
                    requireRunningTarget(job)
                    if (!partial.renameTo(dest) || dest.length() <= 0L) error("无法保存音轨")
                    library.completeBiliDownload(job.id, bvid, cid, dest.absolutePath)
                }
            }
        } finally { partial.delete() }
    }

    private suspend fun cacheBiliSubtitle(job: DownloadEntity) {
        val bvid = job.workKey
        val cid = job.fileKey?.toLongOrNull()
        if (bvid != null && cid != null && libraryTarget(job) != null &&
            readyBiliAudio(filesDir, bvid, cid) != null && readBiliSubtitle(filesDir, bvid, cid) == null
        ) {
            val result = withTimeoutOrNull(30_000L) { bilibili.subtitleDocument(bvid, cid) }
            if (result is BiliSubtitle.NeedsLogin) notice("B 站字幕需要登录后获取，可在「我的」重新登录")
            db.withTransaction {
                if (db.downloads().get(job.id)?.pendingBiliSubtitle != true) return@withTransaction
                val targetReady = libraryTarget(job) != null && readyBiliAudio(filesDir, bvid, cid) != null
                val cached = readBiliSubtitle(filesDir, bvid, cid) != null
                val written = if (result is BiliSubtitle.Ready && targetReady && !cached) {
                    writeBiliSubtitle(filesDir, bvid, cid, result.document, result.language)
                } else null
                // 写盘失败、需登录、读取失败或超时都保留标记，待下次打开应用接手。
                if (shouldClearPendingBiliSubtitle(result, targetReady, cached, written)) {
                    db.downloads().setPendingBiliSubtitle(job.id, null)
                }
            }
        } else {
            db.downloads().setPendingBiliSubtitle(job.id, null)
        }
    }

    private fun localFileReady(path: String?): Boolean =
        !path.isNullOrBlank() && path.startsWith("/") && File(path).isFile

}

internal fun CoroutineContext.cancellationCause(): CancellationException? = try {
    ensureActive()
    null
} catch (cancelled: CancellationException) {
    cancelled
}

/** 待补 B 站字幕标记的清除决策：确无字幕、目标已不在或成功写盘才清；其余保留待下次接手。 */
internal fun shouldClearPendingBiliSubtitle(
    result: BiliSubtitle?,
    targetReady: Boolean,
    alreadyCached: Boolean,
    writeSucceeded: Boolean?,
): Boolean = when {
    !targetReady || alreadyCached -> true
    result is BiliSubtitle.Missing -> true
    result is BiliSubtitle.Ready -> writeSucceeded == true
    else -> false
}
