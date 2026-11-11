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
import com.nanahoshi.audioplayer.data.ClipboardSource
import com.nanahoshi.audioplayer.ui.CoverImage
import com.nanahoshi.audioplayer.ui.formatDuration

@Composable
internal fun ClipboardImportDialog(
    state: ClipboardImportState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onOpenDetails: (String) -> Unit,
) {
    val preview = state.preview
    val multiPartBili = preview.target.source == ClipboardSource.BILIBILI && preview.parts.size > 1
    val sourceLabel = if (preview.dlsiteWork != null) "DLsite" else preview.target.source.label
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = !state.saving, dismissOnClickOutside = !state.saving),
        title = { Text(if (preview.detailsOnly) "找到作品" else "导入这个内容？") },
        text = { Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CoverImage(preview.cover, size = 88.dp, modifier = Modifier.semantics { contentDescription = "导入内容封面" })
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(preview.title,
                        style = MaterialTheme.typography.titleMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Text("$sourceLabel · ${preview.target.id}", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (preview.alreadySaved) Text(if (multiPartBili) "已在音频库，可调整导入方式" else "已在音频库", Modifier.padding(top = 12.dp))
            if (preview.detailsOnly) Text("没有有效来源，无法加入音频库", Modifier.padding(top = 12.dp))
            if (state.saving) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                Text("正在导入…", Modifier.padding(top = 8.dp))
            }
            state.error?.let { Text(it, Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = {
            if (preview.detailsOnly) {
                TextButton(onClick = { onOpenDetails(preview.target.id) }) { Text("查看详情页") }
            } else if (!preview.alreadySaved || preview.target.source == ClipboardSource.BILIBILI) {
                TextButton(onClick = onConfirm, enabled = state.canImport) {
                    Text(when {
                        multiPartBili -> "选择导入方式"
                        else -> "导入"
                    })
                }
            }
        },
        dismissButton = {
            val alreadySaved = preview.alreadySaved && preview.target.source != ClipboardSource.BILIBILI
            TextButton(onClick = onDismiss, enabled = !state.saving) { Text(if (alreadySaved) "关闭" else "取消") }
        },
    )
}
