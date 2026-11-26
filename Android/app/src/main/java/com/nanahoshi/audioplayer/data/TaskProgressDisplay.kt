package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.text.resolve
import com.nanahoshi.audioplayer.text.resIdOrNull
import android.content.Context
import com.nanahoshi.audioplayer.R
import java.util.Locale
import com.nanahoshi.audioplayer.data.db.DownloadEntity
import com.nanahoshi.audioplayer.data.db.DownloadStatus

fun progressDuration(context: Context, ms: Long?): String {
    if (ms == null) return "—"
    val seconds = ms.coerceAtLeast(0) / 1000
    return when {
        seconds >= 3600 -> context.getString(
            R.string.progress_duration_hms,
            seconds / 3600,
            seconds % 3600 / 60,
            seconds % 60,
        )
        seconds >= 60 -> context.getString(R.string.progress_duration_ms, seconds / 60, seconds % 60)
        else -> context.getString(R.string.progress_duration_s, seconds)
    }
}
fun progressBytes(bytes: Long?): String = ByteSizeFormatter.format(bytes)
fun progressRate(rate: Double?): String = rate?.takeIf { it.isFinite() && it >= 0 }
    ?.let { "${progressBytes(it.toLong())}/s" } ?: "—"

/** 仅用当前阶段确实有含义的计数；上传结束后不能沿用上传百分比。 */
fun TaskProgressSnapshot.fraction(): Float? {
    if (!active) return null
    val phase = summary.phase ?: return null
    if (!phase.transfer && phase !in setOf(ProgressPhase.TRANSCRIBING, ProgressPhase.EXTRACTING,
            ProgressPhase.IMPORTING, ProgressPhase.TRANSLATING, ProgressPhase.CORRECTING, ProgressPhase.POLISHING)) return null
    val total = summary.total?.takeIf { it > 0 } ?: return null
    return summary.done?.let { (it.toDouble() / total).coerceIn(0.0, 1.0).toFloat() }
}

fun TaskProgressSnapshot.commonText(context: Context): String = buildList {
    val s = summary
    val phase = s.phase
    if (phase?.transfer == true) {
        val amount = "${progressBytes(s.done)} / ${progressBytes(s.total)}"
        add(if (s.volume != null) context.getString(R.string.progress_volume, s.volume, amount) else amount)
        fraction()?.let { add("${(it * 100).toInt()}%") }
        add(progressRate(rate))
        if (s.cumulativeBytes != null) add(context.getString(R.string.progress_cumulative, progressBytes(s.cumulativeBytes)))
    } else if (phase == ProgressPhase.TRANSCRIBING) {
        add(context.getString(
            R.string.progress_audio_amount,
            progressDuration(context, s.done),
            progressDuration(context, s.total),
        ))
        if ((s.done ?: 0) > 0 && phaseElapsedMs > 0) add(String.format(Locale.ROOT, "%.1f×", s.done!!.toDouble() / phaseElapsedMs))
    } else if (phase in setOf(ProgressPhase.TRANSLATING, ProgressPhase.CORRECTING, ProgressPhase.POLISHING)) {
        s.batch?.let { add(context.getString(R.string.progress_batch, it, s.batches ?: 0)) }
        s.done?.let { add(context.getString(R.string.progress_count, it, s.total ?: 0)) }
    } else if (phase in setOf(ProgressPhase.EXTRACTING, ProgressPhase.IMPORTING)) {
        s.done?.let { add(context.getString(R.string.progress_items, it, s.total ?: 0)) }
        if (phase == ProgressPhase.EXTRACTING) s.cumulativeBytes?.let {
            add(context.getString(R.string.progress_extracted, progressBytes(it)))
        }
    }
    if (phase?.waiting == true) add(context.getString(R.string.progress_waited, progressDuration(context, queuedMs)))
    else add(context.getString(R.string.progress_elapsed_value, progressDuration(context, s.elapsedMs)))
    remainingMs?.let { add(context.getString(R.string.progress_remaining, progressDuration(context, it))) }
    noDataMs?.let { add(context.getString(R.string.progress_waiting_data, progressDuration(context, it))) }
}.joinToString(" · ")

fun progressPartLabel(context: Context, part: ProgressPart): String = context.getString(
    when (part) {
        ProgressPart.DOWNLOAD -> R.string.progress_part_download
        ProgressPart.BILI_SUBTITLE -> R.string.progress_part_bili_subtitle
        ProgressPart.TRANSCRIPTION -> R.string.progress_part_transcription
        ProgressPart.TRANSLATION -> R.string.progress_part_translation
    },
)
fun progressResultLabel(context: Context, part: ProgressPart, result: ProgressResult): String = when {
    part == ProgressPart.BILI_SUBTITLE && result == ProgressResult.COMPLETED ->
        context.getString(R.string.progress_result_saved)
    part == ProgressPart.TRANSCRIPTION && result == ProgressResult.REUSED ->
        context.getString(R.string.progress_result_reused_subtitle)
    else -> context.getString(result.labelRes)
}

