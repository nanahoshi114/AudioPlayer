package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.text.TextRef
import androidx.annotation.StringRes
import com.nanahoshi.audioplayer.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class TaskKind { DOWNLOAD, WHISPER, TRANSLATION }
@Serializable
enum class ProgressPart { DOWNLOAD, BILI_SUBTITLE, TRANSCRIPTION, TRANSLATION }
data class TaskKey(val kind: TaskKind, val id: Long, val child: ProgressPart? = null) {
    fun parent() = copy(child = null)
}
@Serializable
enum class ProgressResult(@param:StringRes val labelRes: Int) {
    COMPLETED(R.string.progress_result_completed),
    FAILED(R.string.progress_result_failed),
    CANCELLED(R.string.progress_result_cancelled),
    INTERRUPTED(R.string.progress_result_interrupted),
    REUSED(R.string.progress_result_reused),
    RESUMED_REMOTE(R.string.progress_result_resumed_remote),
    NO_SUBTITLE(R.string.progress_result_no_subtitle),
    NEEDS_LOGIN(R.string.progress_result_needs_login),
    RETRY_LATER(R.string.progress_result_retry_later),
}
@Serializable
enum class ProgressPhase(@param:StringRes val labelRes: Int, val transfer: Boolean = false, val waiting: Boolean = false) {
    WAITING_SLOT(R.string.progress_phase_waiting_slot, waiting = true),
    WAITING_WORK(R.string.progress_phase_waiting_work, waiting = true),
    WAITING_AUDIO(R.string.progress_phase_waiting_audio, waiting = true),
    WAITING_TRANSCRIPTION(R.string.progress_phase_waiting_transcription, waiting = true),
    WAITING_TRANSLATION(R.string.progress_phase_waiting_translation, waiting = true),
    WAITING_POLISH(R.string.progress_phase_waiting_polish, waiting = true),
    WAITING_SUBTITLE(R.string.progress_phase_waiting_subtitle, waiting = true),
    PREPARING(R.string.progress_phase_preparing),
    RESOLVING(R.string.progress_phase_resolving),
    REQUESTING(R.string.progress_phase_requesting),
    WAITING_RESPONSE(R.string.progress_phase_waiting_response),
    DOWNLOADING(R.string.progress_phase_downloading, transfer = true),
    CHECKING(R.string.progress_phase_checking),
    SAVING(R.string.progress_phase_saving),
    IMPORTING(R.string.progress_phase_importing),
    EXTRACTING(R.string.progress_phase_extracting),
    MUXING(R.string.progress_phase_muxing),
    CLEANING(R.string.progress_phase_cleaning),
    UPLOAD_POLICY(R.string.progress_phase_upload_policy),
    MODEL_INFO(R.string.progress_phase_model_info),
    UPLOADING(R.string.progress_phase_uploading, transfer = true),
    SUBMITTING(R.string.progress_phase_submitting),
    REMOTE_PENDING(R.string.progress_phase_remote_pending),
    REMOTE_RUNNING(R.string.progress_phase_remote_running),
    REMOTE_UNKNOWN(R.string.progress_phase_remote_unknown),
    REMOTE_FAILED(R.string.progress_phase_remote_failed),
    RESTORING_REMOTE(R.string.progress_phase_restoring_remote),
    WAITING_TRANSCRIPT(R.string.progress_phase_waiting_transcript),
    FETCHING_RESULT(R.string.progress_phase_fetching_result),
    LOADING_MODEL(R.string.progress_phase_loading_model),
    TRANSCRIBING(R.string.progress_phase_transcribing),
    SAVING_SUBTITLES(R.string.progress_phase_saving_subtitles),
    TRANSLATING(R.string.progress_phase_translating),
    CORRECTING(R.string.progress_phase_correcting),
    POLISHING(R.string.progress_phase_polishing),
    SAVING_TRANSLATION(R.string.progress_phase_saving_translation),
    FETCHING_SUBTITLE(R.string.progress_phase_fetching_subtitle),
    RETRYING(R.string.progress_phase_retrying),
}
@Serializable
enum class ProgressUnit { BYTES, AUDIO_MS, ITEMS }

