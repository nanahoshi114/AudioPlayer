package com.nanahoshi.audioplayer.playback

import com.nanahoshi.audioplayer.data.db.PlaybackStateEntity
import com.nanahoshi.audioplayer.queuedTrack
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackRestoreSelectionTest {
    private val rows = listOf(
        queuedTrack(queueItemId = 10, position = 0, title = "first"),
        queuedTrack(queueItemId = 11, position = 1, title = "second"),
    )

    @Test
    fun missingQueueItemFallsBackToFirstAndResetsPosition() {
        val restored = restoredQueueSelection(rows, PlaybackStateEntity(queueItemId = 99, positionMs = 5_000))
        assertEquals(0, restored.index)
        assertEquals("旧进度不能套到另一首曲目上", 0L, restored.positionMs)
    }

    @Test
    fun matchingQueueItemKeepsItsPosition() {
        val restored = restoredQueueSelection(rows, PlaybackStateEntity(queueItemId = 11, positionMs = 5_000))
        assertEquals(1, restored.index)
        assertEquals(5_000L, restored.positionMs)
    }

    @Test
    fun negativePersistedPositionIsClampedToZero() {
        val restored = restoredQueueSelection(rows, PlaybackStateEntity(queueItemId = 10, positionMs = -20))
        assertEquals(0, restored.index)
        assertEquals(0L, restored.positionMs)
    }
}