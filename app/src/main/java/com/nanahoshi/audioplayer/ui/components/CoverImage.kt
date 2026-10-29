package com.nanahoshi.audioplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nanahoshi.audioplayer.data.toHttps
import com.nanahoshi.audioplayer.ui.theme.LocalReduceMotion
import java.io.File

@Composable
fun CoverImage(
    uri: String?,
    modifier: Modifier = Modifier,
    size: Dp? = 48.dp,
    shape: Shape = RoundedCornerShape(12.dp),
) {
    val model = when {
        uri.isNullOrBlank() -> null
        uri.startsWith("/") -> File(uri)
        else -> uri.toHttps()
    }
    val reduceMotion = LocalReduceMotion.current
    val sized = if (size != null) Modifier.size(size) else Modifier
    Box(
        modifier
            .then(sized)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (model == null) {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val context = LocalContext.current
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(model)
                    .crossfade(!reduceMotion)
                    .build(),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
