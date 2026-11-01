package com.nanahoshi.audioplayer.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nanahoshi.audioplayer.ui.components.CoverImage as CoverArtwork
import com.nanahoshi.audioplayer.ui.components.EmptyState

fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

@Composable
fun CoverImage(
    uri: String?,
    modifier: Modifier = Modifier,
    size: Dp? = 48.dp,
    shape: Shape = RoundedCornerShape(12.dp),
) {
    CoverArtwork(uri = uri, modifier = modifier, size = size, shape = shape)
}

@Composable
fun EmptyHint(text: String) {
    EmptyState(title = text)
}
