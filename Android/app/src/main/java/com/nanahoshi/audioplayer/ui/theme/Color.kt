package com.nanahoshi.audioplayer.ui.theme

import androidx.compose.ui.graphics.Color

val NightBackground = Color(0xFF10141C)
val NightSurface = Color(0xFF10141C)
val NightSurfaceContainer = Color(0xFF1C2433)
val NightOnSurface = Color(0xFFE6E9F2)
val NightOnSurfaceVariant = Color(0xFFB7C0D0)
val NightPrimary = Color(0xFFB8C4FF)
val NightOnPrimary = Color(0xFF0C1B6B)
val NightPrimaryContainer = Color(0xFF2C3EBE)
val NightOnPrimaryContainer = Color(0xFFDEE2FF)
val NightSecondary = Color(0xFF7AD7F0)
val NightOnSecondary = Color(0xFF003640)
val NightSecondaryContainer = Color(0xFF004E5E)
val NightOnSecondaryContainer = Color(0xFFB6ECFF)
val NightTertiary = Color(0xFFD7BAFF)
val NightOnTertiary = Color(0xFF3E2068)
val NightTertiaryContainer = Color(0xFF56387F)
val NightOnTertiaryContainer = Color(0xFFF0DBFF)

val DayBackground = Color(0xFFF6F7FB)
val DaySurface = Color(0xFFF6F7FB)
val DaySurfaceContainer = Color(0xFFE8EBF4)
val DayOnSurface = Color(0xFF171C26)
val DayOnSurfaceVariant = Color(0xFF434B5C)
val DayPrimary = Color(0xFF3A4AD0)
val DayOnPrimary = Color(0xFFFFFFFF)
val DayPrimaryContainer = Color(0xFFDEE2FF)
val DayOnPrimaryContainer = Color(0xFF00115A)
val DaySecondary = Color(0xFF00687A)
val DayOnSecondary = Color(0xFFFFFFFF)
val DaySecondaryContainer = Color(0xFFB4EBFF)
val DayOnSecondaryContainer = Color(0xFF001F27)
val DayTertiary = Color(0xFF6C4E94)
val DayOnTertiary = Color(0xFFFFFFFF)
val DayTertiaryContainer = Color(0xFFF0DBFF)
val DayOnTertiaryContainer = Color(0xFF27084C)

data class SourcePalette(
    val bilibili: Color,
    val asmr: Color,
    val dlsite: Color,
    val local: Color,
)

val LightSourcePalette = SourcePalette(
    bilibili = Color(0xFFC2185B),
    asmr = Color(0xFF4E3BB5),
    dlsite = Color(0xFF0B63B6),
    local = Color(0xFF3E4C5A),
)

val DarkSourcePalette = SourcePalette(
    bilibili = Color(0xFFFF9BB8),
    asmr = Color(0xFFC9BEFF),
    dlsite = Color(0xFF8ECAFF),
    local = Color(0xFFC5D0DC),
)
