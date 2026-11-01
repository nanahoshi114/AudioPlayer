package com.nanahoshi.audioplayer.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}

enum class AccentMode {
    /** Android 12 及以上用系统取色；更低版本回到默认靛蓝。 */
    SYSTEM,

    /** 跟随当前播放音频的封面；没有封面或取不出颜色时保持上一次的颜色。 */
    COVER,

    /** 用户从预设里选一个。 */
    PRESET,
}

data class Appearance(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accentMode: AccentMode = AccentMode.SYSTEM,
    val presetId: String = DEFAULT_PRESET_ID,
)

const val DEFAULT_PRESET_ID = "indigo"

class AppearancePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<Appearance> = _state

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
        _state.value = _state.value.copy(themeMode = mode)
    }

    fun useSystemAccent() = setAccent(AccentMode.SYSTEM, _state.value.presetId)

    fun useCoverAccent() = setAccent(AccentMode.COVER, _state.value.presetId)

    fun usePreset(id: String) = setAccent(AccentMode.PRESET, id)

    private fun setAccent(mode: AccentMode, presetId: String) {
        prefs.edit()
            .putString(KEY_ACCENT, mode.name)
            .putString(KEY_PRESET, presetId)
            .apply()
        _state.value = _state.value.copy(accentMode = mode, presetId = presetId)
    }

    private fun read(): Appearance = Appearance(
        themeMode = enumOr(prefs.getString(KEY_MODE, null), ThemeMode.SYSTEM),
        accentMode = enumOr(prefs.getString(KEY_ACCENT, null), AccentMode.SYSTEM),
        presetId = prefs.getString(KEY_PRESET, null) ?: DEFAULT_PRESET_ID,
    )

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback

    private companion object {
        const val KEY_MODE = "theme_mode"
        const val KEY_ACCENT = "accent_mode"
        const val KEY_PRESET = "preset_id"
    }
}
