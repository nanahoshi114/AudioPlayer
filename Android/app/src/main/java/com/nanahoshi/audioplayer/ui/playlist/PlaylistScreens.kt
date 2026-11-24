package com.nanahoshi.audioplayer.ui.playlist

import com.nanahoshi.audioplayer.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nanahoshi.audioplayer.data.db.FolderEntity
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
    val coverFolders by viewModel.coverFolders.collectAsStateWithLifecycle(null)
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize()) {
        if (playlists.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.playlist_empty_title),
                message = stringResource(R.string.playlist_empty_message),
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                items(playlists, key = { it.id }) { playlist ->
                    PlaylistSummaryRow(playlist, viewModel, coverFolders, onClick = { onOpen(playlist.id) })
                }
            }
        }
        ExtendedFloatingActionButton(
            text = { Text(stringResource(R.string.playlist_new)) },
            icon = { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.playlist_new_title)) },
            onClick = { name = ""; creating = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
    if (creating) {
        NameDialog(
            title = stringResource(R.string.playlist_new_title),
            confirmLabel = stringResource(R.string.playlist_create),
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
    val sources by viewModel.collectionSources.collectAsStateWithLifecycle(null)
    var online by remember(playlistId) { mutableStateOf<Boolean?>(null) }
    androidx.compose.runtime.LaunchedEffect(sources, playlistId) {
        if (online == null && sources != null) online = sources!!.any { it.playlistId == playlistId }
    }
    if (online == null) { com.nanahoshi.audioplayer.ui.components.LoadingState(stringResource(R.string.playlist_loading), Modifier.fillMaxSize()); return }
    if (online == true) {
        com.nanahoshi.audioplayer.ui.bilibili.BiliCollectionScreen(playlistId = playlistId, onBack = onBack)
        return
    }
    val rows by viewModel.playlistTracks(playlistId).collectAsStateWithLifecycle(emptyList())
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
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
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.a11y_playlist_actions))
                }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.playlist_rename)) }, onClick = { menu = false; renaming = true })
                DropdownMenuItem(text = { Text(stringResource(R.string.playlist_delete)) }, onClick = { menu = false; confirmDelete = true })
            }
            },
        )
        Text(
            playlist?.name ?: stringResource(R.string.playlist_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        val playDescription = stringResource(R.string.a11y_playlist_replace_queue)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.playReplacing(playlistId) },
                enabled = rows.isNotEmpty(),
                modifier = Modifier.weight(1f).semantics {
                    contentDescription = playDescription
                },
            ) { Text(stringResource(R.string.playlist_play)) }
            OutlinedButton(onClick = { viewModel.appendToQueue(playlistId) }, enabled = rows.isNotEmpty(), modifier = Modifier.weight(1f)) { Text(stringResource(R.string.playlist_add_queue)) }
        }
        if (rows.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.playlist_detail_empty_title),
                message = stringResource(R.string.playlist_detail_empty_message),
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
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

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.playlist_delete)) },
            text = { Text(stringResource(R.string.playlist_delete_message, playlist?.name.orEmpty())) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete(playlistId)
                    onDeleted()
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    if (renaming) {
        NameDialog(
            title = stringResource(R.string.playlist_rename),
            confirmLabel = stringResource(R.string.playlist_save_name),
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
    folders: Map<Long, FolderEntity>?,
    onClick: () -> Unit,
) {
    val sources by viewModel.collectionSources.collectAsStateWithLifecycle(emptyList())
    val source = sources.firstOrNull { it.playlistId == playlist.id }?.let(viewModel::collection)
    if (source != null) {
        OnlinePlaylistSummary(playlist.name, source, onClick)
        return
    }
    val rows by viewModel.playlistTracks(playlist.id).collectAsStateWithLifecycle(emptyList())
    val covers = remember(rows, folders) {
        if (folders == null) emptyList() else playlistCoverUris(rows, folders)
    }
    ListItem(
        headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(stringResource(R.string.playlist_track_count, rows.size), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            Box(Modifier.size(48.dp)) {
                covers.forEachIndexed { index, coverUri ->
                    CoverImage(
                        coverUri,
                        modifier = Modifier.offset(x = (index * 8).dp),
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
        headlineContent = { Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(row.artist?.ifBlank { null } ?: formatDuration(row.durationMs), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DragHandle, contentDescription = stringResource(R.string.a11y_drag_sort), modifier = Modifier.size(48.dp).then(handleModifier).padding(12.dp))
                CoverImage(row.coverUri, size = 44.dp, shape = MaterialTheme.shapes.small)
            }
        },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.a11y_playlist_remove))
            }
        },
    )
}

@Composable
private fun NameDialog(
    title: String,
    confirmLabel: String,
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
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
internal fun OnlinePlaylistSummary(name: String, source: com.nanahoshi.audioplayer.bilibili.BiliCollection, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(stringResource(R.string.playlist_online_summary, stringResource(source.kind.labelRes), source.total), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { CoverImage(source.coverUrl) }, modifier = Modifier.clickable(onClick = onClick))
}
