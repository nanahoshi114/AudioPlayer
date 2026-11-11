package com.nanahoshi.audioplayer.playback

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class DesktopSubtitlePosition(val x: Float = .5f, val y: Float = .92f)

internal data class DesktopSubtitleSettings(
    val enabled: Boolean = false,
    val locked: Boolean = false,
    val position: DesktopSubtitlePosition = DesktopSubtitlePosition(),
)

internal class SubtitleDisplayPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("subtitle_display", Context.MODE_PRIVATE)
    private val _desktop = MutableStateFlow(
        DesktopSubtitleSettings(
            enabled = prefs.getBoolean("desktop_enabled", false),
            locked = prefs.getBoolean("desktop_locked", false),
            position = DesktopSubtitlePosition(prefs.getFloat("desktop_x", .5f), prefs.getFloat("desktop_y", .92f)),
        ),
    )
    val desktop = _desktop.asStateFlow()
    val appVisible = MutableStateFlow(false)

    fun enableDesktop(enabled: Boolean) {
        prefs.edit().putBoolean("desktop_enabled", enabled).apply()
        _desktop.value = _desktop.value.copy(enabled = enabled)
    }

    fun lockDesktop(locked: Boolean) {
        prefs.edit().putBoolean("desktop_locked", locked).apply()
        _desktop.value = _desktop.value.copy(locked = locked)
    }

    fun moveDesktop(position: DesktopSubtitlePosition) {
        prefs.edit().putFloat("desktop_x", position.x).putFloat("desktop_y", position.y).apply()
        _desktop.value = _desktop.value.copy(position = position)
    }
}
