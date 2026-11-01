package com.nanahoshi.audioplayer.ui.importing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.DialogProperties
import com.nanahoshi.audioplayer.data.ClipboardImportState
import com.nanahoshi.audioplayer.ui.CoverImage
import com.nanahoshi.audioplayer.ui.formatDuration

@Composable
internal fun ClipboardImportDialog(
    state: ClipboardImportState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
    onTogglePart: (Long) -> Unit,
    onToggleAll: () -> Unit,
) {
    val preview = state.preview
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = !state.saving, dismissOnClickOutside = !state.saving),
        title = { Text("是否要导入：") },
        text = { Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CoverImage(preview?.cover, size = 88.dp, modifier = Modifier.semantics { contentDescription = "导入内容封面" })
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(preview?.title ?: if (state.loading) "正在读取标题和封面…" else "暂时无法读取内容",
                        style = MaterialTheme.typography.titleMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Text("${state.candidate.target.source.label} · ${state.candidate.target.id}", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (preview?.alreadySaved == true) Text("已在音频库", Modifier.padding(top = 12.dp))
            if (preview != null && preview.parts.size > 1 && !preview.alreadySaved) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("已选 ${state.selected.size} / ${preview.parts.size} P", Modifier.weight(1f))
                    TextButton(onClick = onToggleAll, enabled = !state.saving) { Text("全选 / 取消全选") }
                }
                LazyColumn(Modifier.heightIn(max = 220.dp)) {
                    items(preview.parts, key = { it.cid }) { part ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = !state.saving) { onTogglePart(part.cid) }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(part.cid in state.selected, { onTogglePart(part.cid) }, enabled = !state.saving)
                            Column(Modifier.weight(1f)) {
                                Text("P${part.page} · ${part.title}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(formatDuration(part.durationMs), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            if (state.loading || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            if (state.saving) Text("正在导入…", Modifier.padding(top = 8.dp))
            state.error?.let { Text(it, Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = {
            if (preview == null && state.error != null) TextButton(onClick = onRetry, enabled = !state.loading) { Text("重试") }
            else TextButton(onClick = onConfirm, enabled = state.canImport) {
                Text(if (preview?.alreadySaved == true) "已在音频库" else "导入")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.saving) { Text("取消") } },
    )
}
