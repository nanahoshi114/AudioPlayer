package com.nanahoshi.audioplayer.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nanahoshi.audioplayer.playback.PlayerUiState
import com.nanahoshi.audioplayer.playback.SubtitleCue
import com.nanahoshi.audioplayer.playback.hasTranslation
import com.nanahoshi.audioplayer.playback.indexAt
import com.nanahoshi.audioplayer.playback.line
import com.nanahoshi.audioplayer.playback.textAt
import com.nanahoshi.audioplayer.ui.CoverImage
import com.nanahoshi.audioplayer.ui.components.AppTopBar
import com.nanahoshi.audioplayer.ui.components.EmptyState
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
    val fraction = if (state.durationMs > 0) state.positionMs / state.durationMs.toFloat() else 0f
    val progress by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = if (reduce) snap() else tween(400),
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
                        state.title.ifBlank { "正在播放" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        state.artist,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = viewModel.client::toggle,
                    interactionSource = playInteraction,
                    modifier = Modifier.pressScale(playInteraction),
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "暂停" else "播放",
                    )
                }
                IconButton(onClick = viewModel.client::next) {
                    Icon(Icons.Default.SkipNext, contentDescription = "下一首")
                }
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NowPlayingScreen(
    onBack: () -> Unit,
    viewModel: PlayerViewModel = viewModel(),
    coverModifier: Modifier = Modifier,
    onOpenCollection: (com.nanahoshi.audioplayer.bilibili.BiliCollection) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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
    val cues = viewModel.subtitleCues
    val subtitle = cues.textAt(state.positionMs, viewModel.showChinese)
    var showSubtitles by remember { mutableStateOf(false) }
    var discardPrompt by remember { mutableStateOf(false) }
    var fullTitle by remember { mutableStateOf(false) }
    val localNetwork = rememberLocalNetworkAccess { viewModel.reportLocalNetworkDenied() }
    val current = queue.find { it.queueItemId == state.queueItemId }
    LaunchedEffect(queueExpanded, state.queueItemId) {
        if (!queueExpanded) return@LaunchedEffect
        val index = queue.indexOfFirst { it.queueItemId == state.queueItemId }
        if (index >= 0) listState.scrollToItem(index)
    }
    WhisperPromptDialog(viewModel)
    if (fullTitle) {
        AlertDialog(
            onDismissRequest = { fullTitle = false },
            title = { Text("标题与作者") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(state.title, style = MaterialTheme.typography.titleMedium)
                    if (state.artist.isNotBlank()) {
                        Text(state.artist, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { fullTitle = false }) { Text("关闭") } },
        )
    }
    if (discardPrompt) {
        AlertDialog(
            onDismissRequest = { discardPrompt = false },
            text = { Text("删除这首的字幕？可以之后重新生成。") },
            confirmButton = {
                TextButton(onClick = {
                    discardPrompt = false
                    showSubtitles = false
                    viewModel.discardSubtitles()
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { discardPrompt = false }) { Text("取消") }
            },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            text = { Text("清空当前播放队列？") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.client.clearQueue()
                    closeQueue()
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
    Box(Modifier.fillMaxSize()) {
        if (!state.hasMedia) {
            Column(Modifier.fillMaxSize()) {
                AppTopBar(title = "正在播放", onBack = onBack)
                EmptyState(
                    title = "当前没有正在播放的音频",
                    message = "从音频库点一首，或播放一个列表。",
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                AppTopBar(
                    title = "正在播放",
                    onBack = onBack,
                    actions = {
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
                            modifier = coverModifier
                                .padding(top = 8.dp)
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
                                .clickable(onClickLabel = "查看完整标题") { fullTitle = true }
                                .padding(vertical = 4.dp),
                        )
                        Text(
                            state.artist,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .heightIn(min = 96.dp)
                                .padding(vertical = 12.dp)
                                .then(
                                    if (cues.isNotEmpty()) {
                                        Modifier.clickable(onClickLabel = "查看字幕全文") { showSubtitles = true }
                                    } else {
                                        Modifier
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            when {
                                subtitle.isNotEmpty() -> CueLine(subtitle)
                                cues.isNotEmpty() -> Unit
                                viewModel.generatedEmpty -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        "未检测到人声",
                                        textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    TextButton(onClick = { discardPrompt = true }) { Text("删除字幕") }
                                }
                                viewModel.subtitlesReady -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    viewModel.subtitleNotice?.let { notice ->
                                        Text(
                                            notice,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(bottom = 8.dp),
                                        )
                                    }
                                    FlowRow(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        FilledTonalButton(
                                            onClick = {
                                                localNetwork.run(viewModel.generateNeedsLocalNetwork()) {
                                                    viewModel.requestGenerate(translate = true)
                                                }
                                            },
                                        ) {
                                            Text("生成字幕和翻译", textAlign = TextAlign.Center)
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                localNetwork.run(viewModel.generateNeedsLocalNetwork()) {
                                                    viewModel.requestGenerate(translate = false)
                                                }
                                            },
                                        ) {
                                            Text("生成字幕", textAlign = TextAlign.Center)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
                    SeekBar(state, onSeek = viewModel.client::seekTo)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = viewModel.client::previous, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "上一首", modifier = Modifier.size(32.dp))
                        }
                        PlayButton(playing = state.isPlaying, onClick = viewModel.client::toggle)
                        IconButton(onClick = viewModel.client::next, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.Default.SkipNext, contentDescription = "下一首", modifier = Modifier.size(32.dp))
                        }
                    }
                    FlowRow(
                        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        SecondaryAction(
                            icon = { Icon(Icons.Outlined.Timer, contentDescription = null) },
                            label = remaining?.let { formatDuration(it) } ?: "定时关闭",
                            onClick = { sleepDialog = true },
                        )
                        if (remaining != null) {
                            SecondaryAction(
                                icon = { Text("×", style = MaterialTheme.typography.titleMedium) },
                                label = "取消定时",
                                onClick = viewModel.client::cancelSleepTimer,
                            )
                        }
                        SecondaryAction(
                            icon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) },
                            label = "队列",
                            onClick = { queueExpanded = true },
                        )
                        PlayerCollectionAction(current, collectionLink, onOpenCollection)
                        if (viewModel.offerBiliCache) {
                            SecondaryAction(
                                icon = { Icon(Icons.Default.Download, contentDescription = null) },
                                label = "下载音轨",
                                onClick = viewModel::cacheCurrent,
                            )
                        }
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
                    chinese = viewModel.showChinese,
                    translating = viewModel.translating,
                    canDelete = viewModel.fromGenerated,
                    onChinese = { viewModel.showChinese = true },
                    onJapanese = { viewModel.showChinese = false },
                    onTranslate = viewModel::requestTranslation,
                    canTranslate = viewModel.canTranslateSubtitles,
                    onStop = viewModel::cancelTranslation,
                    polishing = viewModel.polishing,
                    onPolish = viewModel::requestPolish,
                    onStopPolish = viewModel::cancelPolish,
                    onDelete = { discardPrompt = true },
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
                Text("播放队列", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 16.dp))
                TextButton(onClick = { confirmClear = true }, enabled = queue.isNotEmpty()) { Text("清空") }
                IconButton(onClick = { closeQueue() }) {
                    Icon(Icons.Default.Close, contentDescription = "关闭")
                }
            }
            if (queue.isEmpty()) {
                EmptyState(title = "队列是空的", modifier = Modifier.padding(bottom = 24.dp))
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
                                        contentDescription = "拖动排序",
                                        modifier = Modifier.size(48.dp).draggableHandle().padding(12.dp),
                                    )
                                },
                                trailingContent = {
                                    IconButton(onClick = { viewModel.client.remove(index, item.queueItemId) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "从队列移除")
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
private fun PlayButton(playing: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .padding(horizontal = 18.dp)
            .pressScale(interaction)
            .size(72.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = if (playing) "暂停" else "播放",
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
        label = "合集：${collection.title}",
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
            .pressScale(interaction)
            .clip(MaterialTheme.shapes.medium)
            .heightIn(min = 48.dp)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
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
) {
    BackHandler(onBack = onBack)
    val listState = key(mediaId) { rememberLazyListState() }
    val follow = remember(mediaId) { SubtitleFollowState() }
    val active = cues.indexAt(positionMs)
    val anchor = follow.anchorIndex(cues, positionMs)
    val blue = if (LocalDarkTheme.current) Color(0xFF90CAF9) else Color(0xFF1565C0)
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
            title = "字幕",
            onBack = onBack,
            actions = {
                when {
                    polishing -> TextButton(onClick = onStopPolish) { Text("中止") }
                    translating -> TextButton(onClick = onStop) { Text("中止") }
                    cues.hasTranslation() -> TextButton(onClick = onPolish) { Text("润色") }
                    cues.isNotEmpty() && canTranslate -> TextButton(onClick = onTranslate) { Text("生成翻译") }
                }
                if (canDelete) {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menu = false
                                onDelete()
                            },
                        )
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
                ) { Text("中文") }
                SegmentedButton(
                    selected = !chinese,
                    onClick = onJapanese,
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("日文") }
            }
        }
        if (cues.isEmpty()) {
            EmptyState(title = "这首没有字幕")
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
                        targetValue = if (current) blue else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
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
                ) { Text("回到当前句") }
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

@Composable
private fun SleepTimerDialog(onDismiss: () -> Unit, onStart: (Long) -> Unit) {
    var minutes by remember { mutableStateOf("") }
    var seconds by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("定时关闭") },
        text = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = minutes,
                    onValueChange = { minutes = it.filter(Char::isDigit).take(4) },
                    label = { Text("分") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = seconds,
                    onValueChange = { seconds = it.filter(Char::isDigit).take(2) },
                    label = { Text("秒") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val minuteValue = (minutes.toLongOrNull() ?: 0L).coerceAtLeast(0L)
                val secondValue = (seconds.toLongOrNull() ?: 0L).coerceIn(0L, 59L)
                val totalMs = minuteValue * 60_000L + secondValue * 1000L
                if (totalMs > 0L) onStart(totalMs)
            }) { Text("开始倒计时") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun WhisperPromptDialog(viewModel: PlayerViewModel) {
    when (viewModel.whisperPrompt) {
        WhisperPrompt.NeedSettings -> AlertDialog(
            onDismissRequest = viewModel::dismissWhisperPrompt,
            text = { Text("请先在「设置 → 听写和翻译」保存听写设置（线上模型填 API Key，自部署模型填地址和 API Key）。") },
            confirmButton = {
                TextButton(onClick = viewModel::dismissWhisperPrompt) { Text("知道了") }
            },
        )
        WhisperPrompt.NeedModelDownload -> AlertDialog(
            onDismissRequest = viewModel::dismissWhisperPrompt,
            text = { Text("请先在「设置 → 听写和翻译」下载 SenseVoiceSmall 模型。") },
            confirmButton = {
                TextButton(onClick = viewModel::dismissWhisperPrompt) { Text("知道了") }
            },
        )
        WhisperPrompt.NeedDownload -> AlertDialog(
            onDismissRequest = viewModel::dismissWhisperPrompt,
            text = { Text("生成字幕需要先下载这首歌的音频，是否下载？") },
            confirmButton = {
                TextButton(onClick = viewModel::confirmWhisperDownload) { Text("下载") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissWhisperPrompt) { Text("取消") }
            },
        )
        null -> Unit
    }
    if (viewModel.translatePrompt) {
        AlertDialog(
            onDismissRequest = viewModel::dismissTranslatePrompt,
            text = { Text("请先在「设置 → 听写和翻译」选择翻译平台并填写 API Key。") },
            confirmButton = {
                TextButton(onClick = viewModel::dismissTranslatePrompt) { Text("知道了") }
            },
        )
    }
}

@Composable
private fun SeekBar(state: PlayerUiState, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = state.durationMs.coerceAtLeast(1L)
    val shown = if (dragging) dragValue else state.positionMs / duration.toFloat()
    LaunchedEffect(state.positionMs, dragging) {
        if (!dragging) dragValue = state.positionMs / duration.toFloat()
    }
    Column {
        Slider(
            value = shown.coerceIn(0f, 1f),
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                onSeek((dragValue * duration).toLong())
                dragging = false
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                formatDuration(if (dragging) (dragValue * duration).toLong() else state.positionMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatDuration(state.durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
