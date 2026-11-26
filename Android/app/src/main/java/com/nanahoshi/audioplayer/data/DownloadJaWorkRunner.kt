package com.nanahoshi.audioplayer.data

import androidx.room.withTransaction
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.data.db.DownloadEntity
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.japaneseasmr.JA_VERIFY_PHASE
import com.nanahoshi.audioplayer.japaneseasmr.JA_WORK_DOWNLOAD
import com.nanahoshi.audioplayer.japaneseasmr.JaBuzzVerification
import com.nanahoshi.audioplayer.japaneseasmr.JaDownloadVerification
import com.nanahoshi.audioplayer.japaneseasmr.JaFailure
import com.nanahoshi.audioplayer.japaneseasmr.JaMegaFatalException
import com.nanahoshi.audioplayer.japaneseasmr.JaMegaManifestInvalidException
import com.nanahoshi.audioplayer.japaneseasmr.JaPostRef
import com.nanahoshi.audioplayer.japaneseasmr.JaTransferVerification
import com.nanahoshi.audioplayer.japaneseasmr.JaVisibleDownload
import com.nanahoshi.audioplayer.japaneseasmr.JaWorkAddressExpired
import com.nanahoshi.audioplayer.japaneseasmr.JaWorkEntry
import com.nanahoshi.audioplayer.japaneseasmr.JaWorkException
import com.nanahoshi.audioplayer.japaneseasmr.JaWorkManifest
import com.nanahoshi.audioplayer.japaneseasmr.JapaneseAsmrException
import com.nanahoshi.audioplayer.japaneseasmr.isBuzzDownloadEndpoint
import com.nanahoshi.audioplayer.japaneseasmr.isBuzzHost
import com.nanahoshi.audioplayer.japaneseasmr.jaDownloadEntry
import com.nanahoshi.audioplayer.japaneseasmr.jaHash
import com.nanahoshi.audioplayer.text.PersistedErrors
import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.UserFacingException
import com.nanahoshi.audioplayer.text.resolve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

private const val JA_MANIFEST_LIFETIME_MS = 120_000L

/** Japanese ASMR 整作清单、网页验证与整作下载。 */
internal class DownloadJaWorkRunner(private val center: DownloadCenter) {
    private val db get() = center.db
    private val library get() = center.library

    private fun clockMs() = System.nanoTime() / 1_000_000

    internal fun invalidateWorkManifests(rj: String, keepJobId: Long? = null) {
        center.jaWorkManifests.entries.removeIf { it.key != keepJobId && it.value.manifest.entry.post.rj == rj }
    }

    internal fun rememberManifest(jobId: Long, rootId: Long, manifest: JaWorkManifest): JaWorkManifest =
        synchronized(center.jaWorkManifests) {
            invalidateWorkManifests(manifest.entry.post.rj, keepJobId = jobId)
            val retained = if (manifest.pureMega) manifest.forMegaRetention(keepInitialAddress = true) else manifest
            val expires = manifest.resolvedAtMs + JA_MANIFEST_LIFETIME_MS
            if (manifest.pureMega || clockMs() < expires) {
                center.jaWorkManifests[jobId] = DownloadCenter.AvailableManifest(retained, rootId, expires)
            } else {
                center.jaWorkManifests.remove(jobId)
            }
            retained
        }

    private fun cachedManifest(job: DownloadEntity): JaWorkManifest? {
        val cached = center.jaWorkManifests[job.id] ?: return null
        if (cached.rootFolderId != job.workFolderId || cached.manifest.entry.post.postId != job.fileKey ||
            cached.manifest.entry.post.rj != job.workKey ||
            job.jaEntryKey?.let { it != jaHash(cached.manifest.entry.address) } == true
        ) {
            center.jaWorkManifests.remove(job.id, cached)
            return null
        }
        if (!cached.pureMega) {
            center.jaWorkManifests.remove(job.id, cached)
            return cached.manifest.takeIf { clockMs() < cached.expires }
        }
        if (clockMs() < cached.expires) return cached.manifest
        val stripped = cached.withoutMegaAddresses()
        center.jaWorkManifests.replace(job.id, cached, stripped)
        return stripped.manifest
    }

