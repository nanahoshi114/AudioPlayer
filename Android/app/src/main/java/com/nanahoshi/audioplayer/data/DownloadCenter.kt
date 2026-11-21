package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.text.resolveUserError
import com.nanahoshi.audioplayer.text.resolveNoUrl
import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.dlsite.DlsiteArchiveFailureKind
import com.nanahoshi.audioplayer.dlsite.DlsiteArchiveException
import com.nanahoshi.audioplayer.japaneseasmr.JA_WORK_DOWNLOAD
import com.nanahoshi.audioplayer.japaneseasmr.JA_VERIFY_PHASE
import com.nanahoshi.audioplayer.japaneseasmr.JA_WORK_FILE_MISSING
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.text.PersistedErrors
import com.nanahoshi.audioplayer.text.resolve
import com.nanahoshi.audioplayer.text.userTextOr
import com.nanahoshi.audioplayer.japaneseasmr.JaBuzzVerification
import com.nanahoshi.audioplayer.japaneseasmr.JaDownloadVerification
import com.nanahoshi.audioplayer.japaneseasmr.JaFailure
import com.nanahoshi.audioplayer.japaneseasmr.JaPostRef
import com.nanahoshi.audioplayer.japaneseasmr.JaVisibleDownload
import com.nanahoshi.audioplayer.japaneseasmr.JaTransferVerification
import com.nanahoshi.audioplayer.japaneseasmr.JaWorkAddressExpired
import com.nanahoshi.audioplayer.japaneseasmr.JaWorkClient
import com.nanahoshi.audioplayer.japaneseasmr.JaWorkEntry
import com.nanahoshi.audioplayer.japaneseasmr.JaWorkException
import com.nanahoshi.audioplayer.japaneseasmr.JaWorkManifest
import com.nanahoshi.audioplayer.japaneseasmr.isBuzzDownloadEndpoint
import com.nanahoshi.audioplayer.japaneseasmr.isBuzzHost
import com.nanahoshi.audioplayer.japaneseasmr.jaDownloadEntry
import com.nanahoshi.audioplayer.japaneseasmr.JaMediaKind
import com.nanahoshi.audioplayer.japaneseasmr.JapaneseAsmrClient
import com.nanahoshi.audioplayer.japaneseasmr.JapaneseAsmrClient.Companion.isChallenge
import com.nanahoshi.audioplayer.japaneseasmr.JapaneseAsmrException
import com.nanahoshi.audioplayer.japaneseasmr.JapaneseAsmrHlsDownload
import com.nanahoshi.audioplayer.japaneseasmr.jaHash
import com.nanahoshi.audioplayer.japaneseasmr.resolveStoredJaMatches
import com.nanahoshi.audioplayer.japaneseasmr.resolveQueuedJaMatch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.channels.Channel
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

private const val JA_HLS = "JAPANESE_ASMR_HLS"

