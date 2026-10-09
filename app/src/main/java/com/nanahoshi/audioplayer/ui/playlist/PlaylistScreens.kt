package com.nanahoshi.audioplayer.ui.playlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.nanahoshi.audioplayer.data.db.PlaylistTrack
import com.nanahoshi.audioplayer.ui.CoverImage
import com.nanahoshi.audioplayer.ui.EmptyHint
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
            Column(Modifier.padding(16.dp)) {
                EmptyHint("还没有播放列表。点右下角新建，再从音频库加入曲目。")
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(playlists, key = { it.id }) { playlist ->
                    ListItem(
                        headlineContent = { Text(playlist.name) },
                        modifier = Modifier.clickable { onOpen(playlist.id) },
                    )
                }
            }
        }
        FloatingActionButton(
            onClick = { name = ""; creating = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = "新建播放列表")
        }
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    viewModel: PlaylistViewModel = viewModel(),
) {
    val playlist by viewModel.playlist(playlistId).collectAsStateWithLifecycle(null)
    val rows by viewModel.playlistTracks(playlistId).collectAsStateWithLifecycle(emptyList())
    val pickerFolders by viewModel.pickerFolders.collectAsStateWithLifecycle(emptyList())
    val pickerTracks by viewModel.pickerTracks.collectAsStateWithLifecycle(emptyList())
    val pickerFolder by viewModel.pickerFolder.collectAsStateWithLifecycle(null)
    var renaming by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(listOf<Long>()) }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        viewModel.move(playlistId, from.index, to.index)
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(playlist?.name ?: "播放列表", modifier = Modifier.weight(1f))
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
        }
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            TextButton(onClick = { viewModel.playReplacing(playlistId) }) { Text("播放") }
            TextButton(onClick = { viewModel.appendToQueue(playlistId) }) { Text("加到当前列表末尾") }
            TextButton(onClick = {
                selected = emptyList()
                viewModel.resetPicker()
                adding = true
            }) { Text("加入音频") }
        }
        if (rows.isEmpty()) {
            EmptyHint("这个列表还是空的。")
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
    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("加入音频") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    if (pickerFolder != null) {
                        item(key = "up") {
                            ListItem(
                                headlineContent = { Text(pickerFolder?.name ?: "返回") },
                                leadingContent = {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回上一级")
                                },
                                modifier = Modifier.clickable { viewModel.upPicker() },
                            )
                        }
                    }
                    items(pickerFolders, key = { "folder-${it.id}" }) { folder ->
                        val work = folder.asmrSourceId != null
                        ListItem(
                            headlineContent = { Text(folder.name) },
                            supportingContent = { Text(if (work) "作品" else "文件夹") },
                            leadingContent = {
                                if (work) CoverImage(folder.coverUri) else Icon(Icons.Default.Folder, contentDescription = null)
                            },
                            modifier = Modifier.clickable { viewModel.openPickerFolder(folder.id) },
                        )
                    }
                    if (pickerFolders.isEmpty() && pickerTracks.isEmpty()) {
                        item(key = "empty") { Text(if (pickerFolder == null) "音频库是空的" else "这个文件夹是空的") }
                    }
                    items(pickerTracks, key = { "track-${it.id}" }) { track ->
                        val checked = track.id in selected
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                selected = if (checked) selected - track.id else selected + track.id
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = {
                                selected = if (it) selected + track.id else selected - track.id
                            })
                            Text(track.title, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.addTracks(playlistId, selected.toList())
                    adding = false
                }) { Text("加入") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("取消") } },
        )
    }
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
