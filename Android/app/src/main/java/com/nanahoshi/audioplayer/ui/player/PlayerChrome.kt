package com.nanahoshi.audioplayer.ui.player

import com.nanahoshi.audioplayer.text.asString
import com.nanahoshi.audioplayer.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.lazy.items
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.matches
import com.nanahoshi.audioplayer.data.operation
import com.nanahoshi.audioplayer.data.SubtitleTask
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import com.nanahoshi.audioplayer.ui.CoverImage
import com.nanahoshi.audioplayer.ui.components.AppTopBar
import com.nanahoshi.audioplayer.ui.components.EmptyState
import com.nanahoshi.audioplayer.ui.components.RoundedProgress
import com.nanahoshi.audioplayer.ui.components.SourceBadge
import com.nanahoshi.audioplayer.ui.components.pressScale
import com.nanahoshi.audioplayer.ui.components.rememberLocalNetworkAccess
import com.nanahoshi.audioplayer.ui.formatDuration
import com.nanahoshi.audioplayer.ui.theme.LocalDarkTheme
import com.nanahoshi.audioplayer.ui.theme.LocalReduceMotion
import com.nanahoshi.audioplayer.ui.theme.Motion
import com.nanahoshi.audioplayer.ui.theme.SubtitleLarge
import com.nanahoshi.audioplayer.ui.theme.SubtitleReader
import com.nanahoshi.audioplayer.ui.theme.pressSpec
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun MiniPlayer(
    onOpen: () -> Unit,
    viewModel: PlayerViewModel = viewModel(),
    coverModifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val reduce = LocalReduceMotion.current
    val enter = if (reduce) {
        EnterTransition.None
    } else {
        slideInVertically(tween(Motion.medium, easing = Motion.enter)) { it / 2 } + fadeIn(tween(Motion.medium, easing = Motion.enter))
    }
    val exit = if (reduce) {
        ExitTransition.None
    } else {
        slideOutVertically(tween(Motion.short, easing = Motion.exit)) { it / 2 } + fadeOut(tween(Motion.short, easing = Motion.exit))
    }
    AnimatedVisibility(visible = state.hasMedia, enter = enter, exit = exit) {
        MiniPlayerCard(state, onOpen, viewModel, coverModifier)
    }
}

