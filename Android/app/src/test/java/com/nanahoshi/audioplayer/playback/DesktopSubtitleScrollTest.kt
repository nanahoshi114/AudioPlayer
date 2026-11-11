package com.nanahoshi.audioplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopSubtitleScrollTest {
    @Test
    fun startsAtTopAndReachesEndBeforeTheCueChanges() {
        val cue = SubtitleCue(1_000, 11_000, "长字幕")
        assertEquals(0f, desktopSubtitleScrollProgress(cue, 1_500, 20_000, 100), 0f)
        assertTrue(desktopSubtitleScrollProgress(cue, 6_000, 20_000, 100) in 0.5f..0.6f)
        assertEquals(1f, desktopSubtitleScrollProgress(cue, 9_000, 20_000, 100), 0f)
    }

    @Test
    fun openEndedCaptionUsesTheTrackDurationOrAReadingInterval() {
        val cue = SubtitleCue(10_000, Long.MAX_VALUE, "最后一句")
        assertEquals(1f, desktopSubtitleScrollProgress(cue, 18_000, 20_000, 200), 0f)
        assertTrue(desktopSubtitleScrollProgress(cue, 18_000, -1, 200) in 0.4f..0.5f)
        assertEquals(1f, desktopSubtitleScrollProgress(cue, 26_000, -1, 200), 0f)
    }
}