    internal fun invalidateRootManifests(removedIds: Set<Long>) {
        center.jaWorkManifests.entries.removeIf { it.value.rootFolderId in removedIds }
        center.jaWaitingEntries.entries.removeIf { it.value.rootFolderId in removedIds }
        center.jaVerifications.forEach { (token, request) ->
            if (request.rootId in removedIds && center.jaVerifications.remove(token, request)) request.result.complete(null)
        }
    }

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
            center.jaWorkClient.manifest(entry, sitePage, onVerifyBuzz = { buzz ->
                withContext(Dispatchers.Main.immediate) {
                    val verified = awaitWorkVerification(
                        DownloadCenter.WorkVerification(entry, null, rootId, buzz, retryFileLink = buzz.fileLink), onVerify,
                    ) ?: throw CancellationException()
                    onReadVerifiedManifest()
                    verified.buzzPages
                }
            })
        }
        return try {
            read(null)
        } catch (verify: JaDownloadVerification) {
            val pending = DownloadCenter.WorkVerification(entry.copy(address = verify.entry), null, rootId)
            val captured = awaitWorkVerification(pending, onVerify)?.sitePage ?: return null
            if (!jaArticleInRoot(entry.post, rootId)) return null
            onReadVerifiedManifest()
            read(captured)
        }
    }

    private suspend fun awaitWorkVerification(
        pending: DownloadCenter.WorkVerification,
        onVerify: suspend (token: String, buzzheavier: Boolean) -> Boolean,
    ): DownloadCenter.WorkVerificationCapture? {
        val token = UUID.randomUUID().toString()
        center.jaVerifications[token] = pending
        try {
            return if (onVerify(token, pending.buzz != null)) pending.result.await() else null
        } finally {
            center.jaVerifications.remove(token)
        }
    }

    internal fun jaVerification(id: String): DownloadCenter.WorkVerification? = center.jaVerifications[id]

    internal fun cancelJaVerification(id: String) {
        center.jaVerifications.remove(id)?.result?.complete(null)
    }

    internal suspend fun beginJaTaskVerification(id: Long): String = withContext(Dispatchers.IO) {
        val job = db.downloads().get(id)?.takeIf { it.kind == JA_WORK_DOWNLOAD &&
            it.status == DownloadStatus.FAILED && it.phase == JA_VERIFY_PHASE } ?: throw UserFacingException(TextRef.Res(R.string.download_task_ended))
        if (center.targetResolver.libraryTarget(job.copy(workFolderId = null)) == null) throw UserFacingException(TextRef.Raw(PersistedErrors.JA_POST_REMOVED))
        val pending = center.jaWaitingEntries[id] ?: DownloadCenter.WaitingWorkVerification(try {
            entryForJob(job)
        } catch (verify: JaDownloadVerification) {
            JaWorkEntry(jaPostForJob(job), verify.entry)
        })
        if (db.downloads().get(id)?.phase != JA_VERIFY_PHASE) throw UserFacingException(TextRef.Res(R.string.download_task_ended))
        val token = UUID.randomUUID().toString()
        center.jaVerifications.entries.removeIf { it.value.taskId == id }
        center.jaVerifications[token] = DownloadCenter.WorkVerification(
            pending.entry, id, job.workFolderId, pending.buzz,
            verifiedBuzzPages = pending.verifiedBuzzPages,
            retryFileLink = pending.retryFileLink ?: pending.buzz?.fileLink,
        )
        token
    }

    internal suspend fun completeJaVerification(
        token: String,
        visible: JaVisibleDownload,
    ): DownloadCenter.WorkVerificationResult {
        val ended = DownloadCenter.WorkVerificationResult.Finished(false)
        val request = center.jaVerifications[token] ?: return ended
        val id = request.taskId
        val job = if (id == null) {
            if (request.rootId == null || !jaArticleInRoot(request.entry.post, request.rootId)) return ended
            null
        } else {
            val waiting = db.downloads().get(id)?.takeIf { it.phase == JA_VERIFY_PHASE && it.status == DownloadStatus.FAILED }
                ?: return ended
            if (center.targetResolver.libraryTarget(waiting.copy(workFolderId = null)) == null) return ended
            waiting
        }

        val buzz = request.buzz
        val confirmed = if (buzz == null) request.verifiedBuzzPages else {
            val page = request.nextBuzzPage ?: return ended
            if (!page.accepts(visible.url)) throw JaWorkException(TextRef.Res(R.string.download_verify_current_host))
            val transfer = visible.fileUrl ?: visible.direct ?: visible.copyLink
            if (transfer == null || !isBuzzHost(transfer) ||
                (visible.fileUrl == null && isBuzzDownloadEndpoint(transfer) &&
                    transfer.toHttpUrlOrNull()?.queryParameter("t") == null)
            ) throw JaWorkException(TextRef.Res(R.string.download_click_download_file))
            val pages = request.verifiedBuzzPages.toMutableMap().apply {
                put(page.fileLink, visible)
                putIfAbsent(visible.url, visible)
            }
            val updated = request.copy(verifiedBuzzPages = pages, retryFileLink = null)
            val next = updated.nextBuzzPage
            if (next != null) {
                return if (center.jaVerifications.replace(token, request, updated)) DownloadCenter.WorkVerificationResult.OpenEntry(next.address) else ended
            }
            pages
        }

        if (job == null) {
            val captured = DownloadCenter.WorkVerificationCapture(visible.takeIf { buzz == null }, confirmed)
            return DownloadCenter.WorkVerificationResult.Finished(request.result.complete(captured))
        }
        try {
            val articlePage = if (buzz == null) com.nanahoshi.audioplayer.japaneseasmr.jaPostId(request.entry.address) else null
            if (articlePage == request.entry.post.postId) {
                val article = library.japaneseAsmr.rememberVerifiedArticle(visible.url, visible.html,
                    request.entry.post.rj, requireMedia = false)
                if (article.post.postId != articlePage) return ended
                val selected = chooseEntry(job, jaDownloadEntry(request.entry.post, article))
                return if (center.jaVerifications.replace(token, request, request.copy(entry = selected))) {
                    DownloadCenter.WorkVerificationResult.OpenEntry(selected.address)
                } else ended
            }
            val manifest = withContext(Dispatchers.IO) {
                center.jaWorkClient.manifest(request.entry, if (buzz == null) visible else buzz.sourcePage, confirmed)
            }
            if (!center.jaVerifications.remove(token, request)) return ended
            return DownloadCenter.WorkVerificationResult.Finished(center.retry(job.id, manifest, verification = true) == job.id)
        } catch (verify: JaBuzzVerification) {
            val updated = request.copy(
                buzz = verify, verifiedBuzzPages = confirmed, retryFileLink = verify.fileLink,
            )
            return if (center.jaVerifications.replace(token, request, updated)) DownloadCenter.WorkVerificationResult.OpenEntry(updated.address) else ended
        } catch (_: JaDownloadVerification) {
            throw JaWorkException(TextRef.Res(R.string.download_verify_first))
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
    ): DownloadCenter.Enqueued = center.prepare {
        val post = manifest.entry.post
        val key = jaHash(manifest.entry.address)
        db.withTransaction {
            val job = DownloadEntity(trackId = 0, title = "${post.rj} · ${center.appContext.getString(R.string.download_volume_work)}", bytesDone = 0,
                bytesTotal = manifest.bytes ?: 0, status = DownloadStatus.QUEUED, error = null,
                source = TrackSource.JAPANESE_ASMR.name, kind = JA_WORK_DOWNLOAD, workKey = post.rj,
                workFolderId = rootId, fileKey = post.postId, jaEntryKey = key, jaReplace = replace, jaForce = force)
            check(center.targetResolver.libraryTarget(job) != null) { PersistedErrors.JA_POST_REMOVED }
            db.downloads().reserveWorkTask(post.rj, 0)?.let { return@withTransaction DownloadCenter.Enqueued(it.id, false) }
            val id = db.downloads().insert(job)
            rememberManifest(id, rootId, manifest)
            center.registerJob(id)
            DownloadCenter.Enqueued(id, true)
        }
    }

    private suspend fun jaPostForJob(job: DownloadEntity): JaPostRef =
        job.fileKey?.let { db.japaneseAsmr().post(it)?.ref() } ?: error(PersistedErrors.JA_POST_REMOVED)

    private fun chooseEntry(job: DownloadEntity, entries: List<JaWorkEntry>): JaWorkEntry =
        if (job.jaEntryKey == null) entries.singleOrNull() ?: throw UserFacingException(TextRef.Res(R.string.download_choose_entry))
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

    internal suspend fun downloadJaWork(job: DownloadEntity, report: TaskReporter) {
        val cached = cachedManifest(job)
        suspend fun phase(phase: ProgressPhase) {
            report.phase(phase)
            db.downloads().setPhase(job.id, phase.name)
        }
        phase(ProgressPhase.RESOLVING)
        try {
            val entry = cached?.entry ?: entryForJob(job)
            suspend fun readManifest(): JaWorkManifest {
                val fresh = center.jaWorkClient.manifest(entry)
                center.targetResolver.requireRunningTarget(job)
                if (fresh.pureMega) return rememberManifest(job.id, requireNotNull(job.workFolderId), fresh)
                synchronized(center.jaWorkManifests) { invalidateWorkManifests(entry.post.rj) }
                return fresh
            }
            suspend fun run(manifest: JaWorkManifest): TextRef? = try {
                library.jaWorkFiles.execute(job, manifest, center.jaWorkClient, report, ::phase) {
                    center.targetResolver.requireRunningTarget(job)
                }
            } catch (verify: JaTransferVerification) {
                center.targetResolver.requireRunningTarget(job)
                val buzz = JaBuzzVerification(entry, manifest.sourcePage, listOf(verify.page), verify.page.fileLink)
                waitForJaWorkVerification(job, DownloadCenter.WaitingWorkVerification(entry, buzz, verify.page.fileLink, manifest.verifiedBuzzPages))
                null
            }
            val message = try {
                run(cached ?: readManifest())
            } catch (expired: JaWorkAddressExpired) {
                if (expired.sessionRequired && cached?.fromVerifiedPage == true) throw expired
                phase(ProgressPhase.RESOLVING)
                center.targetResolver.requireRunningTarget(job)
                run(readManifest())
            }
            if (message != null) {
                // Acceptance already evicted older manifests. Preserve a new job accepted just after publication.
                center.jaWorkManifests.remove(job.id)
                center.notice(center.appContext.resolve(message))
            }
        } catch (verify: JaDownloadVerification) {
            waitForJaWorkVerification(job, DownloadCenter.WaitingWorkVerification(JaWorkEntry(jaPostForJob(job), verify.entry)))
        } catch (verify: JaBuzzVerification) {
            waitForJaWorkVerification(job, DownloadCenter.WaitingWorkVerification(verify.entry, verify, verify.fileLink))
        } catch (failure: JaWorkException) {
            if (failure is JaMegaManifestInvalidException ||
                failure is JaMegaFatalException && failure.userText == TextRef.Res(R.string.ja_error_mega_share_expired) ||
                failure.persistedError == PersistedErrors.JA_ENTRY_CHANGED
            ) center.jaWorkManifests.remove(job.id)
            throw failure
        } finally {
            center.jaWorkManifests.computeIfPresent(job.id) { _, value -> value.withoutMegaAddresses() }
        }
    }

    private suspend fun waitForJaWorkVerification(job: DownloadEntity, pending: DownloadCenter.WaitingWorkVerification) {
        center.jaWaitingEntries[job.id] = pending.copy(rootFolderId = job.workFolderId)
        db.downloads().waitForJaVerification(job.id)
        center.notice(center.appContext.getString(R.string.download_ja_verify_notice))
    }
}