@Serializable
data class TaskSegmentSummary(
    val phase: ProgressPhase? = null,
    val result: ProgressResult? = null,
    val elapsedMs: Long? = null,
    val transferMs: Long? = null,
    val transferredBytes: Long? = null,
    val done: Long? = null,
    val total: Long? = null,
    val unit: ProgressUnit? = null,
    val retries: Int = 0,
    val model: String? = null,
    val platform: String? = null,
    val failurePhase: ProgressPhase? = null,
    val batch: Int? = null,
    val batches: Int? = null,
    val volume: Int? = null,
    val cumulativeBytes: Long? = null,
    val skipped: Int? = null,
    val polishFallback: Boolean = false,
    val reusedFiles: Int? = null,
    val retainedFiles: Int? = null,
    val subtitleCount: Int? = null,
) {
    val averageRate: Double? get() = if ((transferMs ?: 0) > 0 && transferredBytes != null)
        transferredBytes * 1000.0 / transferMs!! else null
}
@Serializable
data class TaskProgressSummary(val parts: Map<ProgressPart, TaskSegmentSummary> = emptyMap()) {
    fun merge(part: ProgressPart, value: TaskSegmentSummary) = copy(parts = parts + (part to value))
    fun without(part: ProgressPart): TaskProgressSummary = copy(parts = parts - part)
    fun encodeOrNull(): String? = if (parts.isEmpty()) null else encode()
}
private val progressJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }
fun decodeProgressSummary(raw: String?): TaskProgressSummary = raw?.let {
    runCatching { progressJson.decodeFromString<TaskProgressSummary>(it) }.getOrNull()
} ?: TaskProgressSummary()
fun TaskProgressSummary.encode(): String = progressJson.encodeToString(this)

data class TaskProgressSnapshot(
    val key: TaskKey, val attempt: Long, val part: ProgressPart,
    val summary: TaskSegmentSummary, val active: Boolean,
    val phaseElapsedMs: Long = 0, val queuedMs: Long = 0,
    val rate: Double? = null, val remainingMs: Long? = null,
    val noDataMs: Long? = null, val detail: TextRef? = null,
    val batchElapsedMs: Long? = null,
)
internal data class ProgressCheckpoint(val key: TaskKey, val attempt: Long, val part: ProgressPart, val summary: TaskSegmentSummary)

