package com.nanahoshi.audioplayer.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSeekTest {
    @Test
    fun eachSeekUsesItsOwnMedia3CommandAndChecksAvailabilityAtDispatch() {
        val calls = mutableListOf<String>()
        val available = mutableSetOf(Player.COMMAND_SEEK_BACK)
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "isCommandAvailable" -> args.single() in available
                "seekBack", "seekForward" -> {
                    calls.add(method.name)
                    null
                }
                else -> error("Unexpected Player call ${method.name}")
            }
        } as Player

        assertTrue(player.seekStepIfAvailable(forward = false))
        assertFalse(player.seekStepIfAvailable(forward = true))
        available.clear()
        available.add(Player.COMMAND_SEEK_FORWARD)
        assertFalse(player.seekStepIfAvailable(forward = false))
        assertTrue(player.seekStepIfAvailable(forward = true))
        assertEquals(listOf("seekBack", "seekForward"), calls)
    }

    @Test
    fun unknownDurationHasNoProgressOrScrubTarget() {
        for (duration in listOf(C.TIME_UNSET, -1L, 0L)) {
            assertEquals(0f, playbackProgress(positionMs = 30_000L, durationMs = duration), 0f)
            assertNull(playbackSeekPosition(0.75f, duration))
        }
    }

    @Test
    fun knownDurationClampsProgressAndScrubbingToTheTrack() {
        assertEquals(0f, playbackProgress(-1L, 40_000L), 0f)
        assertEquals(0.25f, playbackProgress(10_000L, 40_000L), 0f)
        assertEquals(1f, playbackProgress(60_000L, 40_000L), 0f)
        assertEquals(0L, playbackSeekPosition(-1f, 40_000L))
        assertEquals(10_000L, playbackSeekPosition(0.25f, 40_000L))
        assertEquals(40_000L, playbackSeekPosition(1.5f, 40_000L))
    }
}
