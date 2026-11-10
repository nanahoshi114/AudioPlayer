package com.nanahoshi.audioplayer.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanahoshi.audioplayer.data.DlsiteDownloadMode
import com.nanahoshi.audioplayer.data.DlsiteDownloadRequests

@Composable
fun DlsiteDownloadDialog(requests: DlsiteDownloadRequests) {
    val pending by requests.pending.collectAsStateWithLifecycle()
    val prompt = pending ?: return
    AlertDialog(
        onDismissRequest = { requests.answer(prompt.id, null) },
        title = { Text("选择下载方式") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    if (prompt.allowPartial) {
                        "下载部分文件时，DLsite Play 可能会压缩音频、降低音质。是否改为下载完整原始包并自动解压？"
                    } else {
                        "所选内容里有无法单独下载的文件，需要下载完整原始包并自动解压。"
                    },
                )
                Text("完整原始包的音质以作者提供的文件为准。", style = MaterialTheme.typography.bodySmall)
                if (prompt.workNames.isNotEmpty()) {
                    Text(prompt.workNames.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth()) {
                TextButton(onClick = { requests.answer(prompt.id, DlsiteDownloadMode.ARCHIVE) }, modifier = Modifier.fillMaxWidth()) {
                    Text("下载完整原始包")
                }
                if (prompt.allowPartial) {
                    TextButton(onClick = { requests.answer(prompt.id, DlsiteDownloadMode.PARTIAL) }, modifier = Modifier.fillMaxWidth()) {
                        Text("仅下载所选文件")
                    }
                }
                TextButton(onClick = { requests.answer(prompt.id, null) }, modifier = Modifier.fillMaxWidth()) {
                    Text("取消")
                }
            }
        },
    )
}
