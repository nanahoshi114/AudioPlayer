package com.nanahoshi.audioplayer.ui.asmr

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.nanahoshi.audioplayer.asmr.WorkFacts
import com.nanahoshi.audioplayer.ui.CoverImage

@Composable
fun WorkFactsBlock(
    facts: WorkFacts,
    coverUrl: String?,
    singleLine: Boolean,
    modifier: Modifier = Modifier,
    showCover: Boolean = true,
    footnote: String? = null,
    expandTitle: Boolean = false,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (showCover) CoverImage(coverUrl)
        Column(Modifier.padding(start = if (showCover) 12.dp else 0.dp).weight(1f)) {
            FactLine(null, facts.title.ifBlank { facts.sourceId }, singleLine && !expandTitle, emphasize = true)
            FactLine("社团", facts.circle.ifBlank { "—" }, singleLine)
            FactLine("声优", facts.vas.joinToString("、").ifBlank { "—" }, singleLine)
            FactLine(null, facts.sourceId.ifBlank { "—" }, singleLine)
            FactLine("发售", facts.release.ifBlank { "—" }, singleLine)
            FactLine("标签", facts.tags.joinToString("、").ifBlank { "—" }, singleLine)
            if (!footnote.isNullOrBlank()) FactLine(null, footnote, singleLine)
        }
    }
}

@Composable
private fun FactLine(label: String?, value: String, singleLine: Boolean, emphasize: Boolean = false) {
    val body = if (label == null) {
        buildAnnotatedString { append(value) }
    } else {
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)) {
                append(label)
            }
            append(" ")
            append(value)
        }
    }
    Text(
        text = body,
        style = if (emphasize) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
        color = if (emphasize) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (singleLine) 1 else Int.MAX_VALUE,
        overflow = if (singleLine) TextOverflow.Ellipsis else TextOverflow.Clip,
    )
}
