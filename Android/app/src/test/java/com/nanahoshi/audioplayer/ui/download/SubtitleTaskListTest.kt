package com.nanahoshi.audioplayer.ui.download

import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.TranslateMark
import com.nanahoshi.audioplayer.data.db.WhisperJobEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTaskListTest {
    @Test
    fun completedWhisperRowsAreClearableForEveryTerminalTranslationState() {
        for (translateStatus in listOf(null, TranslateMark.DONE, TranslateMark.FAILED)) {
            val job = job().copy(status = DownloadStatus.DONE, translateStatus = translateStatus)
            assertTrue("translateStatus=$translateStatus", isClearableCompletedWhisper(job, emptySet()))
        }
    }

    @Test
    fun publishedDismissedOrActiveWhisperRowsStayVisible() {
        val published = job().copy(status = DownloadStatus.DONE, translateStatus = TranslateMark.FAILED)
        assertFalse(isClearableCompletedWhisper(published, setOf(published.id)))
        assertFalse(isClearableCompletedWhisper(published.copy(dismissedAt = 1L), emptySet()))

        for (status in listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.FAILED)) {
            assertFalse(status.name, isClearableCompletedWhisper(job().copy(status = status), emptySet()))
        }
        for (translateStatus in listOf(TranslateMark.QUEUED, TranslateMark.RUNNING)) {
            assertFalse(
                "translateStatus=$translateStatus",
                isClearableCompletedWhisper(
                    job().copy(status = DownloadStatus.DONE, translateStatus = translateStatus),
                    emptySet(),
                ),
            )
        }
    }

    private fun job() = WhisperJobEntity(
        id = 7L,
        title = "Track",
        fingerprint = "fingerprint",
        trackId = null,
        localPath = null,
        remoteJobId = null,
        status = DownloadStatus.DONE,
        progress = 1f,
        error = null,
        awaitRemoteUrl = null,
    )
}