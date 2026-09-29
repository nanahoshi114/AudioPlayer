package com.nanahoshi.audioplayer.ui

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nanahoshi.audioplayer.data.toHttps
import java.io.File

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
fun CoverImage(uri: String?, modifier: Modifier = Modifier) {
    val model = when {
        uri.isNullOrBlank() -> null
        uri.startsWith("/") -> File(uri)
        else -> uri.toHttps()
    }
    AsyncImage(
        model = model,
        contentDescription = null,
        modifier = modifier
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp)),
        contentScale = ContentScale.Crop,
    )
}

@Composable
fun EmptyHint(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