@Composable
private fun MiniPlayerCard(
    state: PlayerUiState,
    onOpen: () -> Unit,
    viewModel: PlayerViewModel,
    coverModifier: Modifier,
) {
    val openInteraction = remember { MutableInteractionSource() }
    val playInteraction = remember { MutableInteractionSource() }
    val reduce = LocalReduceMotion.current
    val nowPlaying = stringResource(R.string.player_now_playing)
    val hasDuration = state.durationMs > 0L
    val progress by animateFloatAsState(
        targetValue = playbackProgress(state.positionMs, state.durationMs),
        animationSpec = if (reduce || !hasDuration) snap() else tween(400, easing = LinearEasing),
        label = "miniProgress",
    )
    Surface(
        onClick = onOpen,
        interactionSource = openInteraction,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 6.dp,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).pressScale(openInteraction),
    ) {
        Column {
            Row(
                Modifier.padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverImage(state.coverUri, modifier = coverModifier, size = 44.dp)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(
                        state.title.ifBlank { nowPlaying },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (state.artist.isNotBlank()) {
                        Text(
                            state.artist,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(
                    onClick = viewModel.client::toggle,
                    interactionSource = playInteraction,
                    modifier = Modifier.pressScale(playInteraction),
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = stringResource(if (state.isPlaying) R.string.a11y_pause else R.string.a11y_play),
                    )
                }
                IconButton(onClick = viewModel.client::next) {
                    Icon(Icons.Default.SkipNext, contentDescription = stringResource(R.string.a11y_next))
                }
            }
            if (hasDuration) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            } else {
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

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
        AlertDialog(
            onDismissRequest = close,
            text = { Text(stringResource(failure.messageRes)) },
            confirmButton = {
                TextButton(onClick = {
                    close()
                    if (verifyPost != null) {
                        onVerifyJapaneseAsmr(JapaneseAsmrClient.postUrl(verifyPost), failure.queueId, failure.mediaKey)
                    }
                }) {
                    Text(stringResource(if (verifyPost != null) R.string.download_verify_continue else R.string.common_close))
                }
            },
            dismissButton = {
                if (verifyPost != null) TextButton(onClick = close) { Text(stringResource(R.string.common_close)) }
            },
        )
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
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.player_chapters), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { chaptersOpen = false }) { Text(stringResource(R.string.common_close)) }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).navigationBarsPadding()) {
                items(chapters, key = { it.startMs }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.title) },
                        supportingContent = { Text(com.nanahoshi.audioplayer.ui.formatDuration(entry.startMs)) },
                        colors = androidx.compose.material3.ListItemDefaults.colors(
                            containerColor = if (entry == chapter) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
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
    val cues = loaded?.cues.orEmpty()
    val chinese = !selected || subtitleSelection.chinese
    val subtitle = cues.textAt(state.positionMs, chinese)
    var showSubtitles by remember { mutableStateOf(false) }
    var discardTarget by remember { mutableStateOf<QueuedTrack?>(null) }
    var fullTitle by remember { mutableStateOf(false) }
    val localNetwork = rememberLocalNetworkAccess { viewModel.reportLocalNetworkDenied() }
    LaunchedEffect(queueExpanded, state.queueItemId) {
        if (!queueExpanded) return@LaunchedEffect
        val index = queue.indexOfFirst { it.queueItemId == state.queueItemId }
        if (index >= 0) listState.scrollToItem(index)
    }
    WhisperPromptDialog(viewModel)
    if (fullTitle) {
        AlertDialog(
            onDismissRequest = { fullTitle = false },
            title = { Text(stringResource(R.string.player_edit_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(state.title, style = MaterialTheme.typography.titleMedium)
                    if (state.artist.isNotBlank()) {
                        Text(state.artist, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { fullTitle = false }) { Text(stringResource(R.string.common_close)) } },
        )
    }
    if (discardTarget != null) {
        AlertDialog(
            onDismissRequest = { discardTarget = null },
            text = { Text(stringResource(R.string.player_delete_generated_subtitle_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    val target = discardTarget
                    discardTarget = null
                    if (target == null) return@TextButton
                    if (target.queueItemId == state.queueItemId && loaded?.hasManualBinding != true) {
                        showSubtitles = false
                    }
                    viewModel.discardGeneratedSubtitles(target)
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { discardTarget = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            text = { Text(stringResource(R.string.player_clear_queue_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.client.clearQueue()
                    closeQueue()
                }) { Text(stringResource(R.string.player_clear_queue)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.common_cancel)) }
            },
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
                            style = MaterialTheme.typography.headlineSmall,
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
                                        TextButton(onClick = { discardTarget = current }) { Text(stringResource(R.string.player_delete_subtitle)) }
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
                                icon = { Text("×", style = MaterialTheme.typography.titleMedium) },
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
                    onDelete = { discardTarget = current },
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
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.player_queue_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 16.dp))
                TextButton(onClick = { confirmClear = true }, enabled = queue.isNotEmpty()) { Text(stringResource(R.string.player_clear_queue)) }
                IconButton(onClick = { closeQueue() }) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.a11y_close))
                }
            }
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
                                colors = if (playing) {
                                    ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                                } else {
                                    ListItemDefaults.colors()
                                },
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

@Composable
private fun CueLine(text: String) {
    val reduce = LocalReduceMotion.current
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            if (reduce) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                fadeIn(tween(Motion.short, easing = Motion.enter)) togetherWith
                    fadeOut(tween(100, easing = Motion.enter))
            }
        },
        label = "subtitleLine",
    ) { line ->
        Text(
            line,
            style = SubtitleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth(),
        )
    }
}

@Composable
private fun WhisperProgress(
    job: WhisperJobEntity,
    progress: TaskProgressSnapshot?,
    onStop: () -> Unit,
) {
    val translating = TranslateMark.isActive(job.translateStatus)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(
                if (translating) R.string.player_translating_subtitles else R.string.player_transcribing_subtitles,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        RoundedProgress(
            progress = progress?.fraction(),
            modifier = Modifier.padding(top = 8.dp),
        )
        TextButton(onClick = onStop, modifier = Modifier.padding(top = 4.dp)) {
            Text(stringResource(R.string.common_stop))
        }
    }
}

@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .padding(horizontal = 10.dp)
            .pressScale(interaction)
            .size(72.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(interactionSource = interaction, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = stringResource(if (playing) R.string.a11y_pause else R.string.a11y_play),
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(36.dp),
        )
    }
}

@Composable
internal fun PlayerCollectionAction(
    row: com.nanahoshi.audioplayer.data.db.QueuedTrack?,
    link: com.nanahoshi.audioplayer.playback.PlayingBiliCollectionLink?,
    onOpen: (com.nanahoshi.audioplayer.bilibili.BiliCollection) -> Unit,
) {
    val collection = com.nanahoshi.audioplayer.playback.playingBiliCollection(row, link) ?: return
    SecondaryAction(
        icon = { Icon(Icons.Default.CollectionsBookmark, contentDescription = null) },
        label = stringResource(R.string.player_collection_label, collection.title),
        onClick = { onOpen(collection) },
        modifier = Modifier.widthIn(max = 240.dp),
        maxLines = 2,
    )
}

