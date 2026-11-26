package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.text.UserFacingException
import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.resolveNoUrl
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.text.PersistedErrors
import com.nanahoshi.audioplayer.text.resolve
import com.nanahoshi.audioplayer.text.userTextOr

import com.nanahoshi.audioplayer.japaneseasmr.jaAudioIdentity
import androidx.room.withTransaction
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import com.nanahoshi.audioplayer.data.db.TranslateMark
import com.nanahoshi.audioplayer.data.db.TranslationJobEntity
import com.nanahoshi.audioplayer.playback.SubtitleTranslationTarget
import com.nanahoshi.audioplayer.playback.SubtitleCue
import com.nanahoshi.audioplayer.playback.GeneratedSubtitles
import com.nanahoshi.audioplayer.playback.LoadedSubtitles
import com.nanahoshi.audioplayer.playback.encodeGeneratedCues
import com.nanahoshi.audioplayer.playback.canGenerateTranslation
import com.nanahoshi.audioplayer.playback.hasTranslation
import com.nanahoshi.audioplayer.playback.parseGeneratedSubtitles
import com.nanahoshi.audioplayer.playback.usesJapaneseCorrection
import com.nanahoshi.audioplayer.playback.subtitleSourceFingerprint
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal enum class GeneratedRetryResult {
    Queued, Duplicate, MissingConfig, MissingSource, AlreadyTranslated, Unavailable,
}

