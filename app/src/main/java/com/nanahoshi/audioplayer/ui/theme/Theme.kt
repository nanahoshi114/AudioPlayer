package com.nanahoshi.audioplayer.ui.theme

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = NightPrimary,
    onPrimary = NightOnPrimary,
    primaryContainer = NightPrimaryContainer,
    onPrimaryContainer = NightOnPrimaryContainer,
    secondary = NightSecondary,
    onSecondary = NightOnSecondary,
    secondaryContainer = NightSecondaryContainer,
    onSecondaryContainer = NightOnSecondaryContainer,
    tertiary = NightTertiary,
    onTertiary = NightOnTertiary,
    tertiaryContainer = NightTertiaryContainer,
    onTertiaryContainer = NightOnTertiaryContainer,
    background = NightBackground,
    onBackground = NightOnSurface,
    surface = NightSurface,
    onSurface = NightOnSurface,
    surfaceVariant = NightSurfaceContainer,
    onSurfaceVariant = NightOnSurfaceVariant,
    surfaceContainer = NightSurfaceContainer,
)

private val LightColorScheme = lightColorScheme(
    primary = DayPrimary,
    onPrimary = DayOnPrimary,
    primaryContainer = DayPrimaryContainer,
    onPrimaryContainer = DayOnPrimaryContainer,
    secondary = DaySecondary,
    onSecondary = DayOnSecondary,
    secondaryContainer = DaySecondaryContainer,
    onSecondaryContainer = DayOnSecondaryContainer,
    tertiary = DayTertiary,
    onTertiary = DayOnTertiary,
    tertiaryContainer = DayTertiaryContainer,
    onTertiaryContainer = DayOnTertiaryContainer,
    background = DayBackground,
    onBackground = DayOnSurface,
    surface = DaySurface,
    onSurface = DayOnSurface,
    surfaceVariant = DaySurfaceContainer,
    onSurfaceVariant = DayOnSurfaceVariant,
    surfaceContainer = DaySurfaceContainer,
)

val LocalSourceColors = staticCompositionLocalOf { LightSourcePalette }

/** 应用当前实际使用的是否深色（受「外观」里的主题模式影响，不等于系统深色）。 */
val LocalDarkTheme = staticCompositionLocalOf { false }

@Composable
fun AudioPlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accent: ThemeAccent = ThemeAccent.System,
    content: @Composable () -> Unit,
) {
    val reduceMotion = rememberReduceMotion()
    val seedTarget = (accent as? ThemeAccent.Seed)?.color
    // 换颜色或换封面时，种子色渐变，整套配色跟着过渡。
    val held = remember { arrayOf(Color(0xFF4F5FD6)) }
    if (seedTarget != null) held[0] = seedTarget
    val seed by animateColorAsState(
        targetValue = held[0],
        animationSpec = if (reduceMotion) snap() else tween(Motion.long),
        label = "accentSeed",
    )
    val colorScheme = when {
        accent is ThemeAccent.System && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        seedTarget != null -> seedColorScheme(seed, darkTheme)
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val sources = if (darkTheme) DarkSourcePalette else LightSourcePalette
    CompositionLocalProvider(
        LocalReduceMotion provides reduceMotion,
        LocalSourceColors provides sources,
        LocalDarkTheme provides darkTheme,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = AppShapes,
            content = content,
        )
    }
}
