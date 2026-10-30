package com.nanahoshi.audioplayer.ui.playlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.nanahoshi.audioplayer.ui.components.AppTopBar
import com.nanahoshi.audioplayer.ui.components.EmptyState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nanahoshi.audioplayer.data.db.PlaylistEntity
import com.nanahoshi.audioplayer.data.db.PlaylistTrack
import com.nanahoshi.audioplayer.ui.CoverImage
import com.nanahoshi.audioplayer.ui.formatDuration
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun PlaylistListScreen(
    onOpen: (Long) -> Unit,
    viewModel: PlaylistViewModel = viewModel(),
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle(emptyList())
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize()) {
        if (playlists.isEmpty()) {
            EmptyState(
                title = "还没有播放列表",
                message = "点右下角新建。在音频库里多选曲目，再加入播放列表。",
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(playlists, key = { it.id }) { playlist ->
                    PlaylistSummaryRow(playlist, viewModel, onClick = { onOpen(playlist.id) })
                }
            }
        }
        ExtendedFloatingActionButton(
            text = { Text("新建") },
            icon = { Icon(Icons.Default.Add, contentDescription = "新建播放列表") },
            onClick = { name = ""; creating = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
    if (creating) {
        NameDialog(
            title = "新建播放列表",
            initial = name,
            onDismiss = { creating = false },
            onConfirm = {
                viewModel.create(it)
                creating = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    viewModel: PlaylistViewModel = viewModel(),
) {
    val playlist by viewModel.playlist(playlistId).collectAsStateWithLifecycle(null)
    val rows by viewModel.playlistTracks(playlistId).collectAsStateWithLifecycle(emptyList())
    var renaming by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        viewModel.move(playlistId, from.index, to.index)
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            title = "",
            onBack = onBack,
            actions = {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多")
                }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("改名") }, onClick = { menu = false; renaming = true })
                DropdownMenuItem(
                    text = { Text("删除列表") },
                    onClick = {
                        menu = false
                        viewModel.delete(playlistId)
                        onDeleted()
                    },
                )
            }
            },
        )
        Text(
            playlist?.name ?: "播放列表",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.playReplacing(playlistId) }, modifier = Modifier.weight(1f)) { Text("播放") }
            OutlinedButton(onClick = { viewModel.appendToQueue(playlistId) }, modifier = Modifier.weight(1f)) { Text("加到末尾") }
        }
        if (rows.isEmpty()) {
            EmptyState(title = "这个列表还是空的", message = "在音频库里多选曲目，再加入播放列表。", modifier = Modifier.fillMaxSize())
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(rows, key = { _, row -> row.itemId }) { index, row ->
                    ReorderableItem(reorderState, key = row.itemId) {
                        PlaylistTrackRow(
                            row = row,
                            handleModifier = Modifier.draggableHandle(),
                            onClick = { viewModel.playFrom(playlistId, index) },
                            onDelete = { viewModel.removeItem(playlistId, row.itemId) },
                        )
                    }
                }
            }
        }
    }
    }

    if (renaming) {
        NameDialog(
            title = "改名",
            initial = playlist?.name.orEmpty(),
            onDismiss = { renaming = false },
            onConfirm = {
                viewModel.rename(playlistId, it)
                renaming = false
            },
        )
    }
}

@Composable
private fun PlaylistSummaryRow(
    playlist: PlaylistEntity,
    viewModel: PlaylistViewModel,
    onClick: () -> Unit,
) {
    val rows by viewModel.playlistTracks(playlist.id).collectAsStateWithLifecycle(emptyList())
    ListItem(
        headlineContent = { Text(playlist.name) },
        supportingContent = { Text("${rows.size} 首") },
        leadingContent = {
            Box(Modifier.size(48.dp)) {
                rows.take(3).forEachIndexed { index, row ->
                    CoverImage(
                        row.coverUri,
                        modifier = Modifier.offset(x = (index * 10).dp),
                        size = 32.dp,
                    )
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun PlaylistTrackRow(
    row: PlaylistTrack,
    handleModifier: Modifier,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(row.title) },
        supportingContent = { Text(row.artist?.ifBlank { null } ?: formatDuration(row.durationMs)) },
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Icon(Icons.Default.DragHandle, contentDescription = "拖动排序", modifier = handleModifier)
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoverImage(row.coverUri)
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "移除")
                }
            }
        },
    )
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
