package com.nanahoshi.audioplayer.ui.download

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.dlsite.RemotePlayable
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nanahoshi.audioplayer.data.db.DownloadEntity
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.graph
import com.nanahoshi.audioplayer.ui.EmptyHint

class DownloadViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
    val jobs = graph.downloads.observe()

    fun retry(id: Long) {
        graph.downloads.retry(id)
    }

    fun play(job: DownloadEntity) {
        val path = job.localPath ?: return
        graph.player.replaceAndPlayRemote(
            listOf(
                RemotePlayable(
                    title = job.title,
                    artist = null,
                    coverUri = null,
                    remoteUrl = path,
                    source = TrackSource.LOCAL,
                    referer = "",
                ),
            ),
        )
    }
}

@Composable
fun DownloadScreen(
    onBack: () -> Unit,
    viewModel: DownloadViewModel = viewModel(),
) {
    val jobs by viewModel.jobs.collectAsStateWithLifecycle(emptyList())
    Column(Modifier.fillMaxSize()) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        if (jobs.isEmpty()) {
            EmptyHint("还没有下载。在作品里可以选择单个音频，或缓存整个文件夹。")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(jobs, key = { it.id }) { job ->
                    DownloadRow(
                        job,
                        onRetry = { viewModel.retry(job.id) },
                        onPlay = if (job.status == DownloadStatus.DONE && !job.localPath.isNullOrBlank()) {
                            { viewModel.play(job) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(job: DownloadEntity, onRetry: () -> Unit, onPlay: (() -> Unit)?) {
    val status = when (job.status) {
        DownloadStatus.QUEUED -> "排队"
        DownloadStatus.RUNNING -> "下载中"
        DownloadStatus.DONE -> "完成"
        DownloadStatus.FAILED -> job.error ?: "失败"
    }
    ListItem(
        modifier = if (onPlay != null) Modifier.clickable(onClick = onPlay) else Modifier,
        headlineContent = { Text(job.title) },
        supportingContent = {
            Column(Modifier.fillMaxWidth()) {
                Text(status)
                if (job.status == DownloadStatus.RUNNING || job.status == DownloadStatus.QUEUED) {
                    if (job.bytesTotal > 0) {
                        LinearProgressIndicator(
                            progress = { (job.bytesDone.toFloat() / job.bytesTotal).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    } else if (job.status == DownloadStatus.RUNNING) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                }
            }
        },
        trailingContent = {
            if (job.status == DownloadStatus.FAILED) {
                TextButton(onClick = onRetry) { Text("重试") }
            }
        },
    )
}
