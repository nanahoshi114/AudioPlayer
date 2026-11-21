package com.nanahoshi.audioplayer.ui.download

import com.nanahoshi.audioplayer.data.ProgressResult
import com.nanahoshi.audioplayer.data.SubtitleTask
import com.nanahoshi.audioplayer.text.resolveNoUrl
import androidx.annotation.StringRes
import androidx.compose.ui.platform.LocalContext
import com.nanahoshi.audioplayer.R
import androidx.compose.ui.res.stringResource
import com.nanahoshi.audioplayer.japaneseasmr.JA_WORK_DOWNLOAD
import com.nanahoshi.audioplayer.japaneseasmr.JA_VERIFY_PHASE

import android.app.Application
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import com.nanahoshi.audioplayer.ui.components.ConfirmActionDialog
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
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
import com.nanahoshi.audioplayer.ui.components.StatusBadge
import com.nanahoshi.audioplayer.ui.components.StatusTone
import com.nanahoshi.audioplayer.ui.components.rememberLocalNetworkAccess
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.nanahoshi.audioplayer.data.*
import com.nanahoshi.audioplayer.text.PersistedErrors
import com.nanahoshi.audioplayer.text.asString
import com.nanahoshi.audioplayer.text.persistedErrorText
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.DlsiteDownloadMode
import com.nanahoshi.audioplayer.dlsite.RemotePlayable
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nanahoshi.audioplayer.data.operation
import com.nanahoshi.audioplayer.data.db.DownloadEntity
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.TranslateMark
import com.nanahoshi.audioplayer.data.db.TranslationJobEntity
import com.nanahoshi.audioplayer.data.db.WhisperJobEntity
import com.nanahoshi.audioplayer.data.missingOfflineWhisperModelMessage
import com.nanahoshi.audioplayer.graph
import kotlinx.coroutines.launch

class DownloadViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
    val progress = graph.taskProgress.snapshots
    val jobs = graph.downloads.observe()
    val whisperJobs = graph.whisper.observe()
    val translationJobs = graph.db.translations().observe()
    val dependencies = combine(jobs, whisperJobs) { _, rows ->
        rows.filter { it.status == DownloadStatus.QUEUED && it.fingerprint.isEmpty() }
            .mapNotNull { row -> graph.whisper.progressDependency(row)?.let { row.id to it } }.toMap()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun cancelDownload(id: Long) { graph.downloads.cancel(id) }

    fun verifyJapaneseWork(id: Long, navigate: (String) -> Unit) {
        viewModelScope.launch {
            try {
                navigate(graph.downloads.beginJaTaskVerification(id))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.showError(failure, R.string.download_error_continue)
            }
        }
    }

    fun retry(id: Long) {
        viewModelScope.launch {
            try {
                graph.downloads.retry(id)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                graph.showError(error, R.string.download_error_retry)
            }
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch { graph.downloads.deleteFailed(id) }
    }

    fun retryWhisper(id: Long) {
        viewModelScope.launch {
            val job = graph.db.whisper().get(id) ?: return@launch
            graph.whisper.missingOfflineModel(job.model)?.let {
                graph.show(missingOfflineWhisperModelMessage(it))
                return@launch
            }
            var mode = DlsiteDownloadMode.PARTIAL
            if (graph.whisper.needsDlsiteDownloadChoice(job)) {
                val track = job.trackId?.takeIf { it > 0L }?.let { graph.db.tracks().getById(it) }
                mode = graph.dlsiteDownloadRequests.choose(
                    listOf(job.title),
                    allowPartial = track == null || !track.fileHash.isNullOrBlank(),
                ) ?: return@launch
            }
            graph.whisper.retry(id, mode)?.let(graph::show)
        }
    }

    /** 重试会沿用任务自己的听写地址；局域网地址要先拿到本地网络权限。 */
    fun retryNeedsLocalNetwork(job: WhisperJobEntity): Boolean = graph.whisper.needsLocalNetwork(job)

    fun reportLocalNetworkDenied() {
        graph.show(R.string.whisper_error_local_network)
    }

    fun cancelWhisper(id: Long) {
        graph.whisper.cancel(id)
    }

    fun cancelTranslation(id: Long) {
        graph.translator.cancel(id)
    }

    fun retryTranslation(job: TranslationJobEntity) {
        viewModelScope.launch {
            try {
                graph.translator.retry(job.id, job.operation)
            } catch (stopped: com.nanahoshi.audioplayer.data.SubtitleTaskStopped) {
                graph.show(stopped.userText)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                graph.showError(error, R.string.download_error_retry_subtitle)
            }
        }
    }

    fun openArticle(job: DownloadEntity) {
        viewModelScope.launch { openJapaneseArticle(getApplication(), graph, if (job.kind == JA_WORK_DOWNLOAD) job.fileKey else job.workKey) }
    }

    fun play(job: DownloadEntity) {
        val path = job.localPath ?: return
        viewModelScope.launch {
            // 已经入库的曲目按曲目播放，队列才带 bvid/cid，缓存过的字幕也能读到。
            val track = job.trackId.takeIf { it > 0L }?.let { graph.library.track(it) }
            if (track != null && track.localUri == path) {
                graph.player.replaceAndPlay(listOf(track))
                return@launch
            }
            if (job.source == TrackSource.JAPANESE_ASMR.name) {
                val postId = job.workKey ?: return@launch
                val key = job.fileKey ?: return@launch
                val post = graph.db.japaneseAsmr().post(postId) ?: return@launch
                graph.player.replaceAndPlayRemote(listOf(RemotePlayable(
                    job.title, post.title, post.coverUri, post.url, TrackSource.JAPANESE_ASMR,
                    com.nanahoshi.audioplayer.japaneseasmr.JapaneseAsmrClient.REFERER,
                    workno = postId, fileKey = key, mediaKind = graph.db.japaneseAsmr().media(postId, key)?.kind,
                )))
                return@launch
            }
            // 没入库的 B 站缓存（例如从收藏夹直接缓存）借 workno/fileKey 带上 bvid/cid，
            // 播放队列里不新增列，字幕也能按这两个字段找回。
            val bili = job.source == TrackSource.BILIBILI.name
            val youtube = job.source == TrackSource.YOUTUBE.name
            graph.player.replaceAndPlayRemote(
                listOf(
                    RemotePlayable(
                        title = job.title,
                        artist = null,
                        coverUri = null,
                        remoteUrl = if (youtube) "https://www.youtube.com/watch?v=${job.workKey.orEmpty()}" else path,
                        source = if (youtube) TrackSource.YOUTUBE else if (bili) TrackSource.BILIBILI else TrackSource.LOCAL,
                        referer = if (bili) "https://www.bilibili.com/video/${job.workKey.orEmpty()}" else "",
                        workno = job.workKey.takeIf { bili || youtube },
                        fileKey = job.fileKey.takeIf { bili || youtube },
                    ),
                ),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadScreen(
    onBack: () -> Unit,
    viewModel: DownloadViewModel = viewModel(),
    initialSubtitles: Boolean = false,
    onVerifyJaDownload: (String) -> Unit = {},
) {
    val jobs by viewModel.jobs.collectAsStateWithLifecycle(emptyList())
    val whisperJobs by viewModel.whisperJobs.collectAsStateWithLifecycle(emptyList())
    val translationJobs by viewModel.translationJobs.collectAsStateWithLifecycle(emptyList())
    val snapshots by viewModel.progress.collectAsStateWithLifecycle()
    val dependencies by viewModel.dependencies.collectAsStateWithLifecycle()
    val localNetwork = rememberLocalNetworkAccess { viewModel.reportLocalNetworkDenied() }
    var subtitles by rememberSaveable { mutableStateOf(initialSubtitles) }
    val audioState = rememberLazyListState()
    val subtitleState = rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = stringResource(R.string.download_tasks_title), onBack = onBack)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            SegmentedButton(selected = !subtitles, onClick = { subtitles = false },
                shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.download_segment_download)) }
            SegmentedButton(selected = subtitles, onClick = { subtitles = true },
                shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(R.string.download_segment_subtitle)) }
        }
        if (!subtitles && jobs.any { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED }) {
            Text(stringResource(
                R.string.download_summary,
                jobs.count { it.status == DownloadStatus.RUNNING },
                jobs.count { it.status == DownloadStatus.QUEUED },
                progressRate(totalDownloadRate(snapshots)),
            ),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
        }
        if (if (subtitles) whisperJobs.isEmpty() && translationJobs.isEmpty() else jobs.isEmpty()) {
            EmptyState(
                title = stringResource(
                    if (subtitles) R.string.download_tasks_empty_subtitle_title
                    else R.string.download_tasks_empty_audio_title,
                ),
                message = stringResource(
                    if (subtitles) R.string.download_tasks_empty_subtitle_message
                    else R.string.download_tasks_empty_audio_message,
                ),
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(state = if (subtitles) subtitleState else audioState, modifier = Modifier.fillMaxSize()) {
                if (!subtitles && jobs.isNotEmpty()) {
                    items(jobs, key = { "download-${it.id}" }) { job ->
                        DownloadRow(
                            job,
                            runtime = snapshots[TaskKey(TaskKind.DOWNLOAD, job.id)],
                            subtitleRuntime = snapshots[TaskKey(TaskKind.DOWNLOAD, job.id, ProgressPart.BILI_SUBTITLE)],
                            onRetry = {
                                if (job.phase == JA_VERIFY_PHASE) viewModel.verifyJapaneseWork(job.id, onVerifyJaDownload)
                                else viewModel.retry(job.id)
                            },
                            onCancel = { viewModel.cancelDownload(job.id) },
                            onDelete = { viewModel.delete(job.id) },
                            onOpenArticle = if (job.source == TrackSource.JAPANESE_ASMR.name) ({ viewModel.openArticle(job) }) else null,
                            onPlay = if (job.kind !in setOf("DLSITE_ARCHIVE", JA_WORK_DOWNLOAD) && job.status == DownloadStatus.DONE && !job.localPath.isNullOrBlank()) {
                                { viewModel.play(job) }
                            } else {
                                null
                            },
                        )
                    }
                }
                if (subtitles && (whisperJobs.isNotEmpty() || translationJobs.isNotEmpty())) {
                    items(whisperJobs, key = { "whisper-${it.id}" }) { job ->
                        WhisperJobRow(
                            job,
                            runtime = snapshots[TaskKey(TaskKind.WHISPER, job.id)],
                            dependency = dependencies[job.id]?.let { snapshots[TaskKey(TaskKind.DOWNLOAD, it)] },
                            onRetry = {
                                localNetwork.run(viewModel.retryNeedsLocalNetwork(job)) {
                                    viewModel.retryWhisper(job.id)
                                }
                            },
                            onCancel = { viewModel.cancelWhisper(job.id) },
                        )
                    }
                    items(translationJobs, key = { "translation-${it.id}" }) { job ->
                        TranslationJobRow(job, runtime = snapshots[TaskKey(TaskKind.TRANSLATION, job.id)], onCancel = { viewModel.cancelTranslation(job.id) }, onRetry = { viewModel.retryTranslation(job) })
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    job: DownloadEntity,
    runtime: TaskProgressSnapshot?,
    subtitleRuntime: TaskProgressSnapshot?,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onPlay: (() -> Unit)?,
    onOpenArticle: (() -> Unit)?,
) {
    var confirming by remember { mutableStateOf(false) }
    var showingError by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val active = job.status == DownloadStatus.RUNNING || job.status == DownloadStatus.QUEUED
    val failureText = taskFailureText(job.error, R.string.download_failed_generic)
    val downloadResult = runtime?.summary?.result
        ?: decodeProgressSummary(job.progressSummary).parts[ProgressPart.DOWNLOAD]?.result
    val status = runtime?.takeIf { active && it.active }?.summary?.phase?.let { stringResource(it.labelRes) } ?: when (job.status) {
        DownloadStatus.QUEUED -> stringResource(R.string.status_queued)
        DownloadStatus.RUNNING -> if (job.kind in setOf("DLSITE_ARCHIVE", JA_WORK_DOWNLOAD)) {
            when (job.phase) {
                "RESOLVING" -> stringResource(R.string.status_resolving)
                "EXTRACTING" -> stringResource(R.string.status_extracting)
                "IMPORTING" -> stringResource(R.string.status_importing)
                else -> stringResource(R.string.status_downloading)
            }
        } else stringResource(R.string.status_downloading)
        DownloadStatus.DONE -> stringResource(R.string.status_done)
        DownloadStatus.FAILED -> when {
            job.phase == JA_VERIFY_PHASE -> stringResource(R.string.status_waiting_verification)
            else -> stringResource(taskFailedLabelRes(job.error, downloadResult))
        }
    }
    ListItem(
        modifier = if (onPlay != null) Modifier.clickable(onClick = onPlay) else Modifier,
        headlineContent = { Text(job.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                val badgeStatus = when {
                    status.length <= 6 -> status
                    job.status == DownloadStatus.QUEUED || job.phase == JA_VERIFY_PHASE ||
                        runtime?.summary?.phase?.waiting == true -> stringResource(R.string.status_waiting)
                    else -> stringResource(R.string.status_running)
                }
                StatusBadge(badgeStatus, toneFor(job.status))
                if (badgeStatus != status) {
                    Text(status, style = MaterialTheme.typography.bodySmall)
                }
                if (job.kind == JA_WORK_DOWNLOAD && active) runtime?.detail?.let {
                    Text(it.asString(), style = MaterialTheme.typography.bodySmall)
                }
                if (onOpenArticle != null) TextButton(onClick = onOpenArticle) { Text(stringResource(R.string.download_open_article)) }
                val failure = failureText
                if (job.status == DownloadStatus.FAILED && !failure.isNullOrBlank()) {
                    TaskFailureMessage(failure) { showingError = true }
                }
                TaskProgressContent(TaskKey(TaskKind.DOWNLOAD, job.id), job.progressSummary, runtime, active,
                    legacyFraction = if (job.kind != "DLSITE_ARCHIVE" && job.bytesTotal > 0) job.bytesDone.toFloat() / job.bytesTotal else null)
                val subtitleSummary = decodeProgressSummary(job.progressSummary).parts[ProgressPart.BILI_SUBTITLE]
                if (job.status == DownloadStatus.DONE && (job.pendingBiliSubtitle == true || subtitleRuntime != null || subtitleSummary != null)) {
                    val subtitleState = subtitleRuntime?.let {
                        if (it.active) it.summary.phase?.let { phase -> stringResource(phase.labelRes) }
                        else it.summary.result?.let { result -> progressResultLabel(context, ProgressPart.BILI_SUBTITLE, result) }
                    } ?: subtitleSummary?.result?.let { progressResultLabel(context, ProgressPart.BILI_SUBTITLE, it) }
                        ?: stringResource(R.string.download_waiting_subtitle)
                    Text(
                        stringResource(R.string.download_bilibili_subtitle, subtitleState),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TaskProgressContent(TaskKey(TaskKind.DOWNLOAD, job.id, ProgressPart.BILI_SUBTITLE),
                        job.progressSummary, subtitleRuntime, subtitleRuntime?.active ?: (job.pendingBiliSubtitle == true))
                }
            }
        },
        trailingContent = {
            if (job.status in setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING)) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.common_stop)) }
            } else if (job.status == DownloadStatus.FAILED) {
                Column(horizontalAlignment = Alignment.End) {
                    FilledTonalButton(onClick = onRetry) { Text(stringResource(if (job.phase == JA_VERIFY_PHASE) R.string.download_verify_continue else R.string.common_retry)) }
                    TextButton(onClick = { confirming = true }) { Text(stringResource(R.string.common_delete)) }
                }
            }
        },
    )
    if (confirming) {
        ConfirmActionDialog(
            title = stringResource(R.string.download_delete_title),
            message = stringResource(
                when (job.kind) {
                    "DLSITE_ARCHIVE" -> R.string.download_delete_archive_message
                    JA_WORK_DOWNLOAD -> R.string.download_delete_ja_message
                    "JAPANESE_ASMR_HLS" -> R.string.download_delete_hls_message
                    else -> R.string.download_delete_default_message
                },
            ),
            confirmLabel = stringResource(R.string.common_delete),
            onConfirm = { confirming = false; onDelete() },
            onDismiss = { confirming = false },
        )
    }
    if (showingError && failureText != null) ReplyDialog(failureText) { showingError = false }
}

@StringRes
private fun taskFailedLabelRes(error: String?, result: ProgressResult?, @StringRes fallback: Int = R.string.status_failed): Int =
    when {
        PersistedErrors.isCancelled(error) || result == ProgressResult.CANCELLED -> R.string.status_cancelled
        result == ProgressResult.INTERRUPTED || (result == null && PersistedErrors.isInterrupted(error)) ->
            R.string.progress_result_interrupted
        else -> fallback
    }

@Composable
private fun TaskFailureMessage(text: String, onDetails: () -> Unit) {
    var truncated by remember(text) { mutableStateOf(false) }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { truncated = it.hasVisualOverflow },
    )
    if (truncated || '\n' in text) {
        TextButton(onClick = onDetails) { Text(stringResource(R.string.download_error_details)) }
    }
}

@Composable
private fun taskFailureText(error: String?, @StringRes fallback: Int): String? {
    val context = LocalContext.current
    return persistedErrorText(error)?.let { context.resolveNoUrl(it, fallback) }
}

@Composable
private fun WhisperJobRow(job: WhisperJobEntity, runtime: TaskProgressSnapshot?, dependency: TaskProgressSnapshot?, onRetry: () -> Unit, onCancel: () -> Unit) {
    val translating = TranslateMark.isActive(job.translateStatus)
    val stopped = job.error == PersistedErrors.CANCELLED
    val reply = job.error?.takeIf { job.translateStatus == TranslateMark.FAILED && it.isNotBlank() && !stopped }
    val replyText = taskFailureText(reply, R.string.translate_status_failed)
    var shownReply by remember { mutableStateOf<String?>(null) }
    val canStop = translating || job.status == DownloadStatus.QUEUED || job.status == DownloadStatus.RUNNING
    val progressPart = if (job.translateStatus == TranslateMark.FAILED) ProgressPart.TRANSLATION else ProgressPart.TRANSCRIPTION
    val terminal = runtime?.takeIf { it.part == progressPart }?.summary?.result
        ?: decodeProgressSummary(job.progressSummary).parts[progressPart]?.result
    val status = runtime?.takeIf { canStop && it.active }?.summary?.phase?.let { phase -> stringResource(phase.labelRes) } ?: when {
        job.translateStatus == TranslateMark.QUEUED -> stringResource(R.string.status_waiting_translation)
        translating -> stringResource(R.string.status_translating)
        job.translateStatus == TranslateMark.FAILED ->
            stringResource(taskFailedLabelRes(job.error, terminal, R.string.translate_status_failed))
        else -> when (job.status) {
            DownloadStatus.QUEUED -> stringResource(R.string.status_queued)
            DownloadStatus.RUNNING -> stringResource(R.string.status_transcribing)
            DownloadStatus.DONE -> stringResource(R.string.status_done)
            DownloadStatus.FAILED -> stringResource(taskFailedLabelRes(job.error, terminal))
        }
    }
    val errorDetail = job.error?.takeIf {
        job.status == DownloadStatus.FAILED &&
            job.translateStatus != TranslateMark.FAILED &&
            it.isNotBlank() &&
            it != PersistedErrors.CANCELLED
    }
    val errorDetailText = taskFailureText(errorDetail, R.string.whisper_error_failed)
    ListItem(
        headlineContent = { Text(job.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                StatusBadge(status, whisperTone(job, translating, stopped))
                errorDetailText?.let { detail ->
                    TaskFailureMessage(detail) { shownReply = detail }
                }
                TaskProgressContent(TaskKey(TaskKind.WHISPER, job.id), job.progressSummary, runtime, canStop,
                    legacyFraction = job.progress.takeIf { !translating && it > 0 }, dependency = dependency)
            }
        },
        trailingContent = {
            when {
                canStop -> TextButton(onClick = onCancel) { Text(stringResource(R.string.common_stop)) }
                job.status == DownloadStatus.FAILED -> TextButton(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
                replyText != null -> TextButton(onClick = { shownReply = replyText }) { Text(stringResource(R.string.download_error_details)) }
            }
        },
    )
    shownReply?.let { ReplyDialog(it) { shownReply = null } }
}

@Composable
private fun TranslationJobRow(job: TranslationJobEntity, runtime: TaskProgressSnapshot?, onCancel: () -> Unit, onRetry: () -> Unit) {
    val stopped = job.status == DownloadStatus.FAILED && job.error == PersistedErrors.CANCELLED
    val reply = job.error?.takeIf {
        job.status == DownloadStatus.FAILED && it.isNotBlank() &&
            it != PersistedErrors.TRANSLATE_FAILED && it != PersistedErrors.CANCELLED
    }
    val replyText = taskFailureText(reply, R.string.translate_status_failed)
    var shownReply by remember { mutableStateOf<String?>(null) }
    val running = job.status == DownloadStatus.RUNNING || job.status == DownloadStatus.QUEUED
    val terminal = runtime?.summary?.result
        ?: decodeProgressSummary(job.progressSummary).parts[ProgressPart.TRANSLATION]?.result
    val status = runtime?.takeIf { running && it.active }?.summary?.phase?.let { phase -> stringResource(phase.labelRes) } ?: when {
        job.status == DownloadStatus.QUEUED -> stringResource(R.string.status_queued)
        running -> stringResource(if (job.operation == SubtitleTask.POLISH) R.string.status_polishing else R.string.status_translating)
        job.status == DownloadStatus.FAILED -> stringResource(taskFailedLabelRes(job.error, terminal))
        else -> stringResource(R.string.status_done)
    }
    ListItem(
        headlineContent = { Text("${job.title} · ${stringResource(job.operation.labelRes)}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                StatusBadge(
                    status,
                    when {
                        stopped -> StatusTone.Neutral
                        running -> StatusTone.Active
                        job.status == DownloadStatus.FAILED -> StatusTone.Error
                        else -> StatusTone.Success
                    },
                )
                TaskProgressContent(TaskKey(TaskKind.TRANSLATION, job.id), job.progressSummary, runtime, running)
            }
        },
        trailingContent = {
            when {
                running -> TextButton(onClick = onCancel) { Text(stringResource(R.string.common_stop)) }
                job.status == DownloadStatus.FAILED -> Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
                    if (replyText != null) TextButton(onClick = { shownReply = replyText }) { Text(stringResource(R.string.download_error_details)) }
                }
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
        title = { Text(stringResource(R.string.download_error_details_title)) },
        text = {
            Text(
                text,
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}
