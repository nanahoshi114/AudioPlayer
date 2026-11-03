package com.nanahoshi.audioplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DlsiteOriginalPathsTest {
    @Test
    fun identicalNamesInDifferentFoldersKeepTheirIdentity() {
        val originals = listOf("left/voice.wav", "right/voice.wav")
        val candidates = listOf("right/voice.wav", "left/voice.wav")
        assertEquals(originals.associateWith { it }, matchDlsiteOriginalPaths(originals, candidates))
    }

    @Test
    fun oneOuterDirectoryMayDifferOnBothSides() {
        val originals = listOf("original/WAV/01.wav", "original/MP3/01.mp3", "original/readme.txt")
        val candidates = listOf("play/WAV/01.wav", "play/MP3/01.mp3", "play/readme.txt")
        assertEquals(originals.zip(candidates).toMap(), matchDlsiteOriginalPaths(originals, candidates))
    }

    @Test
    fun aUniqueOriginalWrapperMayBeRemoved() {
        assertEquals(
            mapOf("work/voice.wav" to "voice.wav", "work/readme.txt" to "readme.txt"),
            matchDlsiteOriginalPaths(listOf("work/voice.wav", "work/readme.txt"), listOf("voice.wav", "readme.txt")),
        )
    }

    @Test
    fun neverMatchesJustTheStemOrBasenameOfAnUnrelatedDirectory() {
        assertTrue(matchDlsiteOriginalPaths(listOf("voice.wav"), listOf("voice.mp3")).isEmpty())
        assertTrue(matchDlsiteOriginalPaths(
            listOf("left/voice.wav", "right/voice.wav"), listOf("voice.wav"),
        ).isEmpty())
        val longPrefix = "a".repeat(130)
        assertTrue(matchDlsiteOriginalPaths(listOf("$longPrefix.wav"), listOf("$longPrefix.mp3")).isEmpty())
        assertTrue(matchDlsiteOriginalPaths(listOf("$longPrefix.wav"), listOf("a".repeat(120))).isEmpty())
    }

    @Test
    fun originalAndPreviouslySanitizedFullPathsStillMatch() {
        assertEquals(
            mapOf("ＷＡＶ/何してほしい？.wav" to "WAV/何してほしい_.wav"),
            matchDlsiteOriginalPaths(listOf("ＷＡＶ/何してほしい？.wav"), listOf("WAV/何してほしい_.wav")),
        )
    }

    @Test
    fun ambiguousSanitizedNamesAndDuplicateOldPathsAreNotAssigned() {
        assertTrue(matchDlsiteOriginalPaths(listOf("a?.wav", "a*.wav"), listOf("a_.wav")).isEmpty())
        assertTrue(matchDlsiteOriginalPaths(listOf("voice.wav"), listOf("voice.wav", "voice.wav")).isEmpty())
    }

    @Test
    fun exactCaseMatchWinsBeforeLegacyCaseFolding() {
        assertEquals(
            mapOf("Voice.wav" to "Voice.wav", "voice.wav" to "voice.wav"),
            matchDlsiteOriginalPaths(listOf("Voice.wav", "voice.wav"), listOf("voice.wav", "Voice.wav")),
        )
    }

    @Test
    fun unicodeCanonicalEquivalentsMatchWithoutChangingTheStoredPath() {
        val decomposed = "cafe\u0301.wav"
        val composed = "caf\u00e9.wav"
        assertEquals(mapOf(decomposed to composed), matchDlsiteOriginalPaths(listOf(decomposed), listOf(composed)))
    }
}
