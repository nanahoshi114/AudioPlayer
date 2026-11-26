package com.nanahoshi.audioplayer.playback

import android.content.Context
import com.nanahoshi.audioplayer.data.PreferenceFlow
import com.nanahoshi.audioplayer.data.appPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class DesktopSubtitlePosition(val x: Float = .5f, val y: Float = .92f)

internal data class DesktopSubtitleSettings(
    val enabled: Boolean = false,
    val locked: Boolean = false,
    val position: DesktopSubtitlePosition = DesktopSubtitlePosition(),
)

internal class SubtitleDisplayPreferences(context: Context) {
    private val prefs = appPreferences(context, "subtitle_display")
    private val desktopFlow = PreferenceFlow(
        DesktopSubtitleSettings(
            enabled = prefs.getBoolean("desktop_enabled", false),
            locked = prefs.getBoolean("desktop_locked", false),
            position = DesktopSubtitlePosition(prefs.getFloat("desktop_x", .5f), prefs.getFloat("desktop_y", .92f)),
        ),
    )
    val desktop: StateFlow<DesktopSubtitleSettings> = desktopFlow.state
    val appVisible = MutableStateFlow(false)
    private val fallbackFlow = PreferenceFlow(prefs.getBoolean("cross_source_fallback", true))
    val crossSourceFallback: StateFlow<Boolean> = fallbackFlow.state

    fun setCrossSourceFallback(enabled: Boolean) {
        prefs.edit().putBoolean("cross_source_fallback", enabled).apply()
        fallbackFlow.set(enabled)
    }

    fun enableDesktop(enabled: Boolean) {
        prefs.edit().putBoolean("desktop_enabled", enabled).apply()
        desktopFlow.set(desktopFlow.value.copy(enabled = enabled))
    }

    fun lockDesktop(locked: Boolean) {
        prefs.edit().putBoolean("desktop_locked", locked).apply()
        desktopFlow.set(desktopFlow.value.copy(locked = locked))
    }

    fun moveDesktop(position: DesktopSubtitlePosition) {
        prefs.edit().putFloat("desktop_x", position.x).putFloat("desktop_y", position.y).apply()
        desktopFlow.set(desktopFlow.value.copy(position = position))
    }
}