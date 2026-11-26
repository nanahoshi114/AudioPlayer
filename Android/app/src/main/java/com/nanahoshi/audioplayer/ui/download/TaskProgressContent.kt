package com.nanahoshi.audioplayer.ui.download

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.data.ProgressPart
import com.nanahoshi.audioplayer.data.TaskKey
import com.nanahoshi.audioplayer.data.TaskProgressSnapshot
import com.nanahoshi.audioplayer.data.TaskProgressSummary
import com.nanahoshi.audioplayer.data.commonText
import com.nanahoshi.audioplayer.data.decodeProgressSummary
import com.nanahoshi.audioplayer.data.detailLines
import com.nanahoshi.audioplayer.data.fraction
import com.nanahoshi.audioplayer.data.progressDuration
import com.nanahoshi.audioplayer.data.progressPartLabel
import com.nanahoshi.audioplayer.data.progressResultLabel
import com.nanahoshi.audioplayer.ui.components.RoundedProgress

@Composable
internal fun TaskProgressContent(
    key: TaskKey,
    rawSummary: String?,
    runtime: TaskProgressSnapshot?,
    active: Boolean,
    legacyFraction: Float? = null,
    dependency: TaskProgressSnapshot? = null,
    decodedSummary: TaskProgressSummary? = null,
) {
    var expanded by rememberSaveable(key.kind.name, key.id, key.child?.name) { mutableStateOf(false) }
    val context = LocalContext.current
    val live = runtime?.takeIf { active && it.active }
    val baseSummary = decodedSummary ?: remember(rawSummary) { decodeProgressSummary(rawSummary) }
    val parts = remember(baseSummary, runtime) {
        if (runtime == null) baseSummary.parts
        else baseSummary.parts.toMutableMap().apply { put(runtime.part, runtime.summary) }
    }
    val visibleParts = remember(parts, key.child) {
        if (key.child != null) parts.filterKeys { it == key.child }
        else parts.filterKeys { it != ProgressPart.BILI_SUBTITLE }
    }
    Column(Modifier.fillMaxWidth()) {
        if (live != null) {
            Text(live.commonText(context), style = MaterialTheme.typography.bodySmall)
        } else if (!active) {
            val recent = visibleParts.values.lastOrNull()
            Text(
                recent?.let {
                    val resultText = it.result?.let { result ->
                        progressResultLabel(context, runtime?.part ?: visibleParts.keys.last(), result)
                    } ?: "—"
                    "$resultText · ${stringResource(R.string.progress_elapsed, progressDuration(context, it.elapsedMs))}"
                } ?: stringResource(R.string.progress_history),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (active) RoundedProgress(
            progress = live?.fraction() ?: legacyFraction.takeIf { live == null },
            modifier = Modifier.padding(top = 8.dp),
        )
        dependency?.takeIf { it.active }?.let {
            Text(
                stringResource(
                    R.string.progress_dependency,
                    it.summary.phase?.let { phase -> stringResource(phase.labelRes) } ?: stringResource(R.string.progress_waiting),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(it.commonText(context), style = MaterialTheme.typography.bodySmall)
            RoundedProgress(progress = it.fraction(), modifier = Modifier.padding(top = 4.dp))
        }
        if (visibleParts.isNotEmpty() || live != null) {
            TextButton(onClick = { expanded = !expanded }) { Text(stringResource(if (expanded) R.string.progress_collapse else R.string.progress_details)) }
            if (expanded) {
                if (visibleParts.isEmpty()) Text(stringResource(R.string.progress_history), style = MaterialTheme.typography.bodySmall)
                visibleParts.forEach { (part, summary) ->
                    Text(progressPartLabel(context, part), style = MaterialTheme.typography.labelMedium)
                    summary.detailLines(context, runtime?.takeIf { it.part == part }).forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
