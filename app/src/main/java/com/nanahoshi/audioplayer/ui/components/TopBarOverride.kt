package com.nanahoshi.audioplayer.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.staticCompositionLocalOf

val LocalTopBarOverride = staticCompositionLocalOf<MutableState<(@Composable () -> Unit)?>?> { null }
