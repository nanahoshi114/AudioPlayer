package com.nanahoshi.audioplayer.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nanahoshi.audioplayer.ui.theme.LocalReduceMotion

@Composable
fun RoundedProgress(
    progress: Float?,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
) {
    val shape = RoundedCornerShape(99.dp)
    val bar = modifier.fillMaxWidth().height(height).clip(shape)
    if (progress == null) {
        LinearProgressIndicator(bar)
        return
    }
    val reduce = LocalReduceMotion.current
    val shown by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = if (reduce) snap() else tween(400, easing = LinearEasing),
        label = "progress",
    )
    LinearProgressIndicator(progress = { shown }, modifier = bar)
}
