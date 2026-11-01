package com.nanahoshi.audioplayer.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nanahoshi.audioplayer.playback.SubtitleCue

/** Only user scrolling pauses following; seeks and automatic centering leave this mode alone. */
internal class SubtitleFollowState {
    var following by mutableStateOf(true)
        private set

    fun onUserScroll() { following = false }
    fun resume() { following = true }

    fun anchorIndex(cues: List<SubtitleCue>, positionMs: Long): Int {
        if (cues.isEmpty()) return -1
        return cues.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)
    }
}