fun TaskSegmentSummary.detailLines(context: Context, runtime: TaskProgressSnapshot? = null): List<String> = buildList {
    result?.let { add(context.getString(R.string.progress_result_line, context.getString(it.labelRes))) }
    if (result == ProgressResult.RESUMED_REMOTE) add(context.getString(R.string.progress_resumed_stats))
    add(context.getString(R.string.progress_duration_line, progressDuration(context, elapsedMs)))
    if (runtime?.active == true) {
        val phaseText = phase?.let { context.getString(it.labelRes) } ?: "—"
        add(context.getString(
            R.string.progress_current_phase,
            phaseText,
            progressDuration(context, runtime.phaseElapsedMs),
        ))
        add(context.getString(R.string.progress_queue_wait, progressDuration(context, runtime.queuedMs)))
        runtime.detail?.let { add(context.resolve(it)) }
        runtime.batchElapsedMs?.let { add(context.getString(R.string.progress_batch_wait, progressDuration(context, it))) }
    }
    if (transferredBytes != null || unit == ProgressUnit.BYTES) {
        add(context.getString(R.string.progress_actual_transfer, progressBytes(transferredBytes)))
        add(context.getString(
            R.string.progress_transfer_time,
            progressDuration(context, transferMs),
            progressRate(averageRate),
        ))
    } else if (unit == ProgressUnit.AUDIO_MS) {
        add(context.getString(
            R.string.progress_audio_processed,
            progressDuration(context, done),
            progressDuration(context, total),
        ))
    } else if (unit == ProgressUnit.ITEMS) add(context.getString(R.string.progress_items_processed, done ?: "—", total ?: "—"))
    if (unit != ProgressUnit.BYTES && transferredBytes == null) add(context.getString(R.string.progress_transfer_rate))
    batch?.let { add(context.getString(R.string.progress_batch_line, it, batches ?: 0)) }
    subtitleCount?.let { add(context.getString(R.string.progress_subtitles_count, it)) }
    volume?.let { add(context.getString(R.string.progress_volume_line, it)) }
    reusedFiles?.let { add(context.getString(R.string.progress_reused_files, it)) }
    retainedFiles?.takeIf { it > 0 }?.let { add(context.getString(R.string.progress_retained_files, it)) }
    skipped?.takeIf { it > 0 }?.let {
        add(context.getString(if (reusedFiles == null) R.string.progress_skipped_other_track else R.string.progress_skipped_files, it))
    }
    add(context.getString(R.string.progress_retries, retries))
    model?.let { add(context.getString(R.string.progress_model_line, it)) }
    platform?.let { add(context.getString(R.string.progress_platform_line, it)) }
    failurePhase?.let { add(context.getString(R.string.progress_failure_phase, context.getString(it.labelRes))) }
    if (polishFallback) add(context.getString(R.string.progress_polish_fallback))
}

fun totalDownloadRate(snapshots: Map<TaskKey, TaskProgressSnapshot>): Double? = snapshots.values
    .filter { it.key.kind == TaskKind.DOWNLOAD && it.key.child == null && it.active && it.summary.phase?.transfer == true }
    .mapNotNull { it.rate }.takeIf { it.isNotEmpty() }?.sum()

internal data class AudioProgressNotification(
    val title: String = "",
    val detail: String = "",
    val percent: Int? = null,
)

internal fun audioNotificationState(
    context: Context,
    jobs: List<DownloadEntity>,
    snapshots: Map<TaskKey, TaskProgressSnapshot>,
): AudioProgressNotification {
    val active = jobs.firstOrNull { it.status == DownloadStatus.RUNNING }
    val subtitle = jobs.firstOrNull { it.status == DownloadStatus.DONE && it.pendingBiliSubtitle == true }
    val live = active?.let { snapshots[TaskKey(TaskKind.DOWNLOAD, it.id)] }
        ?: subtitle?.let { snapshots[TaskKey(TaskKind.DOWNLOAD, it.id, ProgressPart.BILI_SUBTITLE)] }
    val detail = buildList {
        add(context.getString(R.string.progress_notification_run, jobs.count { it.status == DownloadStatus.RUNNING }))
        add(context.getString(R.string.progress_notification_queue, jobs.count { it.status == DownloadStatus.QUEUED }))
        add(context.getString(R.string.progress_notification_rate, progressRate(totalDownloadRate(snapshots))))
        live?.summary?.phase?.let { add(context.getString(it.labelRes)) }
        live?.detail?.takeIf { it.resIdOrNull() == R.string.progress_detail_files }
            ?.let { add(context.resolve(it)) }
    }.joinToString(" · ")
    return AudioProgressNotification(
        active?.title ?: subtitle?.title ?: context.getString(R.string.progress_notification_preparing),
        detail,
        live?.fraction()?.let { (it * 100).toInt() },
    )
}