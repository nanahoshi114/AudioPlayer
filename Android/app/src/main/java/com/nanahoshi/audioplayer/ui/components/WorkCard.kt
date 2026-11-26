package com.nanahoshi.audioplayer.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nanahoshi.audioplayer.asmr.WorkFacts
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.ui.theme.LocalReduceMotion
import com.nanahoshi.audioplayer.ui.theme.Motion

internal fun FolderEntity.toWorkFacts(): WorkFacts =
    WorkFacts.fromJson(workMeta) ?: WorkFacts(
        title = name,
        circle = "",
        vas = emptyList(),
        tags = emptyList(),
        release = "",
        sourceId = asmrSourceId.orEmpty(),
    )

/**
 * 通用媒体预览卡片。使用普通 Row / Column 布局与横向滑动标签栏展示徽章。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaPreviewCard(
    title: String,
    secondary: String,
    coverUrl: String?,
    leadingBadges: List<BadgeItem>,
    tagBadges: List<BadgeItem> = emptyList(),
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    selecting: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val reduce = LocalReduceMotion.current
    val animatedContainer by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        animationSpec = if (reduce) snap() else tween(Motion.short),
        label = "mediaPreviewCardContainer",
    )
    val clickModifier = when {
        onClick != null && onLongClick != null -> Modifier.combinedClickable(
            enabled = enabled,
            onClick = onClick,
            onLongClick = onLongClick,
        )
        onClick != null -> Modifier.clickable(
            enabled = enabled,
            onClick = onClick,
        )
        else -> Modifier
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = animatedContainer,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(clickModifier)
                .padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                SelectableLeading(
                    selecting = selecting,
                    selected = selected,
                    onToggle = { onClick?.invoke() },
                    label = "mediaPreviewLead",
                ) {
                    CoverImage(coverUrl, size = 84.dp, shape = MaterialTheme.shapes.medium)
                }
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = if (selected) Int.MAX_VALUE else 2,
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
                val allBadges = leadingBadges + tagBadges
                if (allBadges.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        itemsIndexed(allBadges, key = { index, item -> "$index-${item.text}" }) { _, item ->
                            LabelBadge(item.text, item.color)
                        }
                    }
                }
            }
            if (trailing != null) {
                trailing()
            }
        }
    }
}

/**
 * 作品卡片。委托给 [MediaPreviewCard]。
 */
@Composable
fun WorkCard(
    facts: WorkFacts,
    coverUrl: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    extraBadges: List<BadgeItem> = emptyList(),
    selected: Boolean = false,
    selecting: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    emptySecondaryFallback: String = facts.sourceId,
) {
    val title = facts.title.ifBlank { facts.sourceId }
    val secondary = listOf(facts.circle, facts.vas.joinToString("、"))
        .filter { it.isNotBlank() }
        .joinToString(" · ")
        .ifBlank { emptySecondaryFallback }
    val allTags = remember(facts.tags) {
        facts.tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
    MediaPreviewCard(
        title = title,
        secondary = secondary,
        coverUrl = coverUrl,
        leadingBadges = buildList {
            if (facts.sourceId.isNotBlank()) {
                add(BadgeItem(facts.sourceId, MaterialTheme.colorScheme.primary))
            }
            addAll(extraBadges)
        },
        tagBadges = allTags.map {
            BadgeItem(it, MaterialTheme.colorScheme.onSurfaceVariant)
        },
        modifier = modifier,
        selected = selected,
        selecting = selecting,
        enabled = enabled,
        onClick = onClick,
        onLongClick = onLongClick,
        trailing = trailing,
    )
}
