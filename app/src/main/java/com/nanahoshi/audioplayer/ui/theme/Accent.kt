package com.nanahoshi.audioplayer.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/** 主题色的来源。`System` 在 Android 12 以下退回 `Default`。 */
sealed interface ThemeAccent {
    data object System : ThemeAccent
    data object Default : ThemeAccent
    data class Seed(val color: Color) : ThemeAccent
}

class AccentPreset(val id: String, val name: String, val color: Color, val builtIn: Boolean = false)

val AccentPresets = listOf(
    AccentPreset("indigo", "靛蓝", Color(0xFF4F5FD6), builtIn = true),
    AccentPreset("blue", "天蓝", Color(0xFF1E88E5)),
    AccentPreset("teal", "青绿", Color(0xFF00897B)),
    AccentPreset("green", "草绿", Color(0xFF43A047)),
    AccentPreset("amber", "琥珀", Color(0xFFF9A825)),
    AccentPreset("orange", "橙", Color(0xFFF4511E)),
    AccentPreset("red", "绯红", Color(0xFFD32F2F)),
    AccentPreset("pink", "粉", Color(0xFFD81B60)),
    AccentPreset("purple", "紫", Color(0xFF8E24AA)),
    AccentPreset("brown", "棕", Color(0xFF795548)),
    AccentPreset("slate", "灰蓝", Color(0xFF607D8B)),
)

fun accentPreset(id: String): AccentPreset = AccentPresets.firstOrNull { it.id == id } ?: AccentPresets.first()

fun presetAccent(id: String): ThemeAccent {
    val preset = accentPreset(id)
    return if (preset.builtIn) ThemeAccent.Default else ThemeAccent.Seed(preset.color)
}

private fun hsl(hue: Float, saturation: Float, lightness: Float): Color =
    Color.hsl(((hue % 360f) + 360f) % 360f, saturation.coerceIn(0f, 1f), lightness.coerceIn(0f, 1f))

/** 由一个种子色按固定明度档位生成整套配色，不依赖额外的库。 */
fun seedColorScheme(seed: Color, dark: Boolean): ColorScheme {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(seed.toArgb(), hsv)
    val h = hsv[0]
    val s = hsv[1].coerceIn(0.35f, 0.85f)
    val t = h + 60f
    return if (dark) {
        darkColorScheme(
            primary = hsl(h, s, 0.80f),
            onPrimary = hsl(h, s, 0.18f),
            primaryContainer = hsl(h, s, 0.30f),
            onPrimaryContainer = hsl(h, s, 0.90f),
            secondary = hsl(h, s * 0.40f, 0.78f),
            onSecondary = hsl(h, s * 0.40f, 0.18f),
            secondaryContainer = hsl(h, s * 0.35f, 0.28f),
            onSecondaryContainer = hsl(h, s * 0.40f, 0.88f),
            tertiary = hsl(t, s * 0.50f, 0.78f),
            onTertiary = hsl(t, s * 0.50f, 0.18f),
            tertiaryContainer = hsl(t, s * 0.45f, 0.28f),
            onTertiaryContainer = hsl(t, s * 0.50f, 0.88f),
            background = hsl(h, 0.22f, 0.08f),
            onBackground = hsl(h, 0.15f, 0.90f),
            surface = hsl(h, 0.22f, 0.08f),
            onSurface = hsl(h, 0.15f, 0.90f),
            surfaceVariant = hsl(h, 0.18f, 0.20f),
            onSurfaceVariant = hsl(h, 0.12f, 0.75f),
            surfaceContainerLowest = hsl(h, 0.22f, 0.06f),
            surfaceContainerLow = hsl(h, 0.22f, 0.10f),
            surfaceContainer = hsl(h, 0.20f, 0.13f),
            surfaceContainerHigh = hsl(h, 0.20f, 0.17f),
            surfaceContainerHighest = hsl(h, 0.18f, 0.21f),
            outline = hsl(h, 0.08f, 0.55f),
            outlineVariant = hsl(h, 0.12f, 0.28f),
        )
    } else {
        lightColorScheme(
            primary = hsl(h, s, 0.40f),
            onPrimary = Color.White,
            primaryContainer = hsl(h, s, 0.90f),
            onPrimaryContainer = hsl(h, s, 0.14f),
            secondary = hsl(h, s * 0.35f, 0.40f),
            onSecondary = Color.White,
            secondaryContainer = hsl(h, s * 0.45f, 0.89f),
            onSecondaryContainer = hsl(h, s * 0.50f, 0.14f),
            tertiary = hsl(t, s * 0.50f, 0.40f),
            onTertiary = Color.White,
            tertiaryContainer = hsl(t, s * 0.50f, 0.90f),
            onTertiaryContainer = hsl(t, s * 0.50f, 0.14f),
            background = hsl(h, 0.25f, 0.975f),
            onBackground = hsl(h, 0.20f, 0.10f),
            surface = hsl(h, 0.25f, 0.975f),
            onSurface = hsl(h, 0.20f, 0.10f),
            surfaceVariant = hsl(h, 0.20f, 0.91f),
            onSurfaceVariant = hsl(h, 0.12f, 0.30f),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = hsl(h, 0.25f, 0.96f),
            surfaceContainer = hsl(h, 0.22f, 0.93f),
            surfaceContainerHigh = hsl(h, 0.20f, 0.91f),
            surfaceContainerHighest = hsl(h, 0.18f, 0.89f),
            outline = hsl(h, 0.10f, 0.46f),
            outlineVariant = hsl(h, 0.15f, 0.80f),
        )
    }
}