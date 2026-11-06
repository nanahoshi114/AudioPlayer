package com.nanahoshi.audioplayer.playback

import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.dlsite.RemotePlayable
import org.junit.Assert.*
import org.junit.Test

class MixedQueueTest {
    @Test fun mixedRowsKeepSavedReferencesAndRemoteIdentitiesInOrder() {
        val track = TrackEntity(12, TrackSource.BILIBILI, "A P1", "UP", 1000, null, null,
            "BV1234567890", 101, 1, 0)
        val items = listOf(QueuePlayable.Saved(track),
            QueuePlayable.Remote(RemotePlayable("A P2", "UP", null, "https://www.bilibili.com/video/BV1234567890",
                TrackSource.BILIBILI, "https://www.bilibili.com", 1000, "BV1234567890", "102")),
            QueuePlayable.Remote(RemotePlayable("B P1", "UP", null, "https://www.bilibili.com/video/BV1234567891",
                TrackSource.BILIBILI, "https://www.bilibili.com", 1000, "BV1234567891", "201")))
        val rows = items.mapIndexed { index, item -> item.queueRow(index) }
        assertEquals(listOf(0, 1, 2), rows.map { it.position })
        assertEquals(12L, rows[0].trackId); assertNull(rows[1].trackId)
        assertEquals("BV1234567890", rows[1].workno); assertEquals("102", rows[1].fileKey)
        assertEquals("BV1234567891", rows[2].workno)
    }
}
