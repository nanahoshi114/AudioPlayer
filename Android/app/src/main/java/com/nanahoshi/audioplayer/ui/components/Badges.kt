package com.nanahoshi.audioplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
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
        TrackSource.YOUTUBE -> "YouTube" to palette.youtube
        TrackSource.BILIBILI -> "B 站" to palette.bilibili
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
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** 一行徽章里的一项。颜色由调用方决定，RJ 号、标签、提示可以混用。 */
data class BadgeItem(val text: String, val color: Color)

/**
 * 单行徽章列：先放 [leading]（例如 RJ 号），再按宽度放 [tags]，放不下的收进 `+N`。
 * 所有徽章保持单行，不会因为宽度不够而竖排。
 */
@Composable
fun BadgeLine(
    leading: List<BadgeItem>,
    tags: List<BadgeItem>,
    totalTags: Int = tags.size,
    modifier: Modifier = Modifier,
) {
    if (leading.isEmpty() && tags.isEmpty()) return
    val style = MaterialTheme.typography.labelMedium
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val horizontalPadding = 8.dp
    val gap = 6.dp

    fun badgeWidth(text: String): Float = with(density) {
        measurer.measure(AnnotatedString(text), style = style, maxLines = 1).size.width.toDp().value
    } + (horizontalPadding * 2).value

    val items = leading + tags
    val widths = remember(items, style, density, measurer) { items.map { badgeWidth(it.text) } }
    val total = leading.size + totalTags.coerceAtLeast(tags.size)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val available = maxWidth.value
        val shown = if (available.isFinite()) {
            fitBadgeCount(
                widths = widths,
                leadingCount = leading.size,
                totalCount = total,
                available = available,
                gap = gap.value,
                overflowWidth = { count -> badgeWidth("+$count") },
            )
        } else {
            items.size
        }
        val hidden = total - shown
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            items.take(shown).forEach { item ->
                LabelBadge(item.text, item.color)
            }
            if (hidden > 0) {
                LabelBadge("+$hidden", MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/**
 * 计算 [widths]（leading + 已量过的 tags）里能放下几项。
 * [totalCount] 是完整项数，可能大于 [widths].size，用来算 `+N`。
 */
internal fun fitBadgeCount(
    widths: List<Float>,
    leadingCount: Int,
    totalCount: Int,
    available: Float,
    gap: Float,
    overflowWidth: (Int) -> Float,
): Int {
    if (widths.isEmpty()) return 0
    val pinned = leadingCount.coerceIn(0, widths.size)
    var used = 0f
    for (index in 0 until pinned) {
        used += widths[index] + if (index > 0) gap else 0f
    }
    var shown = pinned
    if (used < available) {
        for (index in pinned until widths.size) {
            val hiddenAfter = totalCount - index - 1
            val reserve = if (hiddenAfter > 0) gap + overflowWidth(hiddenAfter) else 0f
            val need = gap + widths[index]
            if (used + need + reserve <= available) {
                used += need
                shown = index + 1
            } else {
                break
            }
        }
    }
    var hidden = totalCount - shown
    while (hidden > 0 && shown > pinned) {
        if (used + gap + overflowWidth(hidden) <= available) break
        shown -= 1
        used -= gap + widths[shown]
        hidden = totalCount - shown
    }
    return shown
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
