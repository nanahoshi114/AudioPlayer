package com.nanahoshi.audioplayer.ui.download

import android.app.Application
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.nanahoshi.audioplayer.ui.components.AppTopBar
import com.nanahoshi.audioplayer.ui.components.EmptyState
import com.nanahoshi.audioplayer.ui.components.RoundedProgress
import com.nanahoshi.audioplayer.ui.components.SectionHeader
import com.nanahoshi.audioplayer.ui.components.StatusBadge
import com.nanahoshi.audioplayer.ui.components.StatusTone
import com.nanahoshi.audioplayer.ui.components.rememberLocalNetworkAccess
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

    /** 重试会沿用任务自己的听写地址；局域网地址要先拿到本地网络权限。 */
    fun retryNeedsLocalNetwork(job: WhisperJobEntity): Boolean = graph.whisper.needsLocalNetwork(job)

    fun reportLocalNetworkDenied() {
        graph.messages.value = "需要允许访问本地网络，才能连接这台设备"
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadScreen(
    onBack: () -> Unit,
    viewModel: DownloadViewModel = viewModel(),
) {
    val jobs by viewModel.jobs.collectAsStateWithLifecycle(emptyList())
    val whisperJobs by viewModel.whisperJobs.collectAsStateWithLifecycle(emptyList())
    val translationJobs by viewModel.translationJobs.collectAsStateWithLifecycle(emptyList())
    val localNetwork = rememberLocalNetworkAccess { viewModel.reportLocalNetworkDenied() }
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "下载", onBack = onBack)
        if (jobs.isEmpty() && whisperJobs.isEmpty() && translationJobs.isEmpty()) {
            EmptyState(
                title = "还没有下载",
                message = "在作品里可以选择单个音频，或缓存整个文件夹。",
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                if (jobs.isNotEmpty()) {
                    stickyHeader(key = "download-heading") { SectionHeader("下载") }
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
                }
                if (whisperJobs.isNotEmpty() || translationJobs.isNotEmpty()) {
                    stickyHeader(key = "whisper-heading") { SectionHeader("生成字幕") }
                    items(whisperJobs, key = { "whisper-${it.id}" }) { job ->
                        WhisperJobRow(
                            job,
                            onRetry = {
                                localNetwork.run(viewModel.retryNeedsLocalNetwork(job)) {
                                    viewModel.retryWhisper(job.id)
                                }
                            },
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
        DownloadStatus.FAILED -> "失败"
    }
    ListItem(
        modifier = if (onPlay != null) Modifier.clickable(onClick = onPlay) else Modifier,
        headlineContent = { Text(job.title) },
        supportingContent = {
            Column(Modifier.fillMaxWidth()) {
                StatusBadge(status, toneFor(job.status))
                if (job.status == DownloadStatus.FAILED && !job.error.isNullOrBlank()) {
                    Text(job.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (job.status == DownloadStatus.RUNNING || (job.status == DownloadStatus.QUEUED && job.bytesTotal > 0)) {
                    RoundedProgress(
                        progress = if (job.bytesTotal > 0) job.bytesDone.toFloat() / job.bytesTotal else null,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        trailingContent = {
            if (job.status == DownloadStatus.FAILED) {
                FilledTonalButton(onClick = onRetry) { Text("重试") }
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
                StatusBadge(status, whisperTone(job, translating, stopped))
                if (translating || job.status == DownloadStatus.RUNNING || job.status == DownloadStatus.QUEUED) {
                    RoundedProgress(
                        progress = if (translating || job.progress <= 0f) null else job.progress,
                        modifier = Modifier.padding(top = 8.dp),
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
                StatusBadge(
                    status,
                    when {
                        stopped -> StatusTone.Neutral
                        running -> StatusTone.Active
                        job.status == DownloadStatus.FAILED -> StatusTone.Error
                        else -> StatusTone.Success
                    },
                )
                if (running) {
                    RoundedProgress(progress = null, modifier = Modifier.padding(top = 8.dp))
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
private fun toneFor(status: DownloadStatus) = when (status) {
    DownloadStatus.QUEUED -> StatusTone.Neutral
    DownloadStatus.RUNNING -> StatusTone.Active
    DownloadStatus.DONE -> StatusTone.Success
    DownloadStatus.FAILED -> StatusTone.Error
}

private fun whisperTone(job: WhisperJobEntity, translating: Boolean, stopped: Boolean) = when {
    stopped -> StatusTone.Neutral
    translating -> StatusTone.Active
    job.translateStatus == TranslateMark.FAILED -> StatusTone.Error
    job.status == DownloadStatus.FAILED -> StatusTone.Error
    job.status == DownloadStatus.DONE -> StatusTone.Success
    job.status == DownloadStatus.RUNNING -> StatusTone.Active
    else -> StatusTone.Neutral
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