/** 统计与网络回调解耦；只在阶段变化和终态发出持久化检查点。 */
class TaskProgressTracker(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val checkpoint: (TaskKey, Long, ProgressPart, TaskSegmentSummary) -> Unit = { _, _, _, _ -> },
) {
    private data class Sample(val at: Long, val bytes: Long)
    private class Entry(val key: TaskKey, val token: Long, val part: ProgressPart, val queued: Long,
                        var phase: ProgressPhase, var model: String?, var platform: String?, val managed: Boolean) {
        var version = 0L
        var phaseAt = queued
        var started: Long? = if (phase.waiting) null else queued
        var active = true
        var result: ProgressResult? = null
        var reused = false
        var endedAt: Long? = null
        var transferAt: Long? = if (phase.transfer) queued else null
        var transferMs = 0L
        var bytes = 0L
        var windowBytes = 0L
        val samples = ArrayDeque<Sample>()
        var lastData: Long? = null
        var done: Long? = null
        var total: Long? = null
        var lastDone: Long? = null
        var lastTotal: Long? = null
        var lastUnit: ProgressUnit? = null
        var unit: ProgressUnit? = null
        var retries = 0
        var batch: Int? = null
        var batches: Int? = null
        var lastBatch: Int? = null
        var lastBatches: Int? = null
        var batchAt: Long? = null
        var volume: Int? = null
        var cumulative: Long? = null
        var skipped: Int? = null
        var reusedFiles: Int? = null
        var retainedFiles: Int? = null
        var fallback = false
        var resumedRemote = false
        var subtitles: Int? = null
        var detail: TextRef? = null
        init { if (phase.transfer) samples.add(Sample(queued, 0)) }
    }
    private val entries = linkedMapOf<TaskKey, MutableMap<ProgressPart, Entry>>()
    private fun all() = entries.values.flatMap { it.values }
    private fun entry(key: TaskKey, token: Long) = entries[key]?.values?.firstOrNull { it.token == token }
    private var nextToken = 0L
    private val state = MutableStateFlow<Map<TaskKey, TaskProgressSnapshot>>(emptyMap())
    val snapshots = state.asStateFlow()
    @Synchronized fun hasActive(): Boolean = all().any { it.active }
    @Synchronized fun current(key: TaskKey): TaskProgressSnapshot? = entries[key]?.values?.maxByOrNull { it.token }?.let { snapshot(it, clock()) }
    @Synchronized fun currentPart(key: TaskKey, part: ProgressPart): TaskProgressSnapshot? =
        entries[key]?.get(part)?.let { snapshot(it, clock()) }
    @Synchronized fun finishPart(key: TaskKey, part: ProgressPart, result: ProgressResult) {
        entries[key]?.get(part)?.let { finish(key, it.token, result) }
    }
    @Synchronized fun isCurrent(key: TaskKey, token: Long) = entry(key, token) != null
    @Synchronized fun reset(key: TaskKey) {
        entries.keys.filter { it.parent() == key.parent() }.forEach(entries::remove)
        publish()
    }
    @Synchronized fun resetPart(key: TaskKey, part: ProgressPart) {
        val parts = entries[key] ?: return
        parts.remove(part) ?: return
        if (parts.isEmpty()) entries.remove(key)
        publish()
    }
    @Synchronized fun queued(key: TaskKey, part: ProgressPart, phase: ProgressPhase) {
        val parts = entries.getOrPut(key) { linkedMapOf() }
        val old = parts[part]
        if (old?.active == true) {
            if (!old.managed && old.phase != phase) { old.phase = phase; old.phaseAt = clock(); old.version++ }
        } else parts[part] = Entry(key, ++nextToken, part, clock(), phase, null, null, false)
        publish()
    }
    @Synchronized fun begin(key: TaskKey, part: ProgressPart, phase: ProgressPhase = ProgressPhase.PREPARING,
                            model: String? = null, platform: String? = null): TaskReporter {
        val now = clock()
        val parts = entries.getOrPut(key) { linkedMapOf() }
        val waitingAt = parts[part]?.takeIf { it.active && it.phase.waiting }?.queued ?: now
        val e = Entry(key, ++nextToken, part, waitingAt, phase, model, platform, true)
        e.phaseAt = now
        e.started = if (phase.waiting) null else now
        parts[part] = e
        publish()
        save(e)
        return TaskReporter(this, key, e.token, part)
    }
    @Synchronized internal fun phase(key: TaskKey, token: Long, phase: ProgressPhase, force: Boolean): Long {
        val e = entry(key, token)?.takeIf { it.active } ?: return -1
        if (e.phase == phase && !force) return e.version
        val now = clock()
        e.transferAt?.let { e.transferMs += now - it }
        e.transferAt = if (phase.transfer) now else null
        val keepBatch = phase == ProgressPhase.CORRECTING || (e.phase == ProgressPhase.CORRECTING && phase == ProgressPhase.TRANSLATING)
        e.phase = phase; e.phaseAt = now; e.version++
        if (!phase.waiting && e.started == null) e.started = now
        if (!keepBatch) { e.done = null; e.total = null; e.unit = null }
        e.detail = null
        e.windowBytes = 0; e.samples.clear(); e.lastData = null
        if (phase.transfer) e.samples.add(Sample(now, 0))
        if (!keepBatch) { e.batch = null; e.batches = null; e.batchAt = null }
        publish(); save(e)
        return e.version
    }
    @Synchronized internal fun bytes(key: TaskKey, token: Long, version: Long, delta: Long, done: Long, total: Long?) {
        val e = entry(key, token)?.takeIf { it.version == version && it.active && it.phase.transfer } ?: return
        if (delta > 0) { e.bytes += delta; e.windowBytes += delta; e.lastData = clock() }
        e.done = done.coerceAtLeast(0); e.total = total?.takeIf { it > 0 }; e.unit = ProgressUnit.BYTES
        e.lastDone = e.done; e.lastTotal = e.total; e.lastUnit = e.unit
    }
    @Synchronized internal fun units(key: TaskKey, token: Long, done: Long, total: Long?, unit: ProgressUnit, detail: TextRef?, version: Long? = null) {
        val e = entry(key, token)?.takeIf { it.active && (version == null || it.version == version) } ?: return
        e.done = done.coerceAtLeast(0); e.total = total?.takeIf { it > 0 }; e.unit = unit; e.detail = detail
        e.lastDone = e.done; e.lastTotal = e.total; e.lastUnit = e.unit
    }
    @Synchronized internal fun metadata(key: TaskKey, token: Long, model: String?, platform: String?) {
        val e = entry(key, token)?.takeIf { it.active } ?: return
        e.model = model; e.platform = platform
    }
    @Synchronized internal fun batch(key: TaskKey, token: Long, batch: Int, total: Int) {
        val e = entry(key, token)?.takeIf { it.active } ?: return
        e.batch = batch; e.batches = total; e.batchAt = clock()
        e.lastBatch = batch; e.lastBatches = total
    }
    @Synchronized internal fun annotate(key: TaskKey, token: Long, retry: Boolean = false, volume: Int? = null,
                                        cumulative: Long? = null, skipped: Int? = null, fallback: Boolean = false,
                                        resumed: Boolean = false, subtitles: Int? = null, reused: Boolean = false,
                                        reusedFiles: Int? = null, retainedFiles: Int? = null) {
        val e = entry(key, token)?.takeIf { it.active } ?: return
        if (retry) e.retries++
        if (volume != null) e.volume = volume
        if (cumulative != null) e.cumulative = cumulative
        if (skipped != null) e.skipped = skipped
        if (reusedFiles != null) e.reusedFiles = reusedFiles
        if (retainedFiles != null) e.retainedFiles = retainedFiles
        if (fallback) e.fallback = true
        if (resumed) e.resumedRemote = true
        if (subtitles != null) e.subtitles = subtitles
        if (reused) e.reused = true
    }
    @Synchronized internal fun reusedIfNoTransfer(key: TaskKey, token: Long) {
        entry(key, token)?.takeIf { it.active && it.bytes == 0L }?.reused = true
    }
    @Synchronized internal fun finish(key: TaskKey, token: Long, result: ProgressResult) {
        val e = entry(key, token)?.takeIf { it.active } ?: return
        val now = clock()
        e.transferAt?.let { e.transferMs += now - it }; e.transferAt = null
        e.active = false
        e.result = when {
            result != ProgressResult.COMPLETED -> result
            e.reused -> ProgressResult.REUSED
            e.resumedRemote -> ProgressResult.RESUMED_REMOTE
            else -> result
        }
        e.endedAt = now
        publish(); save(e)
    }
    @Synchronized fun reconcile(key: TaskKey, part: ProgressPart, result: ProgressResult, force: Boolean = false) {
        val e = entries[key]?.get(part) ?: return
        if (!e.managed || force) finish(key, e.token, result)
    }
    @Synchronized fun sample() {
        val now = clock()
        all().filter { it.active && it.phase.transfer }.forEach { e ->
            e.samples.add(Sample(now, e.windowBytes))
            while (e.samples.size > 1 && e.samples.elementAt(1).at <= now - 5000) e.samples.removeFirst()
        }
        publish()
    }
    private fun summary(e: Entry, now: Long): TaskSegmentSummary {
        val reused = e.result == ProgressResult.REUSED
        return TaskSegmentSummary(e.phase, e.result,
            if (reused) null else e.started?.let { (now - it).coerceAtLeast(0) },
            if (reused || e.bytes == 0L) null else e.transferMs + (e.transferAt?.let { now - it } ?: 0),
            if (reused || e.bytes == 0L) null else e.bytes,
            e.done ?: e.lastDone, e.total ?: e.lastTotal, e.unit ?: e.lastUnit, e.retries, e.model, e.platform,
            e.phase.takeIf { e.result in setOf(ProgressResult.FAILED, ProgressResult.CANCELLED, ProgressResult.INTERRUPTED) },
            e.batch ?: e.lastBatch, e.batches ?: e.lastBatches, e.volume, e.cumulative, e.skipped, e.fallback, e.reusedFiles, e.retainedFiles, e.subtitles)
    }
    private fun snapshot(e: Entry, now: Long): TaskProgressSnapshot {
        val first = e.samples.firstOrNull()
        val rate = if (e.active && e.phase.transfer && first != null && now - first.at >= 1000)
            (e.windowBytes - first.bytes).coerceAtLeast(0) * 1000.0 / (now - first.at) else null
        val elapsed = ((e.endedAt ?: now) - e.phaseAt).coerceAtLeast(0)
        val total = e.total
        val done = e.done
        val remaining = if (e.active && total != null && done != null && total >= done) {
            when {
                e.phase.transfer && rate != null && rate > 0 -> ((total - done) * 1000.0 / rate).toLong()
                e.phase == ProgressPhase.TRANSCRIBING && done > 0 && elapsed >= 1000 ->
                    ((total - done).toDouble() * elapsed / done).toLong()
                else -> null
            }
        } else null
        return TaskProgressSnapshot(e.key, e.token, e.part, summary(e, e.endedAt ?: now), e.active,
            elapsed, ((e.started ?: e.endedAt ?: now) - e.queued).coerceAtLeast(0), rate, remaining,
            if (e.active && e.phase.transfer) (now - (e.lastData ?: e.phaseAt)).takeIf { it >= 5000 } else null,
            e.detail, e.batchAt?.let { ((e.endedAt ?: now) - it).coerceAtLeast(0) })
    }
    private fun publish() { val now = clock(); state.value = entries.mapValues { snapshot(it.value.values.maxBy { e -> e.token }, now) } }
    private fun save(e: Entry) { checkpoint(e.key, e.token, e.part, summary(e, e.endedAt ?: clock())) }
}

