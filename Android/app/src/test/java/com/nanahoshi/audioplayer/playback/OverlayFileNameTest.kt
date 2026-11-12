package com.nanahoshi.audioplayer.playback

import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import com.nanahoshi.audioplayer.data.db.WhisperJobEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayFileNameTest {
    private fun whisperJob(id: Long, trackId: Long?, url: String?, path: String?) = WhisperJobEntity(
        id = id, title = "song", fingerprint = "fp$id", trackId = trackId, localPath = path,
        remoteJobId = null, status = DownloadStatus.DONE, progress = 1f, error = null, awaitRemoteUrl = url,
    )
    private fun orphanRow(path: String) = QueuedTrack(
        queueItemId = -1, position = 0, trackId = null, source = TrackSource.LOCAL, title = "",
        artist = null, durationMs = 0, coverUri = null, localUri = path, bvid = null, cid = null,
        page = null, remoteUrl = null, referer = null, workno = null, fileKey = null,
    )
    private fun nameOf(row: QueuedTrack) = overlayFileName(row.trackId, row.remoteUrl, row.localUri, row.queueItemId)

    @Test fun orphanRowRecoversTrackIdentityFromWhisperJob() {
        val row = orphanRow("/data/song.mp3")
        val recovered = recoveredOverlayIdentities(row, listOf(whisperJob(1, 7L, null, "/data/song.mp3")))
        // 覆盖文件按旧 track 身份命名，不能落在 path 身份上。
        assertEquals(overlayFileName(7L, null, null, null), nameOf(recovered.single()))
    }

    @Test fun recoveredIdentitiesDeduplicateAndSkipEmptyOnes() {
        val row = orphanRow("/data/song.mp3")
        val jobs = listOf(
            whisperJob(1, 7L, null, "/data/song.mp3"),
            whisperJob(2, 7L, null, "/data/song.mp3"),
            whisperJob(3, null, "https://example.com/a", "/data/song.mp3"),
            whisperJob(4, null, null, "/data/song.mp3"),
        )
        val names = recoveredOverlayIdentities(row, jobs).map(::nameOf)
        assertEquals(listOf(overlayFileName(7L, null, null, null), overlayFileName(null, "https://example.com/a", null, null)), names)
    }

    @Test fun knownIdentityIsNotRecoveredAgain() {
        val row = storedQueueRow(7L, "/data/song.mp3", null)
        assertTrue(recoveredOverlayIdentities(row, listOf(whisperJob(1, 7L, null, "/data/song.mp3"))).isEmpty())
    }

    @Test
    fun trackIdentityWinsAndDigestIsStable() {
        assertEquals("extra-4682e8009ad06258.json", overlayFileName(5L, "https://example.com/a", "/data/song.mp3", 9L))
    }

    @Test
    fun urlUsedWhenTrackMissingOrNonPositive() {
        val expected = "extra-04eb124138012504.json"
        assertEquals(expected, overlayFileName(null, "https://example.com/a", "/data/song.mp3", 9L))
        assertEquals(expected, overlayFileName(0L, "https://example.com/a", null, null))
        assertEquals(expected, overlayFileName(-1L, "https://example.com/a", null, null))
    }

    @Test
    fun pathSlotPreservedBeforeQueue() {
        assertEquals("extra-635a79799a6f1146.json", overlayFileName(null, null, "/data/song.mp3", 9L))
    }

    @Test
    fun blankUrlFallsThroughToQueue() {
        assertEquals("extra-e1b27275356deaa1.json", overlayFileName(null, "  ", null, 9L))
    }

    @Test
    fun noIdentityReturnsNull() {
        assertNull(overlayFileName(null, null, null, null))
    }
}