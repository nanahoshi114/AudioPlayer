package com.nanahoshi.audioplayer.data

import android.content.Context
import androidx.room.withTransaction
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.asmr.AsmrClient
import com.nanahoshi.audioplayer.bilibili.BilibiliClient
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.data.db.DownloadEntity
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.dlsite.DlsiteClient
import com.nanahoshi.audioplayer.japaneseasmr.JA_VERIFY_PHASE
import com.nanahoshi.audioplayer.japaneseasmr.JA_WORK_DOWNLOAD
import com.nanahoshi.audioplayer.japaneseasmr.JaBuzzVerification
import com.nanahoshi.audioplayer.japaneseasmr.JaPostRef
import com.nanahoshi.audioplayer.japaneseasmr.JapaneseAsmrClient
import com.nanahoshi.audioplayer.japaneseasmr.JaWorkEntry
import com.nanahoshi.audioplayer.japaneseasmr.JaWorkManifest
import com.nanahoshi.audioplayer.japaneseasmr.jaHash
import com.nanahoshi.audioplayer.playback.SubtitleSource
import com.nanahoshi.audioplayer.text.PersistedErrors
import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.UserFacingException
import com.nanahoshi.audioplayer.youtube.YouTubeClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

internal const val DOWNLOAD_LIBRARY_REQUIRED = PersistedErrors.LIBRARY_REQUIRED

private class DownloadPreparation : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    val jobs = ConcurrentHashMap.newKeySet<Long>()

    companion object Key : CoroutineContext.Key<DownloadPreparation>
}