class TranslateCenter internal constructor(
    context: android.content.Context,
    private val db: AppDatabase,
    private val credentials: CredentialStore,
    private val subtitles: com.nanahoshi.audioplayer.playback.SubtitleSource,
    private val requests: ForegroundTaskRequests<SubtitleTask>,
    private val recovery: Job,
    internal val progress: TaskProgressCenter = TaskProgressCenter(db),
    http: OkHttpClient = HttpRoots.shared,
    private val notice: (String) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val calls = InflightCalls()
    private val client = TranslateClient(credentials, calls, http)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal suspend fun close() {
        scope.coroutineContext[Job]?.cancelAndJoin()
        progress.close()
    }
    private val runners = ConcurrentHashMap<Long, Job>()
    private val attempts = ConcurrentHashMap<Long, Long>()
    private val nextAttempt = java.util.concurrent.atomic.AtomicLong()
    // When both are needed, acquire generatedWriteLock before submissions.
    private val submissions = Mutex()
    private val execution = TranslationExecution()
    internal val jobs = db.translations().observe()
    fun observeVisible() = db.translations().observeVisible()
    private val revision = MutableStateFlow(0L)
    internal val updates = revision.asStateFlow()

    init { progress.watchSubtitles(recovery) }

    fun readyToTranslate(fingerprint: String): Boolean {
        if (!credentials.hasTranslate()) return false
        val document = readGenerated(fingerprint) ?: return false
        return canGenerateTranslation(document.displayCues, document.language)
    }

    fun stopForWhisper(jobId: Long) {
        calls.cancel("whisper-$jobId")
    }

    internal suspend fun hasActiveGenerated(fingerprint: String): Boolean =
        db.whisper().activeTranslation(fingerprint) != null ||
            db.translations().active().any { it.generatedFingerprint == fingerprint }

    /** 调用方持有字幕写入锁；与手动提交共用提交锁，避免两个入口重复排队。 */
    internal suspend fun queueGenerated(jobId: Long, fingerprint: String): Boolean = submissions.withLock {
        db.withTransaction {
            if (hasActiveGenerated(fingerprint)) {
                db.whisper().finishIfActive(jobId)
                false
            } else db.whisper().queueTranslationIfActive(jobId) > 0
        }
    }

    /** The caller holds generatedWriteLock, including through registration of the queued attempt. */
    internal suspend fun requeueGeneratedTranslation(jobId: Long, createdAt: Long): GeneratedRetryResult = submissions.withLock {
        db.withTransaction {
            val dao = db.whisper()
            val job = dao.get(jobId) ?: return@withTransaction GeneratedRetryResult.Unavailable
            if (job.status != DownloadStatus.DONE || job.translateStatus != TranslateMark.FAILED) {
                return@withTransaction GeneratedRetryResult.Unavailable
            }
            if (!credentials.hasTranslate()) return@withTransaction GeneratedRetryResult.MissingConfig
            val model = knownWhisperModel(job.model) ?: return@withTransaction GeneratedRetryResult.MissingSource
            val document = runCatching { readGenerated(job.fingerprint) }.getOrNull()
                ?: return@withTransaction GeneratedRetryResult.MissingSource
            if (document.cues.isEmpty() || !generatedSubtitlesMatch(document, model, job.endpoint, job)) {
                return@withTransaction GeneratedRetryResult.MissingSource
            }
            if (!canGenerateTranslation(document.displayCues, document.language)) return@withTransaction GeneratedRetryResult.AlreadyTranslated
            if (hasActiveGenerated(job.fingerprint)) return@withTransaction GeneratedRetryResult.Duplicate

            val nextSummary = decodeProgressSummary(job.progressSummary).without(ProgressPart.TRANSLATION).encodeOrNull()
            if (dao.requeueTranslation(jobId, createdAt, nextSummary) == 0) {
                return@withTransaction GeneratedRetryResult.Unavailable
            }
            progress.resetPart(TaskKey(TaskKind.WHISPER, jobId), ProgressPart.TRANSLATION)
            GeneratedRetryResult.Queued
        }
    }

    suspend fun deleteTerminal(id: Long) {
        subtitles.generatedWriteLock.withLock {
            submissions.withLock {
                if (runners.containsKey(id)) return
                val dao = db.translations()
                when (dao.get(id)?.status) {
                    DownloadStatus.FAILED -> dao.deleteFailed(id)
                    DownloadStatus.DONE -> dao.dismissDone(id, System.currentTimeMillis())
                    else -> Unit
                }
            }
        }
    }

    fun cancel(id: Long) {
        scope.launch {
            if (abandon(id, PersistedErrors.CANCELLED)) runners[id]?.cancel()
        }
    }

    suspend fun applyGenerated(jobId: Long, fingerprint: String): String? {
        if (db.whisper().get(jobId)?.translateStatus != TranslateMark.QUEUED) throw CancellationException()
        val report = progress.begin(TaskKey(TaskKind.WHISPER, jobId), ProgressPart.TRANSLATION, ProgressPhase.WAITING_TRANSLATION)
        try {
            return execution.run {
                if (db.whisper().startQueuedTranslation(jobId) == 0) throw CancellationException()
                val config = credentials.translateConfig()
                report.metadata(config.model, appContext.getString(config.platform.labelRes))
                report.phase(ProgressPhase.TRANSLATING)
                val ticket = calls.prepare("whisper-$jobId", report)
                calls.cancelWithCoroutine(ticket) { applyGenerated(jobId, fingerprint, ticket, report, config) }
            }
        } finally {
            withContext(NonCancellable) { progress.end(report) }
        }
    }

    private suspend fun applyGenerated(
        jobId: Long,
        fingerprint: String,
        ticket: InflightCalls.Ticket,
        report: TaskReporter,
        config: TranslateConfig,
    ): String? {
        return try {
            val document = readGenerated(fingerprint) ?: return appContext.getString(R.string.translate_no_japanese)
            val job = db.whisper().get(jobId) ?: throw CancellationException()
            val model = knownWhisperModel(job.model) ?: return appContext.getString(R.string.translate_model_changed)
            if (!generatedSubtitlesMatch(document, model, job.endpoint, job)) return appContext.getString(R.string.translate_subtitle_updated)
            if (config.token.isNullOrBlank()) return appContext.getString(R.string.translate_config_required)
            val cues = document.cues
            if (cues.isEmpty() || !canGenerateTranslation(document.displayCues, document.language)) return appContext.getString(R.string.translate_nothing)
            if (!whisperTranslating(jobId)) throw CancellationException()
            val translated = translateAll(cues, document.language, MergeLimits.forModel(document.origin?.model), ticket, config)
            val lines = translated.cues ?: return appContext.resolveNoUrl(translated.detail, R.string.translate_status_failed)
            if (!whisperTranslating(jobId)) throw CancellationException()
            val finished = finishWithPolish(lines, ticket, config) { whisperTranslating(jobId) }
            if (!whisperTranslating(jobId)) throw CancellationException()
            ticket.progress?.phase(ProgressPhase.SAVING_TRANSLATION)
            if (!commitTranslated(jobId, fingerprint, document, finished, report)) return appContext.getString(R.string.translate_subtitle_updated)
            revision.update { it + 1 }
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            appContext.resolveNoUrl(error.userTextOr(R.string.translate_status_failed), R.string.translate_status_failed)
        }
    }

    private suspend fun whisperTranslating(jobId: Long): Boolean =
        db.whisper().get(jobId)?.translateStatus == TranslateMark.RUNNING

    private suspend fun commitTranslated(
        jobId: Long,
        fingerprint: String,
        expected: GeneratedSubtitles,
        cues: List<SubtitleCue>,
        report: TaskReporter,
    ): Boolean = subtitles.generatedWriteLock.withLock {
        if (!progress.isCurrent(report) || readGenerated(fingerprint) != expected) return@withLock false
        val payload = encodeGeneratedCues(expected.cues, expected.origin, revisedCues = cues, language = expected.language)
        subtitles.publishTranslation(generatedFile(fingerprint), payload, jobId, null)
    }

    internal suspend fun submit(
        task: SubtitleTask,
        row: QueuedTrack,
        expected: LoadedSubtitles?,
    ): Boolean {
        // Reserve the foreground start before any disk access. The service waits for the task row.
        val queued = kotlinx.coroutines.CompletableDeferred<Pair<Long, Long>?>()
        val request = requests.submit(task) { queued.await()?.let { (id, token) -> runRow(id, row, task, expected, token) } ?: false }
        request.result.invokeOnCompletion { cause ->
            if (cause is SubtitleTaskStopped) scope.launch {
                queued.await()?.let { (id, token) -> abandon(id, cause.persistedError, token) }
            }
        }
        try {
            withContext(Dispatchers.IO + NonCancellable) {
                recovery.join()
                withSubmissionLock {
                    val fingerprint = expected?.generatedFingerprint
                    val job = newJob(task, row, fingerprint, expected)
                    if (db.translations().active().any { it.sameTarget(job) } ||
                        fingerprint?.let { db.whisper().activeTranslation(it) != null } == true) {
                        noticeDuplicate()
                        return@withSubmissionLock
                    }
                    val id = db.translations().insert(job)
                    val token = nextAttempt.incrementAndGet()
                    attempts[id] = token
                    queued.complete(id to token)
                }
            }
        } finally {
            queued.complete(null)
        }
        return request.result.await()
    }

    internal suspend fun retry(id: Long, task: SubtitleTask): Boolean {
        val ready = kotlinx.coroutines.CompletableDeferred<Triple<Long, QueuedTrack, Long>?>()
        val request = requests.submit(task) {
            val (nextId, row, token) = ready.await() ?: return@submit false
            runRow(nextId, row, task, null, token)
        }
        request.result.invokeOnCompletion { cause ->
            if (cause is SubtitleTaskStopped) scope.launch {
                ready.await()?.let { (nextId, _, token) -> abandon(nextId, cause.persistedError, token) }
            }
        }
        try {
            withContext(Dispatchers.IO + NonCancellable) {
                recovery.join()
                withSubmissionLock {
                    if (runners.containsKey(id)) return@withSubmissionLock
                    val job = db.translations().get(id) ?: return@withSubmissionLock
                    if (job.status != DownloadStatus.FAILED || job.operation != task) return@withSubmissionLock
                    val row = restoreRow(job) ?: throw UserFacingException(TextRef.Res(R.string.translate_source_missing))
                    if (db.translations().active().any { it.sameTarget(job) } ||
                        job.generatedFingerprint?.let { db.whisper().activeTranslation(it) != null } == true) {
                        noticeDuplicate()
                        return@withSubmissionLock
                    }
                    progress.reset(TaskKey(TaskKind.TRANSLATION, id))
                    if (db.translations().requeue(id) > 0) {
                        val token = nextAttempt.incrementAndGet()
                        attempts[id] = token
                        ready.complete(Triple(id, row, token))
                    }
                }
            }
        } finally {
            ready.complete(null)
        }
        return request.result.await()
    }

    private suspend fun runRow(id: Long, row: QueuedTrack, task: SubtitleTask, expected: LoadedSubtitles?, token: Long): Boolean {
        val self = coroutineContext[Job]!!
        val registered = withSubmissionLock {
            if (runners.containsKey(id) || attempts[id] != token || db.translations().get(id)?.status != DownloadStatus.QUEUED) {
                false
            } else {
                runners[id] = self
                true
            }
        }
        if (!registered) return false
        val report = progress.begin(TaskKey(TaskKind.TRANSLATION, id), ProgressPart.TRANSLATION,
            if (task == SubtitleTask.POLISH) ProgressPhase.WAITING_POLISH else ProgressPhase.WAITING_TRANSLATION)
        try {
            if (db.translations().get(id)?.status != DownloadStatus.QUEUED) return false
            return execution.run { executeRow(id, row, task, expected, report) }
        } catch (cancelled: CancellationException) {
            val reason = coroutineContext.cancellationCause() ?: cancelled
            abandon(id, (reason as? SubtitleTaskStopped)?.persistedError ?: PersistedErrors.CANCELLED, token)
            throw cancelled
        } catch (error: Exception) {
            val stopped = coroutineContext.cancellationCause() as? SubtitleTaskStopped
            val fallback = TextRef.Res(R.string.subtitle_task_failed, listOf(TextRef.Res(task.labelRes)))
            abandon(id, stopped?.persistedError ?: appContext.resolveNoUrl(error.userTextOr(fallback), fallback), token)
            return false
        } finally {
            withContext(NonCancellable) {
                try {
                    progress.end(report)
                } finally {
                    withSubmissionLock {
                        runners.remove(id, self)
                        attempts.remove(id, token)
                    }
                }
            }
        }
    }

    private fun noticeDuplicate() = notice(appContext.getString(R.string.translate_duplicate_notice))

    private suspend fun executeRow(id: Long, row: QueuedTrack, task: SubtitleTask, expected: LoadedSubtitles?, report: TaskReporter): Boolean {
        if (db.translations().startQueued(id) == 0) return false
        val job = db.translations().get(id) ?: return false
        val config = credentials.translateConfig()
        report.metadata(config.model, appContext.getString(config.platform.labelRes))
        report.phase(ProgressPhase.PREPARING)
        if (config.token.isNullOrBlank()) throw UserFacingException(TextRef.Res(R.string.translate_config_required))
        val loaded = when {
            job.targetKind == SubtitleTranslationTarget.OVERLAY -> subtitles.loadNonGenerated(row)
            job.generatedFingerprint != null ->
                subtitles.loadGenerated(job.generatedFingerprint) ?: throw UserFacingException(TextRef.Res(R.string.translate_source_missing_short))
            job.sourceFingerprint != null -> subtitles.loadNonGenerated(row)
            else -> subtitles.load(row)
        }
        val inputFingerprint = loaded.inputFingerprint(task)
        if ((expected != null && !expected.matchesInput(loaded, task)) ||
            (job.sourceFingerprint != null && job.sourceFingerprint != inputFingerprint)
        ) throw UserFacingException(TextRef.Res(R.string.translate_updated_restart))
        val fingerprint = loaded.generatedFingerprint
        val target = if (fingerprint != null) SubtitleTranslationTarget.GENERATED else SubtitleTranslationTarget.OVERLAY
        val filename = outputFilename(row, loaded)
        if ((job.targetKind != null && job.targetKind != target) ||
            (job.outputFilename != null && job.outputFilename != filename)
        ) throw UserFacingException(TextRef.Res(R.string.translate_updated_restart))
        if (job.sourceFingerprint == null || job.targetKind == null || job.outputFilename == null) {
            val captured = withSubmissionLock {
                db.withTransaction {
                    if (fingerprint != null && (db.whisper().activeTranslation(fingerprint) != null ||
                            db.translations().active().any { it.id != id && it.generatedFingerprint == fingerprint })
                    ) return@withTransaction false
                    db.translations().setTarget(id, inputFingerprint, target, filename, fingerprint) > 0
                }
            }
            if (!captured) throw UserFacingException(TextRef.Res(R.string.translate_duplicate_notice))
        }
        val ticket = calls.prepare("row-$id", report)
        val lines = calls.cancelWithCoroutine(ticket) {
            when (task) {
                SubtitleTask.POLISH -> {
                    if (!loaded.unshiftedCues.hasTranslation()) throw UserFacingException(TextRef.Res(R.string.translate_no_polish_text))
                    val polished = polishAll(loaded.unshiftedCues, ticket, config)
                    polished.cues ?: throw UserFacingException(polished.detail)
                }
                else -> {
                    if (!loaded.canTranslate) throw UserFacingException(TextRef.Res(R.string.translate_no_text))
                    val translated = translateAll(loaded.sourceCues, loaded.language, MergeLimits.forModel(loaded.origin?.model), ticket, config)
                    finishWithPolish(translated.cues ?: throw UserFacingException(translated.detail), ticket, config) {
                        translationActive(id)
                    }
                }
            }
        }
        if (!translationActive(id)) throw CancellationException()
        report.phase(ProgressPhase.SAVING_TRANSLATION)
        if (!subtitles.saveTranslations(row, lines, loaded, id, fingerprint, target, filename)) {
            abandon(id, appContext.getString(R.string.translate_save_failed))
            return false
        }
        revision.update { it + 1 }
        return true
    }

    private suspend fun translationActive(id: Long): Boolean {
        coroutineContext.ensureActive()
        return db.translations().get(id)?.status == DownloadStatus.RUNNING
    }

    private suspend fun abandon(id: Long, message: String, expectedToken: Long? = null): Boolean =
        withContext(NonCancellable + Dispatchers.IO) {
            val changed = subtitles.generatedWriteLock.withLock {
                submissions.withLock {
                    if (expectedToken != null && attempts[id] != expectedToken) return@withLock false
                    if (db.translations().failIfActive(id, message) > 0) {
                        if (expectedToken != null) attempts.remove(id, expectedToken) else attempts.remove(id)
                        true
                    } else {
                        false
                    }
                }
            }
            if (changed) progress.finishFromDb(TaskKey(TaskKind.TRANSLATION, id), ProgressPart.TRANSLATION, true)
            changed
        }

    private fun newJob(task: SubtitleTask, row: QueuedTrack, fingerprint: String?, expected: LoadedSubtitles?) =
        TranslationJobEntity(
            title = row.title.ifBlank { appContext.getString(R.string.translate_audio_label) },
            trackId = row.trackId?.takeIf { it > 0L },
            remoteUrl = row.jaAudioIdentity() ?: row.remoteUrl?.takeUnless { row.source == TrackSource.JAPANESE_ASMR }?.takeIf { it.isNotBlank() },
            queueItemId = row.queueItemId,
            status = DownloadStatus.QUEUED,
            error = null,
            kind = task.name,
            generatedFingerprint = fingerprint,
            sourceFingerprint = expected?.inputFingerprint(task),
            bvid = row.bvid ?: row.workno.takeIf { row.source == TrackSource.BILIBILI },
            cid = row.cid ?: row.fileKey?.toLongOrNull()?.takeIf { row.source == TrackSource.BILIBILI },
            localUri = row.localUri,
            createdAt = System.currentTimeMillis(),
            targetKind = expected?.let { if (it.generated) SubtitleTranslationTarget.GENERATED else SubtitleTranslationTarget.OVERLAY },
            outputFilename = expected?.let { outputFilename(row, it) },
        )

    private fun outputFilename(row: QueuedTrack, loaded: LoadedSubtitles): String =
        loaded.generatedFingerprint?.let { subtitles.generatedFile(it).name }
            ?: subtitles.expectedOverlayFile(row, loaded.sourceCues).name

    private suspend fun <T> withSubmissionLock(block: suspend () -> T): T =
        subtitles.generatedWriteLock.withLock { submissions.withLock { block() } }

    private suspend fun restoreRow(job: TranslationJobEntity): QueuedTrack? {
        val queue = db.queue().snapshot().firstOrNull { row ->
            if (row.queueItemId != job.queueItemId) return@firstOrNull false
            job.matches(row, subtitles.generatedFingerprint(row))
        }
        if (queue != null) return queue
        val track = job.trackId?.let { db.tracks().getById(it) }
        if (track == null && job.generatedFingerprint == null && (job.bvid == null || job.cid == null)) return null
        return QueuedTrack(
            queueItemId = job.queueItemId ?: 0L, position = 0, trackId = track?.id ?: job.trackId,
            source = track?.source ?: if (job.bvid != null) TrackSource.BILIBILI else TrackSource.LOCAL,
            title = job.title, artist = track?.artist, durationMs = track?.durationMs ?: 0L,
            coverUri = track?.coverUri, localUri = track?.localUri ?: job.localUri, bvid = job.bvid ?: track?.bvid,
            cid = job.cid ?: track?.cid, page = track?.page, remoteUrl = job.remoteUrl,
            referer = null, workno = job.bvid, fileKey = job.cid?.toString(),
        )
    }

    private fun readGenerated(fingerprint: String): GeneratedSubtitles? {
        val file = generatedFile(fingerprint)
        if (file.name == ".json" || !file.isFile) return null
        return parseGeneratedSubtitles(file.readText())
    }

    private fun generatedFile(fingerprint: String): File {
        val safe = fingerprint.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }
        return File(File(appContext.filesDir, "subtitles"), "$safe.json")
    }

    private suspend fun finishWithPolish(
        cues: List<SubtitleCue>,
        ticket: InflightCalls.Ticket,
        config: TranslateConfig,
        stillActive: suspend () -> Boolean,
    ): List<SubtitleCue> {
        if (!credentials.translateAutoPolish()) return cues
        if (!stillActive()) throw CancellationException()
        val polished = polishAll(cues, ticket, config).cues ?: run { ticket.progress?.polishFallback(); return cues }
        if (!stillActive()) throw CancellationException()
        return polished
    }

    private fun polishAll(
        cues: List<SubtitleCue>,
        ticket: InflightCalls.Ticket,
        config: TranslateConfig,
    ): TranslatedCues {
        val updated = cues.toMutableList()
        val indexed = cues.mapIndexedNotNull { index, cue ->
            val translation = cue.translation?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
            index to (cue.text to translation)
        }
        if (indexed.isEmpty()) return TranslatedCues(cues)
        val batches = indexed.chunked(BATCH)
        ticket.progress?.phase(ProgressPhase.POLISHING)
        ticket.progress?.units(0, indexed.size.toLong())
        var processed = 0L
        batches.forEachIndexed { batchIndex, batch ->
            ticket.progress?.batch(batchIndex + 1, batches.size)
            val reply = client.polish(batch.map { it.second }, ticket, config)
            val lines = reply.lines
            if (lines == null || lines.size != batch.size) return TranslatedCues(detail = failureDetail(reply.error, reply.raw, reply.diagnostic))
            batch.forEachIndexed { offset, (index, _) ->
                val cue = cues[index]
                val line = lines[offset]
                updated[index] = cue.copy(translation = line)
            }
            processed += batch.size
            ticket.progress?.units(processed, indexed.size.toLong())
        }
        return TranslatedCues(updated)
    }

    private fun translateAll(
        cues: List<SubtitleCue>,
        language: String?,
        limits: MergeLimits,
        ticket: InflightCalls.Ticket,
        config: TranslateConfig,
    ): TranslatedCues {
        val updated = mutableListOf<SubtitleCue>()
        val options = if (usesJapaneseCorrection(cues, language)) {
            JapaneseTranslateOptions(
                proofread = credentials.translateJapaneseProofread(),
                merge = credentials.translateJapaneseMerge(),
            )
        } else {
            JapaneseTranslateOptions()
        }
        val structured = options.usesStructuredProtocol
        val autoCorrect = options.merge && credentials.translateAutoCorrect()
        val batches = cues.chunked(if (structured) JAPANESE_BATCH else BATCH)
        ticket.progress?.phase(ProgressPhase.TRANSLATING)
        ticket.progress?.units(0, cues.size.toLong())
        var processed = 0L
        for ((batchIndex, batch) in batches.withIndex()) {
            ticket.progress?.phase(ProgressPhase.TRANSLATING)
            ticket.progress?.batch(batchIndex + 1, batches.size)
            if (structured) {
                // 每批最多 2 次请求：首次 + 一次后续（结构损坏盲目重试，或软违规定向调优）。
                // 同 ticket 连续请求安全（前一个 call 已 end）；取消以异常传播，不会误入后续请求。
                val first = client.translateJapanese(batch, limits, ticket, options, config)
                val delivered = if (first.cues == null) {
                    ticket.progress?.retry()
                    val retry = client.translateJapanese(batch, limits, ticket, options, config)
                    retry.cues ?: return TranslatedCues(detail = failureDetail(retry.error, retry.raw, retry.diagnostic))
                } else if (first.violations.isNotEmpty() && autoCorrect) {
                    // 调优只能改善交付：结构损坏时回退首次结果，软限制违规直接交付。
                    ticket.progress?.retry()
                    ticket.progress?.phase(ProgressPhase.CORRECTING)
                    val corrected = client.correctJapanese(batch, limits, first.raw.orEmpty(), first.violations, ticket, options, config)
                    corrected.cues ?: first.cues
                } else {
                    first.cues
                }
                updated += delivered
            } else {
                val reply = client.translate(batch.map { it.text }, ticket, config)
                val lines = reply.lines ?: return TranslatedCues(detail = failureDetail(reply.error, reply.raw, reply.diagnostic))
                updated += batch.zip(lines) { cue, line -> cue.copy(translation = line) }
            }
            processed += batch.size
            ticket.progress?.units(processed, cues.size.toLong())
        }
        return TranslatedCues(cues = updated)
    }

    private fun failureDetail(error: TextRef?, raw: String?, diagnostic: TextRef?): TextRef =
        translationFailureDetail(error, raw, appContext.getString(R.string.translate_error_url_redacted), diagnostic)

    private companion object {
        const val BATCH = 40
        const val JAPANESE_BATCH = 20
    }
}

