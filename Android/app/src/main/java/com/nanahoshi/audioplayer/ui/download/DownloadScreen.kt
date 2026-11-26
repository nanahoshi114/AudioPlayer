package com.nanahoshi.audioplayer.ui.download

import android.app.Application
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.data.DlsiteDownloadMode
import com.nanahoshi.audioplayer.data.ProgressPart
import com.nanahoshi.audioplayer.data.SubtitleTask
import com.nanahoshi.audioplayer.data.TaskKey
import com.nanahoshi.audioplayer.data.TaskKind
import com.nanahoshi.audioplayer.data.TaskProgressSnapshot
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.DownloadEntity
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.TranslateMark
import com.nanahoshi.audioplayer.data.db.TranslationJobEntity
import com.nanahoshi.audioplayer.data.db.WhisperJobEntity
import com.nanahoshi.audioplayer.data.decodeProgressSummary
import com.nanahoshi.audioplayer.data.missingOfflineWhisperModelMessage
import com.nanahoshi.audioplayer.data.operation
import com.nanahoshi.audioplayer.data.progressRate
import com.nanahoshi.audioplayer.data.progressResultLabel
import com.nanahoshi.audioplayer.data.totalDownloadRate
import com.nanahoshi.audioplayer.graph
import com.nanahoshi.audioplayer.japaneseasmr.JA_VERIFY_PHASE
import com.nanahoshi.audioplayer.japaneseasmr.JA_WORK_DOWNLOAD
import com.nanahoshi.audioplayer.playback.StoredAudioRequest
import com.nanahoshi.audioplayer.text.PersistedErrors
import com.nanahoshi.audioplayer.text.asString
import com.nanahoshi.audioplayer.ui.components.AppTopBar
import com.nanahoshi.audioplayer.ui.components.ConfirmActionDialog
import com.nanahoshi.audioplayer.ui.components.EmptyState
import com.nanahoshi.audioplayer.ui.components.SegmentedTabBar
import com.nanahoshi.audioplayer.ui.components.StatusTone
import com.nanahoshi.audioplayer.ui.components.rememberLocalNetworkAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DownloadViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
    val progress = graph.taskProgress.snapshots
    val jobs = graph.downloads.observeVisible()
    val whisperJobs = graph.whisper.observeVisible()
    val translationJobs = graph.translator.observeVisible()
    val publishedWhisperIds = graph.db.translations().observeWhisperIds()
    val publishedTranslationIds = graph.db.translations().observeTranslationIds()
    val dependencies = combine(jobs, whisperJobs) { _, rows ->
        rows.filter { it.status == DownloadStatus.QUEUED && it.fingerprint.isEmpty() }
            .mapNotNull { row -> graph.whisper.progressDependency(row)?.let { row.id to it } }.toMap()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun cancelDownload(id: Long) { graph.downloads.cancel(id) }

    fun verifyJapaneseWork(id: Long, navigate: (String) -> Unit) {
        graph.launchGuarded(viewModelScope, R.string.download_error_continue) {
            navigate(graph.downloads.beginJaTaskVerification(id))
        }
    }

    fun retry(id: Long) {
        graph.launchGuarded(viewModelScope, R.string.download_error_retry) {
            graph.downloads.retry(id)
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch { graph.downloads.deleteTerminal(id) }
    }

    fun clearCompletedDownloads() {
        viewModelScope.launch { graph.downloads.clearCompleted() }
    }

    fun deleteWhisper(id: Long) {
        viewModelScope.launch { graph.whisper.deleteTerminal(id) }
    }

    fun clearCompletedSubtitles() {
        viewModelScope.launch { graph.whisper.clearCompletedSubtitles() }
    }

    fun deleteTranslation(id: Long) {
        viewModelScope.launch { graph.translator.deleteTerminal(id) }
    }

    fun retryWhisperTranslation(id: Long) {
        viewModelScope.launch { graph.whisper.retryTranslation(id)?.let(graph::show) }
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
            } catch (cancelled: CancellationException) {
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
        val source = job.source?.let { raw -> runCatching { TrackSource.valueOf(raw) }.getOrNull() }
        graph.player.playStoredAudio(
            StoredAudioRequest(
                path = path,
                title = job.title,
                artist = null,
                trackId = job.trackId.takeIf { it > 0L },
                source = source,
                workKey = job.workKey,
                workCode = job.workKey,
                fileKey = job.fileKey,
            ),
        )
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
    val publishedWhisperIds by viewModel.publishedWhisperIds.collectAsStateWithLifecycle(emptyList())
    val publishedTranslationIds by viewModel.publishedTranslationIds.collectAsStateWithLifecycle(emptyList())
    val dependencies by viewModel.dependencies.collectAsStateWithLifecycle()
    val localNetwork = rememberLocalNetworkAccess { viewModel.reportLocalNetworkDenied() }
    var subtitles by rememberSaveable { mutableStateOf(initialSubtitles) }
    var confirmingClear by rememberSaveable { mutableStateOf(false) }
    val audioState = rememberLazyListState()
    val subtitleState = rememberLazyListState()
    val publishedWhispers = remember(publishedWhisperIds) { publishedWhisperIds.toSet() }
    val publishedTranslations = remember(publishedTranslationIds) { publishedTranslationIds.toSet() }
    val subtitleTasks = remember(whisperJobs, translationJobs) { sortSubtitleTasks(whisperJobs, translationJobs) }
    val clearableCount = remember(subtitles, whisperJobs, translationJobs, jobs, publishedWhispers, publishedTranslations) {
        if (subtitles) {
            whisperJobs.count { isClearableCompletedWhisper(it, publishedWhispers) } +
                translationJobs.count { isClearableCompletedTranslation(it, publishedTranslations) }
        } else {
            jobs.count { isClearableCompletedDownload(it) }
        }
    }
    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            title = stringResource(R.string.download_tasks_title),
            onBack = onBack,
            actions = {
                if (clearableCount > 0) {
                    TextButton(onClick = { confirmingClear = true }) {
                        Text(stringResource(R.string.download_clear_completed))
                    }
                }
            },
        )
        SegmentedTabBar(
            options = listOf(false, true),
            selected = subtitles,
            label = { isSub ->
                stringResource(if (isSub) R.string.download_segment_subtitle else R.string.download_segment_download)
            },
            onSelect = { subtitles = it },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (!subtitles && jobs.any { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED }) {
            ActiveDownloadBanner(jobs, viewModel.progress)
        }
        if (if (subtitles) subtitleTasks.isEmpty() else jobs.isEmpty()) {
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
            LazyColumn(
                state = if (subtitles) subtitleState else audioState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                if (!subtitles && jobs.isNotEmpty()) {
                    items(jobs, key = { "download-${it.id}" }) { job ->
                        DownloadRow(
                            job = job,
                            progressFlow = viewModel.progress,
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
                if (subtitles && subtitleTasks.isNotEmpty()) {
                    items(subtitleTasks, key = { it.key }) { item ->
                        when (item) {
                            is SubtitleTaskItem.Whisper -> {
                                val job = item.job
                                WhisperJobRow(
                                    job = job,
                                    progressFlow = viewModel.progress,
                                    dependencyId = dependencies[job.id],
                                    publishedIds = publishedWhispers,
                                    onRetry = {
                                        localNetwork.run(viewModel.retryNeedsLocalNetwork(job)) {
                                            viewModel.retryWhisper(job.id)
                                        }
                                    },
                                    onCancel = { viewModel.cancelWhisper(job.id) },
                                    onRetryTranslation = { viewModel.retryWhisperTranslation(job.id) },
                                    onDelete = { viewModel.deleteWhisper(job.id) },
                                )
                            }
                            is SubtitleTaskItem.Translation -> {
                                val job = item.job
                                TranslationJobRow(
                                    job = job,
                                    progressFlow = viewModel.progress,
                                    publishedIds = publishedTranslations,
                                    onCancel = { viewModel.cancelTranslation(job.id) },
                                    onRetry = { viewModel.retryTranslation(job) },
                                    onDelete = { viewModel.deleteTranslation(job.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmingClear) {
        ConfirmActionDialog(
            title = stringResource(R.string.download_clear_completed_title),
            message = stringResource(
                if (subtitles) R.string.download_clear_completed_subtitle_message
                else R.string.download_clear_completed_audio_message,
            ),
            confirmLabel = stringResource(R.string.download_clear_completed_confirm),
            onConfirm = {
                confirmingClear = false
                if (subtitles) viewModel.clearCompletedSubtitles() else viewModel.clearCompletedDownloads()
            },
            onDismiss = { confirmingClear = false },
            destructive = false,
        )
    }
}

@Composable
private fun ActiveDownloadBanner(
    jobs: List<DownloadEntity>,
    progressFlow: StateFlow<Map<TaskKey, TaskProgressSnapshot>>,
) {
    val snapshots by progressFlow.collectAsStateWithLifecycle()
    val runningCount = remember(jobs) { jobs.count { it.status == DownloadStatus.RUNNING } }
    val queuedCount = remember(jobs) { jobs.count { it.status == DownloadStatus.QUEUED } }
    Text(
        stringResource(
            R.string.download_summary,
            runningCount,
            queuedCount,
            progressRate(totalDownloadRate(snapshots)),
        ),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun rememberTaskSnapshot(
    key: TaskKey?,
    observe: Boolean,
    progressFlow: StateFlow<Map<TaskKey, TaskProgressSnapshot>>,
): TaskProgressSnapshot? {
    val snapshotFlow = remember(key, observe, progressFlow) {
        if (key != null && observe) {
            progressFlow.map { it[key] }.distinctUntilChanged()
        } else {
            flowOf(null)
        }
    }
    return snapshotFlow.collectAsStateWithLifecycle(null).value
}

@Composable
private fun DownloadRow(
    job: DownloadEntity,
    progressFlow: StateFlow<Map<TaskKey, TaskProgressSnapshot>>,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onPlay: (() -> Unit)?,
    onOpenArticle: (() -> Unit)?,
) {
    var confirming by remember { mutableStateOf(false) }
    var showingError by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val active = job.status == DownloadStatus.RUNNING || job.status == DownloadStatus.QUEUED
    val taskKey = remember(job.id) { TaskKey(TaskKind.DOWNLOAD, job.id) }
    val subtitleKey = remember(job.id) { TaskKey(TaskKind.DOWNLOAD, job.id, ProgressPart.BILI_SUBTITLE) }
    val runtime = rememberTaskSnapshot(taskKey, observe = active, progressFlow = progressFlow)
    val subtitleActive = job.status == DownloadStatus.DONE && job.pendingBiliSubtitle == true
    val subtitleRuntime = rememberTaskSnapshot(subtitleKey, observe = subtitleActive, progressFlow = progressFlow)
    val decodedSummary = remember(job.progressSummary) { decodeProgressSummary(job.progressSummary) }
    val failureText = taskFailureText(job.error, R.string.download_failed_generic)
    val downloadResult = runtime?.summary?.result
        ?: decodedSummary.parts[ProgressPart.DOWNLOAD]?.result
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
    val badgeStatus = when {
        status.length <= 6 -> status
        job.status == DownloadStatus.QUEUED || job.phase == JA_VERIFY_PHASE ||
            runtime?.summary?.phase?.waiting == true -> stringResource(R.string.status_waiting)
        else -> stringResource(R.string.status_running)
    }
    TaskRowScaffold(
        title = job.title,
        badgeText = badgeStatus,
        badgeTone = toneFor(job.status),
        modifier = if (onPlay != null) Modifier.clickable(onClick = onPlay) else Modifier,
        trailing = {
            when {
                job.status in setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING) -> {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.common_stop)) }
                }
                job.status == DownloadStatus.FAILED -> {
                    Column(horizontalAlignment = Alignment.End) {
                        FilledTonalButton(onClick = onRetry) {
                            Text(stringResource(if (job.phase == JA_VERIFY_PHASE) R.string.download_verify_continue else R.string.common_retry))
                        }
                        TextButton(onClick = { confirming = true }) {
                            Text(stringResource(if (job.phase == JA_VERIFY_PHASE) R.string.download_abandon_task else R.string.common_delete))
                        }
                    }
                }
                job.status == DownloadStatus.DONE && isClearableCompletedDownload(job) -> {
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.download_delete_record)) }
                }
                else -> Unit
            }
        },
    ) {
        if (badgeStatus != status) {
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
        if (job.kind == JA_WORK_DOWNLOAD && active) runtime?.detail?.let {
            Text(it.asString(), style = MaterialTheme.typography.bodySmall)
        }
        if (onOpenArticle != null) {
            TextButton(onClick = onOpenArticle) { Text(stringResource(R.string.download_open_article)) }
        }
        if (job.status == DownloadStatus.FAILED && !failureText.isNullOrBlank()) {
            TaskFailureMessage(failureText) { showingError = true }
        }
        TaskProgressContent(
            key = taskKey,
            rawSummary = job.progressSummary,
            runtime = runtime,
            active = active,
            legacyFraction = if (job.kind != "DLSITE_ARCHIVE" && job.bytesTotal > 0) job.bytesDone.toFloat() / job.bytesTotal else null,
            decodedSummary = decodedSummary,
        )
        val subtitleSummary = decodedSummary.parts[ProgressPart.BILI_SUBTITLE]
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
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            TaskProgressContent(
                key = subtitleKey,
                rawSummary = job.progressSummary,
                runtime = subtitleRuntime,
                active = subtitleRuntime?.active ?: (job.pendingBiliSubtitle == true),
                decodedSummary = decodedSummary,
            )
        }
    }
    if (confirming) {
        ConfirmActionDialog(
            title = stringResource(if (job.phase == JA_VERIFY_PHASE) R.string.download_abandon_task else R.string.download_delete_title),
            message = stringResource(
                if (job.phase == JA_VERIFY_PHASE) R.string.download_abandon_ja_verify_message
                else when (job.kind) {
                    "DLSITE_ARCHIVE" -> R.string.download_delete_archive_message
                    JA_WORK_DOWNLOAD -> R.string.download_delete_ja_message
                    "JAPANESE_ASMR_HLS" -> R.string.download_delete_hls_message
                    else -> R.string.download_delete_default_message
                },
            ),
            confirmLabel = stringResource(if (job.phase == JA_VERIFY_PHASE) R.string.download_abandon_task else R.string.common_delete),
            onConfirm = { confirming = false; onDelete() },
            onDismiss = { confirming = false },
        )
    }
    if (showingError && failureText != null) TaskReplyDialog(failureText) { showingError = false }
}

@Composable
private fun WhisperJobRow(
    job: WhisperJobEntity,
    progressFlow: StateFlow<Map<TaskKey, TaskProgressSnapshot>>,
    dependencyId: Long?,
    publishedIds: Set<Long>,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onRetryTranslation: () -> Unit,
    onDelete: () -> Unit,
) {
    val translating = TranslateMark.isActive(job.translateStatus)
    val stopped = job.error == PersistedErrors.CANCELLED
    val reply = job.error?.takeIf { job.translateStatus == TranslateMark.FAILED && it.isNotBlank() && !stopped }
    val replyText = taskFailureText(reply, R.string.translate_status_failed)
    var shownReply by remember { mutableStateOf<String?>(null) }
    val canStop = translating || job.status == DownloadStatus.QUEUED || job.status == DownloadStatus.RUNNING
    val taskKey = remember(job.id) { TaskKey(TaskKind.WHISPER, job.id) }
    val depKey = remember(dependencyId) { dependencyId?.let { TaskKey(TaskKind.DOWNLOAD, it) } }
    val runtime = rememberTaskSnapshot(taskKey, observe = canStop, progressFlow = progressFlow)
    val dependency = rememberTaskSnapshot(depKey, observe = canStop && depKey != null, progressFlow = progressFlow)
    val decodedSummary = remember(job.progressSummary) { decodeProgressSummary(job.progressSummary) }
    val progressPart = if (job.translateStatus == TranslateMark.FAILED) ProgressPart.TRANSLATION else ProgressPart.TRANSCRIPTION
    val terminal = runtime?.takeIf { it.part == progressPart }?.summary?.result
        ?: decodedSummary.parts[progressPart]?.result
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
    val canRetryTranslation = job.id !in publishedIds &&
        job.status == DownloadStatus.DONE && job.translateStatus == TranslateMark.FAILED
    TaskRowScaffold(
        title = job.title,
        badgeText = status,
        badgeTone = whisperTone(job, translating, stopped),
        trailing = {
            when {
                canStop -> TextButton(onClick = onCancel) { Text(stringResource(R.string.common_stop)) }
                job.status == DownloadStatus.FAILED -> Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
                    if (job.id !in publishedIds) {
                        TextButton(onClick = onDelete) { Text(stringResource(R.string.download_delete_record)) }
                    }
                }
                canRetryTranslation -> Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = onRetryTranslation) { Text(stringResource(R.string.download_retry_translation)) }
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.download_delete_record)) }
                }
                isClearableCompletedWhisper(job, publishedIds) ->
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.download_delete_record)) }
            }
        },
    ) {
        errorDetailText?.let { detail ->
            TaskFailureMessage(detail) { shownReply = detail }
        }
        if (job.translateStatus == TranslateMark.FAILED && !stopped) {
            replyText?.takeIf { it != status }?.let { detail ->
                TaskFailureMessage(detail) { shownReply = detail }
            }
        }
        TaskProgressContent(
            key = taskKey,
            rawSummary = job.progressSummary,
            runtime = runtime,
            active = canStop,
            legacyFraction = job.progress.takeIf { !translating && it > 0 },
            dependency = dependency,
            decodedSummary = decodedSummary,
        )
    }
    shownReply?.let { TaskReplyDialog(it) { shownReply = null } }
}

