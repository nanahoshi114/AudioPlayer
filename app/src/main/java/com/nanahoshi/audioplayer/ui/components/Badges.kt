package com.nanahoshi.audioplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.ui.theme.LocalSourceColors

enum class StatusTone {
    Neutral,
    Active,
    Success,
    Error,
}

@Composable
fun SourceBadge(source: TrackSource, modifier: Modifier = Modifier) {
    val palette = LocalSourceColors.current
    val (label, color) = when (source) {
        TrackSource.BILIBILI -> "B站" to palette.bilibili
        TrackSource.ASMR -> "asmr.one" to palette.asmr
        TrackSource.DLSITE -> "DLsite" to palette.dlsite
        TrackSource.LOCAL -> "本地" to palette.local
    }
    LabelBadge(label, color, modifier)
}

@Composable
fun LabelBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun StatusBadge(text: String, tone: StatusTone, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val color = when (tone) {
        StatusTone.Neutral -> scheme.onSurfaceVariant
        StatusTone.Active -> scheme.primary
        StatusTone.Success -> scheme.tertiary
        StatusTone.Error -> scheme.error
    }
    LabelBadge(text, color, modifier)
}

@Composable
fun IconLabel(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = tint)
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}
