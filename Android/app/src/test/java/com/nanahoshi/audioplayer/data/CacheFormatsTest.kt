package com.nanahoshi.audioplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheFormatsTest {
    @Test
    fun sameStemDifferentExtensionIsConflict() {
        val conflicts = findFormatConflicts(
            listOf(
                CacheCandidate(1, 10, "01.mp3"),
                CacheCandidate(2, 10, "01.wav"),
                CacheCandidate(3, 10, "02.mp3"),
                CacheCandidate(4, 10, "02.wav"),
                CacheCandidate(5, 10, "03.mp3"),
            ),
        )
        assertEquals(1, conflicts.size)
        assertEquals(listOf("mp3", "wav"), conflicts[0].extensions)
        assertEquals(2, conflicts[0].groups.size)
        assertEquals(setOf(2L, 4L), idsToDrop(conflicts[0], "mp3"))
        assertEquals(setOf(1L, 3L), idsToDrop(conflicts[0], "wav"))
    }

    @Test
    fun otherFolderOrSameExtensionIsNotConflict() {
        val conflicts = findFormatConflicts(
            listOf(
                CacheCandidate(1, 10, "01.mp3"),
                CacheCandidate(2, 11, "01.wav"),
                CacheCandidate(3, 10, "02.mp3"),
                CacheCandidate(4, 10, "02.MP3"),
            ),
        )
        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun differentExtensionSetsAskSeparately() {
        val conflicts = findFormatConflicts(
            listOf(
                CacheCandidate(1, 10, "a.mp3"),
                CacheCandidate(2, 10, "a.wav"),
                CacheCandidate(3, 10, "b.flac"),
                CacheCandidate(4, 10, "b.wav"),
            ),
        )
        assertEquals(listOf(listOf("mp3", "wav"), listOf("flac", "wav")), conflicts.map { it.extensions })
    }
}
