package com.nanahoshi.audioplayer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nanahoshi.audioplayer.asmr.WorkFacts
import com.nanahoshi.audioplayer.ui.CoverImage

private const val TagPreviewCount = 8

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkHeader(
    facts: WorkFacts,
    coverUrl: String?,
    modifier: Modifier = Modifier,
    purchased: Boolean = false,
) {
    var tagsExpanded by rememberSaveable(facts.sourceId) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            CoverImage(
                coverUrl,
                size = 128.dp,
                shape = MaterialTheme.shapes.medium,
            )
            Column(Modifier.padding(start = 16.dp).weight(1f)) {
                Text(
                    facts.title.ifBlank { facts.sourceId.ifBlank { "未命名作品" } },
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (facts.sourceId.isNotBlank()) {
                        LabelBadge(facts.sourceId, MaterialTheme.colorScheme.primary)
                    }
                    if (purchased) {
                        LabelBadge("已购买", MaterialTheme.colorScheme.tertiary)
                    }
                }
                FactLine(Icons.Outlined.Groups, facts.circle.ifBlank { "社团未知" }, maxLines = 1)
                FactLine(
                    Icons.Outlined.Mic,
                    facts.vas.joinToString("、").ifBlank { "声优未知" },
                    maxLines = 2,
                )
                if (facts.release.isNotBlank()) {
                    FactLine(Icons.Outlined.CalendarMonth, facts.release, maxLines = 1)
                }
            }
        }
        if (facts.tags.isNotEmpty()) {
            val hidden = (facts.tags.size - TagPreviewCount).coerceAtLeast(0)
            val shown = if (tagsExpanded || hidden == 0) facts.tags else facts.tags.take(TagPreviewCount)
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                shown.forEach { tag ->
                    LabelBadge(tag, MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!tagsExpanded && hidden > 0) {
                    LabelBadge("+$hidden", MaterialTheme.colorScheme.primary)
                }
            }
            if (hidden > 0) {
                TextButton(
                    onClick = { tagsExpanded = !tagsExpanded },
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    Text(if (tagsExpanded) "收起标签" else "展开标签")
                }
            }
        }
    }
}

@Composable
private fun FactLine(
    icon: ImageVector,
    text: String,
    maxLines: Int,
) {
    Row(
        Modifier.padding(top = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.padding(top = 2.dp).size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}
