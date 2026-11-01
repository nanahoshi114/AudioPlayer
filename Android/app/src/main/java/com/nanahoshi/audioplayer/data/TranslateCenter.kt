package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import com.nanahoshi.audioplayer.data.db.TranslateMark
import com.nanahoshi.audioplayer.data.db.TranslationJobEntity
import com.nanahoshi.audioplayer.playback.SubtitleCue
import com.nanahoshi.audioplayer.playback.encodeGeneratedCues
import com.nanahoshi.audioplayer.playback.hasTranslation
import com.nanahoshi.audioplayer.playback.parseGeneratedCues
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class TranslateCenter(
    context: android.content.Context,
    private val db: AppDatabase,
    private val credentials: CredentialStore,
    private val subtitles: com.nanahoshi.audioplayer.playback.SubtitleSource,
) {
    private val appContext = context.applicationContext
    private val calls = InflightCalls()
    private val client = TranslateClient(credentials, calls)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runners = ConcurrentHashMap<Long, Job>()
    private val inflight = ConcurrentHashMap<String, Deferred<Boolean>>()
    private val polishInflight = ConcurrentHashMap<String, Deferred<Boolean>>()
    private val updatesFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val updates: SharedFlow<Unit> = updatesFlow.asSharedFlow()

    fun readyToTranslate(fingerprint: String): Boolean {
        if (!credentials.hasTranslate()) return false
        val cues = readGenerated(fingerprint) ?: return false
        return cues.isNotEmpty() && !cues.hasTranslation()
    }

    fun stopForWhisper(jobId: Long) {
        calls.cancel("whisper-$jobId")
    }

    fun cancel(id: Long) {
        calls.cancel("row-$id")
        runners[id]?.cancel()
        scope.launch { abandon(id) }
    }

    fun cancelRow(row: QueuedTrack) {
        scope.launch {
            val existing = findJob(row) ?: return@launch
            if (existing.status != DownloadStatus.RUNNING && existing.status != DownloadStatus.QUEUED) return@launch
            cancel(existing.id)
        }
    }

    fun cancelPolish(row: QueuedTrack) {
        calls.cancel("polish-${rowKey(row)}")
    }

    suspend fun polishRow(row: QueuedTrack): Boolean {
        val key = rowKey(row)
        while (true) {
            val current = polishInflight[key]
            if (current != null && current.isActive) return current.await()
            lateinit var created: Deferred<Boolean>
            created = scope.async(start = CoroutineStart.LAZY) {
                try {
                    runPolish(row)
                } finally {
                    polishInflight.remove(key, created)
                }
            }
            if (polishInflight.putIfAbsent(key, created) == null) {
                created.start()
                return created.await()
            }
            created.cancel()
        }
    }

    suspend fun applyGenerated(jobId: Long, fingerprint: String): String? {
        val ticket = calls.prepare("whisper-$jobId")
        return try {
            val cues = readGenerated(fingerprint) ?: return "没有读到日文字幕"
            if (cues.isEmpty() || cues.hasTranslation()) return "没有需要翻译的日文"
            if (db.whisper().get(jobId)?.translateStatus != TranslateMark.RUNNING) throw CancellationException()
            val translated = translateAll(cues, ticket)
            val lines = translated.cues ?: return translated.detail
            if (db.whisper().get(jobId)?.translateStatus != TranslateMark.RUNNING) throw CancellationException()
            generatedFile(fingerprint).writeText(encodeGeneratedCues(lines))
            val finished = finishWithPolish(lines, ticket) {
                db.whisper().get(jobId)?.translateStatus == TranslateMark.RUNNING
            }
            if (finished !== lines) {
                if (db.whisper().get(jobId)?.translateStatus != TranslateMark.RUNNING) throw CancellationException()
                generatedFile(fingerprint).writeText(encodeGeneratedCues(finished))
            }
            if (db.whisper().markTranslatedIfRunning(jobId, System.currentTimeMillis()) == 0) {
                throw CancellationException()
            }
            updatesFlow.tryEmit(Unit)
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            error.message ?: "翻译失败"
        }
    }

    suspend fun translateRow(row: QueuedTrack): Boolean {
        val key = rowKey(row)
        while (true) {
            val current = inflight[key]
            if (current != null && current.isActive) return current.await()
            lateinit var created: Deferred<Boolean>
            created = scope.async(start = CoroutineStart.LAZY) {
                try {
                    runRow(row)
                } finally {
                    inflight.remove(key, created)
                }
            }
            if (inflight.putIfAbsent(key, created) == null) {
                created.start()
                return created.await()
            }
            created.cancel()
        }
    }

    private suspend fun runRow(row: QueuedTrack): Boolean {
        if (!credentials.hasTranslate()) return false
        val cues = subtitles.load(row).cues
        if (cues.isEmpty() || cues.hasTranslation()) return false
        val jobId = openJob(row)
        if (!translationActive(jobId)) throw CancellationException()
        val ticket = calls.prepare("row-$jobId")
        if (!translationActive(jobId)) throw CancellationException()
        val self = coroutineContext[Job]!!
        runners[jobId] = self
        try {
            val translated = translateAll(cues, ticket)
            val lines = translated.cues
            if (lines == null) {
                if (translationActive(jobId)) db.translations().fail(jobId, translated.detail)
                return false
            }
            if (!translationActive(jobId)) throw CancellationException()
            subtitles.saveTranslations(row, lines)
            val finished = finishWithPolish(lines, ticket) { translationActive(jobId) }
            if (finished !== lines) {
                if (!translationActive(jobId)) throw CancellationException()
                subtitles.saveTranslations(row, finished)
            }
            if (db.translations().finishIfActive(jobId) == 0) throw CancellationException()
            updatesFlow.tryEmit(Unit)
            return true
        } catch (cancelled: CancellationException) {
            abandon(jobId)
            throw cancelled
        } catch (error: Exception) {
            if (translationActive(jobId)) db.translations().fail(jobId, error.message ?: "翻译失败")
            return false
        } finally {
            runners.remove(jobId, self)
        }
    }

    private suspend fun findJob(row: QueuedTrack): TranslationJobEntity? {
        val trackId = row.trackId?.takeIf { it > 0L }
        val url = row.remoteUrl?.takeIf { it.isNotBlank() }
        return when {
            trackId != null -> db.translations().latestForTrack(trackId)
            url != null -> db.translations().latestForUrl(url)
            else -> db.translations().latestForQueue(row.queueItemId)
        }
    }

    private suspend fun translationActive(id: Long): Boolean {
        val job = db.translations().get(id) ?: return false
        return job.status == DownloadStatus.QUEUED || job.status == DownloadStatus.RUNNING
    }

    private suspend fun abandon(id: Long) {
        withContext(NonCancellable) {
            if (translationActive(id)) db.translations().fail(id, "已中止")
        }
    }

    private fun rowKey(row: QueuedTrack): String {
        val trackId = row.trackId?.takeIf { it > 0L }
        if (trackId != null) return "track-$trackId"
        val url = row.remoteUrl?.takeIf { it.isNotBlank() }
        if (url != null) return "url-$url"
        return "queue-${row.queueItemId}"
    }

    private suspend fun openJob(row: QueuedTrack): Long {
        val title = row.title.ifBlank { "音频" }
        val trackId = row.trackId?.takeIf { it > 0L }
        val url = row.remoteUrl?.takeIf { it.isNotBlank() }
        val existing = findJob(row)
        if (existing != null) {
            db.translations().markRunning(existing.id, title)
            return existing.id
        }
        return db.translations().insert(
            TranslationJobEntity(
                title = title,
                trackId = trackId,
                remoteUrl = url,
                queueItemId = row.queueItemId,
                status = DownloadStatus.RUNNING,
                error = null,
            ),
        )
    }

    private fun readGenerated(fingerprint: String): List<SubtitleCue>? {
        val file = generatedFile(fingerprint)
        if (file.name == ".json" || !file.isFile) return null
        return parseGeneratedCues(file.readText())
    }

    private fun generatedFile(fingerprint: String): File {
        val safe = fingerprint.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }
        return File(File(appContext.filesDir, "subtitles"), "$safe.json")
    }

    private suspend fun runPolish(row: QueuedTrack): Boolean {
        if (!credentials.hasTranslate()) return false
        val cues = subtitles.load(row).cues
        if (!cues.hasTranslation()) return false
        val ticket = calls.prepare("polish-${rowKey(row)}")
        val polished = polishAll(cues, ticket) ?: return false
        subtitles.saveTranslations(row, polished)
        updatesFlow.tryEmit(Unit)
        return true
    }

    private suspend fun finishWithPolish(
        cues: List<SubtitleCue>,
        ticket: InflightCalls.Ticket,
        stillActive: suspend () -> Boolean,
    ): List<SubtitleCue> {
        if (!credentials.translateAutoPolish()) return cues
        if (!stillActive()) throw CancellationException()
        val polished = polishAll(cues, ticket) ?: return cues
        if (!stillActive()) throw CancellationException()
        return polished
    }

    private fun polishAll(cues: List<SubtitleCue>, ticket: InflightCalls.Ticket): List<SubtitleCue>? {
        val updated = cues.toMutableList()
        val indexed = cues.mapIndexedNotNull { index, cue ->
            val translation = cue.translation?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
            index to (cue.text to translation)
        }
        if (indexed.isEmpty()) return cues
        indexed.chunked(BATCH).forEach { batch ->
            val reply = client.polish(batch.map { it.second }, ticket)
            val lines = reply.lines
            if (lines == null || lines.size != batch.size) return null
            batch.forEachIndexed { offset, (index, _) ->
                updated[index] = cues[index].copy(translation = lines[offset])
            }
        }
        return updated
    }

    private fun translateAll(cues: List<SubtitleCue>, ticket: InflightCalls.Ticket): TranslatedCues {
        val updated = cues.toMutableList()
        cues.indices.chunked(BATCH).forEach { indexes ->
            val reply = client.translate(indexes.map { cues[it].text }, ticket)
            val lines = reply.lines
            if (lines == null || lines.size != indexes.size) {
                return TranslatedCues(detail = reply.raw ?: "翻译失败")
            }
            indexes.forEachIndexed { offset, index ->
                updated[index] = cues[index].copy(translation = lines[offset])
            }
        }
        return TranslatedCues(cues = updated)
    }

    private companion object {
        const val BATCH = 40
    }
}

private class TranslatedCues(val cues: List<SubtitleCue>? = null, val detail: String = "翻译失败")
