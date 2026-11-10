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
    associationCount: Int = 0,
) {
    val withSubtitles = subtitleCount > 0
    val mixed = associationCount > 0
    val message = buildList {
        if (mixed) add("将删除 ${itemCount} 个下载副本，并取消 ${associationCount} 首曲目的本地关联。手机上的原文件会保留。")
        if (withSubtitles) {
            add("待删除的 ${itemCount} 个文件中，${subtitleCount} 个音频带有生成字幕。保留字幕后，重新下载同一音频时会自动匹配。")
            add("生成的字幕文件也可在「设置 → 下载管理 → 字幕」中单独删除。")
        } else if (!mixed) {
            add("删除选中的 ${itemCount} 个文件？")
        }
    }.joinToString("\n\n")
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (mixed) "删除副本并取消本地关联" else if (withSubtitles) "音频带有生成字幕" else "删除文件") },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = { onConfirm(true) }) {
                Text(if (withSubtitles) "删除音频和字幕" else if (mixed) "确认" else "删除")
            }
        },
        dismissButton = {
            Row {
                if (withSubtitles) TextButton(onClick = { onConfirm(false) }) {
                    Text(if (mixed) "保留生成字幕" else "只删除音频")
                }
                TextButton(onClick = onCancel) { Text("取消") }
            }
        },
    )
}