internal const val DOWNLOAD_LIBRARY_REQUIRED = PersistedErrors.LIBRARY_REQUIRED

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
    internal val progress: TaskProgressCenter = TaskProgressCenter(db),
    jaWorkHttp: OkHttpClient = JapaneseAsmrClient.newWorkClient(),
    internal val preferences: DownloadPreferences = DownloadPreferences(context),
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
    private val jaRoot = File(filesDir, "japaneseasmr")
    private val jaTransferHttp = library.japaneseAsmr.mediaClient.newBuilder()
        .eventListenerFactory(ProgressNetworkEvents.factory).build()
    private val jaProbeHttp = library.japaneseAsmr.mediaClient.newBuilder()
        .readTimeout(10, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
    private val jaWorkClient = JaWorkClient(
        http = jaWorkHttp,
        sitePages = library.japaneseAsmr.pageRequests,
        cookieProvider = { url ->
            // 仅复用 Buzzheavier 网页会话；JA 站点的 Cookie 不进入原生请求。
            if (url.host.endsWith("buzzheavier.com", ignoreCase = true)) {
                android.webkit.CookieManager.getInstance().getCookie(url.toString())
            } else null
        },
    )
    private val jaHls = JapaneseAsmrHlsDownload(jaTransferHttp)
    private val transferHttp = asmr.httpClient().newBuilder().eventListenerFactory(ProgressNetworkEvents.factory).build()
    private val biliHttp = OkHttpClient.Builder()
        .eventListenerFactory(ProgressNetworkEvents.factory)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal suspend fun close() {
        scope.coroutineContext[Job]?.cancelAndJoin()
        progress.close()
    }
    private val workerLock = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val archiveLock = Mutex()
    private val archiveClient = com.nanahoshi.audioplayer.dlsite.DlsiteArchiveClient { dlsite.cookieHeader() }
    private val originals = DlsiteOriginalStore(appContext, db, dlsite, library)


    private data class AvailableManifest(val manifest: JaWorkManifest, val expires: Long)
    internal data class WorkVerification(
        val entry: JaWorkEntry,
        val taskId: Long?,
        val rootId: Long?,
        val buzz: JaBuzzVerification? = null,
        val verifiedBuzzPages: Map<String, JaVisibleDownload> = emptyMap(),
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
        val sitePage: JaVisibleDownload? = null,
        val buzzPages: Map<String, JaVisibleDownload> = emptyMap(),
    )

    private data class WaitingWorkVerification(
        val entry: JaWorkEntry,
        val buzz: JaBuzzVerification? = null,
        val retryFileLink: String? = null,
        val verifiedBuzzPages: Map<String, JaVisibleDownload> = emptyMap(),
    )

    internal sealed interface WorkVerificationResult {
        data class OpenEntry(val address: String) : WorkVerificationResult
        data class Finished(val accepted: Boolean) : WorkVerificationResult
    }

    private val jaWorkManifests = ConcurrentHashMap<Long, AvailableManifest>()
    private val jaVerifications = ConcurrentHashMap<String, WorkVerification>()
    private val jaWaitingEntries = ConcurrentHashMap<Long, WaitingWorkVerification>()

    private fun clockMs() = System.nanoTime() / 1_000_000

    internal suspend fun jaWorkEntries(posts: List<JaPostRef>): List<JaWorkEntry> = withContext(Dispatchers.IO) {
        posts.flatMap { post ->
            val article = try {
                library.japaneseAsmr.article(post.postId, post.rj)
            } catch (failure: JapaneseAsmrException) {
                if (failure.failure !in setOf(JaFailure.VERIFY, JaFailure.LEGACY)) throw failure
                null
            }
            jaDownloadEntry(post, article)
        }.distinctBy { it.address }
    }

    internal suspend fun prepareJaManifest(
        entry: JaWorkEntry,
        rootId: Long,
        onReadVerifiedManifest: () -> Unit,
        onVerify: suspend (token: String, buzzheavier: Boolean) -> Boolean,
    ): JaWorkManifest? {
        suspend fun read(sitePage: JaVisibleDownload?) = withContext(Dispatchers.IO) {
            jaWorkClient.manifest(entry, sitePage, onVerifyBuzz = { buzz ->
                withContext(Dispatchers.Main.immediate) {
                    val verified = awaitWorkVerification(
                        WorkVerification(entry, null, rootId, buzz, retryFileLink = buzz.fileLink), onVerify,
                    ) ?: throw CancellationException()
                    onReadVerifiedManifest()
                    verified.buzzPages
                }
            })
        }
        return try {
            read(null)
        } catch (verify: JaDownloadVerification) {
            val pending = WorkVerification(entry.copy(address = verify.entry), null, rootId)
            val captured = awaitWorkVerification(pending, onVerify)?.sitePage ?: return null
            if (!jaArticleInRoot(entry.post, rootId)) return null
            onReadVerifiedManifest()
            read(captured)
        }
    }

    private suspend fun awaitWorkVerification(
        pending: WorkVerification,
        onVerify: suspend (token: String, buzzheavier: Boolean) -> Boolean,
    ): WorkVerificationCapture? {
        val token = java.util.UUID.randomUUID().toString()
        jaVerifications[token] = pending
        try {
            return if (onVerify(token, pending.buzz != null)) pending.result.await() else null
        } finally {
            jaVerifications.remove(token)
        }
    }

    internal fun jaVerification(id: String): WorkVerification? = jaVerifications[id]

    internal fun cancelJaVerification(id: String) {
        jaVerifications.remove(id)?.result?.complete(null)
    }

    internal suspend fun beginJaTaskVerification(id: Long): String = withContext(Dispatchers.IO) {
        val job = db.downloads().get(id)?.takeIf { it.kind == JA_WORK_DOWNLOAD &&
            it.status == DownloadStatus.FAILED && it.phase == JA_VERIFY_PHASE } ?: error(appContext.getString(R.string.download_task_ended))
        check(libraryTarget(job.copy(workFolderId = null)) != null) { PersistedErrors.JA_POST_REMOVED }
        val pending = jaWaitingEntries[id] ?: WaitingWorkVerification(try {
            entryForJob(job)
        } catch (verify: JaDownloadVerification) {
            JaWorkEntry(jaPostForJob(job), verify.entry)
        })
        check(db.downloads().get(id)?.phase == JA_VERIFY_PHASE) { appContext.getString(R.string.download_task_ended) }
        val token = java.util.UUID.randomUUID().toString()
        jaVerifications.entries.removeIf { it.value.taskId == id }
        jaVerifications[token] = WorkVerification(
            pending.entry, id, job.workFolderId, pending.buzz,
            verifiedBuzzPages = pending.verifiedBuzzPages,
            retryFileLink = pending.retryFileLink ?: pending.buzz?.fileLink,
        )
        token
    }

    internal suspend fun completeJaVerification(token: String, visible: JaVisibleDownload): WorkVerificationResult {
        val ended = WorkVerificationResult.Finished(false)
        val request = jaVerifications[token] ?: return ended
        val id = request.taskId
        val job = if (id == null) {
            if (request.rootId == null || !jaArticleInRoot(request.entry.post, request.rootId)) return ended
            null
        } else {
            val waiting = db.downloads().get(id)?.takeIf { it.phase == JA_VERIFY_PHASE && it.status == DownloadStatus.FAILED }
                ?: return ended
            if (libraryTarget(waiting.copy(workFolderId = null)) == null) return ended
            waiting
        }

        val buzz = request.buzz
        val confirmed = if (buzz == null) request.verifiedBuzzPages else {
            val page = request.nextBuzzPage ?: return ended
            if (!page.accepts(visible.url)) throw JaWorkException(appContext.getString(R.string.download_verify_current_host))
            val transfer = visible.fileUrl ?: visible.direct ?: visible.copyLink
            if (transfer == null || !isBuzzHost(transfer) ||
                (visible.fileUrl == null && isBuzzDownloadEndpoint(transfer) &&
                    transfer.toHttpUrlOrNull()?.queryParameter("t") == null)
            ) throw JaWorkException(appContext.getString(R.string.download_click_download_file))
            val pages = request.verifiedBuzzPages.toMutableMap().apply {
                put(page.fileLink, visible)
                putIfAbsent(visible.url, visible)
            }
            val updated = request.copy(verifiedBuzzPages = pages, retryFileLink = null)
            val next = updated.nextBuzzPage
            if (next != null) {
                return if (jaVerifications.replace(token, request, updated)) WorkVerificationResult.OpenEntry(next.address) else ended
            }
            pages
        }

        if (job == null) {
            val captured = WorkVerificationCapture(visible.takeIf { buzz == null }, confirmed)
            return WorkVerificationResult.Finished(request.result.complete(captured))
        }
        try {
            val articlePage = if (buzz == null) com.nanahoshi.audioplayer.japaneseasmr.jaPostId(request.entry.address) else null
            if (articlePage == request.entry.post.postId) {
                val article = library.japaneseAsmr.rememberVerifiedArticle(visible.url, visible.html,
                    request.entry.post.rj, requireMedia = false)
                if (article.post.postId != articlePage) return ended
                val selected = chooseEntry(job, jaDownloadEntry(request.entry.post, article))
                return if (jaVerifications.replace(token, request, request.copy(entry = selected))) {
                    WorkVerificationResult.OpenEntry(selected.address)
                } else ended
            }
            val manifest = withContext(Dispatchers.IO) {
                jaWorkClient.manifest(request.entry, if (buzz == null) visible else buzz.sourcePage, confirmed)
            }
            if (!jaVerifications.remove(token, request)) return ended
            return WorkVerificationResult.Finished(retry(job.id, manifest, verification = true) == job.id)
        } catch (verify: JaBuzzVerification) {
            val updated = request.copy(
                buzz = verify, verifiedBuzzPages = confirmed, retryFileLink = verify.fileLink,
            )
            return if (jaVerifications.replace(token, request, updated)) WorkVerificationResult.OpenEntry(updated.address) else ended
        } catch (_: JaDownloadVerification) {
            throw JaWorkException(appContext.getString(R.string.download_verify_first))
        }
    }

    private suspend fun jaArticleInRoot(post: JaPostRef, rootId: Long): Boolean =
        library.folderBySource(post.rj)?.id == rootId && db.japaneseAsmr().post(post.postId)?.rootFolderId == rootId

    internal suspend fun checkJaWork(manifest: JaWorkManifest, force: Boolean) = withContext(Dispatchers.IO) {
        library.jaWorkFiles.check(manifest, jaHash(manifest.entry.address), force)
    }

    internal suspend fun enqueueJaWork(
        rootId: Long,
        manifest: JaWorkManifest,
        replace: Boolean,
        force: Boolean,
    ): Enqueued = prepare {
        val post = manifest.entry.post
        val key = jaHash(manifest.entry.address)
        db.withTransaction {
            val job = DownloadEntity(trackId = 0, title = "${post.rj} · ${appContext.getString(R.string.download_volume_work)}", bytesDone = 0,
                bytesTotal = manifest.bytes ?: 0, status = DownloadStatus.QUEUED, error = null,
                source = TrackSource.JAPANESE_ASMR.name, kind = JA_WORK_DOWNLOAD, workKey = post.rj,
                workFolderId = rootId, fileKey = post.postId, jaEntryKey = key, jaReplace = replace, jaForce = force)
            check(libraryTarget(job) != null) { PersistedErrors.JA_POST_REMOVED }
            db.downloads().reserveWorkTask(post.rj, 0)?.let { return@withTransaction Enqueued(it.id, false) }
            val id = db.downloads().insert(job)
            jaWorkManifests[id] = AvailableManifest(manifest, manifest.resolvedAtMs + 120_000L)
            registerJob(id)
            Enqueued(id, true)
        }
    }

    private suspend fun jaPostForJob(job: DownloadEntity): JaPostRef =
        job.fileKey?.let { db.japaneseAsmr().post(it)?.ref() } ?: error(PersistedErrors.JA_POST_REMOVED)

    private fun chooseEntry(job: DownloadEntity, entries: List<JaWorkEntry>): JaWorkEntry =
        if (job.jaEntryKey == null) entries.singleOrNull() ?: error(appContext.getString(R.string.download_choose_entry))
        else entries.firstOrNull { jaHash(it.address) == job.jaEntryKey }
            ?: throw JaWorkException(TextRef.Res(R.string.ja_error_entry_changed), PersistedErrors.JA_ENTRY_CHANGED)

    private suspend fun entryForJob(job: DownloadEntity): JaWorkEntry {
        val post = jaPostForJob(job)
        val article = try {
            library.japaneseAsmr.article(post.postId, post.rj)
        } catch (failure: JapaneseAsmrException) {
            if (failure.failure !in setOf(JaFailure.VERIFY, JaFailure.LEGACY)) throw failure
            val fallback = jaDownloadEntry(post, null)
            if (failure.failure == JaFailure.VERIFY && job.jaEntryKey != null &&
                fallback.none { jaHash(it.address) == job.jaEntryKey }
            ) throw JaDownloadVerification(post.url)
            return chooseEntry(job, fallback)
        }
        return chooseEntry(job, jaDownloadEntry(post, article))
    }

    private suspend fun downloadJaWork(job: DownloadEntity, report: TaskReporter) {
        val cached = jaWorkManifests.remove(job.id)?.takeIf { clockMs() < it.expires }?.manifest
            ?.takeIf { it.entry.post.postId == job.fileKey && it.entry.post.rj == job.workKey }
        suspend fun phase(phase: ProgressPhase) {
            report.phase(phase)
            db.downloads().setPhase(job.id, phase.name)
        }
        phase(ProgressPhase.RESOLVING)
        try {
            val entry = cached?.entry ?: entryForJob(job)
            suspend fun run(manifest: JaWorkManifest): TextRef? = try {
                library.jaWorkFiles.execute(job, manifest, jaWorkClient, report, ::phase) { requireRunningTarget(job) }
            } catch (verify: JaTransferVerification) {
                requireRunningTarget(job)
                val buzz = JaBuzzVerification(entry, manifest.sourcePage, listOf(verify.page), verify.page.fileLink)
                waitForJaWorkVerification(job, WaitingWorkVerification(entry, buzz, verify.page.fileLink, manifest.verifiedBuzzPages))
                null
            }
            val message = try {
                run(cached ?: jaWorkClient.manifest(entry))
            } catch (expired: JaWorkAddressExpired) {
                if (expired.sessionRequired && cached?.fromVerifiedPage == true) throw expired
                phase(ProgressPhase.RESOLVING)
                requireRunningTarget(job)
                run(jaWorkClient.manifest(entry))
            }
            if (message != null) notice(appContext.resolve(message))
        } catch (verify: JaDownloadVerification) {
            waitForJaWorkVerification(job, WaitingWorkVerification(JaWorkEntry(jaPostForJob(job), verify.entry)))
        } catch (verify: JaBuzzVerification) {
            waitForJaWorkVerification(job, WaitingWorkVerification(verify.entry, verify, verify.fileLink))
        }
    }

    private suspend fun waitForJaWorkVerification(job: DownloadEntity, pending: WaitingWorkVerification) {
        jaWaitingEntries[job.id] = pending
        db.downloads().waitForJaVerification(job.id)
        notice(appContext.getString(R.string.download_ja_verify_notice))
    }

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
                check(library.folderBySource(key)?.id == folder.id) { DOWNLOAD_LIBRARY_REQUIRED }
                if (!needsRelink) db.downloads().latestArchive(key)?.takeIf { it.status == DownloadStatus.DONE }
                    ?.let { return@withLock Enqueued(it.id, false) }
            }
            db.withTransaction {
                check(library.folderBySource(key)?.id == folder.id) { DOWNLOAD_LIBRARY_REQUIRED }
                val id = db.downloads().insert(DownloadEntity(
                    trackId = 0, title = "$title · ${appContext.getString(R.string.download_volume_archive)}", bytesDone = 0, bytesTotal = 0,
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
            jaWorkManifests.remove(id)
            if (db.downloads().stopActive(id, PersistedErrors.CANCELLED) > 0) {
                progress.finishFromDb(TaskKey(TaskKind.DOWNLOAD, id), ProgressPart.DOWNLOAD, true)
                runners[id]?.cancel()
            }
        }
    }

    suspend fun originalPathForTrack(trackId: Long): String? =
        db.dlsiteOriginals().entryForTrack(trackId)?.localPath?.takeIf { File(it).isFile }

    private suspend fun downloadArchive(job: DownloadEntity, report: TaskReporter) = kotlinx.coroutines.coroutineScope {
        val workno = job.workKey ?: error(appContext.getString(R.string.download_missing_workno))
        val self = currentCoroutineContext()[Job]!!
        val saved = db.dlsiteOriginals().work(workno)
        val entries = db.dlsiteOriginals().entries(workno)
        if (saved != null && entries.any { it.kind == "AUDIO" } && entries.all {
                if (it.kind == "FOLDER") File(it.localPath).isDirectory else File(it.localPath).isFile
            }) {
            report.phase(ProgressPhase.IMPORTING)
            db.downloads().setPhase(job.id, "IMPORTING")
            val imported = originals.importDirectory(workno, File(saved.directory), saved.generation,
                beforeCommit = { self.ensureActive(); requireRunningTarget(job) },
                onPublish = {
                    db.downloads().setLocalPath(job.id, saved.directory)
                    db.downloads().finish(job.id, 0, 0)
                },
                onProgress = { done, total -> report.units(done.toLong(), total.toLong()) },
            )
            report.skipped(imported.skipped.size)
            reportSkipped(imported)
            File(filesDir, "dlsite-archives/$workno/${job.id}").deleteRecursively()
            report.reused()
            return@coroutineScope
        }
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
            report.phase(ProgressPhase.RESOLVING)
            db.downloads().setPhase(job.id, "RESOLVING")
            db.downloads().setPhase(job.id, "DOWNLOADING")
            var lastProgress = 0L
            val packages = archiveClient.download(workno, staging, taskProgress = report, onProgress = { done, total ->
                if (done - lastProgress >= 256 * 1024 || total == done) {
                    lastProgress = done
                    scope.launch {
                        if (self.isActive) db.downloads().activeProgress(job.id, done, total ?: 0L)
                    }
                }
            }, checkCancelled = { self.ensureActive() })
            self.ensureActive()
            report.cumulative(0)
            report.phase(ProgressPhase.EXTRACTING)
            db.downloads().setPhase(job.id, "EXTRACTING")
            com.nanahoshi.audioplayer.dlsite.DlsiteArchiveExtractor.extract(packages, directory, onProgress = { files, bytes -> report.units(files.toLong(), null); report.cumulative(bytes) }) { self.ensureActive() }
            self.ensureActive()
            report.phase(ProgressPhase.IMPORTING)
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
                onProgress = { done, total -> report.units(done.toLong(), total.toLong()) },
            )
            report.skipped(imported.skipped.size)
            reportSkipped(imported)
            report.phase(ProgressPhase.CLEANING)
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
        notice(appContext.getString(R.string.download_archive_published_notice, result.skipped.size))
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
        for (directory in listOf(root, dlsiteRoot, File(filesDir, "youtube"), File(filesDir, "bilibili"), jaRoot)) {
            directory.walkTopDown()
                .onEnter { it.parentFile?.parentFile != dlsiteRoot || it.name != "original" }
                .filter { it.isFile && it.name.endsWith(".part") }
                .forEach { it.delete() }
        }
        db.withTransaction {
            for (job in db.downloads().active()) {
                val target = libraryTarget(job)
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
        cleanUnpublishedOriginals()
        library.jaWorkFiles.cleanup()
        val liveHls = db.downloads().observe().first().filter {
            it.source == TrackSource.JAPANESE_ASMR.name && it.kind != JA_WORK_DOWNLOAD && it.status != DownloadStatus.DONE
        }
            .mapNotNull { hlsStaging(it) }.toSet()
        for (post in jaRoot.listFiles().orEmpty().filter { it.isDirectory }) {
            post.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("hls-") && it !in liveHls }
                .forEach { it.deleteRecursively() }
        }
    }

    init {
        progress.watchDownloads()
        scope.launch {
            observe().map { jobs -> jobs.map { Triple(it.id, it.status, it.pendingBiliSubtitle) } }
                .distinctUntilChanged().collect { jobs ->
                    jobs.filter { it.second == DownloadStatus.FAILED }.forEach { runners[it.first]?.cancel() }
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
                        if (!work.subtitle) downloadOne(work.job)
                        else {
                            triedSubtitles += work.job.id
                            try {
                                cacheBiliSubtitle(work.job)
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

    private suspend fun registerJob(id: Long) {
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
            val ja = if (current.source == TrackSource.JAPANESE_ASMR) {
                db.japaneseAsmr().mediaForTrack(current.id) ?: error(
                    if (db.jaDownloads().entryForTrack(current.id) != null) JA_WORK_FILE_MISSING else DOWNLOAD_LIBRARY_REQUIRED,
                )
            } else null
            val work = when (current.source) {
                TrackSource.JAPANESE_ASMR -> ja?.postId
                TrackSource.BILIBILI -> checkNotNull(current.bvid)
                TrackSource.YOUTUBE -> checkNotNull(current.youtubeVideoId)
                else -> worknoOf(current)
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
                kind = JA_HLS.takeIf { ja?.kind == JaMediaKind.HLS.name },
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
            TrackSource.JAPANESE_ASMR -> jaTrack(row.workno, row.fileKey)
            TrackSource.LOCAL -> null
        }
    }

    internal suspend fun japaneseRoot(row: com.nanahoshi.audioplayer.data.db.QueuedTrack): Long? {
        if (row.source != TrackSource.JAPANESE_ASMR) return null
        val postId = row.workno ?: return null
        val post = db.japaneseAsmr().post(postId)
        val rj = post?.rj ?: library.japaneseAsmr.cachedArticle(postId)?.post?.rj ?: return null
        return library.folderBySource(rj)?.id
    }

    internal suspend fun prepareJapaneseTrack(row: com.nanahoshi.audioplayer.data.db.QueuedTrack): TrackEntity? {
        val root = japaneseRoot(row) ?: return null
        val postId = row.workno ?: return null
        val expected = db.japaneseAsmr().post(postId)?.rj
            ?: library.japaneseAsmr.cachedArticle(postId)?.post?.rj ?: return null
        val article = library.japaneseAsmr.article(postId, expected)
        val key = row.fileKey ?: return null
        val stored = resolveStoredJaMatches(article, postId, db.japaneseAsmr())
        val match = resolveQueuedJaMatch(article, key, row.mediaPathHash, stored, db.queue().snapshot())
        val media = match.media ?: return null
        library.importJapaneseAsmrPost(article, root)
        val resolved = resolveStoredJaMatches(article, postId, db.japaneseAsmr())
            .entries.firstOrNull { it.value.media?.key == media.key }?.key ?: return null
        return jaTrack(postId, resolved)
    }

    private suspend fun jaTrack(postId: String?, key: String?): TrackEntity? =
        if (postId == null || key == null) null else db.japaneseAsmr().track(postId, key)

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
        if (job.kind == JA_WORK_DOWNLOAD) {
            val rj = job.workKey ?: return null
            val post = job.fileKey?.let { db.japaneseAsmr().post(it) } ?: return null
            val root = library.folderBySource(rj) ?: return null
            if (post.rootFolderId != root.id || !post.rj.equals(rj, ignoreCase = true) ||
                job.workFolderId?.let { it != root.id } == true) return null
            return LibraryTarget(0L, root.id)
        }
        if (job.kind == "DLSITE_ARCHIVE") {
            val folder = job.workKey?.let { library.folderBySource(it) } ?: return null
            if (job.workFolderId != null && job.workFolderId != folder.id) return null
            return LibraryTarget(0L, folder.id)
        }
        val source = TrackSource.entries.firstOrNull { it.name == job.source }
        if (source == TrackSource.JAPANESE_ASMR) {
            val track = jaTrack(job.workKey, job.fileKey) ?: return null
            val post = db.japaneseAsmr().post(job.workKey ?: return null) ?: return null
            val root = post.rootFolderId?.let { db.folders().get(it) } ?: return null
            if (track.folderId != (post.articleFolderId ?: root.id) ||
                root.asmrSourceId?.let(::canonicalWorkKey) != canonicalWorkKey(post.rj)
            ) return null
            return LibraryTarget(track.id, root.id)
        }
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
        check(libraryTarget(job) == LibraryTarget(job.trackId, job.workFolderId)) {
            if (job.kind == JA_WORK_DOWNLOAD) PersistedErrors.JA_POST_REMOVED else DOWNLOAD_LIBRARY_REQUIRED
        }
    }

    private suspend fun downloadYouTube(job: DownloadEntity, report: TaskReporter) {
        val videoId = job.workKey ?: throw YouTubeException(appContext.getString(R.string.download_no_video_id))
        val destination = youtubeAudioFile(filesDir, videoId)
            ?: throw YouTubeException(appContext.getString(R.string.download_invalid_video_id))
        destination.parentFile?.mkdirs()
        // 每次运行独立暂存，旧请求取消后的清理不会碰到新一次重试。
        val partial = File(destination.path + ".${job.id}.part")
        try {
            val cached = readyYouTubeAudio(filesDir, videoId)
            if (cached == null) {
                report.phase(ProgressPhase.RESOLVING)
                val runner = currentCoroutineContext()[Job]!!
                youtube.downloadAudio(videoId, partial, taskProgress = report) { done, total ->
                    scope.launch { if (runner.isActive) db.downloads().activeProgress(job.id, done, total) }
                }
            }
            currentCoroutineContext().ensureActive()
            if (cached != null) report.reused() else report.phase(ProgressPhase.SAVING)
            withContext(NonCancellable) {
                db.withTransaction {
                    requireRunningTarget(job)
                    if (cached == null && !partial.renameTo(destination)) {
                        throw YouTubeException(appContext.getString(R.string.download_save_youtube_failed))
                    }
                    library.completeYouTubeDownload(job.id, videoId, destination.absolutePath)
                }
            }
        } finally {
            partial.delete()
        }
    }

    suspend fun retry(id: Long): Long? = retry(id, null)

    private suspend fun retry(id: Long, manifest: JaWorkManifest?, verification: Boolean = false): Long? = prepare {
        if (runners[id] != null) error(appContext.getString(R.string.download_stopping_retry))
        db.withTransaction {
            val job = db.downloads().get(id) ?: return@withTransaction null
            if (verification && (job.status != DownloadStatus.FAILED || job.phase != JA_VERIFY_PHASE)) return@withTransaction null
            if (job.status != DownloadStatus.FAILED) return@withTransaction job.id
            val candidate = job.copy(workFolderId = null)
            val target = libraryTarget(candidate) ?: error(
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
                jaWorkManifests[id] = AvailableManifest(manifest, manifest.resolvedAtMs + 120_000L)
            }
            jaWaitingEntries.remove(id)
            bindLibraryTarget(job, target)
            progress.reset(TaskKey(TaskKind.DOWNLOAD, id))
            db.downloads().requeue(id)
            registerJob(id)
            id
        }
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
            ) hlsStaging(job)?.deleteRecursively()
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

    private suspend fun downloadOne(snapshot: DownloadEntity) = supervisorScope {
        val job = db.withTransaction {
            val target = libraryTarget(snapshot)
            if (snapshot.kind == JA_WORK_DOWNLOAD && snapshot.workKey != null &&
                db.downloads().reserveWorkTask(snapshot.workKey, snapshot.id)?.let {
                    it.id < snapshot.id || it.status != DownloadStatus.QUEUED
                } == true
            ) {
                db.downloads().stopActive(snapshot.id, appContext.getString(R.string.download_already_has_task))
                return@withTransaction null
            }
            if (target == null) {
                db.downloads().stopActive(snapshot.id,
                    if (snapshot.kind == JA_WORK_DOWNLOAD) PersistedErrors.JA_POST_REMOVED else DOWNLOAD_LIBRARY_REQUIRED)
                return@withTransaction null
            }
            bindLibraryTarget(snapshot, target)
            if (db.downloads().startQueued(snapshot.id) == 0) return@withTransaction null
            db.downloads().get(snapshot.id)
        } ?: return@supervisorScope
        val report = progress.begin(TaskKey(TaskKind.DOWNLOAD, job.id), ProgressPart.DOWNLOAD)
        val runner = async(start = kotlinx.coroutines.CoroutineStart.LAZY) { transfer(job, report) }
        runners[job.id] = runner
        try {
            if (db.downloads().get(job.id)?.status == DownloadStatus.RUNNING) runner.await() else runner.cancel()
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
        } finally {
            runners.remove(job.id, runner)
            withContext(NonCancellable) { progress.end(report) }
        }
    }

    private suspend fun transfer(job: DownloadEntity, report: TaskReporter) {
        var partialFile: File? = null
        try {
            requireRunningTarget(job)
            if (job.kind == "DLSITE_ARCHIVE") return downloadArchive(job, report)
            if (job.kind == JA_WORK_DOWNLOAD) return downloadJaWork(job, report)
            if (job.source == TrackSource.YOUTUBE.name) return downloadYouTube(job, report)
            if (job.source == TrackSource.BILIBILI.name) return downloadBili(job, report)
            if (job.source == TrackSource.JAPANESE_ASMR.name) return downloadJapaneseAsmr(job, report)
            val track = db.tracks().getById(job.trackId) ?: error(appContext.getString(R.string.download_track_removed))
            if (LocalAudio.hasStoredCopy(track.localUri)) {
                report.reused()
                db.withTransaction {
                    requireRunningTarget(job)
                    val length = if (localFileReady(track.localUri)) File(track.localUri!!).length() else 0L
                    db.downloads().finish(job.id, length, length)
                }
                return
            }
            report.phase(ProgressPhase.RESOLVING)
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
                    .taskProgress(report)
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
                val expired = transferHttp.newCall(request).withResponse { response ->
                    if (response.code == 401) error(appContext.getString(R.string.download_login_expired))
                    if ((response.code == 403 || response.code == 404) && !refreshed) return@withResponse true
                    if (!response.isSuccessful) error(appContext.getString(R.string.download_failed_generic))
                    val body = response.body ?: error(appContext.getString(R.string.download_failed_generic))
                    val total = body.contentLength().coerceAtLeast(0)
                    val transfer = report.phase(ProgressPhase.DOWNLOADING)
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
                                transfer.bytes(read.toLong(), done, total.takeIf { it > 0 })
                                if (done - reported >= 256 * 1024) {
                                    db.downloads().activeProgress(job.id, done, total)
                                    reported = done
                                }
                            }
                            if (done == 0L || (total > 0L && done != total)) error(appContext.getString(R.string.download_incomplete_audio))
                        }
                    }
                    false
                }
                if (!expired) break
                refreshed = true
                report.retry()
                report.phase(ProgressPhase.RESOLVING)
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
                    report.phase(ProgressPhase.SAVING)
                    if (!partial.renameTo(dest)) error(appContext.getString(R.string.download_save_file_failed))
                    report.phase(ProgressPhase.IMPORTING)
                    finishTrackDownload(job, dest)
                }
            }
        } catch (cancelled: CancellationException) {
            val queueActive = currentCoroutineContext().isActive
            val stopped = currentCoroutineContext().cancellationCause()
            val message = ((stopped ?: cancelled) as? AudioDownloadStopped)?.persistedError
                ?: if (queueActive) PersistedErrors.CANCELLED else AUDIO_DOWNLOAD_INTERRUPTED
            withContext(NonCancellable) { db.downloads().failRunning(job.id, message) }
            if (!queueActive) throw cancelled
        } catch (error: Exception) {
            val stopped = currentCoroutineContext().cancellationCause()
            val message = when {
                stopped != null -> (stopped as? AudioDownloadStopped)?.persistedError ?: AUDIO_DOWNLOAD_INTERRUPTED
                error.message == DOWNLOAD_LIBRARY_REQUIRED -> DOWNLOAD_LIBRARY_REQUIRED
                error is DlsiteArchiveException && error.archiveKind == DlsiteArchiveFailureKind.INTERRUPTED ->
                    PersistedErrors.DLSITE_ARCHIVE_INTERRUPTED
                error is DlsiteArchiveException -> appContext.resolveNoUrl(error.userText, R.string.download_archive_failed)
                job.kind == "DLSITE_ARCHIVE" -> appContext.resolveUserError(error, R.string.download_archive_or_extract_failed)
                error is JaWorkException -> error.persistedError
                    ?: appContext.resolveNoUrl(error.userText, R.string.download_ja_work_failed)
                error is JapaneseAsmrException && error.failure == JaFailure.WORK_FILE_MISSING ->
                    PersistedErrors.JA_WORK_FILE_MISSING
                error is JapaneseAsmrException && error.failure == JaFailure.UNSAFE ->
                    appContext.getString(error.failure.messageRes)
                job.kind == JA_WORK_DOWNLOAD -> error.message?.takeIf {
                    // 「文章…」兼容旧版本已落库的错误文本；新任务写入「篇目…」。
                    it in setOf(PersistedErrors.JA_POST_REMOVED_LEGACY, PersistedErrors.JA_POST_REMOVED, PersistedErrors.JA_ENTRY_CHANGED)
                }
                    ?: appContext.getString(R.string.download_ja_work_failed)
                error is JapaneseAsmrException -> appContext.resolveNoUrl(error.userText, R.string.download_failed_generic)
                error is YouTubeException -> appContext.resolveNoUrl(error.userTextOr(R.string.download_youtube_audio_failed), R.string.download_youtube_audio_failed)
                job.source == TrackSource.YOUTUBE.name -> appContext.getString(R.string.download_youtube_audio_failed)
                error is IOException -> appContext.resolveNoUrl(error.userTextOr(R.string.download_save_audio_failed), R.string.download_save_audio_failed)
                else -> appContext.resolveNoUrl(error.userTextOr(R.string.download_failed_generic), R.string.download_failed_generic)
            }
            withContext(NonCancellable) { db.downloads().failRunning(job.id, message) }
            if (stopped != null) throw stopped
        } finally {
            partialFile?.delete()
        }
    }

    private fun hlsStaging(job: DownloadEntity): File? {
        val postId = job.workKey?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return null
        val key = job.fileKey ?: return null
        return File(jaRoot, "$postId/hls-${jaHash(key).take(16)}")
    }

    private suspend fun downloadJapaneseAsmr(job: DownloadEntity, report: TaskReporter) {
        val postId = job.workKey ?: error(DOWNLOAD_LIBRARY_REQUIRED)
        val key = job.fileKey ?: error(DOWNLOAD_LIBRARY_REQUIRED)
        val track = db.tracks().getById(job.trackId) ?: error(DOWNLOAD_LIBRARY_REQUIRED)
        val post = db.japaneseAsmr().post(postId) ?: error(DOWNLOAD_LIBRARY_REQUIRED)
        val directory = File(jaRoot, "$postId/japaneseasmr-${track.id}").apply { mkdirs() }
        val partial = File(directory, ".${job.id}.part")
        val staging = requireNotNull(hlsStaging(job))
        try {
            var extension: String? = null
            var hlsLength = 0L
            for (attempt in 0..1) {
                report.phase(ProgressPhase.RESOLVING)
                val article = library.japaneseAsmr.article(postId, post.rj, refresh = attempt != 0)
                val media = resolveStoredJaMatches(article, postId, db.japaneseAsmr())[key]?.media
                    ?: throw JapaneseAsmrException(JaFailure.MEDIA)
                if (media.kind == JaMediaKind.HLS) {
                    val candidate = media.candidates.firstOrNull { it.kind == JaMediaKind.HLS }
                        ?: throw JapaneseAsmrException(JaFailure.MEDIA)
                    hlsLength = jaHls.download(candidate.url, staging, partial, report) ?: continue
                    extension = "m4a"
                } else {
                    extension = downloadJaDirect(job, media.candidates.filter { it.kind == JaMediaKind.DIRECT }, partial, report)
                }
                if (extension != null) break
                report.retry()
            }
            if (extension == null) throw JapaneseAsmrException(JaFailure.MEDIA)
            currentCoroutineContext().ensureActive()
            report.phase(ProgressPhase.CHECKING)
            val duration = android.media.MediaMetadataRetriever().let { reader ->
                try {
                    reader.setDataSource(partial.absolutePath)
                    reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                } catch (_: RuntimeException) {
                    null
                } finally {
                    reader.release()
                }
            } ?: hlsLength
            val safeTitle = sanitizeEntryName(track.title)
            val name = if (safeTitle.endsWith(".$extension", ignoreCase = true)) safeTitle else "$safeTitle.$extension"
            val dest = File(directory, name)
            withContext(NonCancellable) {
                var renamed = false
                try {
                    db.withTransaction {
                        requireRunningTarget(job)
                        report.phase(ProgressPhase.SAVING)
                        if (!partial.renameTo(dest)) error(appContext.getString(R.string.download_save_file_failed))
                        renamed = true
                        report.phase(ProgressPhase.IMPORTING)
                        finishTrackDownload(job, dest)
                        if (duration > 0) {
                            db.tracks().fillDuration(track.id, duration)
                            db.japaneseAsmr().fillDuration(track.id, duration)
                        }
                    }
                    staging.deleteRecursively()
                } catch (failure: Exception) {
                    if (renamed) dest.delete()
                    throw failure
                }
            }
        } finally {
            partial.delete()
        }
    }

    private suspend fun downloadJaDirect(
        job: DownloadEntity,
        candidates: List<com.nanahoshi.audioplayer.japaneseasmr.JaCandidate>,
        partial: File,
        report: TaskReporter,
    ): String? {
        for (candidate in candidates) {
            val req = JapaneseAsmrClient.mediaRequest(candidate.url)
                .header("Accept-Encoding", "identity")
            val probe = try {
                jaProbeHttp.newCall(req.header("Range", "bytes=0-1023").build()).withResponse { response ->
                    if (response.isChallenge(if (response.code == 403) response.peekBody(4096).string() else "")) {
                        throw JapaneseAsmrException(JaFailure.VERIFY)
                    }
                    (response.code == 200 || response.code == 206) &&
                        response.body?.contentType()?.subtype != "html" && response.peekBody(1).bytes().isNotEmpty()
                }
            } catch (failure: JapaneseAsmrException) {
                throw failure
            } catch (_: IOException) {
                false
            }
            if (!probe) continue
            val result = jaTransferHttp.newCall(req.removeHeader("Range").taskProgress(report).build()).withResponse { response ->
                if (response.isChallenge(if (response.code == 403) response.peekBody(4096).string() else "")) {
                    throw JapaneseAsmrException(JaFailure.VERIFY)
                }
                if (response.code == 403 || response.code == 404) return@withResponse null
                if (response.code != 200) throw JapaneseAsmrException(if (response.code >= 500) JaFailure.SERVER else JaFailure.MEDIA)
                val body = response.body ?: throw JapaneseAsmrException(JaFailure.MEDIA)
                val total = body.contentLength().coerceAtLeast(0)
                val transfer = report.phase(ProgressPhase.DOWNLOADING)
                var done = 0L
                var reported = 0L
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            transfer.bytes(read.toLong(), done, total.takeIf { it > 0 })
                            if (done - reported >= 256 * 1024) {
                                db.downloads().activeProgress(job.id, done, total)
                                reported = done
                            }
                        }
                    }
                }
                check(done > 0L && (total == 0L || done == total)) { appContext.getString(R.string.download_incomplete_audio) }
                val ext = response.request.url.encodedPath.substringAfterLast('.', "").lowercase()
                if (ext in setOf("mp3", "m4a", "aac", "mp4", "wav", "flac", "ogg", "opus")) ext
                else if ((body.contentType()?.toString() ?: candidate.mime)?.startsWith("audio/mpeg") == true) "mp3" else "m4a"
            }
            if (result != null) return result
        }
        return null
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
        val workId = track.asmrWorkId ?: error(appContext.getString(R.string.download_no_address))
        val fresh = AsmrClient.findStream(asmr.trackTree(workId), rawFileKey(track.fileHash), track.title)
            ?: error(appContext.getString(R.string.download_no_address))
        db.tracks().updateRemoteUrl(track.id, fresh)
        return fresh
    }

    suspend fun savedRemotePath(source: TrackSource, workKey: String, fileKey: String?): String? = withContext(Dispatchers.IO) {
        if (source == TrackSource.JAPANESE_ASMR) return@withContext localCopy(source.name, workKey, fileKey)
        val key = canonicalWorkKey(workKey)
        if (source == TrackSource.DLSITE && !fileKey.isNullOrBlank()) {
            db.dlsiteOriginals().entryForPlay(key, rawFileKey(fileKey) ?: fileKey)?.localPath
                ?.takeIf { File(it).isFile }?.let { return@withContext it }
        }
        workKeys(workKey).firstNotNullOfOrNull { candidate -> localCopy(source.name, candidate, fileKey) }
    }

    suspend fun downloadedKeys(source: TrackSource, workKey: String): Set<String> = withContext(Dispatchers.IO) {
        val keys = if (source == TrackSource.JAPANESE_ASMR) listOf(workKey) else workKeys(workKey)
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
        val workno = worknoOf(track) ?: error(appContext.getString(R.string.download_no_address))
        val fileKey = rawFileKey(track.fileHash) ?: error(appContext.getString(R.string.download_no_address))
        return dlsite.signedPlayFile(workno, fileKey)
    }

    private suspend fun localCopy(source: String, workKey: String, fileKey: String?): String? {
        if (fileKey.isNullOrBlank()) return null
        return db.downloads().finishedForWork(source, workKey)
            .firstOrNull { (if (source == TrackSource.JAPANESE_ASMR.name) it.fileKey else rawFileKey(it.fileKey)) == fileKey && File(it.localPath.orEmpty()).length() > 0L && !it.localPath.isNullOrBlank() && File(it.localPath).isFile }
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
        val unique = trackDownloadRelativePath(track.source, track.id, fileName)
        return if (relative.isEmpty()) File(workDir, unique) else File(workDir, "$relative/$unique")
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

    private suspend fun downloadBili(job: DownloadEntity, report: TaskReporter) {
        val track = job.trackId.takeIf { it > 0L }?.let { db.tracks().getById(it) }
        val bvid = job.workKey?.trim().orEmpty().ifBlank { track?.bvid?.trim().orEmpty() }
        val cid = job.fileKey?.toLongOrNull() ?: track?.cid ?: error(appContext.getString(R.string.download_no_bili_episode))
        val dest = biliAudioFile(filesDir, bvid, cid) ?: error(appContext.getString(R.string.download_no_bili_episode))
        if (dest.isFile && dest.length() > 0L) {
            report.reused()
            library.completeBiliDownload(job.id, bvid, cid, dest.absolutePath)
            return
        }
        dest.parentFile?.mkdirs()
        val partial = File(dest.absolutePath + ".${job.id}.part")
        try {
            var refreshed = false
            while (true) {
                if (partial.exists()) partial.delete()
                report.phase(ProgressPhase.RESOLVING)
                val audio = bilibili.resolveAudio(bvid, cid)
                val request = Request.Builder()
                    .taskProgress(report)
                    .url(audio.url.replace("%2C", ",").replace("%2c", ","))
                    .header("Referer", "https://www.bilibili.com/video/$bvid")
                    .header("User-Agent", BilibiliClient.USER_AGENT)
                    .build()
                val expired = biliHttp.newCall(request).withResponse { response ->
                    if ((response.code == 403 || response.code == 401) && !refreshed) return@withResponse true
                    if (!response.isSuccessful) error(appContext.getString(R.string.download_failed_generic))
                    val body = response.body ?: error(appContext.getString(R.string.download_failed_generic))
                    val total = body.contentLength().coerceAtLeast(0)
                    val transfer = report.phase(ProgressPhase.DOWNLOADING)
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
                                transfer.bytes(read.toLong(), done, total.takeIf { it > 0 })
                                if (done - reported >= 256 * 1024) {
                                    db.downloads().activeProgress(job.id, done, total)
                                    reported = done
                                }
                            }
                            if (done <= 0 || (total > 0 && done != total)) error(appContext.getString(R.string.download_bili_incomplete))
                        }
                    }
                    false
                }
                if (!expired) break
                refreshed = true
                report.retry()
            }
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                db.withTransaction {
                    requireRunningTarget(job)
                    report.phase(ProgressPhase.SAVING)
                    if (!partial.renameTo(dest) || dest.length() <= 0L) error(appContext.getString(R.string.download_save_track_failed))
                    library.completeBiliDownload(job.id, bvid, cid, dest.absolutePath)
                }
            }
        } finally { partial.delete() }
    }

    private suspend fun cacheBiliSubtitle(job: DownloadEntity) {
        val report = progress.begin(TaskKey(TaskKind.DOWNLOAD, job.id, ProgressPart.BILI_SUBTITLE),
            ProgressPart.BILI_SUBTITLE, ProgressPhase.FETCHING_SUBTITLE)
        var outcome = ProgressResult.RETRY_LATER
        try {
            val bvid = job.workKey
            val cid = job.fileKey?.toLongOrNull()
            if (bvid != null && cid != null && libraryTarget(job) != null &&
                readyBiliAudio(filesDir, bvid, cid) != null && readBiliSubtitle(filesDir, bvid, cid) == null
            ) {
                val result = withTimeoutOrNull(30_000L) { bilibili.subtitleDocument(bvid, cid) }
                if (result is BiliSubtitle.NeedsLogin) notice(appContext.getString(R.string.download_bili_subtitle_login))
                db.withTransaction {
                    if (db.downloads().get(job.id)?.pendingBiliSubtitle != true) return@withTransaction
                    val targetReady = libraryTarget(job) != null && readyBiliAudio(filesDir, bvid, cid) != null
                    val cached = readBiliSubtitle(filesDir, bvid, cid) != null
                    val written = if (result is BiliSubtitle.Ready && targetReady && !cached) {
                        writeBiliSubtitle(filesDir, bvid, cid, result.document, result.language)
                    } else null
                    outcome = when {
                        !targetReady -> ProgressResult.CANCELLED
                        cached || written == true -> ProgressResult.COMPLETED
                        result is BiliSubtitle.NeedsLogin -> ProgressResult.NEEDS_LOGIN
                        result is BiliSubtitle.Missing -> ProgressResult.NO_SUBTITLE
                        else -> ProgressResult.RETRY_LATER
                    }
                    // 写盘失败、需登录、读取失败或超时都保留标记，待下次打开应用接手。
                    if (shouldClearPendingBiliSubtitle(result, targetReady, cached, written)) {
                        db.downloads().setPendingBiliSubtitle(job.id, null)
                    }
                }
            } else {
                outcome = if (bvid != null && cid != null && readBiliSubtitle(filesDir, bvid, cid) != null) ProgressResult.COMPLETED else ProgressResult.CANCELLED
                db.downloads().setPendingBiliSubtitle(job.id, null)
            }
        } catch (cancelled: CancellationException) {
            outcome = ProgressResult.INTERRUPTED
            throw cancelled
        } finally {
            report.finish(outcome)
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
