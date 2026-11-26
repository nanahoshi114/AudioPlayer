package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.WhisperJobEntity
import com.nanahoshi.audioplayer.playback.SubtitleCue
import com.nanahoshi.audioplayer.playback.SubtitleOrigin
import com.nanahoshi.audioplayer.playback.encodeGeneratedCues
import com.nanahoshi.audioplayer.playback.parseGeneratedSubtitles
import com.nanahoshi.audioplayer.playback.writeTextAtomically
import com.nanahoshi.audioplayer.text.PersistedErrors
import com.nanahoshi.audioplayer.text.resolveUserError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/** 单个听写／翻译任务的执行模板与字幕发布。 */
internal class WhisperJobRunner(private val center: WhisperCenter) {
    private val db get() = center.db

    internal suspend fun runWork(work: WhisperCenter.SubtitleWork) {
        val id = work.job.id
        val self = coroutineContext[kotlinx.coroutines.Job]!!
        val registry = if (work.translate) center.translationRunners else center.runners
        val part = if (work.translate) ProgressPart.TRANSLATION else ProgressPart.TRANSCRIPTION
        val (job, attempt) = center.subtitles.generatedWriteLock.withLock {
            val current = db.whisper().get(id) ?: return
            val eligible = if (work.translate) current.translateStatus == com.nanahoshi.audioplayer.data.db.TranslateMark.QUEUED else {
                (current.status == DownloadStatus.QUEUED || current.status == DownloadStatus.RUNNING) &&
                    current.fingerprint.isNotBlank() && current.localPath != null
            }
            if (!eligible || registry.containsKey(id)) return
            center.pendingHandoff.remove(id)
            val token = if (work.translate) center.activeTranslationAttempt.getOrPut(id) { center.attemptCounter.incrementAndGet() } else null
            registry[id] = self
            current to token
        }
        try {
            if (attempt != null) translateWritten(job, attempt) else runJob(job)
        } catch (cancelled: CancellationException) {
            val reason = coroutineContext.cancellationCause() ?: cancelled
            val message = (reason as? SubtitleTaskStopped)?.persistedError ?: PersistedErrors.CANCELLED
            withContext(NonCancellable) {
                center.subtitles.generatedWriteLock.withLock { abandonIfNeeded(id, attempt, message) }
            }
            throw cancelled
        } catch (_: Exception) {
            center.subtitles.generatedWriteLock.withLock {
                abandonIfNeeded(id, attempt, center.appContext.getString(
                    if (work.translate) R.string.whisper_translate_failed else R.string.whisper_generate_failed,
                ))
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    center.subtitles.generatedWriteLock.withLock {
                        if (attempt == null || center.activeTranslationAttempt[id] == attempt) {
                            center.progress.finishFromDb(TaskKey(TaskKind.WHISPER, id), part)
                        }
                    }
                    center.stagingOps.sweepStaged()
                } finally {
                    center.subtitles.generatedWriteLock.withLock {
                        registry.remove(id, self)
                        if (attempt != null) center.activeTranslationAttempt.remove(id, attempt)
                    }
                }
            }
        }
    }

    private suspend fun stillActive(id: Long): Boolean {
        coroutineContext.ensureActive()
        val current = db.whisper().get(id) ?: return false
        return current.status == DownloadStatus.QUEUED || current.status == DownloadStatus.RUNNING
    }

    private suspend fun abandonIfNeeded(id: Long, attempt: Long?, message: String) {
        if (attempt != null) {
            if (center.activeTranslationAttempt[id] == attempt) db.whisper().failTranslateIfUnpublished(id, message)
        } else {
            val current = db.whisper().get(id) ?: return
            if (current.status == DownloadStatus.QUEUED || current.status == DownloadStatus.RUNNING) db.whisper().fail(id, message)
        }
    }

    private suspend fun runJob(job: WhisperJobEntity) {
        if (!stillActive(job.id)) return
        val report = center.progress.begin(TaskKey(TaskKind.WHISPER, job.id), ProgressPart.TRANSCRIPTION,
            model = center.jobCredentialModel(job)?.let { whisperModelLabel(center.appContext, it) })
        center.reporters[job.id] = report
        try {
            val ticket = center.calls.prepare("job-${job.id}", report)
            center.calls.cancelWithCoroutine(ticket) { runJob(job, ticket) }
        } finally {
            center.reporters.remove(job.id, report)
            withContext(NonCancellable) { center.progress.end(report) }
        }
    }

    private suspend fun runJob(job: WhisperJobEntity, ticket: InflightCalls.Ticket) {
        if (!stillActive(job.id)) return
        if (finishFromExisting(job)) {
            ticket.progress?.reused()
            finishWritten(job)
            return
        }
        val modelId = center.jobCredentialModel(job)
        val resumingUpload = modelId == WHISPER_MODEL_QWEN && job.remoteJobId != null
        if (resumingUpload) ticket.progress?.resumedRemote()
        val file = job.localPath?.let(::File)
        if (file == null || (!file.isFile && !resumingUpload)) {
            db.whisper().fail(job.id, center.appContext.getString(R.string.whisper_audio_missing_download))
            return
        }
        val base = effectiveWhisperBase(modelId, job.endpoint ?: center.credentials.whisperBaseUrl(modelId))
        when (modelId) {
            WHISPER_MODEL_QWEN -> runQwen(job, file, base, ticket)
            WHISPER_MODEL_QWEN3 -> {
                if (base.isNullOrBlank()) {
                    db.whisper().fail(job.id, center.appContext.getString(R.string.whisper_error_missing_base))
                    return
                }
                runQwen3(job, file, whisperTranscriptionBase(base), ticket)
            }
            SENSE_VOICE_MODEL -> runOffline(job, file, center.offlineModels.getValue(OfflineWhisperModel.SenseVoice))
            PARAKEET_MODEL -> runOffline(job, file, center.offlineModels.getValue(OfflineWhisperModel.Parakeet))
            else -> db.whisper().fail(job.id, center.appContext.getString(R.string.whisper_model_removed))
        }
    }

    private suspend fun runQwen3(job: WhisperJobEntity, file: File, base: String, ticket: InflightCalls.Ticket) {
        try {
            if (db.whisper().markRunningIfActive(job.id, 0f) == 0) return
            val cues = center.client.transcribeQwen3(file, base, audioDurationMs(job, file), ticket, center.jobCredentialModel(job))
            if (!stillActive(job.id)) return
            publishCues(job, cues, base)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (!stillActive(job.id)) return
            db.whisper().fail(job.id, center.appContext.resolveUserError(error, R.string.whisper_error_failed))
        }
    }

    private suspend fun runOffline(job: WhisperJobEntity, file: File, manager: OfflineWhisperModelManager) {
        val report = center.reporters[job.id]
        var nativeStage: TransferProgress? = null
        try {
            if (db.whisper().markRunningIfActive(job.id, 0f) == 0) return
            report?.phase(ProgressPhase.LOADING_MODEL)
            val cues = center.transcriber.transcribe(manager, file, audioDurationMs(job, file)) { progress ->
                if (nativeStage == null) nativeStage = report?.phase(ProgressPhase.TRANSCRIBING)
                nativeStage?.units(progress.processedMs, progress.totalMs, ProgressUnit.AUDIO_MS, progress.detail)
                if (stillActive(job.id)) db.whisper().setProgress(job.id,
                    progress.totalMs?.takeIf { it > 0 }?.let { (progress.processedMs.toFloat() / it).coerceIn(0f, 0.99f) } ?: 0f)
            }
            if (!stillActive(job.id)) return
            publishCues(job, cues, null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (!stillActive(job.id)) return
            db.whisper().fail(job.id, center.appContext.resolveUserError(error, R.string.whisper_error_failed))
        }
    }

    private suspend fun audioDurationMs(job: WhisperJobEntity, file: File): Long {
        val stored = job.trackId?.takeIf { it > 0L }?.let { db.tracks().getById(it)?.durationMs }?.takeIf { it > 0L }
        return stored ?: fileDurationMs(file)
    }

    private fun fileDurationMs(file: File): Long = LocalAudio.readDurationMs(file.absolutePath) ?: 0L

    private suspend fun runQwen(
        job: WhisperJobEntity,
        file: File,
        base: String?,
        ticket: InflightCalls.Ticket,
    ) {
        try {
            if (base.isNullOrBlank()) {
                db.whisper().fail(job.id, center.appContext.getString(R.string.whisper_error_missing_base))
                return
            }
            val token = center.credentials.whisperToken(center.jobCredentialModel(job))
            if (token.isNullOrBlank()) {
                db.whisper().fail(job.id, center.appContext.getString(R.string.whisper_error_missing_key))
                return
            }
            if (db.whisper().markRunningIfActive(job.id, 0f) == 0) return
            val cues = center.qwenFile.transcribe(file, base, ticket, token, job.remoteJobId, job.remoteSubmittedAt) { id, at ->
                withContext(NonCancellable) { db.whisper().setRemoteId(job.id, id, at) }
            }
            if (!stillActive(job.id)) return
            publishCues(job, cues, base)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (!stillActive(job.id)) return
            db.whisper().fail(job.id, center.appContext.resolveUserError(error, R.string.whisper_error_failed))
        }
    }

    private suspend fun finishFromExisting(job: WhisperJobEntity): Boolean {
        if (job.forceRegenerate == true) return false
        val requested = knownWhisperModel(job.model) ?: return false
        val latestDone = db.whisper().forFingerprint(job.fingerprint).lastOrNull {
            it.id != job.id && it.status == DownloadStatus.DONE
        } ?: return false
        val endpoint = effectiveWhisperBase(requested, job.endpoint ?: center.credentials.whisperBaseUrl(requested))
        return canReuseCues(job.fingerprint, requested, endpoint, latestDone)
    }

    internal fun canReuseCues(
        fingerprint: String,
        model: String,
        endpoint: String?,
        latestDone: WhisperJobEntity,
    ): Boolean = runCatching {
        val document = parseGeneratedSubtitles(cueFile(fingerprint).readText()) ?: return false
        generatedSubtitlesMatch(document, model, endpoint, latestDone)
    }.getOrDefault(false)

    private suspend fun finishWritten(job: WhisperJobEntity) {
        center.subtitles.generatedWriteLock.withLock { finishTranscription(job) }
        center.kick.trySend(Unit)
    }

    /** 在字幕写入锁内登记识别完成，随后在锁外执行网络翻译。 */
    private suspend fun finishTranscription(job: WhisperJobEntity): Boolean {
        if (!stillActive(job.id)) return false
        // 用户在这次生成之前关闭或改选过字幕时，新结果应重新可见；请求之后的手动选择优先。
        // 关联写入失败不应连累已经保存的字幕，任务照常完成。
        val switched = try {
            job.trackId?.takeIf { it > 0L }
                ?.let { center.subtitleBindings?.bindGeneratedIfOlder(it, job.createdAt) == true } == true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        val queued = if (job.skipTranslate == true || !center.translator.readyToTranslate(job.fingerprint)) {
            db.whisper().finishIfActive(job.id)
            false
        } else {
            center.translator.queueGenerated(job.id, job.fingerprint)
        }
        // publishCues 自己会刷新；复用/续跑路径（finishWritten）只能靠这里通知界面。
        if (switched) center.subtitles.invalidate()
        return queued
    }

    private suspend fun translateWritten(job: WhisperJobEntity, attempt: Long) {
        val failure = center.translator.applyGenerated(job.id, job.fingerprint)
        if (failure == null) return
        center.subtitles.generatedWriteLock.withLock {
            if (center.activeTranslationAttempt[job.id] == attempt) db.whisper().failTranslateIfUnpublished(job.id, failure)
        }
    }

    private suspend fun publishCues(job: WhisperJobEntity, cues: List<WhisperCue>, endpoint: String?) {
        val model = requireNotNull(center.jobCredentialModel(job))
        val origin = SubtitleOrigin(model, canonicalWhisperEndpoint(endpoint, model))
        val payload = encodeGeneratedCues(cues.map { SubtitleCue(it.startMs, it.endMs, it.text) }, origin)
        center.subtitles.generatedWriteLock.withLock {
            if (!stillActive(job.id)) return
            center.reporters[job.id]?.subtitles(cues.size)
            center.reporters[job.id]?.phase(ProgressPhase.SAVING_SUBTITLES)
            withContext(NonCancellable) {
                cueFile(job.fingerprint).writeTextAtomically(payload)
                finishTranscription(job)
                center.subtitles.invalidate()
            }
        }
        center.kick.trySend(Unit)
    }

    private fun cueFile(fingerprint: String): File {
        val safe = fingerprint.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }
        return File(File(center.appContext.filesDir, "subtitles"), "$safe.json")
    }
}