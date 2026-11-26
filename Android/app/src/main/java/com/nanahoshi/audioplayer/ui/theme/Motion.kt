package com.nanahoshi.audioplayer.ui.theme

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

object Motion {
    const val short = 150
    const val medium = 300
    const val long = 450
    val enter = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val exit = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    fun <T> press(): AnimationSpec<T> = spring(dampingRatio = 0.6f)
}

val LocalReduceMotion = staticCompositionLocalOf { false }

private fun readReduceMotion(resolver: android.content.ContentResolver): Boolean =
    runCatching {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)

@Composable
fun rememberReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    var reduced by remember(resolver) { mutableStateOf(readReduceMotion(resolver)) }
    DisposableEffect(resolver) {
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = readReduceMotion(resolver)
            }
        }
        runCatching { resolver.registerContentObserver(uri, false, observer) }
        reduced = readReduceMotion(resolver)
        onDispose {
            runCatching { resolver.unregisterContentObserver(observer) }
        }
    }
    return reduced
}

@Composable
fun <T> enterSpec(durationMillis: Int = Motion.medium): FiniteAnimationSpec<T> =
    if (LocalReduceMotion.current) snap() else tween(durationMillis, easing = Motion.enter)

@Composable
fun <T> exitSpec(durationMillis: Int = Motion.short): FiniteAnimationSpec<T> =
    if (LocalReduceMotion.current) snap() else tween(durationMillis, easing = Motion.exit)

@Composable
fun <T> pressSpec(): AnimationSpec<T> =
    if (LocalReduceMotion.current) snap() else Motion.press()
