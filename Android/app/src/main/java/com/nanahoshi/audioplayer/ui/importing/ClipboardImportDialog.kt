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
    onRetry: () -> Unit,
) {
    val preview = state.preview
    val multiPartBili = preview?.target?.source == ClipboardSource.BILIBILI && preview.parts.size > 1
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = !state.saving, dismissOnClickOutside = !state.saving),
        title = { Text("导入这个内容？") },
        text = { Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CoverImage(preview?.cover, size = 88.dp, modifier = Modifier.semantics { contentDescription = "导入内容封面" })
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(preview?.title ?: if (state.loading) "正在读取标题和封面…" else "无法读取标题和封面",
                        style = MaterialTheme.typography.titleMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Text("${state.candidate.target.source.label} · ${state.candidate.target.id}", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (preview?.alreadySaved == true) Text(if (multiPartBili) "已在音频库，可调整导入方式" else "已在音频库", Modifier.padding(top = 12.dp))
            if (state.loading || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            if (state.saving) Text("正在导入…", Modifier.padding(top = 8.dp))
            state.error?.let { Text(it, Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = {
            if (preview == null && state.error != null) {
                TextButton(onClick = onRetry, enabled = !state.loading) { Text("重试") }
            } else if (preview?.alreadySaved == true && preview.target.source != ClipboardSource.BILIBILI) {
                // 已保存的内容没有可执行动作，状态放在正文里，不把状态做成禁用按钮。
            } else {
                TextButton(onClick = onConfirm, enabled = state.canImport) {
                    Text(when {
                        multiPartBili -> "选择导入方式"
                        else -> "导入"
                    })
                }
            }
        },
        dismissButton = {
            val alreadySaved = preview?.alreadySaved == true && preview.target.source != ClipboardSource.BILIBILI
            TextButton(onClick = onDismiss, enabled = !state.saving) { Text(if (alreadySaved) "关闭" else "取消") }
        },
    )
}
