package com.nanahoshi.audioplayer.ui.download

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.dlsite.RemotePlayable
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nanahoshi.audioplayer.data.db.DownloadEntity
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.TranslateMark
import com.nanahoshi.audioplayer.data.db.TranslationJobEntity
import com.nanahoshi.audioplayer.data.db.WhisperJobEntity
import com.nanahoshi.audioplayer.graph
import com.nanahoshi.audioplayer.ui.EmptyHint

class DownloadViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
    val jobs = graph.downloads.observe()
    val whisperJobs = graph.whisper.observe()
    val translationJobs = graph.db.translations().observe()

    fun retry(id: Long) {
        graph.downloads.retry(id)
    }

    fun retryWhisper(id: Long) {
        graph.whisper.retry(id)
    }

    fun cancelWhisper(id: Long) {
        graph.whisper.cancel(id)
    }

    fun cancelTranslation(id: Long) {
        graph.translator.cancel(id)
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
    val whisperJobs by viewModel.whisperJobs.collectAsStateWithLifecycle(emptyList())
    val translationJobs by viewModel.translationJobs.collectAsStateWithLifecycle(emptyList())
    Column(Modifier.fillMaxSize()) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        if (jobs.isEmpty() && whisperJobs.isEmpty() && translationJobs.isEmpty()) {
            EmptyHint("还没有下载。在作品里可以选择单个音频，或缓存整个文件夹。")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(jobs, key = { "download-${it.id}" }) { job ->
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
                if (whisperJobs.isNotEmpty() || translationJobs.isNotEmpty()) {
                    item(key = "whisper-heading") {
                        Text(
                            "生成字幕",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
                        )
                    }
                    items(whisperJobs, key = { "whisper-${it.id}" }) { job ->
                        WhisperJobRow(
                            job,
                            onRetry = { viewModel.retryWhisper(job.id) },
                            onCancel = { viewModel.cancelWhisper(job.id) },
                        )
                    }
                    items(translationJobs, key = { "translation-${it.id}" }) { job ->
                        TranslationJobRow(job, onCancel = { viewModel.cancelTranslation(job.id) })
                    }
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

@Composable
private fun WhisperJobRow(job: WhisperJobEntity, onRetry: () -> Unit, onCancel: () -> Unit) {
    val translating = job.translateStatus == TranslateMark.RUNNING
    val stopped = job.error == "已中止"
    val reply = job.error?.takeIf { job.translateStatus == TranslateMark.FAILED && it.isNotBlank() && !stopped }
    var shownReply by remember { mutableStateOf<String?>(null) }
    val canStop = translating || job.status == DownloadStatus.QUEUED || job.status == DownloadStatus.RUNNING
    val status = when {
        job.translateStatus == TranslateMark.FAILED && stopped -> "已中止"
        translating -> "翻译中"
        job.translateStatus == TranslateMark.FAILED -> "听写已完成，翻译失败"
        else -> when (job.status) {
            DownloadStatus.QUEUED -> "排队"
            DownloadStatus.RUNNING -> "听写中"
            DownloadStatus.DONE -> "完成"
            DownloadStatus.FAILED -> job.error ?: "失败"
        }
    }
    ListItem(
        headlineContent = { Text(job.title) },
        supportingContent = {
            Column(Modifier.fillMaxWidth()) {
                Text(status)
                if (translating) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                } else if (job.status == DownloadStatus.RUNNING) {
                    LinearProgressIndicator(
                        progress = { job.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        },
        trailingContent = {
            when {
                canStop -> TextButton(onClick = onCancel) { Text("中止") }
                job.status == DownloadStatus.FAILED -> TextButton(onClick = onRetry) { Text("重试") }
                reply != null -> TextButton(onClick = { shownReply = reply }) { Text("查看返回") }
            }
        },
    )
    shownReply?.let { ReplyDialog(it) { shownReply = null } }
}

@Composable
private fun TranslationJobRow(job: TranslationJobEntity, onCancel: () -> Unit) {
    val stopped = job.status == DownloadStatus.FAILED && job.error == "已中止"
    val reply = job.error?.takeIf {
        job.status == DownloadStatus.FAILED && it.isNotBlank() && it != "翻译失败" && it != "已中止"
    }
    var shownReply by remember { mutableStateOf<String?>(null) }
    val running = job.status == DownloadStatus.RUNNING || job.status == DownloadStatus.QUEUED
    val status = when {
        stopped -> "已中止"
        running -> "翻译中"
        job.status == DownloadStatus.FAILED -> "翻译失败"
        else -> "完成"
    }
    ListItem(
        headlineContent = { Text(job.title) },
        supportingContent = {
            Column(Modifier.fillMaxWidth()) {
                Text(status)
                if (running) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        },
        trailingContent = {
            when {
                running -> TextButton(onClick = onCancel) { Text("中止") }
                reply != null -> TextButton(onClick = { shownReply = reply }) { Text("查看返回") }
            }
        },
    )
    shownReply?.let { ReplyDialog(it) { shownReply = null } }
}

@Composable
private fun ReplyDialog(text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Text(
                text,
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