@Composable
private fun TranslationJobRow(
    job: TranslationJobEntity,
    progressFlow: StateFlow<Map<TaskKey, TaskProgressSnapshot>>,
    publishedIds: Set<Long>,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    val stopped = job.status == DownloadStatus.FAILED && job.error == PersistedErrors.CANCELLED
    val reply = job.error?.takeIf { job.status == DownloadStatus.FAILED && it.isNotBlank() && !stopped }
    val replyText = taskFailureText(reply, R.string.translate_status_failed)
    var shownReply by remember { mutableStateOf<String?>(null) }
    val running = job.status == DownloadStatus.RUNNING || job.status == DownloadStatus.QUEUED
    val taskKey = remember(job.id) { TaskKey(TaskKind.TRANSLATION, job.id) }
    val runtime = rememberTaskSnapshot(taskKey, observe = running, progressFlow = progressFlow)
    val decodedSummary = remember(job.progressSummary) { decodeProgressSummary(job.progressSummary) }
    val terminal = runtime?.summary?.result
        ?: decodedSummary.parts[ProgressPart.TRANSLATION]?.result
    val status = runtime?.takeIf { running && it.active }?.summary?.phase?.let { phase -> stringResource(phase.labelRes) } ?: when {
        job.status == DownloadStatus.QUEUED -> stringResource(R.string.status_queued)
        running -> stringResource(if (job.operation == SubtitleTask.POLISH) R.string.status_polishing else R.string.status_translating)
        job.status == DownloadStatus.FAILED -> stringResource(taskFailedLabelRes(job.error, terminal))
        else -> stringResource(R.string.status_done)
    }
    TaskRowScaffold(
        title = "${job.title} · ${stringResource(job.operation.labelRes)}",
        badgeText = status,
        badgeTone = when {
            stopped -> StatusTone.Neutral
            running -> StatusTone.Active
            job.status == DownloadStatus.FAILED -> StatusTone.Error
            else -> StatusTone.Success
        },
        trailing = {
            when {
                running -> TextButton(onClick = onCancel) { Text(stringResource(R.string.common_stop)) }
                job.status == DownloadStatus.FAILED -> Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
                    if (job.id !in publishedIds) {
                        TextButton(onClick = onDelete) { Text(stringResource(R.string.download_delete_record)) }
                    }
                }
                isClearableCompletedTranslation(job, publishedIds) ->
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.download_delete_record)) }
            }
        },
    ) {
        replyText?.takeIf { it != status }?.let { detail -> TaskFailureMessage(detail) { shownReply = detail } }
        TaskProgressContent(
            key = taskKey,
            rawSummary = job.progressSummary,
            runtime = runtime,
            active = running,
            decodedSummary = decodedSummary,
        )
    }
    shownReply?.let { TaskReplyDialog(it) { shownReply = null } }
}

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
