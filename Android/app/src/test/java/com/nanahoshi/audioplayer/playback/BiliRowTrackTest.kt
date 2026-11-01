package com.nanahoshi.audioplayer.playback

import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BiliRowTrackTest {
    private fun row(
        source: TrackSource = TrackSource.BILIBILI,
        bvid: String? = null,
        cid: Long? = null,
        workno: String? = null,
        fileKey: String? = null,
        remoteUrl: String? = null,
    ) = QueuedTrack(
        queueItemId = 1L,
        position = 0,
        trackId = null,
        source = source,
        title = "标题",
        artist = "UP",
        durationMs = 1000L,
        coverUri = null,
        localUri = null,
        bvid = bvid,
        cid = cid,
        page = 2,
        remoteUrl = remoteUrl,
        referer = null,
        workno = workno,
        fileKey = fileKey,
    )

    @Test
    fun cachedFromFavoritesUsesQueueFields() {
        val track = bilibiliRowTrack(
            row(workno = "BV1234567890", fileKey = "42", remoteUrl = "/data/bilibili/x/42.m4s"),
        )
        assertNotNull(track)
        assertEquals("BV1234567890", track!!.bvid)
        assertEquals(42L, track.cid)
        assertEquals("/data/bilibili/x/42.m4s", track.localUri)
    }

    @Test
    fun queueItemWithTrackFieldsStillWorks() {
        val track = bilibiliRowTrack(row(bvid = "BV1234567890", cid = 7L))
        assertNotNull(track)
        assertEquals("BV1234567890", track!!.bvid)
        assertEquals(7L, track.cid)
    }

    @Test
    fun nonBilibiliOrIncompleteRowsHaveNoTrack() {
        assertNull(bilibiliRowTrack(row(source = TrackSource.LOCAL, workno = "BV1234567890", fileKey = "42")))
        assertNull(bilibiliRowTrack(row(workno = "BV1234567890")))
        assertNull(bilibiliRowTrack(row(fileKey = "42")))
        assertNull(bilibiliRowTrack(row(workno = "bad", fileKey = "42")))
    }
}
