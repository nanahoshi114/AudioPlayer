package com.nanahoshi.audioplayer.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nanahoshi.audioplayer.asmr.WorkFacts

/**
 * 作品卡片。普通 Row / Column 布局承载 [BadgeLine]，
 * 不放入 Material3 ListItem 等会查询固有尺寸的内容槽。
 */
@Composable
fun WorkCard(
    facts: WorkFacts,
    coverUrl: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    extraBadges: List<BadgeItem> = emptyList(),
) {
    val title = facts.title.ifBlank { facts.sourceId }
    val secondary = listOf(facts.circle, facts.vas.joinToString("、"))
        .filter { it.isNotBlank() }
        .joinToString(" · ")
        .ifBlank { facts.sourceId }
    val shape = MaterialTheme.shapes.large
    val color = MaterialTheme.colorScheme.surfaceContainer
    val body: @Composable () -> Unit = {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            CoverImage(coverUrl, size = 84.dp, shape = MaterialTheme.shapes.medium)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                BadgeLine(
                    leading = buildList {
                        if (facts.sourceId.isNotBlank()) {
                            add(BadgeItem(facts.sourceId, MaterialTheme.colorScheme.primary))
                        }
                        addAll(extraBadges)
                    },
                    tags = facts.tags.take(8).map {
                        BadgeItem(it, MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    totalTags = facts.tags.size,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            color = color,
            modifier = modifier,
        ) { body() }
    } else {
        Surface(shape = shape, color = color, modifier = modifier) { body() }
    }
}