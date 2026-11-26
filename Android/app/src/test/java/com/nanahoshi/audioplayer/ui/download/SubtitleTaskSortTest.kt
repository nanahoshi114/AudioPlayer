package com.nanahoshi.audioplayer.ui.download

import com.nanahoshi.audioplayer.data.db.DownloadEntity
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.TranslateMark
import com.nanahoshi.audioplayer.data.db.TranslationJobEntity
import com.nanahoshi.audioplayer.data.db.WhisperJobEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTaskSortTest {
    @Test fun mixesTypesByMostRecentSubmissionWithDeterministicTies() {
        val whispers = listOf(whisper(80, null), whisper(3, 200), whisper(9, 200), whisper(1, 300))
        val translations = listOf(translation(80, null), translation(2, 100), translation(9, 200), translation(100, null))
        val expected = listOf(
            "whisper-1", "translation-9", "whisper-9", "whisper-3", "translation-2",
            "translation-100", "translation-80", "whisper-80",
        )

        assertEquals(expected, sortSubtitleTasks(whispers, translations).map { it.key })
        assertEquals(expected, sortSubtitleTasks(whispers.reversed(), translations.reversed()).map { it.key })
    }

    @Test fun clearsOnlyCompletedDownloadsWithoutPendingBiliSubtitles() {
        val done = DownloadEntity(
            trackId = 1, title = "Audio", bytesDone = 1, bytesTotal = 1, status = DownloadStatus.DONE, error = null,
        )
        assertTrue(isClearableCompletedDownload(done))
        assertTrue(isClearableCompletedDownload(done.copy(pendingBiliSubtitle = false)))
        assertFalse(isClearableCompletedDownload(done.copy(pendingBiliSubtitle = true)))
        assertFalse(isClearableCompletedDownload(done.copy(dismissedAt = 10)))
        assertFalse(isClearableCompletedDownload(done.copy(status = DownloadStatus.QUEUED)))
        assertFalse(isClearableCompletedDownload(done.copy(status = DownloadStatus.FAILED)))
        assertFalse(isClearableCompletedDownload(done.copy(
            status = DownloadStatus.FAILED, phase = "VERIFY", kind = "JAPANESE_ASMR_WORK",
        )))
    }

    @Test fun clearsOnlySuccessfulWhisperRowsWithoutPublication() {
        val done = whisper(3, 100)
        assertTrue(isClearableCompletedWhisper(done, emptySet()))
        assertTrue(isClearableCompletedWhisper(done.copy(translateStatus = TranslateMark.DONE), setOf(4)))
        // 听写完成但翻译失败的任务也允许“删除任务记录/清除已完成”。
        assertTrue(isClearableCompletedWhisper(done.copy(translateStatus = TranslateMark.FAILED), emptySet()))
        for (mark in listOf(TranslateMark.QUEUED, TranslateMark.RUNNING)) {
            assertFalse(isClearableCompletedWhisper(done.copy(translateStatus = mark), emptySet()))
        }
        assertFalse(isClearableCompletedWhisper(done, setOf(3)))
        assertFalse(isClearableCompletedWhisper(done.copy(dismissedAt = 10), emptySet()))
        for (status in listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.FAILED)) {
            assertFalse(status.name, isClearableCompletedWhisper(done.copy(status = status), emptySet()))
        }
    }

    @Test fun clearsOnlySuccessfulTranslationRowsWithoutPublication() {
        val done = translation(3, 100)
        assertTrue(isClearableCompletedTranslation(done, setOf(4)))
        assertFalse(isClearableCompletedTranslation(done, setOf(3)))
        assertFalse(isClearableCompletedTranslation(done.copy(dismissedAt = 10), emptySet()))
        assertFalse(isClearableCompletedTranslation(done.copy(status = DownloadStatus.RUNNING), emptySet()))
        assertFalse(isClearableCompletedTranslation(done.copy(status = DownloadStatus.FAILED), emptySet()))
    }

    private fun whisper(id: Long, createdAt: Long?) = WhisperJobEntity(
        id = id, title = "Whisper", fingerprint = "sha", trackId = null, localPath = null,
        remoteJobId = null, status = DownloadStatus.DONE, progress = 1f, error = null,
        awaitRemoteUrl = null, createdAt = createdAt,
    )

    private fun translation(id: Long, createdAt: Long?) = TranslationJobEntity(
        id = id, title = "Translation", trackId = null, remoteUrl = null, queueItemId = null,
        status = DownloadStatus.DONE, error = null, createdAt = createdAt,
    )
}
