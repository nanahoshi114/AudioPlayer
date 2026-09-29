package com.nanahoshi.audioplayer.ui.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nanahoshi.audioplayer.data.FolderChoice
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.ui.CoverImage
import com.nanahoshi.audioplayer.ui.EmptyHint
import com.nanahoshi.audioplayer.ui.formatDuration

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    onOpenFavorites: (Long?) -> Unit,
    viewModel: LibraryViewModel = viewModel(),
) {
    val tracks by viewModel.tracks.collectAsStateWithLifecycle(emptyList())
    val folders by viewModel.folders.collectAsStateWithLifecycle(emptyList())
    val currentFolder by viewModel.currentFolder.collectAsStateWithLifecycle(null)
    val folderId by viewModel.currentFolderId.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val pending by viewModel.pendingBili.collectAsStateWithLifecycle()
    val selectedTracks by viewModel.selectedTrackIds.collectAsStateWithLifecycle()
    val selectedFolders by viewModel.selectedFolderIds.collectAsStateWithLifecycle()
    val selecting = selectedTracks.isNotEmpty() || selectedFolders.isNotEmpty()
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var bvDialog by remember { mutableStateOf<Boolean?>(null) }
    var bvText by remember { mutableStateOf("") }
    var pendingPlay by remember { mutableStateOf(false) }
    var pendingTree by remember { mutableStateOf<Uri?>(null) }
    var namingFolder by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var moveChoices by remember { mutableStateOf<List<FolderChoice>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmFolderDelete by remember { mutableStateOf<Long?>(null) }

    BackHandler(enabled = folderId != null && !selecting) { viewModel.up() }
    BackHandler(enabled = selecting) { viewModel.clearSelection() }

    val openFile = rememberLauncherForActivityResult(OpenPersistableDocument()) { uri ->
        if (uri != null) {
            persistRead(context, uri)
            viewModel.importFile(uri, play = pendingPlay)
        }
    }
    val openFolder = rememberLauncherForActivityResult(OpenPersistableTree()) { uri ->
        if (uri != null) {
            persistRead(context, uri)
            pendingTree = uri
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (selecting) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = viewModel::clearSelection) { Text("取消") }
                Text("已选 ${selectedTracks.size + selectedFolders.size}", modifier = Modifier.padding(end = 8.dp))
                TextButton(onClick = viewModel::playNextSelection) { Text("下一个播放") }
                TextButton(onClick = viewModel::appendSelection) { Text("加到末尾") }
                TextButton(onClick = { viewModel.loadMoveChoices { moveChoices = it } }) { Text("移动到") }
                TextButton(onClick = { confirmDelete = true }) { Text("移除") }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.padding(end = 8.dp).size(20.dp))
                }
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.Add, contentDescription = "导入")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("新建文件夹") },
                        onClick = {
                            menuOpen = false
                            folderName = ""
                            namingFolder = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("选择文件并播放") },
                        onClick = {
                            menuOpen = false
                            pendingPlay = true
                            openFile.launch(arrayOf("audio/*"))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("选择文件导入") },
                        onClick = {
                            menuOpen = false
                            pendingPlay = false
                            openFile.launch(arrayOf("audio/*"))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("选择文件夹导入") },
                        onClick = {
                            menuOpen = false
                            openFolder.launch(null)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("输入 BV 并播放") },
                        onClick = {
                            menuOpen = false
                            bvText = ""
                            bvDialog = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("输入 BV 仅导入") },
                        onClick = {
                            menuOpen = false
                            bvText = ""
                            bvDialog = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("从收藏夹导入") },
                        onClick = {
                            menuOpen = false
                            onOpenFavorites(folderId)
                        },
                    )
                }
            }
        }
        if (folders.isEmpty() && tracks.isEmpty()) {
            EmptyHint(
                if (currentFolder == null) {
                    "还没有音频。点右上角导入本地文件、文件夹，或输入 BV 号。"
                } else {
                    "这个文件夹是空的。"
                },
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                if (currentFolder != null) {
                    item(key = "up") {
                        ListItem(
                            headlineContent = { Text(currentFolder?.name ?: "返回") },
                            leadingContent = {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回上一级")
                            },
                            modifier = Modifier.clickable(onClick = viewModel::up),
                        )
                    }
                }
                items(folders, key = { "folder-${it.id}" }) { folder ->
                    FolderRow(
                        folder = folder,
                        selected = folder.id in selectedFolders,
                        selecting = selecting,
                        onClick = {
                            if (selecting) viewModel.toggleFolder(folder.id) else viewModel.openFolder(folder)
                        },
                        onLongClick = { viewModel.beginSelection(folderId = folder.id) },
                        onMove = {
                            viewModel.beginSelection(folderId = folder.id)
                            viewModel.loadMoveChoices { moveChoices = it }
                        },
                        onDelete = { confirmFolderDelete = folder.id },
                    )
                }
                items(tracks, key = { "track-${it.id}" }) { track ->
                    TrackRow(
                        track = track,
                        selected = track.id in selectedTracks,
                        selecting = selecting,
                        onClick = {
                            if (selecting) viewModel.toggleTrack(track.id) else viewModel.play(track)
                        },
                        onLongClick = { viewModel.beginSelection(trackId = track.id) },
                        onAppend = { viewModel.append(track) },
                        onPlayNext = { viewModel.playNext(track) },
                        onMove = {
                            viewModel.beginSelection(trackId = track.id)
                            viewModel.loadMoveChoices { moveChoices = it }
                        },
                        onDelete = { viewModel.deleteTrack(track.id) },
                    )
                }
            }
        }
    }

    if (bvDialog != null) {
        AlertDialog(
            onDismissRequest = { bvDialog = null },
            title = { Text(if (bvDialog == true) "播放 B 站视频的声音" else "导入 B 站视频") },
            text = {
                OutlinedTextField(
                    value = bvText,
                    onValueChange = { bvText = it },
                    label = { Text("BV 号或视频链接") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val play = bvDialog == true
                    bvDialog = null
                    viewModel.lookupBvid(bvText, play)
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { bvDialog = null }) { Text("取消") } },
        )
    }

    pendingTree?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingTree = null },
            title = { Text("导入文件夹") },
            text = { Text("可以保留里面的文件夹，也可以只把音频放进当前目录。图片和其他文件会跳过。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingTree = null
                    viewModel.importFolder(uri, keepStructure = true)
                }) { Text("保留文件夹结构") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingTree = null
                    viewModel.importFolder(uri, keepStructure = false)
                }) { Text("只导入音频") }
            },
        )
    }

    if (namingFolder) {
        AlertDialog(
            onDismissRequest = { namingFolder = false },
            title = { Text("新建文件夹") },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    namingFolder = false
                    viewModel.createFolder(folderName)
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { namingFolder = false }) { Text("取消") } },
        )
    }

    moveChoices?.let { choices ->
        AlertDialog(
            onDismissRequest = { moveChoices = null },
            title = { Text("移动到") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(choices, key = { it.id ?: -1L }) { choice ->
                        Text(
                            choice.label,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    moveChoices = null
                                    viewModel.moveSelection(choice.id)
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { moveChoices = null }) { Text("取消") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("从音频库移除") },
            text = { Text("移除选中的内容？手机上的原文件会保留。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteSelection()
                }) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }

    confirmFolderDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmFolderDelete = null },
            title = { Text("从音频库移除") },
            text = { Text("这个文件夹和里面的内容会从音频库移除。手机上的原文件会保留。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmFolderDelete = null
                    viewModel.deleteFolder(id)
                }) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { confirmFolderDelete = null }) { Text("取消") } },
        )
    }

    pending?.let { current ->
        AlertDialog(
            onDismissRequest = viewModel::dismissBili,
            title = { Text("选择要导入的分段") },
            text = {
                LazyColumn {
                    items(current.parts.size) { index ->
                        val part = current.parts[index]
                        Row(
                            Modifier.fillMaxWidth().clickable { viewModel.togglePart(index) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = index in current.selected, onCheckedChange = { viewModel.togglePart(index) })
                            Text(part.title, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::confirmBili) { Text("确定") } },
            dismissButton = { TextButton(onClick = viewModel::dismissBili) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRow(
    folder: FolderEntity,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(folder.name) },
        supportingContent = { Text("文件夹") },
        leadingContent = {
            if (selecting) {
                Checkbox(checked = selected, onCheckedChange = { onClick() })
            } else {
                Icon(Icons.Default.Folder, contentDescription = null)
            }
        },
        trailingContent = {
            if (!selecting) {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("移动到…") }, onClick = { menu = false; onMove() })
                    DropdownMenuItem(text = { Text("从音频库移除") }, onClick = { menu = false; onDelete() })
                }
            }
        },
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = if (selected) {
            androidx.compose.material3.ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            )
        } else {
            androidx.compose.material3.ListItemDefaults.colors()
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(
    track: TrackEntity,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onAppend: () -> Unit,
    onPlayNext: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(track.title) },
        supportingContent = {
            val source = if (track.source == TrackSource.BILIBILI) "B站" else "本地"
            val artist = track.artist?.takeIf { it.isNotBlank() } ?: source
            Text("$artist · $source · ${formatDuration(track.durationMs)}")
        },
        leadingContent = {
            if (selecting) {
                Checkbox(checked = selected, onCheckedChange = { onClick() })
            } else {
                CoverImage(track.coverUri)
            }
        },
        trailingContent = {
            if (!selecting) {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("下一个播放") }, onClick = { menu = false; onPlayNext() })
                    DropdownMenuItem(text = { Text("加入当前列表末尾") }, onClick = { menu = false; onAppend() })
                    DropdownMenuItem(text = { Text("移动到…") }, onClick = { menu = false; onMove() })
                    DropdownMenuItem(text = { Text("从音频库移除") }, onClick = { menu = false; onDelete() })
                }
            }
        },
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = if (selected) {
            androidx.compose.material3.ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            )
        } else {
            androidx.compose.material3.ListItemDefaults.colors()
        },
    )
}

private fun persistRead(context: Context, uri: Uri) {
    try {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (_: SecurityException) {
        // The picker grant is still valid for this session.
    }
}

private class OpenPersistableDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
}

private class OpenPersistableTree : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent =
        super.createIntent(context, input).addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
}
