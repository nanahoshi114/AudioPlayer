package com.nanahoshi.audioplayer.ui.components

import com.nanahoshi.audioplayer.R
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.platform.LocalDensity
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
    BoxWithConstraints(modifier.fillMaxWidth()) {
    val stacked = maxWidth.value / LocalDensity.current.fontScale < 360f
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        if (stacked) {
            CoverImage(coverUrl, size = 128.dp, shape = MaterialTheme.shapes.medium)
            WorkHeading(facts, purchased, Modifier.padding(top = 12.dp))
        } else {
            Row(verticalAlignment = Alignment.Top) {
                CoverImage(coverUrl, size = 128.dp, shape = MaterialTheme.shapes.medium)
                WorkHeading(facts, purchased, Modifier.padding(start = 16.dp).weight(1f))
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
                    Text(stringResource(if (tagsExpanded) R.string.work_tags_collapse else R.string.work_tags_expand))
                }
            }
        }
    }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkHeading(facts: WorkFacts, purchased: Boolean, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            facts.title.ifBlank { facts.sourceId.ifBlank { stringResource(R.string.work_unnamed) } },
            style = MaterialTheme.typography.titleLarge,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        FlowRow(
            Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (facts.sourceId.isNotBlank()) LabelBadge(facts.sourceId, MaterialTheme.colorScheme.primary)
            if (purchased) LabelBadge(stringResource(R.string.work_purchased), MaterialTheme.colorScheme.tertiary)
        }
        FactLine(Icons.Outlined.Groups, facts.circle.ifBlank { stringResource(R.string.work_circle_unknown) }, maxLines = 1)
        FactLine(Icons.Outlined.Mic, facts.vas.joinToString("、").ifBlank { stringResource(R.string.work_va_unknown) }, maxLines = 2)
        if (facts.release.isNotBlank()) FactLine(Icons.Outlined.CalendarMonth, facts.release, maxLines = 1)
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
