package com.nanahoshi.audioplayer.ui.player

import com.nanahoshi.audioplayer.text.asString
import com.nanahoshi.audioplayer.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.lazy.items
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.matches
import com.nanahoshi.audioplayer.data.operation
import com.nanahoshi.audioplayer.data.SubtitleTask
import com.nanahoshi.audioplayer.playback.SubtitleLanguageDelete
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nanahoshi.audioplayer.playback.PlayerUiState
import com.nanahoshi.audioplayer.data.TaskKey
import com.nanahoshi.audioplayer.data.TaskKind
import com.nanahoshi.audioplayer.data.TaskProgressSnapshot
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.data.db.TranslateMark
import com.nanahoshi.audioplayer.data.db.WhisperJobEntity
import com.nanahoshi.audioplayer.data.fraction
import com.nanahoshi.audioplayer.japaneseasmr.JapaneseAsmrClient
import com.nanahoshi.audioplayer.text.PersistedErrors
import com.nanahoshi.audioplayer.playback.SubtitleCue
import com.nanahoshi.audioplayer.playback.hasTranslation
import com.nanahoshi.audioplayer.playback.indexAt
import com.nanahoshi.audioplayer.playback.line
import com.nanahoshi.audioplayer.playback.playbackProgress
import com.nanahoshi.audioplayer.playback.playbackSeekPosition
import com.nanahoshi.audioplayer.playback.textAt
import com.nanahoshi.audioplayer.ui.components.AppTopBar
import com.nanahoshi.audioplayer.ui.components.ConfirmActionDialog
import com.nanahoshi.audioplayer.ui.components.CoverImage
import com.nanahoshi.audioplayer.ui.components.DeleteSubtitleLanguageDialog
import com.nanahoshi.audioplayer.ui.components.EmptyState
import com.nanahoshi.audioplayer.ui.components.InfoNoticeDialog
import com.nanahoshi.audioplayer.ui.components.ModalSheetHeader
import com.nanahoshi.audioplayer.ui.components.RoundedProgress
import com.nanahoshi.audioplayer.ui.components.SegmentedTabBar
import com.nanahoshi.audioplayer.ui.components.SourceBadge
import com.nanahoshi.audioplayer.ui.components.formatDuration
import com.nanahoshi.audioplayer.ui.components.pressScale
import com.nanahoshi.audioplayer.ui.components.rememberLocalNetworkAccess
import com.nanahoshi.audioplayer.ui.theme.LocalDarkTheme
import com.nanahoshi.audioplayer.ui.theme.LocalReduceMotion
import com.nanahoshi.audioplayer.ui.theme.Motion
import com.nanahoshi.audioplayer.ui.theme.SubtitleLarge
import com.nanahoshi.audioplayer.ui.theme.SubtitleReader
import com.nanahoshi.audioplayer.ui.theme.pressSpec
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NowPlayingScreen(
    onBack: () -> Unit,
    onVerifyJapaneseAsmr: (String, Long, String?) -> Unit,
    viewModel: PlayerViewModel = viewModel(),
    coverModifier: Modifier = Modifier,
    onOpenCollection: (com.nanahoshi.audioplayer.bilibili.BiliCollection) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activeWhisperJob by viewModel.activeWhisperJob.collectAsStateWithLifecycle()
    val failedWhisperJob by viewModel.failedWhisperJob.collectAsStateWithLifecycle()
    val taskProgress by viewModel.taskProgress.collectAsStateWithLifecycle()
    val jaFailure by viewModel.client.jaPlaybackFailure.collectAsStateWithLifecycle()
    jaFailure?.takeIf { it.queueId == state.queueItemId }?.let { failure ->
        val verifyPost = failure.postId?.takeIf { failure.reason == com.nanahoshi.audioplayer.japaneseasmr.JaFailure.VERIFY }
        val close = { viewModel.client.dismissJaPlaybackFailure(failure) }
        if (verifyPost != null) {
            ConfirmActionDialog(
                message = stringResource(failure.messageRes),
                confirmLabel = stringResource(R.string.download_verify_continue),
                dismissLabel = stringResource(R.string.common_close),
                destructive = false,
                onConfirm = {
                    close()
                    onVerifyJapaneseAsmr(JapaneseAsmrClient.postUrl(verifyPost), failure.queueId, failure.mediaKey)
                },
                onDismiss = close,
            )
        } else {
            InfoNoticeDialog(
                message = stringResource(failure.messageRes),
                confirmLabel = stringResource(R.string.common_close),
                onDismiss = close,
            )
        }
    }
    val collectionLink by viewModel.collectionLink.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle(emptyList())
    val remaining by viewModel.client.sleepRemainingMs.collectAsStateWithLifecycle()
    var sleepDialog by remember { mutableStateOf(false) }
    var queueExpanded by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val queueSheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val queueScope = rememberCoroutineScope()
    fun closeQueue() {
        queueScope.launch { queueSheet.hide() }.invokeOnCompletion {
            if (!queueSheet.isVisible) queueExpanded = false
        }
    }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        viewModel.client.move(from.index, to.index)
    }
    val subtitleState by viewModel.subtitles.collectAsStateWithLifecycle()
    val subtitleSelection by viewModel.subtitleSelection.collectAsStateWithLifecycle()
    val selected = subtitleSelection.queueItemId == state.queueItemId
    val loaded = if (selected) subtitleState.forItem(state.queueItemId) else null
    val current = queue.find { it.queueItemId == state.queueItemId }
    // 点击时快照曲目身份；字幕弹层和确认框在自动切歌后仍操作原来那一首。
    var pickerRequest by remember { mutableStateOf<QueuedTrack?>(null) }
    var pickerTitle by remember { mutableStateOf("") }
    var pickerResolution by remember { mutableStateOf<SubtitlePickerResolution?>(null) }
    fun openSubtitlePicker() {
        val row = current ?: return
        pickerRequest = row
        pickerTitle = row.title.ifBlank { state.title }
        pickerResolution = SubtitlePickerResolution.Loading
    }
    LaunchedEffect(pickerRequest) {
        val row = pickerRequest ?: return@LaunchedEffect
        pickerResolution = run {
            val track = viewModel.trackForQueueItem(row)
            track?.takeIf { it.id > 0L }?.let { SubtitlePickerResolution.Ready(it) }
                ?: SubtitlePickerResolution.Unlisted
        }
    }
    var chapters by remember(current?.queueItemId) {
        mutableStateOf(emptyList<com.nanahoshi.audioplayer.japaneseasmr.JaChapter>())
    }
    var chaptersOpen by remember(current?.queueItemId) { mutableStateOf(false) }
    val chapter = chapters.lastOrNull { it.startMs <= state.positionMs }
    LaunchedEffect(current?.queueItemId, current?.workno, current?.fileKey) {
        chapters = viewModel.chapters(current)
    }
    if (chaptersOpen && chapters.isNotEmpty()) {
        ModalBottomSheet(
            onDismissRequest = { chaptersOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            dragHandle = null,
            sheetGesturesEnabled = false,
        ) {
            ModalSheetHeader(
                title = stringResource(R.string.player_chapters),
                onDismiss = { chaptersOpen = false },
                titleStyle = MaterialTheme.typography.titleLarge,
                showDivider = false,
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).navigationBarsPadding()) {
                items(chapters, key = { it.startMs }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.title) },
                        supportingContent = { Text(formatDuration(entry.startMs)) },
                        colors = ListItemDefaults.colors(
                            containerColor = if (entry == chapter) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                        ),
                        modifier = Modifier.clickable { viewModel.client.seekTo(entry.startMs) },
                    )
                }
            }
        }
    }
    val translationJobs by viewModel.translationJobs.collectAsStateWithLifecycle(emptyList())
    val subtitleTask = current?.let { row ->
        translationJobs.firstOrNull {
            it.status in setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING) &&
                it.matches(row, loaded?.generatedFingerprint)
        }
    }
    val subtitleBusy = subtitleTask != null || activeWhisperJob != null
    val cues = loaded?.cues.orEmpty()
    val chinese = !selected || subtitleSelection.chinese
    val subtitle = cues.textAt(state.positionMs, chinese)
    var showSubtitles by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<PendingSubtitleDelete?>(null) }
    fun requestSubtitleDelete(mode: SubtitleDeleteMode) {
        current?.let {
            pendingDelete = PendingSubtitleDelete(it, mode, loaded?.generatedFingerprint, loaded?.overlayOutputName)
        }
    }
    var fullTitle by remember { mutableStateOf(false) }
    val localNetwork = rememberLocalNetworkAccess { viewModel.reportLocalNetworkDenied() }
    LaunchedEffect(queueExpanded, state.queueItemId) {
        if (!queueExpanded) return@LaunchedEffect
        val index = queue.indexOfFirst { it.queueItemId == state.queueItemId }
        if (index >= 0) listState.scrollToItem(index)
    }
    WhisperPromptDialog(viewModel)
    if (fullTitle) {
        InfoNoticeDialog(
            title = stringResource(R.string.player_edit_title),
            message = listOfNotNull(
                state.title,
                state.artist.takeIf { it.isNotBlank() },
            ).joinToString("\n\n"),
            onDismiss = { fullTitle = false },
            scrollable = true,
        )
    }
    pendingDelete?.let { target ->
        if (target.mode == SubtitleDeleteMode.ALL_GENERATED) {
            ConfirmActionDialog(
                message = stringResource(R.string.player_delete_generated_subtitle_confirm),
                confirmLabel = stringResource(R.string.common_delete),
                destructive = true,
                onConfirm = {
                    pendingDelete = null
                    if (target.row.queueItemId == state.queueItemId && loaded?.hasManualBinding != true) {
                        showSubtitles = false
                    }
                    viewModel.discardGeneratedSubtitles(target.row)
                },
                onDismiss = { pendingDelete = null },
            )
        } else {
            val langTarget = if (target.mode == SubtitleDeleteMode.CHINESE_ONLY) {
                SubtitleLanguageDelete.CHINESE
            } else {
                SubtitleLanguageDelete.ORIGINAL
            }
            DeleteSubtitleLanguageDialog(
                target = langTarget,
                translationOverlay = target.generatedFingerprint == null,
                onConfirm = {
                    pendingDelete = null
                    viewModel.deleteSubtitleLanguage(
                        target.generatedFingerprint,
                        target.overlayOutputName,
                        langTarget,
                    )
                },
                onDismiss = { pendingDelete = null },
            )
        }
    }
    if (confirmClear) {
        ConfirmActionDialog(
            message = stringResource(R.string.player_clear_queue_confirm),
            confirmLabel = stringResource(R.string.player_clear_queue),
            destructive = true,
            onConfirm = {
                confirmClear = false
                viewModel.client.clearQueue()
                closeQueue()
            },
            onDismiss = { confirmClear = false },
        )
    }
    val dark = LocalDarkTheme.current
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (dark) 0.28f else 0.35f),
                        Color.Transparent,
                    ),
                ),
            ),
    ) {
        if (!state.hasMedia) {
            Column(Modifier.fillMaxSize()) {
                AppTopBar(title = stringResource(R.string.player_now_playing), onBack = onBack)
                EmptyState(
                    title = stringResource(R.string.player_empty_no_track_title),
                    message = stringResource(R.string.player_empty_no_track_message),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                AppTopBar(
                    title = stringResource(R.string.player_now_playing),
                    onBack = onBack,
                    actions = {
                        current?.takeIf { it.source == TrackSource.JAPANESE_ASMR }?.let { row ->
                            val postId = row.workno?.takeIf { id -> id.isNotEmpty() && id.all { it in '0'..'9' } }
                                ?: return@let
                            IconButton(onClick = {
                                onVerifyJapaneseAsmr(JapaneseAsmrClient.postUrl(postId), row.queueItemId, row.fileKey)
                            }) {
                                Icon(Icons.Default.OpenInBrowser, contentDescription = stringResource(R.string.a11y_verify_current_post))
                            }
                        }
                        current?.let { SourceBadge(it.source, Modifier.padding(end = 12.dp)) }
                    },
                )
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val viewportHeight = maxHeight
                    val compact = maxHeight < 480.dp || LocalDensity.current.fontScale >= 1.3f
                    val cover = minOf(maxWidth * 0.72f, maxHeight * 0.4f, if (compact) 144.dp else 280.dp)
                        .coerceAtLeast(48.dp)
                    Column(
                        Modifier.fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .heightIn(min = viewportHeight)
                            .padding(horizontal = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CoverImage(
                            state.coverUri,
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .then(coverModifier)
                                .size(cover)
                                .shadow(12.dp, MaterialTheme.shapes.large),
                            size = null,
                            shape = MaterialTheme.shapes.large,
                        )
                        Text(
                            state.title,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 16.dp)
                                .clip(MaterialTheme.shapes.small)
                                .clickable(onClickLabel = stringResource(R.string.player_view_full_title)) { fullTitle = true }
                                .padding(vertical = 4.dp),
                        )
                        if (state.artist.isNotBlank()) {
                            Text(
                                state.artist,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .heightIn(min = 96.dp)
                                .padding(vertical = 12.dp)
                                .then(
                                    if (cues.isNotEmpty()) {
                                        Modifier.clickable(onClickLabel = stringResource(R.string.player_view_full_subtitle)) { showSubtitles = true }
                                    } else {
                                        Modifier
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            val activeJob = activeWhisperJob
                            when {
                                subtitle.isNotEmpty() -> CueLine(subtitle)
                                cues.isNotEmpty() -> Text(
                                    stringResource(R.string.player_tap_for_subtitles),
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                )
                                activeJob != null -> WhisperProgress(
                                    job = activeJob,
                                    progress = taskProgress[TaskKey(TaskKind.WHISPER, activeJob.id)],
                                    onStop = { viewModel.cancelWhisper(activeJob.id) },
                                )
                                loaded?.generatedEmpty == true -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        stringResource(R.string.player_no_voice),
                                        textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        TextButton(onClick = { requestSubtitleDelete(SubtitleDeleteMode.ALL_GENERATED) }) { Text(stringResource(R.string.player_delete_subtitle)) }
                                        TextButton(onClick = { openSubtitlePicker() }) { Text(stringResource(R.string.player_choose_subtitle)) }
                                    }
                                }
                                loaded != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    val disabled = loaded.disabledByUser
                                    val notice = loaded.notice
                                    when {
                                        disabled -> Text(
                                            stringResource(R.string.player_subtitle_disabled_notice),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(bottom = 8.dp),
                                        )
                                        notice != null -> Text(
                                            notice,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(bottom = 8.dp),
                                        )
                                        failedWhisperJob?.takeIf { !PersistedErrors.isCancelled(it.error) } != null -> Text(
                                            stringResource(R.string.player_subtitle_failed_notice),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(bottom = 8.dp),
                                        )
                                    }
                                    FlowRow(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        // 用户明确关闭字幕时只保留「选择字幕」，避免误触重新生成。
                                        if (!disabled) {
                                            FilledTonalButton(
                                                onClick = {
                                                    localNetwork.run(viewModel.generateNeedsLocalNetwork()) {
                                                        viewModel.requestGenerate(translate = true, targetRow = current)
                                                    }
                                                },
                                            ) {
                                                Text(stringResource(R.string.player_generate_subtitle_translate), textAlign = TextAlign.Center)
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    localNetwork.run(viewModel.generateNeedsLocalNetwork()) {
                                                        viewModel.requestGenerate(translate = false, targetRow = current)
                                                    }
                                                },
                                            ) {
                                                Text(stringResource(R.string.player_generate_subtitle), textAlign = TextAlign.Center)
                                            }
                                        }
                                        OutlinedButton(onClick = { openSubtitlePicker() }) {
                                            Text(stringResource(R.string.player_choose_subtitle), textAlign = TextAlign.Center)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
                    SeekBar(state, onSeek = viewModel.client::seekTo)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = viewModel.client::previous, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = stringResource(R.string.a11y_previous), modifier = Modifier.size(32.dp))
                        }
                        IconButton(
                            onClick = { viewModel.seekBack() },
                            enabled = state.canSeekBack,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(Icons.Default.Replay10, contentDescription = stringResource(R.string.a11y_seek_back_10s), modifier = Modifier.size(28.dp))
                        }
                        PlayButton(playing = state.isPlaying, onClick = viewModel.client::toggle)
                        IconButton(
                            onClick = { viewModel.seekForward() },
                            enabled = state.canSeekForward,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(Icons.Default.Forward10, contentDescription = stringResource(R.string.a11y_seek_forward_10s), modifier = Modifier.size(28.dp))
                        }
                        IconButton(onClick = viewModel.client::next, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.SkipNext, contentDescription = stringResource(R.string.a11y_next), modifier = Modifier.size(32.dp))
                        }
                    }
                    FlowRow(
                        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        SecondaryAction(
                            icon = { Icon(Icons.Outlined.Timer, contentDescription = null) },
                            label = remaining?.let { formatDuration(it) } ?: stringResource(R.string.player_sleep_timer),
                            onClick = { sleepDialog = true },
                        )
                        if (remaining != null) {
                            SecondaryAction(
                                icon = { Icon(Icons.Default.Close, contentDescription = null) },
                                label = stringResource(R.string.player_cancel_timer),
                                onClick = viewModel.client::cancelSleepTimer,
                            )
                        }
                        SecondaryAction(
                            icon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) },
                            label = stringResource(R.string.player_queue),
                            onClick = { queueExpanded = true },
                        )
                        PlayerCollectionAction(current, collectionLink, onOpenCollection)
                        if (chapters.isNotEmpty()) SecondaryAction(
                            icon = { Icon(Icons.Default.CollectionsBookmark, contentDescription = null) },
                            label = chapter?.let { stringResource(R.string.player_chapter_label, it.title) } ?: stringResource(R.string.player_chapters),
                            onClick = { chaptersOpen = true },
                            modifier = Modifier.widthIn(max = 240.dp),
                            maxLines = 2,
                        )
                    }
                }
            }
        }
        val reduce = LocalReduceMotion.current
        AnimatedVisibility(
            visible = showSubtitles,
            modifier = Modifier.fillMaxSize(),
            enter = if (reduce) EnterTransition.None else slideInVertically(tween(Motion.medium, easing = Motion.enter)) { it } + fadeIn(tween(Motion.medium)),
            exit = if (reduce) ExitTransition.None else slideOutVertically(tween(Motion.medium, easing = Motion.exit)) { it } + fadeOut(tween(Motion.short)),
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                SubtitleReader(
                    mediaId = state.queueItemId,
                    cues = cues,
                    positionMs = state.positionMs,
                    chinese = chinese,
                    translating = subtitleTask?.operation == SubtitleTask.TRANSLATE,
                    canDelete = loaded?.generated == true,
                    onChinese = { viewModel.showChinese(current?.queueItemId ?: state.queueItemId, true) },
                    onJapanese = { viewModel.showChinese(current?.queueItemId ?: state.queueItemId, false) },
                    onTranslate = { viewModel.requestTranslation(current) },
                    canTranslate = loaded?.canTranslate == true,
                    onStop = { subtitleTask?.id?.let(viewModel::cancelSubtitleOperation) },
                    polishing = subtitleTask?.operation == SubtitleTask.POLISH,
                    onPolish = { viewModel.requestPolish(current) },
                    onStopPolish = { subtitleTask?.id?.let(viewModel::cancelSubtitleOperation) },
                    whisperJob = activeWhisperJob,
                    whisperProgress = activeWhisperJob?.let { taskProgress[TaskKey(TaskKind.WHISPER, it.id)] },
                    onStopWhisper = { activeWhisperJob?.let { viewModel.cancelWhisper(it.id) } },
                    canSelectSubtitle = current != null,
                    hasManualBinding = loaded?.hasManualBinding == true,
                    disabledByUser = loaded?.disabledByUser == true,
                    canRegenerate = current != null && activeWhisperJob == null && loaded?.disabledByUser != true,
                    onSelectSubtitle = { openSubtitlePicker() },
                    onRegenerate = {
                        localNetwork.run(viewModel.generateNeedsLocalNetwork()) {
                            viewModel.requestGenerate(
                                translate = cues.hasTranslation(), forceRegenerate = true, targetRow = current,
                            )
                        }
                    },
                    // 解除关联同样以点击时的曲目为准。
                    onClearBinding = {
                        current?.trackId?.takeIf { it > 0L }?.let(viewModel::clearSubtitleBinding)
                    },
                    canDeleteChinese = !subtitleBusy && cues.hasTranslation() &&
                        (loaded?.generated == true || loaded?.overlayOutputName != null),
                    canDeleteOriginal = !subtitleBusy && cues.hasTranslation() && loaded?.generated == true,
                    onDeleteChinese = { requestSubtitleDelete(SubtitleDeleteMode.CHINESE_ONLY) },
                    onDeleteOriginal = { requestSubtitleDelete(SubtitleDeleteMode.ORIGINAL_ONLY) },
                    onDelete = { requestSubtitleDelete(SubtitleDeleteMode.ALL_GENERATED) },
                    onSeek = viewModel.client::seekTo,
                    onBack = { showSubtitles = false },
                )
            }
        }
    }
    if (sleepDialog) {
        SleepTimerDialog(
            onDismiss = { sleepDialog = false },
            onStart = { totalMs ->
                viewModel.client.startSleepTimer(totalMs)
                sleepDialog = false
            },
        )
    }
    if (queueExpanded) {
        ModalBottomSheet(
            onDismissRequest = { queueExpanded = false },
            sheetState = queueSheet,
            sheetGesturesEnabled = false,
            dragHandle = null,
        ) {
            ModalSheetHeader(
                title = stringResource(R.string.player_queue_title),
                onDismiss = { closeQueue() },
                showDivider = false,
                trailing = {
                    TextButton(onClick = { confirmClear = true }, enabled = queue.isNotEmpty()) {
                        Text(stringResource(R.string.player_clear_queue))
                    }
                },
            )
            if (queue.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.player_queue_empty_title),
                    message = stringResource(R.string.player_queue_empty_message),
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).navigationBarsPadding(),
                ) {
                    itemsIndexed(queue, key = { _, item -> item.queueItemId }) { index, item ->
                        ReorderableItem(reorderState, key = item.queueItemId) {
                            val playing = item.queueItemId == state.queueItemId
                            ListItem(
                                headlineContent = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(item.artist.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingContent = {
                                    Icon(
                                        Icons.Default.DragHandle,
                                        contentDescription = stringResource(R.string.a11y_drag_sort),
                                        modifier = Modifier.size(48.dp).draggableHandle().padding(12.dp),
                                    )
                                },
                                trailingContent = {
                                    IconButton(onClick = { viewModel.client.remove(index, item.queueItemId) }) {
                                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.a11y_queue_remove))
                                    }
                                },
                                modifier = Modifier.clickable { viewModel.client.playQueueItem(item.queueItemId) },
                                colors = ListItemDefaults.colors(
                                    containerColor = if (playing) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                ),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    pickerRequest?.let { request ->
        val resolution = pickerResolution ?: SubtitlePickerResolution.Loading
        com.nanahoshi.audioplayer.ui.components.TrackSubtitlePickerSheet(
            track = (resolution as? SubtitlePickerResolution.Ready)?.track,
            trackTitle = pickerTitle.ifBlank { request.title },
            loading = resolution == SubtitlePickerResolution.Loading,
            actions = viewModel.subtitlePickerActions,
            onDismiss = {
                pickerRequest = null
                pickerTitle = ""
                pickerResolution = null
            },
        )
    }
}
