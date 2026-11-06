package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.youtube.YouTubeClient
import com.nanahoshi.audioplayer.youtube.YouTubeException
import com.nanahoshi.audioplayer.youtube.YouTubeVideo
import com.nanahoshi.audioplayer.youtube.readyYouTubeAudio
import com.nanahoshi.audioplayer.youtube.youtubeAudioFile

import androidx.room.withTransaction
import android.content.Context
import android.util.Log
import com.nanahoshi.audioplayer.asmr.AsmrClient
import com.nanahoshi.audioplayer.bilibili.BiliPart
import com.nanahoshi.audioplayer.bilibili.BiliSubtitle
import com.nanahoshi.audioplayer.bilibili.BilibiliClient
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.dlsite.DlsiteClient
import com.nanahoshi.audioplayer.dlsite.RemotePlayable
import com.nanahoshi.audioplayer.data.db.DownloadEntity
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.TrackEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
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
import java.util.concurrent.TimeUnit

private const val TAG = "DownloadCenter"

data class DeleteResult(val deleted: Int, val unlinked: Int) {
    val total: Int get() = deleted + unlinked
}

class DownloadCenter(
    context: Context,
    private val db: AppDatabase,
    private val asmr: AsmrClient,
    private val dlsite: DlsiteClient,
    private val bilibili: BilibiliClient,
    private val youtube: YouTubeClient,
    private val library: LibraryRepository,
    private val notice: (String) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val youtubeLock = Mutex()
    private val youtubeRunners = java.util.concurrent.ConcurrentHashMap<Long, kotlinx.coroutines.Deferred<Unit>>()
    private val filesDir = context.filesDir
    private val biliDownloads = BiliDownloads(filesDir, db, library)
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
    private val kick = Channel<Unit>(Channel.CONFLATED)
    private val subtitleLock = Mutex()

    private val archiveLock = Mutex()
    private val archiveRunners = java.util.concurrent.ConcurrentHashMap<Long, kotlinx.coroutines.Job>()
    private val archiveClient = com.nanahoshi.audioplayer.dlsite.DlsiteArchiveClient { dlsite.cookieHeader() }
    private val originals = DlsiteOriginalStore(appContext, db, dlsite, library)

    suspend fun requestWorkno(track: TrackEntity): String? =
        db.dlsiteOriginals().entryForTrack(track.id)?.workno ?: worknoOf(track)

    suspend fun enqueueDlsiteArchive(workno: String, title: String = workno): Long = withContext(Dispatchers.IO) {
        archiveLock.withLock {
            val key = DlsiteClient.canonicalWorkno(workno)
            db.downloads().activeArchive(key)?.let { return@withLock it.id }
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
                    reportSkipped(originals.importDirectory(key, File(saved.directory), saved.generation))
                }
                db.downloads().latestArchive(key)?.takeIf { it.status == DownloadStatus.DONE }?.let { return@withLock it.id }
            }
            val id = db.downloads().insert(DownloadEntity(
                trackId = 0, title = "$title · 原始压缩包", bytesDone = 0, bytesTotal = 0,
                status = DownloadStatus.QUEUED, error = null, source = TrackSource.DLSITE.name,
                workKey = key, kind = "DLSITE_ARCHIVE",
            ))
            kick.trySend(Unit)
            id
        }
    }

    fun cancel(id: Long) {
        scope.launch {
            if (db.downloads().cancelArchive(id) > 0) archiveRunners[id]?.cancel()
            else cancelYouTube(id)
        }
    }

    suspend fun originalPathForTrack(trackId: Long): String? =
        db.dlsiteOriginals().entryForTrack(trackId)?.localPath?.takeIf { File(it).isFile }

    private suspend fun downloadArchive(job: DownloadEntity) = kotlinx.coroutines.coroutineScope {
        val workno = job.workKey ?: error("没有作品编号")
        val self = currentCoroutineContext()[kotlinx.coroutines.Job]!!
        archiveRunners[job.id] = self
        val generation = java.util.UUID.randomUUID().toString()
        val directory = File(dlsiteRoot, "$workno/original/$generation")
        val staging = File(filesDir, "dlsite-archives/$workno/${job.id}")
        var published = false
        val oldCopies = db.tracks().onlineWithLocal().filter { track ->
            track.source == TrackSource.DLSITE && requestWorkno(track)?.let { DlsiteClient.canonicalWorkno(it) } == workno
        }.mapNotNull { track -> track.localUri?.takeIf { path -> path.startsWith("/") }?.let { track.id to it } }
        val oldDirectory = db.dlsiteOriginals().work(workno)?.directory
        try {
            if (db.downloads().get(job.id)?.status != DownloadStatus.RUNNING) throw CancellationException()
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
            val imported = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                val result = originals.importDirectory(workno, directory, generation,
                    beforeCommit = {
                        self.ensureActive()
                        if (db.downloads().get(job.id)?.status != DownloadStatus.RUNNING) throw CancellationException()
                    }, onPublish = {
                        db.downloads().setLocalPath(job.id, directory.absolutePath)
                        val bytes = packages.sumOf { it.length() }
                        db.downloads().finish(job.id, bytes, bytes)
                    })
                published = true
                result
            }
            reportSkipped(imported)
            // 清单发布后不再响应中止；完整包只在发布成功后回收。
            staging.deleteRecursively()
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                retireOriginalCopies(oldCopies, oldDirectory)
            }
        } catch (error: com.nanahoshi.audioplayer.dlsite.DlsiteArchiveIntegrityException) {
            // 已确认损坏的包无法通过再次解压修复；仅清理这个任务的暂存。
            staging.deleteRecursively()
            throw error
        } finally {
            archiveRunners.remove(job.id, self)
            if (!published) directory.deleteRecursively()
        }
    }

    private fun reportSkipped(result: DlsiteOriginalImportResult) {
        if (result.skipped.isEmpty()) return
        notice("已发布原始文件；${result.skipped.size} 个文件已关联其他曲目，保持不变")
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

    init {
        scope.launch {
            File(filesDir, "youtube").walkTopDown().filter { it.isFile && it.name.endsWith(".part") }.forEach { it.delete() }
            db.downloads().failActive("下载中断，请重试")
            cleanUnpublishedOriginals()
            runCatching { library.biliLibrary.publish() }
            for (ignored in kick) {
                while (true) {
                    val job = db.downloads().nextQueued() ?: break
                    downloadOne(job)
                }
            }
        }
    }

    suspend fun enqueueRemote(item: RemotePlayable, workKey: String): Boolean = withContext(Dispatchers.IO) {
        if (item.source == TrackSource.YOUTUBE) {
            val videoId = item.workno ?: return@withContext false
            return@withContext enqueueYouTube(listOf(YouTubeVideo(
                videoId, item.title, item.artist.orEmpty(), item.coverUri ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg", item.durationMs,
            ))) > 0
        }
        if (item.remoteUrl.isBlank()) return@withContext false
        val key = item.fileKey?.let(::rawFileKey)
        val storedKey = canonicalWorkKey(workKey)
        if (savedRemotePath(item.source, storedKey, key) != null) return@withContext false
        // 去重查询与插入必须同一事务，否则并发调用会创建两个任务写同一个 .part。
        val inserted = db.withTransaction {
            if (!key.isNullOrBlank() && db.downloads().activeForFile(item.source.name, storedKey, key) != null) {
                return@withTransaction false
            }
            if (db.downloads().activeForUrl(item.remoteUrl) != null) return@withTransaction false
            db.downloads().insert(
                DownloadEntity(
                    trackId = 0,
                    title = item.title,
                    bytesDone = 0,
                    bytesTotal = 0,
                    status = DownloadStatus.QUEUED,
                    error = null,
                    remoteUrl = item.remoteUrl,
                    referer = item.referer,
                    source = item.source.name,
                    workKey = storedKey,
                    fileKey = key ?: item.fileKey,
                ),
            )
            true
        }
        if (!inserted) return@withContext false
        kick.trySend(Unit)
        true
    }

    /** 删 App 下载的文件；识别得到的曲目（本地文件是用户自己的）只取消识别，不动磁盘上的文件。 */
    suspend fun deleteDownloaded(tracks: List<TrackEntity>, deleteSubtitles: Boolean): DeleteResult = withContext(Dispatchers.IO) {
        var deleted = 0
        var unlinked = 0
        val subtitles = com.nanahoshi.audioplayer.playback.SubtitleSource(appContext, db, bilibili, asmr)
        for (track in tracks) {
            if (track.source != TrackSource.ASMR && track.source != TrackSource.DLSITE && track.source != TrackSource.BILIBILI && track.source != TrackSource.YOUTUBE) {
                continue
            }
            val path = track.localUri
            if (localFileReady(path)) {
                val file = File(path!!)
                if (file.exists() && !file.delete()) continue
                // 选择保留字幕时不动听写记录和字幕文件，重新下载同一音频会自动匹配。
                if (deleteSubtitles) subtitles.discardStored(track.id, path, track.remoteUrl)
                db.downloads().deleteFinishedForPath(path)
                if (track.source == TrackSource.BILIBILI) {
                    deleteBiliSubtitle(filesDir, track.bvid, track.cid)
                }
                db.tracks().clearLocalUri(track.id)
                db.downloads().deleteFinishedForTrack(track.id)
                deleted += 1
            } else if (track.source != TrackSource.BILIBILI && isRecognizedLocal(path)) {
                db.tracks().clearLocalUri(track.id)
                db.downloads().deleteFinishedForTrack(track.id)
                unlinked += 1
            }
        }
        DeleteResult(deleted, unlinked)
    }

    private fun isRecognizedLocal(path: String?): Boolean =
        path != null && (path.startsWith("content:", ignoreCase = true) || path.startsWith("file:", ignoreCase = true))

    suspend fun enqueue(tracks: List<TrackEntity>): Int = withContext(Dispatchers.IO) {
        var added = 0
        for (track in tracks) {
            if (track.source == TrackSource.DLSITE && track.fileHash.isNullOrBlank() && !LocalAudio.hasStoredCopy(track.localUri)) {
                val workno = requestWorkno(track) ?: continue
                enqueueDlsiteArchive(workno)
                added += 1
                continue
            }
            if (track.source == TrackSource.YOUTUBE) {
                val id = track.youtubeVideoId ?: continue
                added += enqueueYouTube(listOf(YouTubeVideo(
                    id, track.title, track.artist.orEmpty(), track.coverUri ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg", track.durationMs,
                )))
                continue
            }
            if (track.source == TrackSource.BILIBILI) {
                val bvid = track.bvid ?: continue
                val cid = track.cid ?: continue
                added += biliDownloads.enqueue(listOf(BiliPart(bvid, cid, track.page ?: 1, track.title,
                    track.artist.orEmpty(), track.durationMs, track.coverUri.orEmpty())))
                continue
            }
            if (track.source != TrackSource.ASMR && track.source != TrackSource.DLSITE) continue
            if (LocalAudio.hasStoredCopy(track.localUri)) continue
            if (db.downloads().activeForTrack(track.id) != null) continue
            db.downloads().insert(
                DownloadEntity(
                    trackId = track.id,
                    title = track.title,
                    bytesDone = 0,
                    bytesTotal = 0,
                    status = DownloadStatus.QUEUED,
                    error = null,
                ),
            )
            added += 1
        }
        if (added > 0) kick.trySend(Unit)
        added
    }

    suspend fun enqueueBili(parts: List<BiliPart>, autoImport: Boolean = false): Int = withContext(Dispatchers.IO) {
        val added = biliDownloads.enqueue(parts, autoImport)
        if (added > 0) kick.trySend(Unit)
        added
    }

    suspend fun enqueueBiliImports(requests: List<com.nanahoshi.audioplayer.bilibili.BiliImportRequest>): Int = withContext(Dispatchers.IO) {
        var added = 0
        try {
            for (request in requests) {
                db.withTransaction {
                    val batch = library.biliLibrary.beginDownload(request)
                    added += biliDownloads.enqueue(request.parts, batchId = batch)
                    library.biliLibrary.publish(request.video.bvid)
                }
            }
            added
        } finally { kick.trySend(Unit) }
    }

    suspend fun enqueueYouTube(videos: List<YouTubeVideo>): Int = withContext(Dispatchers.IO) {
        youtubeLock.withLock {
            var count = 0
            for (video in videos) {
                val file = readyYouTubeAudio(filesDir, video.id)
                if (file != null) { library.saveYouTube(video, file.absolutePath); continue }
                if (youtubeAudioFile(filesDir, video.id) == null) continue
                if (db.downloads().activeForFile(TrackSource.YOUTUBE.name, video.id, "audio") != null) continue
                val track = db.tracks().findYouTube(video.id)
                db.downloads().insert(DownloadEntity(
                    trackId = track?.id ?: 0, title = video.title, bytesDone = 0, bytesTotal = 0,
                    status = DownloadStatus.QUEUED, error = null, remoteUrl = video.watchUrl,
                    source = TrackSource.YOUTUBE.name, workKey = video.id, fileKey = "audio",
                ))
                count++
            }
            if (count > 0) kick.trySend(Unit)
            count
        }
    }

    fun cancelYouTube(id: Long) {
        scope.launch {
            if (db.downloads().cancelYouTube(id) > 0) youtubeRunners[id]?.cancel()
        }
    }

    private suspend fun downloadYouTube(job: DownloadEntity) {
        val videoId = job.workKey ?: throw YouTubeException("没有视频编号")
        val destination = youtubeAudioFile(filesDir, videoId)
            ?: throw YouTubeException("视频编号不正确")
        destination.parentFile?.mkdirs()
        // 每次运行独立暂存，旧请求取消后的清理不会碰到新一次重试。
        val partial = File(destination.path + ".${java.util.UUID.randomUUID()}.part")
        var renamed = false
        try {
            val cached = readyYouTubeAudio(filesDir, videoId)
            if (cached != null) {
                val old = db.tracks().findYouTube(videoId)
                library.saveYouTube(YouTubeVideo(videoId, old?.title ?: job.title,
                    old?.artist.orEmpty(), old?.coverUri ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg", old?.durationMs ?: 0), cached.path, job.id)
                return
            }
            val runner = currentCoroutineContext()[kotlinx.coroutines.Job]!!
            val audio = youtube.downloadAudio(videoId, partial) { done, total ->
                scope.launch { if (runner.isActive) db.downloads().activeProgress(job.id, done, total) }
            }
            currentCoroutineContext().ensureActive()
            if (!partial.renameTo(destination)) throw YouTubeException("无法保存 YouTube 音轨")
            renamed = true
            library.saveYouTube(audio.video, destination.absolutePath, job.id)
        } finally {
            partial.delete()
            if (renamed) withContext(kotlinx.coroutines.NonCancellable) {
                if (db.downloads().get(job.id)?.status != DownloadStatus.DONE && db.tracks().findYouTube(videoId)?.localUri != destination.absolutePath) destination.delete()
            }
        }
    }

    fun retry(id: Long) {
        scope.launch {
            if (youtubeRunners[id]?.isActive == true || archiveRunners[id]?.isActive == true) return@launch
            db.downloads().requeue(id)
            kick.trySend(Unit)
        }
    }

    /** 删除失败任务并回收它的压缩包暂存；排队、下载中和已完成的任务不允许删。 */
    suspend fun deleteFailed(id: Long) = withContext(Dispatchers.IO) {
        val job = db.downloads().get(id) ?: return@withContext
        if (db.downloads().deleteFailed(id) == 0) return@withContext
        if (job.kind == "DLSITE_ARCHIVE") {
            job.workKey?.let { File(filesDir, "dlsite-archives/$it/$id").deleteRecursively() }
        }
    }

    private suspend fun downloadOne(job: DownloadEntity) {
        if (db.downloads().startQueued(job.id) == 0) return
        try {
            if (job.kind == "DLSITE_ARCHIVE") {
                kotlinx.coroutines.supervisorScope {
                    val runner = async { downloadArchive(job) }
                    runner.await()
                }
                return
            }
            if (job.source == TrackSource.YOUTUBE.name) {
                kotlinx.coroutines.coroutineScope {
                    val runner = async(start = kotlinx.coroutines.CoroutineStart.LAZY) { downloadYouTube(job) }
                    youtubeRunners[job.id] = runner
                    try {
                        if (db.downloads().get(job.id)?.status == DownloadStatus.RUNNING) runner.await() else runner.cancel()
                    } finally { youtubeRunners.remove(job.id, runner) }
                }
                return
            }
            if (job.source == TrackSource.BILIBILI.name) {
                downloadBili(job)
                return
            }
            if (!job.remoteUrl.isNullOrBlank() && job.trackId == 0L) {
                downloadRemote(job)
                return
            }
            val track = db.tracks().getById(job.trackId) ?: error("音频已经不在库里")
            if (LocalAudio.hasStoredCopy(track.localUri)) {
                val length = if (localFileReady(track.localUri)) File(track.localUri!!).length() else 0L
                db.downloads().finish(job.id, length, length)
                return
            }
            var url = resolveUrl(track, refresh = false)
            var playCookie: String? = null
            val dest = destination(track)
            dest.parentFile?.mkdirs()
            val partial = File(dest.absolutePath + ".part")
            var refreshed = false
            val dlsiteTrack = track.source == TrackSource.DLSITE
            if (dlsiteTrack) {
                val signed = signedTrack(track)
                url = signed.url
                playCookie = signed.cookie
            }
            while (true) {
                if (partial.exists()) partial.delete()
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", if (dlsiteTrack) DlsiteClient.USER_AGENT else AsmrClient.USER_AGENT)
                    .header("Referer", if (dlsiteTrack) DlsiteClient.PLAY_REFERER else AsmrClient.REFERER)
                    .apply {
                        if (dlsiteTrack) {
                            playCookie?.let { header("Cookie", it) }
                        } else {
                            header("Origin", AsmrClient.ORIGIN)
                        }
                    }
                    .build()
                val expired = asmr.httpClient().newCall(request).execute().use { response ->
                    if (response.code == 401) error("登录已失效，请重新登录")
                    if ((response.code == 403 || response.code == 404) && !refreshed) return@use true
                    if (!response.isSuccessful) error("下载失败")
                    val body = response.body ?: error("下载失败")
                    val total = body.contentLength().coerceAtLeast(0)
                    body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            var reported = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                done += read
                                if (done - reported >= 256 * 1024) {
                                    db.downloads().progress(job.id, done, total)
                                    reported = done
                                }
                            }
                        }
                    }
                    false
                }
                if (!expired) break
                refreshed = true
                if (dlsiteTrack) {
                    val signed = signedTrack(track)
                    url = signed.url
                    playCookie = signed.cookie
                } else {
                    url = resolveUrl(track, refresh = true)
                }
            }
            if (dest.exists()) dest.delete()
            if (!partial.renameTo(dest)) error("无法保存文件")
            db.tracks().updateLocalUri(track.id, dest.absolutePath)
            val size = dest.length()
            db.downloads().finish(job.id, size, size)
        } catch (error: Exception) {
            if (job.kind == "DLSITE_ARCHIVE" && db.downloads().get(job.id)?.status == DownloadStatus.DONE) return
            val message = if (job.kind == "DLSITE_ARCHIVE") {
                if (error is CancellationException) "已中止"
                else if (error is com.nanahoshi.audioplayer.dlsite.DlsiteArchiveException) error.message ?: "原始压缩包下载失败"
                else "原始压缩包处理失败，请重试"
            } else if (job.source == TrackSource.YOUTUBE.name) {
                if (error is CancellationException) "已中止" else if (error is YouTubeException) error.message ?: "YouTube 下载失败" else "YouTube 下载失败"
            } else error.message ?: "下载失败"
            if (job.kind == "DLSITE_ARCHIVE") db.downloads().failRunning(job.id, message)
            else db.downloads().fail(job.id, message)
        }
    }

    private suspend fun downloadRemote(job: DownloadEntity) {
        val workKey = job.workKey?.ifBlank { null } ?: "work"
        if (job.source == TrackSource.DLSITE.name && !job.fileKey.isNullOrBlank()) {
            savedRemotePath(TrackSource.DLSITE, workKey, job.fileKey)?.let { path ->
                db.downloads().setLocalPath(job.id, path)
                val size = File(path).length()
                db.downloads().finish(job.id, size, size)
                return
            }
        }
        val rootDir = if (job.source == TrackSource.ASMR.name) root else dlsiteRoot
        val dest = remoteDestination(job, rootDir, workKey)
        dest.parentFile?.mkdirs()
        val partial = File(dest.absolutePath + ".part")
        var url = job.remoteUrl ?: error("没有可下载的地址")
        var refreshed = false
        val referer = job.referer?.ifBlank { null } ?: DlsiteClient.PLAY_REFERER
        val authed = job.source == TrackSource.DLSITE.name && !job.fileKey.isNullOrBlank()
        var playCookie = if (authed) dlsite.playCookie() else null
        if (authed) {
            val signed = dlsite.signedPlayFileBlocking(workKey, job.fileKey.orEmpty())
            url = signed.url
            playCookie = signed.cookie
        }
        while (true) {
            if (partial.exists()) partial.delete()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", if (authed) DlsiteClient.USER_AGENT else AsmrClient.USER_AGENT)
                .header("Referer", referer)
                .apply {
                    if (authed) playCookie?.let { header("Cookie", it) }
                    if (job.source == TrackSource.ASMR.name) header("Origin", AsmrClient.ORIGIN)
                }
                .build()
            val expired = asmr.httpClient().newCall(request).execute().use { response ->
                if (response.code == 401) error("登录已失效，请重新登录")
                if ((response.code == 403 || response.code == 404) && !refreshed && authed) return@use true
                if (!response.isSuccessful) error("下载失败")
                val body = response.body ?: error("下载失败")
                val total = body.contentLength().coerceAtLeast(0)
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        var reported = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            if (done - reported >= 256 * 1024) {
                                db.downloads().progress(job.id, done, total)
                                reported = done
                            }
                        }
                    }
                }
                false
            }
            if (!expired) break
            refreshed = true
            val workno = job.workKey ?: error("没有可下载的地址")
            val fileKey = job.fileKey ?: error("没有可下载的地址")
            val signed = dlsite.signedPlayFileBlocking(workno, fileKey)
            url = signed.url
            playCookie = signed.cookie
        }
        if (dest.exists()) dest.delete()
        if (!partial.renameTo(dest)) error("无法保存文件")
        db.downloads().setLocalPath(job.id, dest.absolutePath)
        val size = dest.length()
        db.downloads().finish(job.id, size, size)
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

    private fun fileName(job: DownloadEntity): String {
        val fromKey = job.fileKey?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        val base = sanitizeEntryName(job.title)
        return when {
            fileExtension(base).isNotEmpty() -> base
            !fromKey.isNullOrBlank() && fileExtension(fromKey).isNotEmpty() -> {
                val ext = fileExtension(fromKey)
                if (base.endsWith(".$ext")) base else "$base.$ext"
            }
            else -> base
        }
    }

    private suspend fun resolveUrl(track: TrackEntity, refresh: Boolean): String {
        val current = track.remoteUrl
        if (!refresh && !current.isNullOrBlank()) return current
        if (track.source == TrackSource.DLSITE) {
            val signed = signedTrack(track)
            db.tracks().updateRemoteUrl(track.id, signed.url)
            return signed.url
        }
        val workId = track.asmrWorkId ?: error("没有可下载的地址")
        val fresh = AsmrClient.findStream(asmr.trackTree(workId), rawFileKey(track.fileHash), track.title)
            ?: error("没有可下载的地址")
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
        val workno = worknoOf(track) ?: error("没有可下载的地址")
        val fileKey = rawFileKey(track.fileHash) ?: error("没有可下载的地址")
        return dlsite.signedPlayFileBlocking(workno, fileKey)
    }

    /** 同一 fileKey 始终落同一路径；与别的 fileKey 撞名时才分到短哈希子目录，避免互相覆盖。 */
    private suspend fun remoteDestination(job: DownloadEntity, rootDir: File, workKey: String): File {
        val name = fileName(job)
        val key = rawFileKey(job.fileKey)
        val source = job.source.orEmpty()
        val candidates = workKeys(workKey)
        if (!key.isNullOrBlank()) {
            val existing = candidates.firstNotNullOfOrNull { candidate ->
                db.downloads().locatedForWork(source, candidate)
                    .firstOrNull { rawFileKey(it.fileKey) == key && !it.localPath.isNullOrBlank() }
            }
            existing?.localPath?.let { return File(it) }
        }
        val flat = File(rootDir, "$workKey/$name")
        val flatPath = runCatching { flat.canonicalPath }.getOrDefault(flat.absolutePath)
        val occupied = flat.exists() || candidates.any { candidate ->
            db.downloads().locatedForWork(source, candidate).any { row ->
                val path = row.localPath ?: return@any false
                val canonical = runCatching { File(path).canonicalPath }.getOrDefault(path)
                canonical == flatPath && rawFileKey(row.fileKey) != key
            }
        }
        return File(rootDir, "$workKey/${remoteRelativePath(name, key, occupied)}")
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
        val cid = job.fileKey?.toLongOrNull() ?: track?.cid ?: error("没有可缓存的分集")
        val dest = biliAudioFile(filesDir, bvid, cid) ?: error("没有可缓存的分集")
        if (dest.isFile && dest.length() > 0L) {
            library.completeBiliDownload(job.id, bvid, cid, dest.absolutePath)
            cacheBiliSubtitleLater(bvid, cid)
            return
        }
        dest.parentFile?.mkdirs()
        val partial = File(dest.absolutePath + ".part")
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
                val expired = biliHttp.newCall(request).execute().use { response ->
                    if ((response.code == 403 || response.code == 401) && !refreshed) return@use true
                    if (!response.isSuccessful) error("缓存失败")
                    val body = response.body ?: error("缓存失败")
                    val total = body.contentLength().coerceAtLeast(0)
                    body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            var reported = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                done += read
                                if (done - reported >= 256 * 1024) {
                                    db.downloads().progress(job.id, done, total)
                                    reported = done
                                }
                            }
                            if (done <= 0 || (total > 0 && done != total)) error("B站音轨下载不完整，请重试")
                        }
                    }
                    false
                }
                if (!expired) break
                refreshed = true
            }
            if (dest.exists()) dest.delete()
            if (!partial.renameTo(dest) || dest.length() <= 0L) error("无法保存音轨")
            library.completeBiliDownload(job.id, bvid, cid, dest.absolutePath)
            cacheBiliSubtitleLater(bvid, cid)
        } finally { partial.delete() }
    }

    /** 音轨已经缓存好，再慢慢取视频自带字幕；取不到不影响音轨缓存。 */
    private fun cacheBiliSubtitleLater(bvid: String, cid: Long) {
        if (bvid.isBlank() || cid <= 0L) return
        if (readBiliSubtitle(filesDir, bvid, cid) != null) return
        scope.launch {
            subtitleLock.withLock {
                if (readBiliSubtitle(filesDir, bvid, cid) != null) return@withLock
                // 音轨被删掉后就不用再补字幕了。
                if (readyBiliAudio(filesDir, bvid, cid) == null) return@withLock
                try {
                    withTimeoutOrNull(30_000L) {
                        when (val result = bilibili.subtitleDocument(bvid, cid)) {
                            is BiliSubtitle.Ready -> {
                                if (readyBiliAudio(filesDir, bvid, cid) == null) return@withTimeoutOrNull
                                val saved = writeBiliSubtitle(filesDir, bvid, cid, result.document, result.language)
                                Log.d(TAG, "bili subtitle cached bvid=$bvid cid=$cid saved=$saved")
                            }
                            else -> Log.d(
                                TAG,
                                "bili subtitle skipped bvid=$bvid cid=$cid result=${result::class.simpleName}",
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "bili subtitle failed: ${error.javaClass.simpleName}")
                }
            }
        }
    }

    private fun localFileReady(path: String?): Boolean =
        !path.isNullOrBlank() && path.startsWith("/") && File(path).isFile
}
