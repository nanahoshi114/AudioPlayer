package com.nanahoshi.audioplayer.ui.library

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nanahoshi.audioplayer.asmr.WorkFacts
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.ui.components.AppTopBar
import com.nanahoshi.audioplayer.ui.components.EmptyState
import com.nanahoshi.audioplayer.ui.components.LoadingState
import com.nanahoshi.audioplayer.ui.components.SectionHeader
import com.nanahoshi.audioplayer.ui.components.SelectionAction
import com.nanahoshi.audioplayer.ui.components.SelectionTopBar
import com.nanahoshi.audioplayer.ui.components.WorkHeader

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryWorkScreen(
    folderId: Long,
    onBack: () -> Unit,
    onOpenPreview: (Long) -> Unit,
    onOpenOnline: (String) -> Unit,
    viewModel: LibraryViewModel = viewModel(),
) {
    LaunchedEffect(folderId) { viewModel.openWork(folderId) }
    val listing by viewModel.listing.collectAsStateWithLifecycle(LibraryListing(emptyList(), emptyList()))
    val folders = listing.folders
    val entries = listing.entries
    val currentFolder by viewModel.currentFolder.collectAsStateWithLifecycle(null)
    val currentId by viewModel.currentFolderId.collectAsStateWithLifecycle()
    val root by viewModel.workRoot.collectAsStateWithLifecycle(null)
    val rootId by viewModel.workRootId.collectAsStateWithLifecycle()
    val workFileSource by viewModel.workFileSource.collectAsStateWithLifecycle()
    val workSources by viewModel.workSources.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val cachePrompt by viewModel.cachePrompt.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle(emptyList())
    val selectedTracks by viewModel.selectedTrackIds.collectAsStateWithLifecycle()
    val selectedFolders by viewModel.selectedFolderIds.collectAsStateWithLifecycle()
    val selecting = selectedTracks.isNotEmpty() || selectedFolders.isNotEmpty()
    var trail by remember { mutableStateOf(listOf<LibraryCrumb>()) }
    var menu by remember { mutableStateOf(false) }
    var pickingPlaylist by remember { mutableStateOf(false) }
    var confirmRemoveWork by remember { mutableStateOf(false) }
    val climb = rememberLibraryClimb(
        currentFolderId = { viewModel.currentFolderId.value },
        up = viewModel::up,
        floorId = rootId,
    )
    val listState = viewModel.listState(currentId, true)
    LaunchedEffect(currentFolder?.id, currentFolder?.name) {
        trail = libraryTrail(trail, currentFolder)
    }
    val context = LocalContext.current
    val openTree = rememberLauncherForActivityResult(OpenPersistableTree()) { uri ->
        if (uri != null) {
            persistRead(context, uri)
            viewModel.recognizeIntoWork(rootId ?: folderId, uri)
        }
    }
    fun leaveOrUp() {
        if (selecting) {
            viewModel.clearSelection()
        } else if (rootId != null && currentId != null && currentId != rootId) {
            viewModel.up()
        } else {
            onBack()
        }
    }
    BackHandler { leaveOrUp() }

    val headerFolder = root ?: currentFolder
    val facts = headerFolder?.let { folder ->
        WorkFacts.fromJson(folder.workMeta) ?: WorkFacts(
            title = folder.name,
            circle = "",
            vas = emptyList(),
            tags = emptyList(),
            release = "",
            sourceId = folder.asmrSourceId.orEmpty(),
        )
    }
    val sourceId = headerFolder?.asmrSourceId.orEmpty()

    Column(Modifier.fillMaxSize()) {
        if (selecting) {
            SelectionTopBar(
                count = selectedTracks.size + selectedFolders.size,
                onClose = viewModel::clearSelection,
                actions = listOf(
                    SelectionAction("下一个播放", Icons.Default.SkipNext, viewModel::playNextSelection),
                    SelectionAction("加到末尾", Icons.AutoMirrored.Filled.PlaylistAdd, viewModel::appendSelection),
                    SelectionAction("下载", Icons.Default.Download, viewModel::downloadSelection),
                    SelectionAction("加入播放列表", Icons.AutoMirrored.Filled.QueueMusic, {
                        if (playlists.isEmpty()) viewModel.note("请先新建播放列表") else pickingPlaylist = true
                    }),
                    SelectionAction("删除已下载的文件", Icons.Default.Delete, viewModel::deleteDownloadedSelection, destructive = true),
                ),
            )
        } else {
            AppTopBar(
                title = "作品",
                onBack = { leaveOrUp() },
                actions = {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (sourceId.isNotBlank()) {
                            DropdownMenuItem(
                                text = { Text("在线详情") },
                                onClick = {
                                    menu = false
                                    onOpenOnline(sourceId)
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("从音频库移除") },
                            onClick = {
                                menu = false
                                confirmRemoveWork = true
                            },
                        )
                    }
                },
            )
        }
        if (headerFolder == null || facts == null) {
            LoadingState("正在读取作品…")
        } else {
            com.nanahoshi.audioplayer.ui.components.WorkFileBrowser(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                header = {
                    WorkHeader(facts = facts, coverUrl = headerFolder.coverUri)
                    if (headerFolder.asmrWorkId != null) {
                        FlowRow(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                FilledTonalButton(
                    onClick = { viewModel.cacheFolder(headerFolder.id) },
                ) { Text("缓存到本地") }
                OutlinedButton(
                    onClick = { openTree.launch(null) },
                ) { Text("识别本地文件夹") }
            }
        }
    },
    controls = {
        SectionHeader("文件")
        if (workSources) {
            SingleChoiceSegmentedButtonRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                SegmentedButton(
                    selected = workFileSource == TrackSource.DLSITE,
                    onClick = { viewModel.useWorkSource(TrackSource.DLSITE) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("DLSite Play") }
                SegmentedButton(
                    selected = workFileSource == TrackSource.ASMR,
                    onClick = { viewModel.useWorkSource(TrackSource.ASMR) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("asmr.one") }
            }
        }
        val subfolderTrail = trail.drop(1)
        LibraryBreadcrumb(
            trail = subfolderTrail,
            rootLabel = "根目录",
            onRoot = { climb(subfolderTrail.size) },
            onCrumb = { index -> climb(subfolderTrail.lastIndex - index) },
        )
    },
) {
                if (folders.isEmpty() && entries.isEmpty()) {
                    item(key = "empty") {
                        if (busy) {
                            LoadingState("正在读取文件…")
                        } else {
                            EmptyState(title = "这个文件夹是空的")
                        }
                    }
                }
                items(folders, key = { "folder-${it.id}" }) { folder ->
                    FolderRow(
                        modifier = libraryMotionModifier(),
                        folder = folder,
                        selected = folder.id in selectedFolders,
                        selecting = selecting,
                        onClick = {
                            if (selecting) viewModel.toggleFolder(folder.id) else viewModel.openFolder(folder)
                        },
                        onLongClick = { viewModel.beginSelection(folderId = folder.id) },
                        onCache = null,
                        onRecognize = null,
                        onDelete = null,
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
                                    (track.source == TrackSource.BILIBILI || track.source == TrackSource.YOUTUBE)
                                ) {
                                    { viewModel.downloadTrack(track) }
                                } else {
                                    null
                                },
                                onDeleteDownloaded = if (
                                    track.source == TrackSource.ASMR ||
                                    track.source == TrackSource.DLSITE ||
                                    (track.source == TrackSource.BILIBILI || track.source == TrackSource.YOUTUBE)
                                ) {
                                    { viewModel.deleteDownloadedTrack(track) }
                                } else {
                                    null
                                },
                                onDelete = null,
                            )
                        }
                        is LibraryEntry.File -> FileRow(
                            modifier = libraryMotionModifier(),
                            file = entry.file,
                            onClick = { onOpenPreview(entry.file.id) },
                            onDelete = null,
                        )
                    }
                }
            }
        }
    }

    CachePromptDialog(cachePrompt, viewModel::answerCache)

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
    if (confirmRemoveWork) {
        ConfirmRemoveDialog(
            message = "这个作品和里面的内容会从音频库移除。手机上的原文件会保留。",
            onConfirm = {
                confirmRemoveWork = false
                viewModel.deleteFolder(headerFolder?.id ?: folderId)
                onBack()
            },
            onDismiss = { confirmRemoveWork = false },
        )
    }
}
