package com.nanahoshi.audioplayer.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/** 删除音频前的确认；有生成字幕时让用户决定是否一并删除。 */
@Composable
internal fun DeleteAudioDialog(
    itemCount: Int,
    subtitleCount: Int,
    onConfirm: (deleteSubtitles: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    val withSubtitles = subtitleCount > 0
    AlertDialog(
        onDismissRequest = onCancel,
        text = {
            Text(
                if (withSubtitles) {
                    "选中的 $itemCount 个文件里有 $subtitleCount 个音频带有生成的字幕。要一起删除这些字幕吗？" +
                        "保留后重新下载同一音频会自动匹配，无需重新生成；生成的字幕文件也可在「设置 → 下载管理 → 字幕」中单独删除。"
                } else {
                    "删除选中的 $itemCount 个文件？"
                },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(true) }) {
                Text(if (withSubtitles) "同时删除字幕" else "删除")
            }
        },
        dismissButton = {
            Row {
                if (withSubtitles) TextButton(onClick = { onConfirm(false) }) { Text("保留字幕") }
                TextButton(onClick = onCancel) { Text("取消") }
            }
        },
    )
}