@Composable
private fun SecondaryAction(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier
            .widthIn(min = 48.dp)
            .pressScale(interaction)
            .clip(MaterialTheme.shapes.medium)
            .heightIn(min = 48.dp)
            .clickable(interactionSource = interaction, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon()
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 2.dp),
            maxLines = maxLines, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubtitleReader(
    mediaId: Long,
    cues: List<SubtitleCue>,
    positionMs: Long,
    chinese: Boolean,
    translating: Boolean,
    canDelete: Boolean,
    onChinese: () -> Unit,
    onJapanese: () -> Unit,
    onTranslate: () -> Unit,
    onStop: () -> Unit,
    polishing: Boolean,
    onPolish: () -> Unit,
    onStopPolish: () -> Unit,
    onDelete: () -> Unit,
    onSeek: (Long) -> Unit,
    onBack: () -> Unit,
    canTranslate: Boolean,
    whisperJob: WhisperJobEntity? = null,
    whisperProgress: TaskProgressSnapshot? = null,
    onStopWhisper: () -> Unit = {},
    canSelectSubtitle: Boolean = false,
    hasManualBinding: Boolean = false,
    disabledByUser: Boolean = false,
    canRegenerate: Boolean = false,
    onSelectSubtitle: () -> Unit = {},
    onRegenerate: () -> Unit = {},
    onClearBinding: () -> Unit = {},
) {
    BackHandler(onBack = onBack)
    val listState = key(mediaId) { rememberLazyListState() }
    val follow = remember(mediaId) { SubtitleFollowState() }
    val active = cues.indexAt(positionMs)
    val anchor = follow.anchorIndex(cues, positionMs)
    val autoTranslating = whisperJob?.let { TranslateMark.isActive(it.translateStatus) } == true
    var placed by remember(mediaId) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val reduce = LocalReduceMotion.current
    val scrolling = remember(follow) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && consumed.y != 0f) follow.onUserScroll()
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(anchor, cues, follow.following, mediaId) {
        if (!follow.following || anchor < 0) return@LaunchedEffect
        listState.centerOn(anchor, animated = placed && !reduce)
        placed = true
    }
    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            title = stringResource(R.string.player_subtitles),
            onBack = onBack,
            actions = {
                when {
                    polishing -> TextButton(onClick = onStopPolish) { Text(stringResource(R.string.common_stop)) }
                    translating -> TextButton(onClick = onStop) { Text(stringResource(R.string.common_stop)) }
                    whisperJob != null || autoTranslating -> TextButton(onClick = onStopWhisper) { Text(stringResource(R.string.common_stop)) }
                    cues.hasTranslation() -> TextButton(onClick = onPolish) { Text(stringResource(R.string.player_polish)) }
                    cues.isNotEmpty() && canTranslate -> TextButton(onClick = onTranslate) { Text(stringResource(R.string.player_translate)) }
                }
                val regenerateAllowed = canRegenerate && !disabledByUser
                val hasMenu = canSelectSubtitle || regenerateAllowed || hasManualBinding || canDelete
                if (hasMenu) {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.a11y_subtitle_actions))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (canSelectSubtitle) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.player_switch_subtitle)) },
                                onClick = {
                                    menu = false
                                    onSelectSubtitle()
                                },
                            )
                        }
                        if (regenerateAllowed) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.player_regenerate_subtitle)) },
                                onClick = {
                                    menu = false
                                    onRegenerate()
                                },
                            )
                        }
                        if (hasManualBinding) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.player_clear_subtitle_binding)) },
                                onClick = {
                                    menu = false
                                    onClearBinding()
                                },
                            )
                        }
                        if (canDelete) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.player_delete_generated_subtitle), color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    menu = false
                                    onDelete()
                                },
                            )
                        }
                    }
                }
            },
        )
        if (cues.hasTranslation()) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                SegmentedButton(
                    selected = chinese,
                    onClick = onChinese,
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text(stringResource(R.string.player_chinese), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                SegmentedButton(
                    selected = !chinese,
                    onClick = onJapanese,
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text(stringResource(R.string.player_japanese), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
            }
        }
        if (cues.isEmpty()) {
            if (whisperJob != null) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    WhisperProgress(
                        job = whisperJob,
                        progress = whisperProgress,
                        onStop = onStopWhisper,
                    )
                }
            } else if (disabledByUser) {
                EmptyState(
                    title = stringResource(R.string.player_subtitles),
                    message = stringResource(R.string.player_subtitle_disabled_notice),
                )
            } else {
                EmptyState(
                    title = stringResource(R.string.player_subtitle_empty_title),
                    message = stringResource(R.string.player_subtitle_empty_message),
                )
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().nestedScroll(scrolling),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 72.dp),
            ) {
                itemsIndexed(cues, key = { index, cue -> "$index-${cue.startMs}" }) { index, cue ->
                    val current = index == active
                    val color by animateColorAsState(
                        targetValue = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        animationSpec = if (reduce) snap() else tween(Motion.medium, easing = Motion.enter),
                        label = "cueColor",
                    )
                    Text(
                        cue.line(chinese),
                        color = color,
                        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                        style = SubtitleReader,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSeek(cue.startMs) }
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                }
            }
            if (!follow.following) {
                FilledTonalButton(
                    onClick = follow::resume,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                ) { Text(stringResource(R.string.player_back_to_current)) }
            }
            }
        }
    }
}

