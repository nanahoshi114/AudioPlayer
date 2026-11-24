package com.nanahoshi.audioplayer.playback

import androidx.media3.common.Player

internal fun Player.seekStepIfAvailable(forward: Boolean): Boolean {
    val command = if (forward) Player.COMMAND_SEEK_FORWARD else Player.COMMAND_SEEK_BACK
    if (!isCommandAvailable(command)) return false
    if (forward) seekForward() else seekBack()
    return true
}

internal fun playbackProgress(positionMs: Long, durationMs: Long): Float {
    if (durationMs <= 0L) return 0f
    return (positionMs.coerceIn(0L, durationMs).toDouble() / durationMs).toFloat()
}

internal fun playbackSeekPosition(fraction: Float, durationMs: Long): Long? {
    if (durationMs <= 0L) return null
    return (fraction.coerceIn(0f, 1f).toDouble() * durationMs).toLong()
}
