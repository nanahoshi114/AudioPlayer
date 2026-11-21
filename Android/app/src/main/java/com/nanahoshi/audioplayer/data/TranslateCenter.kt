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
import com.nanahoshi.audioplayer.playback.SubtitleCue
import com.nanahoshi.audioplayer.playback.GeneratedSubtitles
import com.nanahoshi.audioplayer.playback.LoadedSubtitles
import com.nanahoshi.audioplayer.playback.encodeGeneratedCues
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
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class TranslateCenter internal constructor(
    context: android.content.Context,
    private val db: AppDatabase,
    private val credentials: CredentialStore,
    private val subtitles: com.nanahoshi.audioplayer.playback.SubtitleSource,
    private val requests: ForegroundTaskRequests<SubtitleTask>,
    private val recovery: Job,
    internal val progress: TaskProgressCenter = TaskProgressCenter(db),
    private val notice: (String) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val calls = InflightCalls()
    private val client = TranslateClient(credentials, calls)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal suspend fun close() {
        scope.coroutineContext[Job]?.cancelAndJoin()
        progress.close()
    }
    private val runners = ConcurrentHashMap<Long, Job>()
    private val submissions = Mutex()
    private val execution = TranslationExecution()
    internal val jobs = db.translations().observe()
    private val revision = MutableStateFlow(0L)
    internal val updates = revision.asStateFlow()

    init { progress.watchSubtitles(recovery) }

    fun readyToTranslate(fingerprint: String): Boolean {
        if (!credentials.hasTranslate()) return false
        val cues = readGenerated(fingerprint)?.displayCues ?: return false
        return cues.isNotEmpty() && !cues.hasTranslation()
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
                report.metadata(credentials.translateModel(), appContext.getString(credentials.translatePlatform().labelRes))
                report.phase(ProgressPhase.TRANSLATING)
                val ticket = calls.prepare("whisper-$jobId", report)
                calls.cancelWithCoroutine(ticket) { applyGenerated(jobId, fingerprint, ticket) }
            }
        } finally {
            withContext(NonCancellable) { progress.end(report) }
        }
    }

    private suspend fun applyGenerated(jobId: Long, fingerprint: String, ticket: InflightCalls.Ticket): String? {
        return try {
            val document = readGenerated(fingerprint) ?: return appContext.getString(R.string.translate_no_japanese)
            val job = db.whisper().get(jobId) ?: throw CancellationException()
            val model = knownWhisperModel(job.model) ?: return appContext.getString(R.string.translate_model_changed)
            if (!generatedSubtitlesMatch(document, model, job.endpoint, job)) return appContext.getString(R.string.translate_subtitle_updated)
            if (!credentials.hasTranslate()) return appContext.getString(R.string.translate_config_required)
            val cues = document.cues
            if (cues.isEmpty() || document.displayCues.hasTranslation()) return appContext.getString(R.string.translate_nothing)
            if (!whisperTranslating(jobId)) throw CancellationException()
            val translated = translateAll(cues, null, MergeLimits.forModel(document.origin?.model), ticket)
            val lines = translated.cues ?: return appContext.resolveNoUrl(translated.detail, R.string.translate_status_failed)
            if (!whisperTranslating(jobId)) throw CancellationException()
            val finished = finishWithPolish(lines, ticket) { whisperTranslating(jobId) }
            if (!whisperTranslating(jobId)) throw CancellationException()
            ticket.progress?.phase(ProgressPhase.SAVING_TRANSLATION)
            if (!commitTranslated(jobId, fingerprint, document, finished)) return appContext.getString(R.string.translate_subtitle_updated)
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
    ): Boolean = subtitles.generatedWriteLock.withLock {
        if (readGenerated(fingerprint) != expected) return@withLock false
        val payload = encodeGeneratedCues(expected.cues, expected.origin, revisedCues = cues)
        subtitles.publishTranslation(generatedFile(fingerprint), payload, jobId, null)
    }

    internal suspend fun submit(
        task: SubtitleTask,
        row: QueuedTrack,
        expected: LoadedSubtitles?,
    ): Boolean {
        // Reserve the foreground start before any disk access. The service waits for the task row.
        val queued = kotlinx.coroutines.CompletableDeferred<Long?>()
        val request = requests.submit(task) { queued.await()?.let { runRow(it, row, task, expected) } ?: false }
        request.result.invokeOnCompletion { cause ->
            if (cause is SubtitleTaskStopped) scope.launch {
                queued.await()?.let { abandon(it, cause.persistedError) }
            }
        }
        try {
            withContext(Dispatchers.IO + NonCancellable) {
                recovery.join()
                submissions.withLock {
                    // expected 非 null 时以其为准（null 即非生成字幕）；仅无快照的 retry 路径才按记录回退。
                    val fingerprint = if (expected != null) expected.generatedFingerprint else subtitles.generatedFingerprint(row)
                    val job = newJob(task, row, fingerprint, expected)
                    if (db.translations().active().any { it.sameTarget(job) } ||
                        fingerprint?.let { db.whisper().activeTranslation(it) != null } == true) {
                        noticeDuplicate()
                        return@withLock
                    }
                    val id = db.translations().insert(job)
                    queued.complete(id)
                }
            }
        } finally {
            queued.complete(null)
        }
        return request.result.await()
    }

    internal suspend fun retry(id: Long, task: SubtitleTask): Boolean {
        val ready = kotlinx.coroutines.CompletableDeferred<Pair<Long, QueuedTrack>?>()
        val request = requests.submit(task) {
            val (nextId, row) = ready.await() ?: return@submit false
            runRow(nextId, row, task, null)
        }
        request.result.invokeOnCompletion { cause ->
            if (cause is SubtitleTaskStopped) scope.launch {
                ready.await()?.let { abandon(it.first, cause.persistedError) }
            }
        }
        try {
            withContext(Dispatchers.IO + NonCancellable) {
                recovery.join()
                submissions.withLock {
                    val job = db.translations().get(id) ?: return@withLock
                    if (job.status != DownloadStatus.FAILED || job.operation != task) return@withLock
                    val row = restoreRow(job) ?: error(appContext.getString(R.string.translate_source_missing))
                    if (db.translations().active().any { it.sameTarget(job) } ||
                        job.generatedFingerprint?.let { db.whisper().activeTranslation(it) != null } == true) {
                        noticeDuplicate()
                        return@withLock
                    }
                    progress.reset(TaskKey(TaskKind.TRANSLATION, id))
                    if (db.translations().requeue(id) > 0) ready.complete(id to row)
                }
            }
        } finally {
            ready.complete(null)
        }
        return request.result.await()
    }

    private suspend fun runRow(id: Long, row: QueuedTrack, task: SubtitleTask, expected: LoadedSubtitles?): Boolean {
        val self = coroutineContext[Job]!!
        runners[id] = self
        val report = progress.begin(TaskKey(TaskKind.TRANSLATION, id), ProgressPart.TRANSLATION,
            if (task == SubtitleTask.POLISH) ProgressPhase.WAITING_POLISH else ProgressPhase.WAITING_TRANSLATION)
        try {
            if (db.translations().get(id)?.status != DownloadStatus.QUEUED) return false
            return execution.run { executeRow(id, row, task, expected, report) }
        } catch (cancelled: CancellationException) {
            val reason = coroutineContext.cancellationCause() ?: cancelled
            abandon(id, (reason as? SubtitleTaskStopped)?.persistedError ?: PersistedErrors.CANCELLED)
            throw cancelled
        } catch (error: Exception) {
            val stopped = coroutineContext.cancellationCause() as? SubtitleTaskStopped
            val fallback = TextRef.Res(R.string.subtitle_task_failed, listOf(TextRef.Res(task.labelRes)))
            abandon(id, stopped?.persistedError ?: appContext.resolveNoUrl(error.userTextOr(fallback), fallback))
            return false
        } finally {
            runners.remove(id, self)
            withContext(NonCancellable) { progress.end(report) }
        }
    }

    private fun noticeDuplicate() = notice(appContext.getString(R.string.translate_duplicate_notice))

    private suspend fun executeRow(id: Long, row: QueuedTrack, task: SubtitleTask, expected: LoadedSubtitles?, report: TaskReporter): Boolean {
        if (db.translations().startQueued(id) == 0) return false
        val job = db.translations().get(id) ?: return false
        report.metadata(credentials.translateModel(), appContext.getString(credentials.translatePlatform().labelRes))
        report.phase(ProgressPhase.PREPARING)
        if (!credentials.hasTranslate()) throw UserFacingException(TextRef.Res(R.string.translate_config_required))
        val loaded = if (job.generatedFingerprint != null) {
            subtitles.loadGenerated(job.generatedFingerprint) ?: throw UserFacingException(TextRef.Res(R.string.translate_source_missing_short))
        } else subtitles.load(row)
        if (expected != null && expected != loaded) throw UserFacingException(TextRef.Res(R.string.translate_updated_restart))
        val inputFingerprint = loaded.inputFingerprint(task)
        if (job.sourceFingerprint != null && job.sourceFingerprint != inputFingerprint) throw UserFacingException(TextRef.Res(R.string.translate_updated_restart))
        if (job.sourceFingerprint == null) db.translations().setSourceFingerprint(id, inputFingerprint)
        val ticket = calls.prepare("row-$id", report)
        val lines = calls.cancelWithCoroutine(ticket) {
            when (task) {
                SubtitleTask.POLISH -> {
                    if (!loaded.cues.hasTranslation()) throw UserFacingException(TextRef.Res(R.string.translate_no_polish_text))
                    val polished = polishAll(loaded.cues, ticket)
                    polished.cues ?: throw UserFacingException(polished.detail)
                }
                else -> {
                    if (!loaded.canTranslate) throw UserFacingException(TextRef.Res(R.string.translate_no_text))
                    val translated = translateAll(loaded.sourceCues, loaded.language, MergeLimits.forModel(loaded.origin?.model), ticket)
                    finishWithPolish(translated.cues ?: throw UserFacingException(translated.detail), ticket) {
                        translationActive(id)
                    }
                }
            }
        }
        if (!translationActive(id)) throw CancellationException()
        report.phase(ProgressPhase.SAVING_TRANSLATION)
        if (!subtitles.saveTranslations(row, lines, loaded, id, job.generatedFingerprint)) {
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

    private suspend fun abandon(id: Long, message: String): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        val changed = subtitles.generatedWriteLock.withLock { db.translations().failIfActive(id, message) > 0 }
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
        )

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
        stillActive: suspend () -> Boolean,
    ): List<SubtitleCue> {
        if (!credentials.translateAutoPolish()) return cues
        if (!stillActive()) throw CancellationException()
        val polished = polishAll(cues, ticket).cues ?: run { ticket.progress?.polishFallback(); return cues }
        if (!stillActive()) throw CancellationException()
        return polished
    }

    private fun polishAll(cues: List<SubtitleCue>, ticket: InflightCalls.Ticket): TranslatedCues {
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
            val reply = client.polish(batch.map { it.second }, ticket)
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
    ): TranslatedCues {
        val updated = mutableListOf<SubtitleCue>()
        val correctJapanese = usesJapaneseCorrection(cues, language)
        val autoCorrect = credentials.translateAutoCorrect()
        val batches = cues.chunked(if (correctJapanese) JAPANESE_BATCH else BATCH)
        ticket.progress?.phase(ProgressPhase.TRANSLATING)
        ticket.progress?.units(0, cues.size.toLong())
        var processed = 0L
        for ((batchIndex, batch) in batches.withIndex()) {
            ticket.progress?.phase(ProgressPhase.TRANSLATING)
            ticket.progress?.batch(batchIndex + 1, batches.size)
            if (correctJapanese) {
                // 每批最多 2 次请求：首次 + 一次后续（结构损坏盲目重试，或软违规定向调优）。
                // 同 ticket 连续请求安全（前一个 call 已 end）；取消以异常传播，不会误入后续请求。
                val first = client.translateJapanese(batch, limits, ticket)
                val delivered = if (first.cues == null) {
                    ticket.progress?.retry()
                    val retry = client.translateJapanese(batch, limits, ticket)
                    retry.cues ?: return TranslatedCues(detail = failureDetail(retry.error, retry.raw, retry.diagnostic))
                } else if (first.violations.isNotEmpty() && autoCorrect) {
                    // 调优只能改善交付：结构损坏时回退首次结果，软限制违规直接交付。
                    ticket.progress?.retry()
                    ticket.progress?.phase(ProgressPhase.CORRECTING)
                    val corrected = client.correctJapanese(batch, limits, first.raw.orEmpty(), first.violations, ticket)
                    corrected.cues ?: first.cues
                } else {
                    first.cues
                }
                updated += delivered
            } else {
                val reply = client.translate(batch.map { it.text }, ticket)
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
    if (task == SubtitleTask.POLISH) {
        val bytes = encodeGeneratedCues(cues).toByteArray()
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    } else subtitleSourceFingerprint(sourceCues)