private suspend fun LazyListState.centerOn(index: Int, animated: Boolean) {
    val viewport = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).coerceAtLeast(0)
    val sample = layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 0
    val offset = if (viewport > 0 && sample > 0) -((viewport - sample) / 2) else 0
    if (animated) animateScrollToItem(index, offset) else scrollToItem(index, offset)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SleepTimerDialog(onDismiss: () -> Unit, onStart: (Long) -> Unit) {
    var minutes by remember { mutableStateOf("") }
    var seconds by remember { mutableStateOf("") }
    val input = validateSleepTimerInput(minutes, seconds)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.player_sleep_timer)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    sleepTimerPresets.forEach { preset ->
                        AssistChip(
                            onClick = {
                                minutes = preset.toString()
                                seconds = ""
                            },
                            label = { Text(stringResource(R.string.player_timer_preset_minutes, preset)) },
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = minutes,
                        onValueChange = { minutes = it.filter(Char::isDigit).take(4) },
                        label = { Text(stringResource(R.string.player_timer_minutes)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = seconds,
                        onValueChange = { seconds = it.filter(Char::isDigit).take(2) },
                        label = { Text(stringResource(R.string.player_timer_seconds)) },
                        singleLine = true,
                        isError = input.secondsOutOfRange,
                        supportingText = if (input.secondsOutOfRange) {
                            { Text(stringResource(R.string.player_timer_seconds_error)) }
                        } else {
                            null
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = input.valid,
                onClick = { onStart(input.totalMs) },
            ) { Text(stringResource(R.string.player_start_timer)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

private val sleepTimerPresets = listOf(15, 30, 60)

/** 字幕选择弹层的目标曲目解析结果；Loading 期间不闪示「未入库曲目」。 */
private sealed interface SubtitlePickerResolution {
    data object Loading : SubtitlePickerResolution
    data object Unlisted : SubtitlePickerResolution
    data class Ready(val track: TrackEntity) : SubtitlePickerResolution
}

@Composable
private fun WhisperPromptDialog(viewModel: PlayerViewModel) {
    when (val prompt = viewModel.whisperPrompt) {
        WhisperPrompt.NeedSettings -> AlertDialog(
            onDismissRequest = viewModel::dismissWhisperPrompt,
            text = { Text(stringResource(R.string.player_whisper_setup_help)) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissWhisperPrompt) { Text(stringResource(R.string.player_got_it)) }
            },
        )
        is WhisperPrompt.NeedModelDownload -> AlertDialog(
            onDismissRequest = viewModel::dismissWhisperPrompt,
            text = { Text(com.nanahoshi.audioplayer.data.missingOfflineWhisperModelMessage(prompt.model).asString()) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissWhisperPrompt) { Text(stringResource(R.string.player_got_it)) }
            },
        )
        is WhisperPrompt.NeedDownload -> AlertDialog(
            onDismissRequest = viewModel::dismissWhisperPrompt,
            text = { Text(stringResource(R.string.player_download_for_subtitle)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmWhisperDownload) { Text(stringResource(R.string.common_download)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissWhisperPrompt) { Text(stringResource(R.string.common_cancel)) }
            },
        )
        null -> Unit
    }
    if (viewModel.translatePrompt) {
        AlertDialog(
            onDismissRequest = viewModel::dismissTranslatePrompt,
            text = { Text(stringResource(R.string.player_translate_setup_help)) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissTranslatePrompt) { Text(stringResource(R.string.player_got_it)) }
            },
        )
    }
}

@Composable
private fun SeekBar(state: PlayerUiState, onSeek: (Long) -> Unit) {
    val duration = state.durationMs
    val canScrub = duration > 0L
    var dragValue by remember(state.queueItemId, canScrub) { mutableStateOf<Float?>(null) }
    val previewPosition = dragValue?.let { playbackSeekPosition(it, duration) }
    Column {
        Slider(
            value = dragValue ?: playbackProgress(state.positionMs, duration),
            enabled = canScrub,
            onValueChange = { dragValue = it },
            onValueChangeFinished = {
                dragValue?.let { playbackSeekPosition(it, duration) }?.let(onSeek)
                dragValue = null
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                formatDuration(previewPosition ?: state.positionMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (canScrub) formatDuration(duration) else stringResource(R.string.player_duration_unknown),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