class TaskReporter internal constructor(private val tracker: TaskProgressTracker, val key: TaskKey, internal val token: Long, val part: ProgressPart) {
    fun metadata(model: String?, platform: String? = null) = tracker.metadata(key, token, model, platform)
    fun snapshot() = tracker.currentPart(key, part)?.takeIf { it.attempt == token }
    fun phase(phase: ProgressPhase, force: Boolean = false) = TransferProgress(tracker, key, token, tracker.phase(key, token, phase, force))
    fun units(done: Long, total: Long?, unit: ProgressUnit = ProgressUnit.ITEMS, detail: TextRef? = null) = tracker.units(key, token, done, total, unit, detail)
    fun batch(index: Int, total: Int) = tracker.batch(key, token, index, total)
    fun retry() = tracker.annotate(key, token, retry = true)
    fun volume(index: Int) = tracker.annotate(key, token, volume = index)
    fun cumulative(bytes: Long) = tracker.annotate(key, token, cumulative = bytes)
    fun skipped(count: Int) = tracker.annotate(key, token, skipped = count)
    fun workFiles(reused: Int, skipped: Int, retained: Int) =
        tracker.annotate(key, token, reusedFiles = reused, skipped = skipped, retainedFiles = retained)
    fun polishFallback() = tracker.annotate(key, token, fallback = true)
    fun resumedRemote() = tracker.annotate(key, token, resumed = true)
    fun reused() = tracker.annotate(key, token, reused = true)
    fun reusedIfNoTransfer() = tracker.reusedIfNoTransfer(key, token)
    fun subtitles(count: Int) = tracker.annotate(key, token, subtitles = count)
    fun finish(result: ProgressResult = ProgressResult.COMPLETED) = tracker.finish(key, token, result)
}
class TransferProgress internal constructor(private val tracker: TaskProgressTracker, private val key: TaskKey,
                                           private val token: Long, private val version: Long) {
    fun bytes(delta: Long, done: Long, total: Long?) = tracker.bytes(key, token, version, delta, done, total)
    fun units(done: Long, total: Long?, unit: ProgressUnit = ProgressUnit.ITEMS, detail: TextRef? = null) =
        tracker.units(key, token, done, total, unit, detail, version)
}
