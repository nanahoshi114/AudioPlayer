package com.nanahoshi.audioplayer.ui.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.key
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.nanahoshi.audioplayer.ui.components.EmptyState
import com.nanahoshi.audioplayer.ui.components.LabelBadge
import com.nanahoshi.audioplayer.ui.components.LoadingState
import com.nanahoshi.audioplayer.ui.components.LocalTopBarOverride
import com.nanahoshi.audioplayer.ui.components.SelectionAction
import com.nanahoshi.audioplayer.ui.components.SelectionTopBar
import com.nanahoshi.audioplayer.ui.components.SourceBadge
import com.nanahoshi.audioplayer.ui.theme.LocalReduceMotion
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
import com.nanahoshi.audioplayer.ui.asmr.WorkFactsBlock
import com.nanahoshi.audioplayer.ui.formatDuration

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    onOpenPreview: (Long) -> Unit,
    onOpenLibraryWork: (Long) -> Unit,
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
    val pending by viewModel.pendingBili.collectAsStateWithLifecycle()
    val cachePrompt by viewModel.cachePrompt.collectAsStateWithLifecycle()
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
    var recognizeFolder by remember { mutableStateOf<Long?>(null) }
    var recognizeCode by remember { mutableStateOf("") }

    val listState = viewModel.listState(folderId, listing.browsing)
    val fabExpanded by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 48 }
    }
    var trail by remember { mutableStateOf(listOf<LibraryCrumb>()) }
    val climb = rememberLibraryClimb(
        currentFolderId = { viewModel.currentFolderId.value },
        up = viewModel::up,
    )
    val topBar = LocalTopBarOverride.current
    DisposableEffect(Unit) {
        onDispose { topBar?.value = null }
    }
    if (selecting) {
        val selectedCount = selectedTracks.size + selectedFolders.size
        // 选中了作品时，播放和加入列表的键都收起来，作品要进作品页再操作。
        val selectsWork = folders.any { it.id in selectedFolders && !it.asmrSourceId.isNullOrBlank() }
        SideEffect {
            topBar?.value = {
                SelectionTopBar(
                    count = selectedCount,
                    onClose = viewModel::clearSelection,
                    actions = buildList {
                        if (!selectsWork) {
                            add(SelectionAction("下一个播放", Icons.Default.SkipNext, viewModel::playNextSelection))
                            add(SelectionAction("加到末尾", Icons.AutoMirrored.Filled.PlaylistAdd, viewModel::appendSelection))
                            add(SelectionAction("下载", Icons.Default.Download, viewModel::downloadSelection))
                            add(
                                SelectionAction("加入播放列表", Icons.AutoMirrored.Filled.QueueMusic, {
                                    if (playlists.isEmpty()) viewModel.note("请先新建播放列表") else pickingPlaylist = true
                                }),
                            )
                        }
                        add(SelectionAction("删除已下载的文件", Icons.Default.Delete, viewModel::deleteDownloadedSelection, destructive = true))
                        add(SelectionAction("移除", Icons.Default.Delete, { confirmDelete = true }, destructive = true))
                    },
                )
            }
        }
    } else {
        SideEffect { topBar?.value = null }
    }
    androidx.compose.runtime.LaunchedEffect(currentFolder?.id, currentFolder?.name) {
        trail = libraryTrail(trail, currentFolder)
    }

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

    Box(Modifier.fillMaxSize()) {
    CollapsingTop(
        header = {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = viewModel::updateSearch,
                placeholder = { Text("搜索名称或 RJ 号") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
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
                var sortMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { sortMenu = true }) {
                        Icon(
                            Icons.AutoMirrored.Filled.Sort,
                            contentDescription = if (sort == LibrarySort.NAME) "按名称" else "按时间",
                        )
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("按名称") },
                            onClick = {
                                sortMenu = false
                                if (sort != LibrarySort.NAME) viewModel.toggleSort()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("按时间") },
                            onClick = {
                                sortMenu = false
                                if (sort == LibrarySort.NAME) viewModel.toggleSort()
                            },
                        )
                    }
                }
            }
        },
    ) { topInset ->
        if (folders.isEmpty() && entries.isEmpty()) {
            Box(topInset.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                when {
                    !listing.browsing -> EmptyState(title = "没有符合的内容")
                    currentFolder == null -> EmptyState(
                        title = "还没有音频",
                        message = "点右下角导入本地文件、文件夹、BV 号或 RJ 号。",
                    )
                    else -> EmptyState(title = "这个文件夹是空的")
                }
            }
        } else {
            key(if (listing.browsing) "folder:${folderId ?: "root"}" else "flat") {
            LazyColumn(
                topInset.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                if (listing.browsing && trail.isNotEmpty()) {
                    item(key = "crumbs") {
                        LibraryBreadcrumb(
                            trail = trail,
                            onRoot = { climb(trail.size) },
                            onCrumb = { index -> climb(trail.lastIndex - index) },
                        )
                    }
                }
                items(folders, key = { "folder-${it.id}" }) { folder ->
                    FolderRow(
                        modifier = libraryMotionModifier(),
                        folder = folder,
                        selected = folder.id in selectedFolders,
                        selecting = selecting,
                        onClick = {
                            when {
                                selecting -> viewModel.toggleFolder(folder.id)
                                !folder.asmrSourceId.isNullOrBlank() -> onOpenLibraryWork(folder.id)
                                else -> viewModel.openFolder(folder)
                            }
                        },
                        onLongClick = { viewModel.beginSelection(folderId = folder.id) },
                        onCache = if (folder.asmrWorkId != null) {
                            { viewModel.cacheFolder(folder.id) }
                        } else {
                            null
                        },
                        onRecognize = if (folder.asmrSourceId == null && folder.asmrWorkId == null) {
                            {
                                recognizeCode = rjHint(folder.name)
                                recognizeFolder = folder.id
                            }
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
                                modifier = libraryMotionModifier(),
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
                                onDownload = if (
                                    track.source == TrackSource.ASMR ||
                                    track.source == TrackSource.DLSITE ||
                                    track.source == TrackSource.BILIBILI
                                ) {
                                    { viewModel.downloadTrack(track) }
                                } else {
                                    null
                                },
                                onDeleteDownloaded = if (
                                    track.source == TrackSource.ASMR ||
                                    track.source == TrackSource.DLSITE ||
                                    track.source == TrackSource.BILIBILI
                                ) {
                                    { viewModel.deleteDownloadedTrack(track) }
                                } else {
                                    null
                                },
                                onDelete = { viewModel.deleteTrack(track.id) },
                            )
                        }
                        is LibraryEntry.File -> FileRow(
                            modifier = libraryMotionModifier(),
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
    if (!selecting) {
        Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            ExtendedFloatingActionButton(
                onClick = { menuOpen = true },
                expanded = fabExpanded,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("导入") },
            )
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
    }

    CachePromptDialog(cachePrompt, viewModel::answerCache)

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

    recognizeFolder?.let { folderIdToRecognize ->
        AlertDialog(
            onDismissRequest = { recognizeFolder = null },
            title = { Text("识别为作品") },
            text = {
                Column {
                    Text("已购买的作品先对照 DLsite 的文件。对不上、没买过，或 DLsite 连不上，再对照 asmr.one。可以少一些文件或文件夹，但不能多出作品里没有的内容。")
                    OutlinedTextField(
                        value = recognizeCode,
                        onValueChange = { recognizeCode = it },
                        label = { Text("RJ 号") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        recognizeFolder = null
                        viewModel.recognizeFolder(folderIdToRecognize, recognizeCode)
                    },
                    enabled = recognizeCode.isNotBlank(),
                ) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { recognizeFolder = null }) { Text("取消") } },
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
        PlaylistPickDialog(
            playlists = playlists,
            onPick = { id ->
                pickingPlaylist = false
                viewModel.addSelectionToPlaylist(id)
            },
            onDismiss = { pickingPlaylist = false },
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
        ConfirmRemoveDialog(
            message = "移除选中的内容？手机上的原文件会保留。",
            onConfirm = {
                confirmDelete = false
                viewModel.deleteSelection()
            },
            onDismiss = { confirmDelete = false },
        )
    }

    confirmFolderDelete?.let { id ->
        ConfirmRemoveDialog(
            message = "这个文件夹和里面的内容会从音频库移除。手机上的原文件会保留。",
            onConfirm = {
                confirmFolderDelete = null
                viewModel.deleteFolder(id)
            },
            onDismiss = { confirmFolderDelete = null },
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



private fun rjHint(name: String): String =
    Regex("(?i)(?:RJ|VJ|BJ)\\d+").find(name)?.value?.uppercase().orEmpty()

internal fun persistRead(context: Context, uri: Uri) {
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

internal class OpenPersistableTree : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent =
        super.createIntent(context, input).addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
}
