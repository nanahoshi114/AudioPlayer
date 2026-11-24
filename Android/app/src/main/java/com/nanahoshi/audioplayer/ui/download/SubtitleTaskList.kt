package com.nanahoshi.audioplayer.ui.download

import com.nanahoshi.audioplayer.data.db.DownloadEntity
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.TranslateMark
import com.nanahoshi.audioplayer.data.db.TranslationJobEntity
import com.nanahoshi.audioplayer.data.db.WhisperJobEntity

internal sealed interface SubtitleTaskItem {
    val key: String
    val createdAt: Long?
    val id: Long
    val kindOrder: Int

    data class Whisper(val job: WhisperJobEntity) : SubtitleTaskItem {
        override val key get() = "whisper-${job.id}"
        override val createdAt get() = job.createdAt
        override val id get() = job.id
        override val kindOrder get() = 1
    }

    data class Translation(val job: TranslationJobEntity) : SubtitleTaskItem {
        override val key get() = "translation-${job.id}"
        override val createdAt get() = job.createdAt
        override val id get() = job.id
        override val kindOrder get() = 0
    }
}

internal fun sortSubtitleTasks(
    whispers: List<WhisperJobEntity>,
    translations: List<TranslationJobEntity>,
): List<SubtitleTaskItem> = buildList<SubtitleTaskItem> {
    whispers.mapTo(this) { SubtitleTaskItem.Whisper(it) }
    translations.mapTo(this) { SubtitleTaskItem.Translation(it) }
}.sortedWith(compareByDescending<SubtitleTaskItem> { it.createdAt }.thenByDescending { it.id }.thenBy { it.kindOrder })

internal fun isClearableCompletedDownload(job: DownloadEntity): Boolean =
    job.status == DownloadStatus.DONE && job.pendingBiliSubtitle != true && job.dismissedAt == null

internal fun isClearableCompletedWhisper(job: WhisperJobEntity, publishedWhisperIds: Set<Long>): Boolean =
    job.status == DownloadStatus.DONE &&
        (job.translateStatus == null || job.translateStatus == TranslateMark.DONE || job.translateStatus == TranslateMark.FAILED) &&
        job.dismissedAt == null && job.id !in publishedWhisperIds

internal fun isClearableCompletedTranslation(job: TranslationJobEntity, publishedTranslationIds: Set<Long>): Boolean =
    job.status == DownloadStatus.DONE && job.dismissedAt == null && job.id !in publishedTranslationIds