class DownloadCenter internal constructor(
    context: Context,
    internal val db: AppDatabase,
    internal val asmr: AsmrClient,
    internal val dlsite: DlsiteClient,
    internal val bilibili: BilibiliClient,
    internal val youtube: YouTubeClient,
    internal val library: LibraryRepository,
    internal val subtitles: SubtitleSource,
    private val startService: (Context, Long) -> Boolean = AudioDownloadService::start,
    internal val progress: TaskProgressCenter = TaskProgressCenter(db),
    jaWorkHttp: OkHttpClient = JapaneseAsmrClient.newWorkClient(),
    internal val preferences: DownloadPreferences = DownloadPreferences(context),
    private val subtitleBindings: SubtitleBindingStore? = null,
    http: OkHttpClient = HttpRoots.shared,
    internal val notice: (String) -> Unit = {},
) {
    internal val appContext = context.applicationContext
    internal val requests = ForegroundTaskRequests<Unit>({ startService(appContext, it) }) {
        AudioDownloadStopped(AUDIO_DOWNLOAD_START_FAILED)
    }
    internal data class ActiveRun(val attemptId: Long, val job: Deferred<Unit>)
    internal val nextAttemptId = AtomicLong(0L)
    internal val runners = ConcurrentHashMap<Long, ActiveRun>()
    internal val filesDir = context.filesDir
    internal val root = File(filesDir, "asmr")
    internal val dlsiteRoot = File(filesDir, "dlsite")
    internal val jaRoot = File(filesDir, "japaneseasmr")
    internal val jaTransferHttp = library.japaneseAsmr.mediaClient.newBuilder()
        .eventListenerFactory(ProgressNetworkEvents.factory).build()
    internal val jaProbeHttp = library.japaneseAsmr.mediaClient.newBuilder()
        .readTimeout(10, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
    internal val jaWorkClient = com.nanahoshi.audioplayer.japaneseasmr.JaWorkClient(
        http = jaWorkHttp,
        sitePages = library.japaneseAsmr.pageRequests,
        cookieProvider = { url ->
            // 仅复用 Buzzheavier 网页会话；JA 站点的 Cookie 不进入原生请求。
            if (url.host.endsWith("buzzheavier.com", ignoreCase = true)) {
                android.webkit.CookieManager.getInstance().getCookie(url.toString())
            } else null
        },
    )
    internal val jaHls = com.nanahoshi.audioplayer.japaneseasmr.JapaneseAsmrHlsDownload(jaTransferHttp)
    internal val transferHttp = asmr.httpClient().newBuilder().eventListenerFactory(ProgressNetworkEvents.factory).build()
    internal val biliHttp = http.newBuilder()
        .eventListenerFactory(ProgressNetworkEvents.factory)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal suspend fun close() {
        scope.coroutineContext[Job]?.cancelAndJoin()
        progress.close()
    }
    private val workerLock = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    internal val archiveLock = Mutex()
    internal val archiveClient = com.nanahoshi.audioplayer.dlsite.DlsiteArchiveClient({ dlsite.cookieHeader() }, http)
    internal val originals = DlsiteOriginalStore(appContext, db, dlsite, library)

    internal data class AvailableManifest(
        val manifest: JaWorkManifest,
        val rootFolderId: Long,
        val expires: Long,
        val pureMega: Boolean = manifest.pureMega,
    ) {
        fun withoutMegaAddresses(): AvailableManifest =
            if (pureMega) copy(manifest = manifest.forMegaRetention(keepInitialAddress = false)) else this
    }
    internal data class WorkVerification(
        val entry: JaWorkEntry,
        val taskId: Long?,
        val rootId: Long?,
        val buzz: JaBuzzVerification? = null,
        val verifiedBuzzPages: Map<String, com.nanahoshi.audioplayer.japaneseasmr.JaVisibleDownload> = emptyMap(),
        val retryFileLink: String? = null,
        val result: kotlinx.coroutines.CompletableDeferred<WorkVerificationCapture?> = kotlinx.coroutines.CompletableDeferred(),
    ) {
        val nextBuzzPage get() = buzz?.pages?.firstOrNull { it.fileLink == retryFileLink }
            ?: buzz?.pages?.firstOrNull {
                val page = verifiedBuzzPages[it.fileLink]
                page == null || (page.direct == null && page.copyLink == null && page.fileUrl == null)
            }
        val address get() = nextBuzzPage?.address ?: buzz?.pages?.firstOrNull()?.address ?: entry.address
    }

    internal data class WorkVerificationCapture(
        val sitePage: com.nanahoshi.audioplayer.japaneseasmr.JaVisibleDownload? = null,
        val buzzPages: Map<String, com.nanahoshi.audioplayer.japaneseasmr.JaVisibleDownload> = emptyMap(),
    )

    internal data class WaitingWorkVerification(
        val entry: JaWorkEntry,
        val buzz: JaBuzzVerification? = null,
        val retryFileLink: String? = null,
        val verifiedBuzzPages: Map<String, com.nanahoshi.audioplayer.japaneseasmr.JaVisibleDownload> = emptyMap(),
        val rootFolderId: Long? = null,
    )

    internal sealed interface WorkVerificationResult {
        data class OpenEntry(val address: String) : WorkVerificationResult
        data class Finished(val accepted: Boolean) : WorkVerificationResult
    }

    internal val jaWorkManifests = ConcurrentHashMap<Long, AvailableManifest>()
    internal val jaVerifications = ConcurrentHashMap<String, WorkVerification>()
    internal val jaWaitingEntries = ConcurrentHashMap<Long, WaitingWorkVerification>()

    internal val jaRunner = DownloadJaWorkRunner(this)
    internal val archiveRunner = DownloadArchiveRunner(this)
    internal val transferOps = DownloadTransferOps(this)
    internal val targetResolver = DownloadTargetResolver(this)

    internal suspend fun jaWorkEntries(posts: List<JaPostRef>): List<JaWorkEntry> = jaRunner.jaWorkEntries(posts)

    internal suspend fun prepareJaManifest(
        entry: JaWorkEntry,
        rootId: Long,
        onReadVerifiedManifest: () -> Unit,
        onVerify: suspend (token: String, buzzheavier: Boolean) -> Boolean,
    ): JaWorkManifest? = jaRunner.prepareJaManifest(entry, rootId, onReadVerifiedManifest, onVerify)

    internal fun jaVerification(id: String): WorkVerification? = jaRunner.jaVerification(id)

    internal fun cancelJaVerification(id: String) = jaRunner.cancelJaVerification(id)

    internal suspend fun beginJaTaskVerification(id: Long): String =
        jaRunner.beginJaTaskVerification(id)

    internal suspend fun completeJaVerification(token: String, visible: com.nanahoshi.audioplayer.japaneseasmr.JaVisibleDownload): WorkVerificationResult =
        jaRunner.completeJaVerification(token, visible)

    internal suspend fun checkJaWork(manifest: JaWorkManifest, force: Boolean) =
        jaRunner.checkJaWork(manifest, force)

    internal suspend fun enqueueJaWork(
        rootId: Long,
        manifest: JaWorkManifest,
        replace: Boolean,
        force: Boolean,
    ): Enqueued = jaRunner.enqueueJaWork(rootId, manifest, replace, force)

    suspend fun requestWorkno(track: TrackEntity): String? =
        db.dlsiteOriginals().entryForTrack(track.id)?.workno ?: targetResolver.worknoOf(track)

    /** 复用已活跃或已完成的整包时 added=false，但 id 仍携带（听写等待绑定依赖它）。 */
    internal suspend fun enqueueDlsiteArchive(
        workno: String,
        title: String = workno,
        expectedFolderId: Long? = null,
    ): Enqueued = archiveRunner.enqueueDlsiteArchive(workno, title, expectedFolderId)

    fun cancel(id: Long) {
        scope.launch {
            val active = runners[id]
            if (db.downloads().stopActive(id, PersistedErrors.CANCELLED) > 0) {
                jaWorkManifests.computeIfPresent(id) { _, value ->
                    if (value.pureMega) value.withoutMegaAddresses() else null
                }
                progress.finishFromDb(TaskKey(TaskKind.DOWNLOAD, id), ProgressPart.DOWNLOAD, true)
                active?.job?.cancel()
            }
        }
    }

    suspend fun originalPathForTrack(trackId: Long): String? =
        db.dlsiteOriginals().entryForTrack(trackId)?.localPath?.takeIf { File(it).isFile }

    fun observe(): Flow<List<DownloadEntity>> = db.downloads().observe()

    fun observeVisible(): Flow<List<DownloadEntity>> = db.downloads().observeVisible()

    internal fun observeActive(): Flow<List<DownloadEntity>> = db.downloads().observeActive()

    private val startup = scope.async {
        for (directory in listOf(root, dlsiteRoot, File(filesDir, "youtube"), File(filesDir, "bilibili"), jaRoot)) {
            directory.walkTopDown()
                .onEnter { it.parentFile?.parentFile != dlsiteRoot || it.name != "original" }
                .filter { it.isFile && it.name.endsWith(".part") }
                .forEach { it.delete() }
        }
        db.withTransaction {
            for (job in db.downloads().active()) {
                val target = targetResolver.libraryTarget(job)
                val jaWork = job.kind == JA_WORK_DOWNLOAD
                if (jaWork && target != null && job.status == DownloadStatus.QUEUED) {
                    val occupying = job.workKey?.let { db.downloads().reserveWorkTask(it, job.id) }
                    if (occupying == null || occupying.status == DownloadStatus.QUEUED && occupying.id > job.id) continue
                }
                val error = when {
                    target != null -> AUDIO_DOWNLOAD_INTERRUPTED
                    jaWork -> PersistedErrors.JA_POST_REMOVED
                    else -> DOWNLOAD_LIBRARY_REQUIRED
                }
                db.downloads().stopActive(job.id, error)
                progress.interrupted(TaskKey(TaskKind.DOWNLOAD, job.id), ProgressPart.DOWNLOAD)
            }
        }
        archiveRunner.cleanUnpublishedOriginals()
        library.jaWorkFiles.cleanup()
        val liveHls = db.downloads().observe().first().filter {
            it.source == TrackSource.JAPANESE_ASMR.name && it.kind != JA_WORK_DOWNLOAD && it.status != DownloadStatus.DONE
        }
            .mapNotNull { transferOps.hlsStaging(it) }.toSet()
        for (post in jaRoot.listFiles().orEmpty().filter { it.isDirectory }) {
            post.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("hls-") && it !in liveHls }
                .forEach { it.deleteRecursively() }
        }
    }

    init {
        progress.watchDownloads()
        scope.launch {
            var previous = emptySet<Long>()
            library.observeAllFolders().map { folders -> folders.mapTo(mutableSetOf()) { it.id } }
                .distinctUntilChanged().collect { ids ->
                    val removed = previous - ids
                    if (removed.isNotEmpty()) jaRunner.invalidateRootManifests(removed)
                    previous = ids
                }
        }
        scope.launch {
            observe().map { jobs -> jobs.map { Triple(it.id, it.status, it.pendingBiliSubtitle) } }
                .distinctUntilChanged().collect { jobs ->
                    jobs.filter { it.second == DownloadStatus.FAILED }.forEach { (id, _, _) ->
                        val active = runners[id] ?: return@forEach
                        if (db.downloads().get(id)?.status == DownloadStatus.FAILED) {
                            active.job.cancel()
                        }
                    }
                    wake.trySend(Unit)
                }
        }
        scope.launch { preferences.maxConcurrentDownloads.collect { wake.trySend(Unit) } }
        scope.launch {
            observe().map { jobs -> jobs.count { it.kind == JA_WORK_DOWNLOAD && it.phase == JA_VERIFY_PHASE } }
                .distinctUntilChanged().collect { AudioDownloadService.updateJaVerificationNotification(appContext, it) }
        }
    }

    internal fun resumePendingSubtitles() {
        scope.launch {
            try {
                startup.await()
                if (db.downloads().pendingBiliSubtitles().isNotEmpty() ||
                    db.downloads().active().any { it.kind == JA_WORK_DOWNLOAD && it.status == DownloadStatus.QUEUED }
                ) startService(appContext, 0L)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice(appContext.getString(R.string.download_recover_failed))
            }
        }
    }

    internal suspend fun drainQueue(
        triedSubtitles: MutableSet<Long> = ConcurrentHashMap.newKeySet<Long>(),
        onActive: (List<Long>) -> Unit,
    ) = withContext(Dispatchers.IO) {
        startup.await()
        workerLock.withLock {
            try {
                pumpTasks(wake,
                    load = {
                        val active = db.downloads().active()
                        onActive(active.map { it.id })
                        active.filter { it.status == DownloadStatus.QUEUED }.sortedBy { it.id }.map { DownloadWork(it) } +
                            db.downloads().pendingBiliSubtitles().filter { it.id !in triedSubtitles }.map { DownloadWork(it, true) }
                    },
                    key = { it.job.id to it.subtitle },
                    canStart = { work, queued, running ->
                        if (work.subtitle) running.none { it.subtitle }
                        else {
                            val decision = downloadStartDecision(work.slot(), queued.filterNot { it.subtitle }.map { it.slot() },
                                running.filterNot { it.subtitle }.map { it.slot() }, preferences.maxConcurrentDownloads.value)
                            if (decision != DownloadStartDecision.Ready) progress.tracker.queued(TaskKey(TaskKind.DOWNLOAD, work.job.id),
                                ProgressPart.DOWNLOAD, if (decision == DownloadStartDecision.WaitingWork) ProgressPhase.WAITING_WORK else ProgressPhase.WAITING_SLOT)
                            decision == DownloadStartDecision.Ready
                        }
                    },
                    onFailure = { work, _ ->
                        if (!work.subtitle) withContext(NonCancellable) {
                            db.downloads().stopActive(work.job.id, appContext.getString(R.string.download_failed_retry))
                            progress.finishFromDb(TaskKey(TaskKind.DOWNLOAD, work.job.id), ProgressPart.DOWNLOAD, true)
                        }
                    },
                    execute = { work ->
                        if (!work.subtitle) transferOps.downloadOne(work.job)
                        else {
                            triedSubtitles += work.job.id
                            try {
                                transferOps.cacheBiliSubtitle(work.job)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                // 保留待补记录，下次进入应用后再尝试，不影响音轨下载。
                            }
                        }
                    },
                )
            } catch (cancelled: CancellationException) {
                val stopped = (currentCoroutineContext().cancellationCause() ?: cancelled) as? AudioDownloadStopped
                if (stopped != null) {
                    withContext(NonCancellable) {
                        stopJobs(db.downloads().active().map { it.id }, stopped.persistedError)
                    }
                }
                throw cancelled
            }
        }
    }

    private data class DownloadWork(val job: DownloadEntity, val subtitle: Boolean = false) {
        fun slot() = DownloadSlot(job.id,
            job.workKey?.takeIf { job.source == TrackSource.DLSITE.name || job.kind == JA_WORK_DOWNLOAD }
                ?.let(DlsiteClient::canonicalWorkno),
            job.kind == "DLSITE_ARCHIVE", exclusiveWork = job.kind == JA_WORK_DOWNLOAD)
    }

    internal suspend fun failAfterServiceStop(ids: List<Long>, message: String) =
        stopJobs((ids + db.downloads().active().map { it.id }).distinct(), message)

    private suspend fun stopJobs(ids: Collection<Long>, message: String) {
        db.withTransaction { ids.forEach { db.downloads().stopActive(it, message) } }
        ids.forEach { progress.finishFromDb(TaskKey(TaskKind.DOWNLOAD, it), ProgressPart.DOWNLOAD, true) }
    }

    internal suspend fun awaitStartup() = startup.await()

    internal fun <T> submitPreparation(block: suspend () -> T) = requests.submit(Unit) {
        startup.await()
        val preparation = DownloadPreparation()
        try {
            withContext(preparation) { block() }
        } catch (stopped: AudioDownloadStopped) {
            withContext(NonCancellable) {
                stopJobs(preparation.jobs, stopped.persistedError)
            }
            throw stopped
        }
    }

    internal suspend fun registerJob(id: Long) {
        currentCoroutineContext()[DownloadPreparation]?.jobs?.add(id)
    }

    internal suspend fun <T> prepare(block: suspend () -> T): T {
        if (currentCoroutineContext()[DownloadPreparation.Key] != null) return block()
        try {
            return submitPreparation(block).result.await()
        } catch (stopped: AudioDownloadStopped) {
            currentCoroutineContext().ensureActive()
            throw IllegalStateException(stopped.persistedError)
        }
    }

    suspend fun deleteDownloaded(tracks: List<TrackEntity>, deleteSubtitles: Boolean): DeleteResult =
        deleteLocalCopies(db, filesDir, subtitles, tracks, deleteSubtitles)

    internal suspend fun deleteLocalTargets(
        plan: LocalRemovalPlan,
        deleteSubtitles: Boolean,
        bindings: SubtitleBindingStore? = null,
    ): DeleteResult = deleteLocalTargets(db, filesDir, subtitles, bindings ?: subtitleBindings, plan, deleteSubtitles)

    suspend fun enqueue(tracks: List<TrackEntity>): Int = prepare {
        tracks.count { enqueueTrack(it.id).added }
    }

    internal data class Enqueued(val id: Long?, val added: Boolean)

    internal suspend fun enqueueTrack(trackId: Long): Enqueued = prepare {
        val track = db.tracks().getById(trackId) ?: error(DOWNLOAD_LIBRARY_REQUIRED)
        if (track.source == TrackSource.LOCAL || LocalAudio.hasStoredCopy(appContext, track.localUri)) return@prepare Enqueued(null, false)
        if (track.source == TrackSource.DLSITE && track.fileHash.isNullOrBlank()) {
            val workno = requestWorkno(track) ?: error(DOWNLOAD_LIBRARY_REQUIRED)
            val folderId = library.folderBySource(workno)?.id ?: error(DOWNLOAD_LIBRARY_REQUIRED)
            check(db.tracks().getById(track.id) != null) { DOWNLOAD_LIBRARY_REQUIRED }
            return@prepare enqueueDlsiteArchive(workno, expectedFolderId = folderId)
        }
        db.withTransaction {
            val current = db.tracks().getById(trackId) ?: error(DOWNLOAD_LIBRARY_REQUIRED)
            db.downloads().activeForTrack(trackId)?.let { return@withTransaction Enqueued(it.id, false) }
            val ja = if (current.source == TrackSource.JAPANESE_ASMR) {
                db.japaneseAsmr().mediaForTrack(current.id) ?: error(
                    if (db.jaDownloads().entryForTrack(current.id) != null) com.nanahoshi.audioplayer.japaneseasmr.JA_WORK_FILE_MISSING else DOWNLOAD_LIBRARY_REQUIRED,
                )
            } else null
            val work = when (current.source) {
                TrackSource.JAPANESE_ASMR -> ja?.postId
                TrackSource.BILIBILI -> checkNotNull(current.bvid)
                TrackSource.YOUTUBE -> checkNotNull(current.youtubeVideoId)
                else -> targetResolver.worknoOf(current)
            }
            val key = when (current.source) {
                TrackSource.JAPANESE_ASMR -> ja?.mediaKey
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
                kind = "JAPANESE_ASMR_HLS".takeIf { ja?.kind == com.nanahoshi.audioplayer.japaneseasmr.JaMediaKind.HLS.name },
            ))
            registerJob(id)
            Enqueued(id, true)
        }
    }

    internal suspend fun libraryTrack(row: com.nanahoshi.audioplayer.data.db.QueuedTrack): TrackEntity? =
        targetResolver.libraryTrack(row)

    internal suspend fun japaneseRoot(row: com.nanahoshi.audioplayer.data.db.QueuedTrack): Long? =
        targetResolver.japaneseRoot(row)

    internal suspend fun prepareJapaneseTrack(row: com.nanahoshi.audioplayer.data.db.QueuedTrack): TrackEntity? =
        targetResolver.prepareJapaneseTrack(row)

    suspend fun retry(id: Long): Long? = retry(id, null)

    internal suspend fun retry(id: Long, manifest: JaWorkManifest?, verification: Boolean = false): Long? = prepare {
        if (runners[id] != null) throw UserFacingException(TextRef.Res(R.string.download_stopping_retry))
        db.withTransaction {
            val job = db.downloads().get(id) ?: return@withTransaction null
            if (verification && (job.status != DownloadStatus.FAILED || job.phase != JA_VERIFY_PHASE)) return@withTransaction null
            if (job.status != DownloadStatus.FAILED) return@withTransaction job.id
            val candidate = job.copy(workFolderId = null)
            val target = targetResolver.libraryTarget(candidate) ?: error(
                if (job.kind == JA_WORK_DOWNLOAD) PersistedErrors.JA_POST_REMOVED else DOWNLOAD_LIBRARY_REQUIRED,
            )
            if (job.phase == JA_VERIFY_PHASE && !verification) error(PersistedErrors.JA_VERIFY)
            val active = if (job.kind == JA_WORK_DOWNLOAD) {
                job.workKey?.let { db.downloads().reserveWorkTask(it, id) }
            } else if (target.trackId > 0L) db.downloads().activeForTrack(target.trackId)
                else job.workKey?.let { db.downloads().activeArchive(it) }
            if (active != null) return@withTransaction active.id
            if (manifest != null) {
                if (manifest.entry.post.postId != job.fileKey || manifest.entry.post.rj != job.workKey ||
                    job.jaEntryKey?.let { it != jaHash(manifest.entry.address) } == true
                ) return@withTransaction null
                jaRunner.rememberManifest(id, requireNotNull(target.folderId), manifest)
            } else {
                jaWorkManifests.computeIfPresent(id) { _, value -> value.withoutMegaAddresses() }
            }
            jaWaitingEntries.remove(id)
            targetResolver.bindLibraryTarget(job, target)
            progress.reset(TaskKey(TaskKind.DOWNLOAD, id))
            db.downloads().requeue(id)
            registerJob(id)
            id
        }
    }

    suspend fun deleteTerminal(id: Long) {
        val dao = db.downloads()
        when (dao.get(id)?.status) {
            DownloadStatus.FAILED -> deleteFailed(id)
            DownloadStatus.DONE -> dao.dismissDone(id, System.currentTimeMillis())
            else -> Unit
        }
    }

    suspend fun clearCompleted() {
        db.downloads().dismissAllDone(System.currentTimeMillis())
    }

    /** 删除失败任务并回收它的压缩包暂存；排队、下载中和已完成的任务不允许删。 */
    suspend fun deleteFailed(id: Long) = withContext(Dispatchers.IO) {
        val job = db.downloads().get(id) ?: return@withContext
        if (runners[id] != null) return@withContext
        val deleted = db.withTransaction {
            if (db.downloads().deleteFailed(id) == 0) return@withTransaction false
            if (job.source == TrackSource.JAPANESE_ASMR.name && job.kind != JA_WORK_DOWNLOAD &&
                job.workKey != null && job.fileKey != null &&
                db.downloads().latestForSourceFile(TrackSource.JAPANESE_ASMR.name, job.workKey, job.fileKey)
                    ?.takeIf { it.status != DownloadStatus.DONE } == null
            ) transferOps.hlsStaging(job)?.deleteRecursively()
            true
        }
        if (!deleted) return@withContext
        if (job.kind == JA_WORK_DOWNLOAD) {
            jaWorkManifests.remove(id)
            jaWaitingEntries.remove(id)
            jaVerifications.entries.removeIf { it.value.taskId == id }
            job.workKey?.let { library.jaWorkFiles.staging(it, id).deleteRecursively() }
        }
        if (job.kind == "DLSITE_ARCHIVE") {
            job.workKey?.let { File(filesDir, "dlsite-archives/$it/$id").deleteRecursively() }
        }
    }

    suspend fun savedRemotePath(source: TrackSource, workKey: String, fileKey: String?): String? =
        targetResolver.savedRemotePath(source, workKey, fileKey)

    suspend fun downloadedKeys(source: TrackSource, workKey: String): Set<String> =
        targetResolver.downloadedKeys(source, workKey)

    internal suspend fun retryTrack(job: DownloadEntity): TrackEntity? = targetResolver.retryTrack(job)
}

internal fun CoroutineContext.cancellationCause(): CancellationException? = try {
    ensureActive()
    null
} catch (cancelled: CancellationException) {
    cancelled
}

/** 待补 B 站字幕标记的清除决策：确无字幕、目标已不在或成功写盘才清；其余保留待下次接手。 */
internal fun shouldClearPendingBiliSubtitle(
    result: com.nanahoshi.audioplayer.bilibili.BiliSubtitle?,
    targetReady: Boolean,
    alreadyCached: Boolean,
    writeSucceeded: Boolean?,
): Boolean = when {
    !targetReady || alreadyCached -> true
    result is com.nanahoshi.audioplayer.bilibili.BiliSubtitle.Missing -> true
    result is com.nanahoshi.audioplayer.bilibili.BiliSubtitle.Ready -> writeSucceeded == true
    else -> false
}