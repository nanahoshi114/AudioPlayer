package com.nanahoshi.audioplayer.ui.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.key
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nanahoshi.audioplayer.asmr.WorkFacts
import com.nanahoshi.audioplayer.data.LibrarySort
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.FileKind
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.LibraryFileEntity
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.ui.CoverImage
import com.nanahoshi.audioplayer.ui.CollapsingTop
import com.nanahoshi.audioplayer.ui.EmptyHint
import com.nanahoshi.audioplayer.ui.asmr.WorkFactsBlock
import com.nanahoshi.audioplayer.ui.formatDuration

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    onOpenPreview: (Long) -> Unit,
    onOpenWork: (String) -> Unit,
    viewModel: LibraryViewModel = viewModel(),
) {
    val listing by viewModel.listing.collectAsStateWithLifecycle(LibraryListing(emptyList(), emptyList()))
    val folders = listing.folders
    val entries = listing.entries
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val sourceFilter by viewModel.sourceFilter.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val pendingAsmr by viewModel.pendingAsmr.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle(emptyList())
    val currentFolder by viewModel.currentFolder.collectAsStateWithLifecycle(null)
    val folderId by viewModel.currentFolderId.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val pending by viewModel.pendingBili.collectAsStateWithLifecycle()
    val selectedTracks by viewModel.selectedTrackIds.collectAsStateWithLifecycle()
    val selectedFolders by viewModel.selectedFolderIds.collectAsStateWithLifecycle()
    val selecting = selectedTracks.isNotEmpty() || selectedFolders.isNotEmpty()
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var bvDialog by remember { mutableStateOf(false) }
    var bvText by remember { mutableStateOf("") }
    var rjDialog by remember { mutableStateOf(false) }
    var rjText by remember { mutableStateOf("") }
    var pickingPlaylist by remember { mutableStateOf(false) }
    var pendingTree by remember { mutableStateOf<Uri?>(null) }
    var namingFolder by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmFolderDelete by remember { mutableStateOf<Long?>(null) }

    BackHandler(enabled = folderId != null && !selecting) { viewModel.up() }
    BackHandler(enabled = selecting) { viewModel.clearSelection() }

    val openFile = rememberLauncherForActivityResult(OpenPersistableDocument()) { uri ->
        if (uri != null) {
            persistRead(context, uri)
            viewModel.importFile(uri, play = false)
        }
    }
    val openFolder = rememberLauncherForActivityResult(OpenPersistableTree()) { uri ->
        if (uri != null) {
            persistRead(context, uri)
            pendingTree = uri
        }
    }

    CollapsingTop(
        header = {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = viewModel::updateSearch,
                placeholder = { Text("搜索名称或 RJ 号") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(
                    LibrarySourceFilter.ALL to "全部",
                    LibrarySourceFilter.BILIBILI to "B站",
                    LibrarySourceFilter.ASMR to "asmr.one",
                    LibrarySourceFilter.LOCAL to "本地",
                ).forEach { (filter, label) ->
                    FilterChip(
                        selected = sourceFilter == filter && searchQuery.isBlank(),
                        onClick = { viewModel.setSourceFilter(filter) },
                        label = { Text(label) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
        },
        pinned = {
        if (selecting) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = viewModel::clearSelection) { Text("取消") }
                Text("已选 ${selectedTracks.size + selectedFolders.size}", modifier = Modifier.padding(end = 8.dp))
                TextButton(onClick = viewModel::playNextSelection) { Text("下一个播放") }
                TextButton(onClick = viewModel::appendSelection) { Text("加到末尾") }
                TextButton(onClick = viewModel::downloadSelection) { Text("下载") }
                TextButton(onClick = {
                    if (playlists.isEmpty()) {
                        viewModel.note("请先新建播放列表")
                    } else {
                        pickingPlaylist = true
                    }
                }) { Text("加入播放列表") }
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
                TextButton(onClick = viewModel::toggleSort) {
                    Text(if (sort == LibrarySort.NAME) "按名称" else "按时间")
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
                        text = { Text("选择文件导入") },
                        onClick = {
                            menuOpen = false
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
                        text = { Text("输入BV号导入") },
                        onClick = {
                            menuOpen = false
                            bvText = ""
                            bvDialog = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("输入 RJ 号导入") },
                        onClick = {
                            menuOpen = false
                            rjText = ""
                            rjDialog = true
                        },
                    )
                }
            }
        }
        },
    ) { topInset ->
        val openedWork = currentFolder?.takeIf { listing.browsing && !it.asmrSourceId.isNullOrBlank() }
        if (folders.isEmpty() && entries.isEmpty() && openedWork == null) {
            Box(topInset.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                EmptyHint(
                    when {
                        !listing.browsing -> "没有符合的内容。"
                        currentFolder == null -> "还没有音频。点右上角导入本地文件、文件夹、BV 号或 RJ 号。"
                        else -> "这个文件夹是空的。"
                    },
                )
            }
        } else {
            val listState = viewModel.listState(folderId, listing.browsing)
            key(if (listing.browsing) "folder:${folderId ?: "root"}" else "flat") {
            LazyColumn(topInset.fillMaxSize(), state = listState) {
                if (listing.browsing && currentFolder != null) {
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
                val workFolder = currentFolder?.takeIf { !it.asmrSourceId.isNullOrBlank() }
                if (workFolder != null) {
                    item(key = "work-facts") {
                        val facts = WorkFacts.fromJson(workFolder.workMeta) ?: WorkFacts(
                            title = workFolder.name,
                            circle = "",
                            vas = emptyList(),
                            tags = emptyList(),
                            release = "",
                            sourceId = workFolder.asmrSourceId.orEmpty(),
                        )
                        WorkFactsBlock(
                            facts = facts,
                            coverUrl = workFolder.coverUri,
                            singleLine = false,
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Button(onClick = { onOpenWork(workFolder.asmrSourceId.orEmpty()) }) {
                                Text("详情页")
                            }
                        }
                    }
                }
                if (folders.isEmpty() && entries.isEmpty()) {
                    item(key = "empty-work") {
                        Box(Modifier.fillMaxWidth().padding(24.dp)) {
                            EmptyHint(if (busy) "正在读取文件…" else "这个文件夹是空的。")
                        }
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
                        onCache = if (folder.asmrWorkId != null) {
                            { viewModel.cacheFolder(folder.id) }
                        } else {
                            null
                        },
                        onDelete = { confirmFolderDelete = folder.id },
                    )
                }
                items(entries, key = { entry ->
                    when (entry) {
                        is LibraryEntry.Audio -> "track-${entry.track.id}"
                        is LibraryEntry.File -> "file-${entry.file.id}"
                    }
                }) { entry ->
                    when (entry) {
                        is LibraryEntry.Audio -> {
                            val track = entry.track
                            TrackRow(
                                track = track,
                                hasSubtitle = entry.hasSubtitle,
                                selected = track.id in selectedTracks,
                                selecting = selecting,
                                onClick = {
                                    if (selecting) viewModel.toggleTrack(track.id) else viewModel.play(track)
                                },
                                onLongClick = { viewModel.beginSelection(trackId = track.id) },
                                onAppend = { viewModel.append(track) },
                                onPlayNext = { viewModel.playNext(track) },
                                onDownload = if (track.source == TrackSource.ASMR || track.source == TrackSource.DLSITE) {
                                    { viewModel.downloadTrack(track) }
                                } else {
                                    null
                                },
                                onDelete = { viewModel.deleteTrack(track.id) },
                            )
                        }
                        is LibraryEntry.File -> FileRow(
                            file = entry.file,
                            onClick = { onOpenPreview(entry.file.id) },
                            onDelete = { viewModel.deleteFile(entry.file.id) },
                        )
                    }
                }
            }
            }
        }
    }

    pendingAsmr?.let { preview ->
        AlertDialog(
            onDismissRequest = viewModel::dismissAsmrPreview,
            title = { Text("asmr.one查询结果") },
            text = {
                Column {
                    CoverImage(preview.coverUrl)
                    Text(preview.title, modifier = Modifier.padding(top = 8.dp))
                    Text("要加入音频库吗？", modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = viewModel::confirmAsmrPreview) { Text("确认") } },
            dismissButton = { TextButton(onClick = viewModel::dismissAsmrPreview) { Text("取消") } },
        )
    }

    if (rjDialog) {
        AlertDialog(
            onDismissRequest = { rjDialog = false },
            title = { Text("导入 asmr.one 作品") },
            text = {
                OutlinedTextField(
                    value = rjText,
                    onValueChange = { rjText = it },
                    label = { Text("RJ 号") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    rjDialog = false
                    viewModel.importRj(rjText)
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { rjDialog = false }) { Text("取消") } },
        )
    }

    if (pickingPlaylist) {
        AlertDialog(
            onDismissRequest = { pickingPlaylist = false },
            title = { Text("加入播放列表") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(playlists, key = { it.id }) { playlist ->
                        Text(
                            playlist.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    pickingPlaylist = false
                                    viewModel.addSelectionToPlaylist(playlist.id)
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pickingPlaylist = false }) { Text("取消") } },
        )
    }

    if (bvDialog) {
        AlertDialog(
            onDismissRequest = { bvDialog = false },
            title = { Text("导入 B 站视频") },
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
                    bvDialog = false
                    viewModel.lookupBvid(bvText, play = false)
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { bvDialog = false }) { Text("取消") } },
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

@Composable
private fun ItemName(name: String, expanded: Boolean) {
    Text(
        name,
        maxLines = if (expanded) Int.MAX_VALUE else 1,
        overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRow(
    folder: FolderEntity,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCache: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val work = folder.asmrSourceId != null
    if (work) {
        WorkPreviewRow(
            folder = folder,
            selected = selected,
            selecting = selecting,
            menu = menu,
            onMenu = { menu = it },
            onClick = onClick,
            onLongClick = onLongClick,
            onCache = onCache,
            onDelete = onDelete,
        )
        return
    }
    ListItem(
        headlineContent = { ItemName(folder.name, selected) },
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
                    if (onCache != null) {
                        DropdownMenuItem(text = { Text("缓存到本地") }, onClick = { menu = false; onCache() })
                    }
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
private fun WorkPreviewRow(
    folder: FolderEntity,
    selected: Boolean,
    selecting: Boolean,
    menu: Boolean,
    onMenu: (Boolean) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCache: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    val facts = WorkFacts.fromJson(folder.workMeta) ?: WorkFacts(
        title = folder.name,
        circle = "",
        vas = emptyList(),
        tags = emptyList(),
        release = "",
        sourceId = folder.asmrSourceId.orEmpty(),
    )
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (selected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = if (selected) Alignment.Top else Alignment.CenterVertically,
    ) {
        if (selecting) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onClick() },
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        WorkFactsBlock(
            facts = facts,
            coverUrl = folder.coverUri,
            singleLine = true,
            expandTitle = selected,
            showCover = !selecting,
            modifier = Modifier.weight(1f),
        )
        if (!selecting) {
            Box {
                IconButton(onClick = { onMenu(true) }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { onMenu(false) }) {
                    if (onCache != null) {
                        DropdownMenuItem(text = { Text("缓存到本地") }, onClick = { onMenu(false); onCache() })
                    }
                    DropdownMenuItem(text = { Text("从音频库移除") }, onClick = { onMenu(false); onDelete() })
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(
    track: TrackEntity,
    hasSubtitle: Boolean,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onAppend: () -> Unit,
    onPlayNext: () -> Unit,
    onDownload: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { ItemName(track.title, selected) },
        supportingContent = {
            val source = when (track.source) {
                TrackSource.BILIBILI -> "B站"
                TrackSource.ASMR -> "asmr.one"
                TrackSource.DLSITE -> "DLsite"
                TrackSource.LOCAL -> "本地"
            }
            val artist = track.artist?.takeIf { it.isNotBlank() } ?: source
            val downloaded = (track.source == TrackSource.ASMR || track.source == TrackSource.DLSITE) &&
                track.localUri?.startsWith("/") == true
            val mark = if (downloaded) " · 已下载" else ""
            val subtitle = if (hasSubtitle) " · 有字幕" else ""
            Text(
                "$artist · $source · ${formatDuration(track.durationMs)}$mark$subtitle",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
                    if (onDownload != null) {
                        DropdownMenuItem(text = { Text("下载") }, onClick = { menu = false; onDownload() })
                    }
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

@Composable
private fun FileRow(
    file: LibraryFileEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val icon = when (file.kind) {
        FileKind.IMAGE -> Icons.Default.Image
        FileKind.TEXT -> Icons.Default.Description
        FileKind.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
    val label = when (file.kind) {
        FileKind.IMAGE -> "图片"
        FileKind.TEXT -> "文本"
        FileKind.OTHER -> "文件"
    }
    ListItem(
        headlineContent = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(label) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "更多")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("从音频库移除") }, onClick = { menu = false; onDelete() })
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
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
