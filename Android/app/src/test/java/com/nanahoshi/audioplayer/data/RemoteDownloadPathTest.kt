package com.nanahoshi.audioplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteDownloadPathTest {
    @Test fun sameReadableTitleCannotOverwriteAnotherTrackOrSource() {
        val first = trackDownloadRelativePath(TrackSource.ASMR, 1, "01.mp3")
        val second = trackDownloadRelativePath(TrackSource.ASMR, 2, "01.mp3")
        val dlsite = trackDownloadRelativePath(TrackSource.DLSITE, 1, "01.mp3")
        assertNotEquals(first, second)
        assertNotEquals(first, dlsite)
        assertTrue(first.endsWith("/01.mp3"))
        assertEquals(first, trackDownloadRelativePath(TrackSource.ASMR, 1, "01.mp3"))
    }
    @Test fun sameTitleDifferentKeysLandInDifferentDirectories() {
        val a = remoteRelativePath("01.mp3", "hash-a", occupied = true)
        val b = remoteRelativePath("01.mp3", "hash-b", occupied = true)
        assertNotEquals(a, b)
        assertTrue(a.endsWith("/01.mp3"))
        assertTrue(b.endsWith("/01.mp3"))
    }

    @Test fun pathIsStableForTheSameKey() {
        assertEquals(
            remoteRelativePath("01.mp3", "hash-a", occupied = true),
            remoteRelativePath("01.mp3", "hash-a", occupied = true),
        )
        assertEquals(8, shortKeyHash("hash-a").length)
    }

    @Test fun freeNameKeepsTheReadableFlatPath() {
        assertEquals("01.mp3", remoteRelativePath("01.mp3", "hash-a", occupied = false))
    }

    @Test fun blankKeyFallsBackToTheFlatName() {
        assertEquals("01.mp3", remoteRelativePath("01.mp3", null, occupied = true))
        assertEquals("01.mp3", remoteRelativePath("01.mp3", "", occupied = true))
    }
}
