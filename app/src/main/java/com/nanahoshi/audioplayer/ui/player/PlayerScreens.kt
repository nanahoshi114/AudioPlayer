package com.nanahoshi.audioplayer.ui.player

import android.app.Application
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nanahoshi.audioplayer.graph
import com.nanahoshi.audioplayer.playback.PlayerUiState
import com.nanahoshi.audioplayer.playback.SubtitleCue
import com.nanahoshi.audioplayer.playback.textAt
import com.nanahoshi.audioplayer.ui.CoverImage
import com.nanahoshi.audioplayer.ui.EmptyHint
import com.nanahoshi.audioplayer.ui.formatDuration
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

class PlayerViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
    val state = graph.player.state
    val queue = graph.library.observeQueue()
    val client = graph.player
    var subtitleCues by mutableStateOf<List<SubtitleCue>>(emptyList())
        private set

    init {
        viewModelScope.launch {
            while (isActive) {
                client.publishNow()
                delay(400)
            }
        }
        viewModelScope.launch {
            client.state.map { it.queueItemId }.distinctUntilChanged().collectLatest { queueItemId ->
                subtitleCues = emptyList()
                if (queueItemId <= 0L) return@collectLatest
                val row = graph.library.queueSnapshot().find { it.queueItemId == queueItemId } ?: return@collectLatest
                val trackId = row.trackId ?: return@collectLatest
                val track = graph.library.track(trackId) ?: return@collectLatest
                val cues = graph.subtitles.cuesFor(track)
                if (client.state.value.queueItemId == queueItemId) subtitleCues = cues
            }
        }
    }
}

@Composable
fun MiniPlayer(onOpen: () -> Unit, viewModel: PlayerViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.hasMedia) return
    Column(Modifier.clickable(onClick = onOpen).fillMaxWidth()) {
        val progress = if (state.durationMs > 0) state.positionMs / state.durationMs.toFloat() else 0f
        LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        ListItem(
            headlineContent = { Text(state.title.ifBlank { "正在播放" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(state.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingContent = { CoverImage(state.coverUri) },
            trailingContent = {
                IconButton(onClick = viewModel.client::toggle) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "暂停" else "播放",
                    )
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(onBack: () -> Unit, viewModel: PlayerViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle(emptyList())
    val remaining by viewModel.client.sleepRemainingMs.collectAsStateWithLifecycle()
    var sleepDialog by remember { mutableStateOf(false) }
    var queueExpanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        viewModel.client.move(from.index, to.index)
    }
    val subtitleScroll = rememberScrollState()
    val subtitle = viewModel.subtitleCues.textAt(state.positionMs)
    LaunchedEffect(subtitle) { subtitleScroll.scrollTo(0) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            if (queueExpanded) {
                TextButton(onClick = { viewModel.client.clearQueue() }) { Text("清空") }
                TextButton(onClick = { queueExpanded = false }) { Text("恢复") }
            }
        }
        if (!state.hasMedia) {
            EmptyHint("当前没有在播的音频。从音频库点一首，或播放一个列表。")
            return
        }
        if (!queueExpanded) {
            CoverImage(state.coverUri, Modifier.size(160.dp).align(Alignment.CenterHorizontally))
            Text(state.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
            Text(state.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().verticalScroll(subtitleScroll),
                    )
                }
            }
            SeekBar(state, onSeek = viewModel.client::seekTo)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                IconButton(onClick = viewModel.client::previous) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "上一首")
                }
                IconButton(onClick = viewModel.client::toggle) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "暂停" else "播放",
                    )
                }
                IconButton(onClick = viewModel.client::next) {
                    Icon(Icons.Default.SkipNext, contentDescription = "下一首")
                }
            }
            SleepTimerRow(
                remainingMs = remaining,
                onOpen = { sleepDialog = true },
                onCancel = viewModel.client::cancelSleepTimer,
            )
            if (sleepDialog) {
                SleepTimerDialog(
                    onDismiss = { sleepDialog = false },
                    onStart = { totalMs ->
                        viewModel.client.startSleepTimer(totalMs)
                        sleepDialog = false
                    },
                )
            }
            TextButton(onClick = { queueExpanded = true }) { Text("播放列表") }
        } else {
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(queue, key = { _, item -> item.queueItemId }) { index, item ->
                ReorderableItem(reorderState, key = item.queueItemId) {
                    val current = item.queueItemId == state.queueItemId
                    ListItem(
                        headlineContent = { Text(item.title) },
                        supportingContent = { Text(item.artist.orEmpty()) },
                        leadingContent = {
                            Icon(Icons.Default.DragHandle, contentDescription = "拖动排序", modifier = Modifier.draggableHandle())
                        },
                        trailingContent = {
                            IconButton(onClick = { viewModel.client.remove(index, item.queueItemId) }) {
                                Icon(Icons.Default.Delete, contentDescription = "从当前列表移除")
                            }
                        },
                        modifier = Modifier.clickable {
                            viewModel.client.playTrack(item.toTrack())
                        },
                        colors = if (current) {
                            androidx.compose.material3.ListItemDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            )
                        } else {
                            androidx.compose.material3.ListItemDefaults.colors()
                        },
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun SleepTimerRow(remainingMs: Long?, onOpen: () -> Unit, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onOpen) { Text("定时关闭") }
        if (remainingMs != null) {
            Text(formatDuration(remainingMs), modifier = Modifier.padding(end = 8.dp))
            TextButton(onClick = onCancel) { Text("取消定时") }
        }
    }
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
                val minuteValue = minutes.toLongOrNull() ?: 0L
                val secondValue = (seconds.toLongOrNull() ?: 0L).coerceIn(0L, 59L)
                val totalMs = minuteValue * 60_000L + secondValue * 1000L
                if (totalMs > 0L) onStart(totalMs)
            }) { Text("开始") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
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
            Text(formatDuration(if (dragging) (dragValue * duration).toLong() else state.positionMs))
            Text(formatDuration(state.durationMs))
        }
    }
}
