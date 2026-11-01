package com.nanahoshi.audioplayer.ui.player

import com.nanahoshi.audioplayer.playback.SubtitleCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleFollowStateTest {
    private val cues = listOf(
        SubtitleCue(0, 1000, "a"),
        SubtitleCue(2000, 3000, "b"),
        SubtitleCue(5000, 6000, "c"),
    )

    @Test
    fun userScrollPausesUntilResume() {
        val state = SubtitleFollowState()
        assertTrue(state.following)
        state.onUserScroll()
        assertFalse(state.following)
        state.resume()
        assertTrue(state.following)
    }

    @Test
    fun anchorUsesLatestStartedCueDuringGap() {
        val state = SubtitleFollowState()
        assertEquals(0, state.anchorIndex(cues, 1500))
        assertEquals(1, state.anchorIndex(cues, 4000))
        assertEquals(2, state.anchorIndex(cues, 5500))
    }
}