private class TranslatedCues(
    val cues: List<SubtitleCue>? = null,
    val detail: TextRef = TextRef.Res(R.string.translate_status_failed),
)


internal val TranslationJobEntity.operation: SubtitleTask
    get() = if (kind == SubtitleTask.POLISH.name) SubtitleTask.POLISH else SubtitleTask.TRANSLATE

internal fun TranslationJobEntity.matches(row: QueuedTrack, fingerprint: String?): Boolean = when {
    generatedFingerprint != null && fingerprint != null -> generatedFingerprint == fingerprint
    bvid != null && cid != null -> row.source == TrackSource.BILIBILI &&
        bvid == (row.bvid ?: row.workno) && cid == (row.cid ?: row.fileKey?.toLongOrNull())
    remoteUrl?.startsWith("japaneseasmr://audio?") == true -> remoteUrl == row.jaAudioIdentity()
    trackId != null -> trackId == row.trackId
    remoteUrl != null -> remoteUrl == row.remoteUrl
    localUri != null -> localUri == row.localUri
    else -> queueItemId == row.queueItemId
}

private fun TranslationJobEntity.sameTarget(other: TranslationJobEntity): Boolean = when {
    generatedFingerprint != null || other.generatedFingerprint != null ->
        generatedFingerprint != null && generatedFingerprint == other.generatedFingerprint
    bvid != null && cid != null -> bvid == other.bvid && cid == other.cid
    trackId != null -> trackId == other.trackId
    remoteUrl != null -> remoteUrl == other.remoteUrl
    localUri != null -> localUri == other.localUri
    else -> queueItemId == other.queueItemId
}

private fun LoadedSubtitles.inputFingerprint(task: SubtitleTask): String =
    if (task == SubtitleTask.POLISH) subtitleSourceFingerprint(unshiftedCues, includeTranslation = true)
    else subtitleSourceFingerprint(sourceCues)

private fun LoadedSubtitles.matchesInput(other: LoadedSubtitles, task: SubtitleTask): Boolean =
    generated == other.generated && generatedFingerprint == other.generatedFingerprint && origin == other.origin &&
        inputFingerprint(task) == other.inputFingerprint(task)
