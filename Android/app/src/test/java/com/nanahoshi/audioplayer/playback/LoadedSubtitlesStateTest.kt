package com.nanahoshi.audioplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadedSubtitlesStateTest {
    private val cues = listOf(SubtitleCue(0, 1000, "こんにちは"))

    @Test
    fun disabledByUserDefaultsToFalseForExistingCallers() {
        val loaded = LoadedSubtitles(cues, generatedEmpty = false, generated = true, generatedFingerprint = "abc")
        assertFalse(loaded.disabledByUser)
        assertTrue(loaded.generated)
        assertTrue(loaded.canTranslate)
        assertFalse(loaded.hasManualBinding)
        assertEquals(cues, loaded.cues)
        assertEquals(cues, loaded.sourceCues)
    }

    @Test
    fun cuesClippedByNegativeOffsetCanStillBeTranslated() {
        val loaded = LoadedSubtitles(cues, generatedEmpty = false, generated = false)
            .copy(cues = emptyList(), appliedOffsetMs = -1_000L)
        assertTrue(loaded.canTranslate)
        assertEquals(cues, loaded.sourceCues)
        assertEquals(cues, loaded.unshiftedCues)
        assertTrue(loaded.cues.isEmpty())
    }

    @Test
    fun explicitNoneStateKeepsManualBindingFlagAndDisablesGeneration() {
        val loaded = LoadedSubtitles(
            emptyList(), generatedEmpty = false, generated = false,
            hasManualBinding = true, disabledByUser = true,
        )
        assertTrue(loaded.disabledByUser)
        assertTrue(loaded.hasManualBinding)
        assertFalse(loaded.generated)
        assertFalse(loaded.canTranslate)
        assertNull(loaded.generatedFingerprint)
        assertEquals(SubtitleSourceKind.NONE, loaded.sourceKind)
    }

    @Test
    fun disabledFlagDoesNotChangeSourceOrTranslationFlags() {
        val translated = listOf(SubtitleCue(0, 1000, "こんにちは", "你好"))
        val generated = LoadedSubtitles(
            translated, generatedEmpty = false, generated = true, generatedFingerprint = "abc",
            sourceLabel = "字幕.srt", appliedOffsetMs = 500L,
        ).copy(disabledByUser = true)
        assertTrue(generated.disabledByUser)
        assertTrue(generated.generated)
        assertEquals("字幕.srt", generated.sourceLabel)
        assertEquals(500L, generated.appliedOffsetMs)
        assertEquals(SubtitleSourceKind.GENERATED, generated.sourceKind)
        // 已有译文时无需再翻译，与关闭标记互不影响。
        assertFalse(generated.canTranslate)
    }
}
