package com.nanahoshi.audioplayer.ui.player

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
import com.nanahoshi.audioplayer.ui.formatDuration
import com.nanahoshi.audioplayer.ui.theme.LocalDarkTheme
import com.nanahoshi.audioplayer.ui.theme.LocalReduceMotion
import com.nanahoshi.audioplayer.ui.theme.Motion
import com.nanahoshi.audioplayer.ui.theme.SubtitleLarge
import com.nanahoshi.audioplayer.ui.theme.SubtitleReader
import com.nanahoshi.audioplayer.ui.theme.pressSpec
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
                    AnimatedContent(
                        targetState = state.isPlaying,
                        transitionSpec = {
                            if (reduce) {
                                EnterTransition.None togetherWith ExitTransition.None
                            } else {
                                (scaleIn(tween(Motion.short, easing = Motion.enter), initialScale = 0.7f) + fadeIn(tween(Motion.short))) togetherWith
                                    (scaleOut(tween(Motion.short, easing = Motion.exit), targetScale = 0.7f) + fadeOut(tween(Motion.short)))
                            }
                        },
                        label = "playPause",
                    ) { playing ->
                        Icon(
                            if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playing) "暂停" else "播放",
                        )
                    }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    onBack: () -> Unit,
    viewModel: PlayerViewModel = viewModel(),
    coverModifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle(emptyList())
    val remaining by viewModel.client.sleepRemainingMs.collectAsStateWithLifecycle()
    var sleepDialog by remember { mutableStateOf(false) }
    var queueExpanded by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        viewModel.client.move(from.index, to.index)
    }
    val subtitleScroll = rememberScrollState()
    val cues = viewModel.subtitleCues
    val subtitle = cues.textAt(state.positionMs, viewModel.showChinese)
    var showSubtitles by remember { mutableStateOf(false) }
    var discardPrompt by remember { mutableStateOf(false) }
    val localNetwork = rememberLocalNetworkAccess { viewModel.reportLocalNetworkDenied() }
    val current = queue.find { it.queueItemId == state.queueItemId }
    LaunchedEffect(subtitle) { subtitleScroll.scrollTo(0) }
    LaunchedEffect(queueExpanded, state.queueItemId) {
        if (!queueExpanded) return@LaunchedEffect
        val index = queue.indexOfFirst { it.queueItemId == state.queueItemId }
        if (index >= 0) listState.scrollToItem(index)
    }
    WhisperPromptDialog(viewModel)
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
                    queueExpanded = false
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
                    title = "当前没有在播的音频",
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
                    val cover = minOf(maxWidth * 0.72f, maxHeight * 0.46f)
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = 20.dp),
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
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                        Text(
                            state.artist,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(subtitleScroll)
                                .then(
                                    if (cues.isNotEmpty()) {
                                        Modifier.clickable { showSubtitles = true }
                                    } else {
                                        Modifier
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            when {
                                subtitle.isNotEmpty() -> CueLine(subtitle) { showSubtitles = true }
                                cues.isNotEmpty() -> Unit
                                viewModel.generatedEmpty -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        "没有听出人声",
                                        textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    TextButton(onClick = { discardPrompt = true }) { Text("删除字幕") }
                                }
                                viewModel.subtitlesReady -> Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    FilledTonalButton(
                                        onClick = {
                                            localNetwork.run(viewModel.generateNeedsLocalNetwork()) {
                                                viewModel.requestGenerate(translate = true)
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text("生成字幕和翻译", textAlign = TextAlign.Center)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            localNetwork.run(viewModel.generateNeedsLocalNetwork()) {
                                                viewModel.requestGenerate(translate = false)
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text("生成字幕", textAlign = TextAlign.Center)
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
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
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
                        if (viewModel.offerBiliCache) {
                            SecondaryAction(
                                icon = { Icon(Icons.Default.Download, contentDescription = null) },
                                label = "缓存音轨",
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
                    cues = cues,
                    positionMs = state.positionMs,
                    chinese = viewModel.showChinese,
                    translating = viewModel.translating,
                    canDelete = viewModel.fromGenerated,
                    onChinese = { viewModel.showChinese = true },
                    onJapanese = { viewModel.showChinese = false },
                    onTranslate = viewModel::requestTranslation,
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
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("播放队列", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 16.dp))
                TextButton(onClick = { confirmClear = true }, enabled = queue.isNotEmpty()) { Text("清空") }
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
                                        modifier = Modifier.draggableHandle(),
                                    )
                                },
                                trailingContent = {
                                    IconButton(onClick = { viewModel.client.remove(index, item.queueItemId) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "从当前列表移除")
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
private fun CueLine(text: String, onOpen: () -> Unit) {
    val reduce = LocalReduceMotion.current
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            if (reduce) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                (slideInVertically(tween(Motion.medium, easing = Motion.enter)) { it / 3 } + fadeIn(tween(Motion.medium))) togetherWith
                    (slideOutVertically(tween(Motion.short, easing = Motion.exit)) { -it / 3 } + fadeOut(tween(Motion.short)))
            }
        },
        label = "subtitleLine",
    ) { line ->
        Text(
            line,
            style = SubtitleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(onOpen) { detectTapGestures(onTap = { onOpen() }) },
        )
    }
}

@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val reduce = LocalReduceMotion.current
    Box(
        Modifier
            .padding(horizontal = 18.dp)
            .pressScale(interaction)
            .size(72.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = playing,
            transitionSpec = {
                if (reduce) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    (scaleIn(tween(Motion.short), initialScale = 0.7f) + fadeIn(tween(Motion.short))) togetherWith
                        (scaleOut(tween(Motion.short), targetScale = 0.7f) + fadeOut(tween(Motion.short)))
                }
            },
            label = "mainPlay",
        ) { isPlaying ->
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "暂停" else "播放",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

@Composable
private fun SecondaryAction(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier
            .pressScale(interaction)
            .clip(MaterialTheme.shapes.medium)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon()
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 2.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubtitleReader(
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
) {
    BackHandler(onBack = onBack)
    val listState = rememberLazyListState()
    val active = cues.indexAt(positionMs)
    val blue = if (LocalDarkTheme.current) Color(0xFF90CAF9) else Color(0xFF1565C0)
    var placed by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val reduce = LocalReduceMotion.current
    LaunchedEffect(active, cues.size) {
        if (active < 0 || cues.isEmpty()) return@LaunchedEffect
        listState.centerOn(active, animated = placed && !reduce)
        if (!placed) {
            placed = true
            listState.centerOn(active, animated = false)
        }
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
                    cues.isNotEmpty() -> TextButton(onClick = onTranslate) { Text("生成翻译") }
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
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(cues, key = { index, cue -> "$index-${cue.startMs}" }) { index, cue ->
                    val current = index == active
                    val color by animateColorAsState(
                        targetValue = if (current) blue else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        animationSpec = if (reduce) snap() else tween(Motion.medium, easing = Motion.enter),
                        label = "cueColor",
                    )
                    val scale by animateFloatAsState(
                        targetValue = if (current && !reduce) 1.05f else 1f,
                        animationSpec = pressSpec(),
                        label = "cueScale",
                    )
                    Text(
                        cue.line(chinese),
                        color = color,
                        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                        style = SubtitleReader,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            }
                            .clickable { onSeek(cue.startMs) }
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                    )
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
            }) { Text("开始") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private class LocalNetworkAccess(val run: (Boolean, () -> Unit) -> Unit)

@Composable
private fun rememberLocalNetworkAccess(onDenied: () -> Unit): LocalNetworkAccess {
    val context = androidx.compose.ui.platform.LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val next = pending
        pending = null
        if (granted) {
            next?.invoke()
            return@rememberLauncherForActivityResult
        }
        onDenied()
        val activity = context.findActivity()
        val permanent = activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            Manifest.permission.ACCESS_LOCAL_NETWORK,
        )
        if (permanent) {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ),
            )
        }
    }
    return LocalNetworkAccess { needed, action ->
        val allowed = Build.VERSION.SDK_INT < 37 || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_LOCAL_NETWORK,
        ) == PackageManager.PERMISSION_GRANTED
        if (!needed || allowed) {
            action()
        } else {
            pending = action
            launcher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

@Composable
private fun WhisperPromptDialog(viewModel: PlayerViewModel) {
    when (viewModel.whisperPrompt) {
        WhisperPrompt.NeedSettings -> AlertDialog(
            onDismissRequest = viewModel::dismissWhisperPrompt,
            text = { Text("请先在「设置 → 听写和翻译」里保存听写设置。线上模型只需 API Key；本地部署的模型还要填写地址和口令") },
            confirmButton = {
                TextButton(onClick = viewModel::dismissWhisperPrompt) { Text("知道了") }
            },
        )
        WhisperPrompt.NeedDownload -> AlertDialog(
            onDismissRequest = viewModel::dismissWhisperPrompt,
            text = { Text("需要下载文件，是否下载") },
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
            text = { Text("请先在「设置 → 听写和翻译」里选择翻译平台并填写口令") },
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
