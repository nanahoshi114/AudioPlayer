package com.nanahoshi.audioplayer.ui.library

import com.nanahoshi.audioplayer.R
import androidx.compose.ui.res.stringResource
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.nanahoshi.audioplayer.ui.components.DeleteAudioDialog
import com.nanahoshi.audioplayer.ui.components.LabelBadge
import com.nanahoshi.audioplayer.ui.components.LoadingState
import com.nanahoshi.audioplayer.ui.components.LocalTopBarOverride
import com.nanahoshi.audioplayer.ui.components.SelectionAction
import com.nanahoshi.audioplayer.ui.components.SelectionTopBar
import com.nanahoshi.audioplayer.ui.components.SourceBadge
import com.nanahoshi.audioplayer.ui.theme.LocalReduceMotion
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    onOpenBiliVideo: (Long) -> Unit,
    onImportOnline: () -> Unit,
    onVerifyJaDownload: (String) -> Unit = {},
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
    val biliAuthors by viewModel.biliAuthors.collectAsStateWithLifecycle()
    val currentFolder by viewModel.currentFolder.collectAsStateWithLifecycle(null)
    val folderId by viewModel.currentFolderId.collectAsStateWithLifecycle()
    val cachePrompt by viewModel.cachePrompt.collectAsStateWithLifecycle()
    val pendingAudioDelete by viewModel.pendingAudioDelete.collectAsStateWithLifecycle()
    val selectedTracks by viewModel.selectedTrackIds.collectAsStateWithLifecycle()
    val selectedFolders by viewModel.selectedFolderIds.collectAsStateWithLifecycle()
    val selecting = selectedTracks.isNotEmpty() || selectedFolders.isNotEmpty()
    val selectedLocalRemoval by viewModel.selectedLocalRemoval.collectAsStateWithLifecycle()
    val workFileSource by viewModel.workFileSource.collectAsStateWithLifecycle()
    val removalSelection = LibraryViewModel.LocalRemovalSelection(selectedTracks, selectedFolders, workFileSource)
    val removalTargets = selectedLocalRemoval?.takeIf { it.first == removalSelection }?.second
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var pickingPlaylist by remember { mutableStateOf(false) }
    var pendingTree by remember { mutableStateOf<Uri?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmFolderDelete by remember { mutableStateOf<Long?>(null) }
    var recognizeFolder by remember { mutableStateOf<Long?>(null) }
    var recognizeCode by remember { mutableStateOf("") }

    val removalPolicy by viewModel.removalPolicy.collectAsStateWithLifecycle()
    val canRemoveSelection by viewModel.canRemoveSelection.collectAsStateWithLifecycle()
    val listState = viewModel.listState(folderId, listing.browsing)
    val fabExpanded by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 48 }
    }
    var trail by rememberSaveable(stateSaver = LibraryTrailSaver) { mutableStateOf(listOf<LibraryCrumb>()) }
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
        val selectsWork = folders.any { it.id in selectedFolders && (!it.asmrSourceId.isNullOrBlank() || it.biliBvid != null) }
        SideEffect {
            topBar?.value = {
                SelectionTopBar(
                    count = selectedCount,
                    onClose = viewModel::clearSelection,
                    actions = buildList {
                        if (!selectsWork) {
                            add(SelectionAction(context.getString(R.string.library_play_next), Icons.Default.SkipNext, viewModel::playNextSelection))
                            add(SelectionAction(context.getString(R.string.library_add_queue), Icons.AutoMirrored.Filled.PlaylistAdd, viewModel::appendSelection))
                            add(SelectionAction(context.getString(R.string.library_download_selected), Icons.Default.Download, viewModel::downloadSelection))
                            add(
                                SelectionAction(context.getString(R.string.library_add_playlist), Icons.AutoMirrored.Filled.QueueMusic, {
                                    if (playlists.isEmpty()) viewModel.note(context.getString(R.string.library_create_playlist_first)) else pickingPlaylist = true
                                }),
                            )
                        }
                        if (removalTargets != null) {
                            add(SelectionAction(context.getString(removalTargets.labelRes), Icons.Default.Delete, {
                                viewModel.deleteDownloadedSelection(removalSelection, removalTargets)
                            }, destructive = true))
                        }
                        if (canRemoveSelection) add(SelectionAction(context.getString(R.string.library_remove_from_library), Icons.Default.Delete, { confirmDelete = true }, destructive = true))
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
                placeholder = { Text(stringResource(if (folderId == null) R.string.library_search_hint_root else R.string.library_search_hint_folder)) },
                trailingIcon = if (folderId != null) ({ LibrarySortMenu(sort) { if (sort != it) viewModel.toggleSort() } }) else null,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (folderId == null) Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf(
                        LibrarySourceFilter.ALL to stringResource(R.string.library_filter_all),
                        LibrarySourceFilter.BILIBILI to stringResource(R.string.library_filter_bilibili),
                        LibrarySourceFilter.YOUTUBE to "YouTube",
                        LibrarySourceFilter.ASMR to stringResource(R.string.library_filter_online),
                        LibrarySourceFilter.LOCAL to stringResource(R.string.library_filter_local),
                    ).forEach { (filter, label) ->
                        FilterChip(
                            selected = sourceFilter == filter && searchQuery.isBlank(),
                            onClick = { viewModel.setSourceFilter(filter) },
                            label = { Text(label) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
                LibrarySortMenu(sort) { if (sort != it) viewModel.toggleSort() }
            }
        },
        pinned = {
            if (folderId != null && trail.isNotEmpty()) LibraryBreadcrumb(trail = trail,
                onRoot = { climb(trail.size) }, onCrumb = { climb(trail.lastIndex - it) })
        },
    ) { topInset ->
        if (folders.isEmpty() && entries.isEmpty()) {
            Box(topInset.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                when {
                    !listing.browsing -> EmptyState(
                        title = stringResource(R.string.library_empty_search_title),
                        message = stringResource(R.string.library_empty_search_message),
                    )
                    currentFolder == null -> EmptyState(
                        title = stringResource(R.string.library_empty_root_title),
                        message = stringResource(R.string.library_empty_root_message),
                    )
                    else -> EmptyState(
                        title = stringResource(R.string.library_empty_folder_title),
                        message = stringResource(R.string.library_empty_folder_message),
                    )
                }
            }
        } else {
            key(if (listing.browsing) "folder:${folderId ?: "root"}" else "flat") {
            LazyColumn(
                topInset.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                items(folders, key = { "folder-${it.id}" }) { folder ->
                    FolderRow(
                        modifier = libraryMotionModifier(),
                        folder = folder,
                        biliArtist = biliAuthors[folder.id],
                        selected = folder.id in selectedFolders,
                        selecting = selecting,
                        onClick = {
                            when {
                                selecting -> viewModel.toggleFolder(folder.id)
                                folder.biliBvid != null -> onOpenBiliVideo(folder.id)
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
                        onRecognize = if (folder.asmrSourceId == null && folder.asmrWorkId == null && folder.biliBvid == null) {
                            {
                                recognizeCode = rjHint(folder.name)
                                recognizeFolder = folder.id
                            }
                        } else {
                            null
                        },
                        onDelete = if (removalPolicy?.canRemoveFolder(folder.id) == true) ({ confirmFolderDelete = folder.id }) else null,
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
                                onDelete = if (removalPolicy?.canRemoveEntry(track.folderId) == true) ({ viewModel.deleteTrack(track.id) }) else null,
                            )
                        }
                        is LibraryEntry.File -> FileRow(
                            modifier = libraryMotionModifier(),
                            file = entry.file,
                            onClick = { onOpenPreview(entry.file.id) },
                            onDelete = if (removalPolicy?.canRemoveEntry(entry.file.folderId) == true) ({ viewModel.deleteFile(entry.file.id) }) else null,
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
                text = { Text(stringResource(R.string.library_import)) },
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_import_files)) },
                    onClick = {
                        menuOpen = false
                        openFile.launch(arrayOf("audio/*"))
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_import_folder)) },
                    onClick = {
                        menuOpen = false
                        openFolder.launch(null)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_import_online)) },
                    onClick = { menuOpen = false; onImportOnline() },
                )
            }
        }
    }
    }

    CachePromptDialog(cachePrompt, viewModel::answerCache, viewModel::cancelCacheWait, onVerifyJaDownload)

    pendingAsmr?.let { preview ->
        AlertDialog(
            onDismissRequest = viewModel::dismissAsmrPreview,
            title = { Text(stringResource(R.string.library_asmr_result_title)) },
            text = {
                Column {
                    CoverImage(preview.coverUrl)
                    Text(preview.title, modifier = Modifier.padding(top = 8.dp))
                    Text(stringResource(R.string.library_asmr_add_confirm), modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = viewModel::confirmAsmrPreview) { Text(stringResource(R.string.common_add_to_library)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissAsmrPreview) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    recognizeFolder?.let { folderIdToRecognize ->
        AlertDialog(
            onDismissRequest = { recognizeFolder = null },
            title = { Text(stringResource(R.string.library_recognize_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.library_recognize_help))
                    OutlinedTextField(
                        value = recognizeCode,
                        onValueChange = { recognizeCode = it },
                        label = { Text(stringResource(R.string.library_rj_label)) },
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
                ) { Text(stringResource(R.string.library_recognize_confirm)) }
            },
            dismissButton = { TextButton(onClick = { recognizeFolder = null }) { Text(stringResource(R.string.common_cancel)) } },
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

    pendingTree?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingTree = null },
            title = { Text(stringResource(R.string.library_import_folder_title)) },
            text = { Text(stringResource(R.string.library_import_folder_help)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingTree = null
                    viewModel.importFolder(uri, keepStructure = true)
                }) { Text(stringResource(R.string.library_import_keep_structure)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingTree = null
                    viewModel.importFolder(uri, keepStructure = false)
                }) { Text(stringResource(R.string.library_import_audio_only)) }
            },
        )
    }

    if (confirmDelete) {
        ConfirmRemoveDialog(
            message = stringResource(R.string.library_remove_selection_message, selectedTracks.size + selectedFolders.size),
            onConfirm = {
                confirmDelete = false
                viewModel.deleteSelection()
            },
            onDismiss = { confirmDelete = false },
        )
    }

    confirmFolderDelete?.let { id ->
        ConfirmRemoveDialog(
            message = stringResource(R.string.library_remove_folder_message),
            onConfirm = {
                confirmFolderDelete = null
                viewModel.deleteFolder(id)
            },
            onDismiss = { confirmFolderDelete = null },
        )
    }

    pendingAudioDelete?.let { pending ->
        DeleteAudioDialog(
            itemCount = pending.targets.downloads.size,
            associationCount = pending.targets.associations.size,
            subtitleCount = pending.subtitles,
            onConfirm = viewModel::confirmAudioDelete,
            onCancel = viewModel::cancelAudioDelete,
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

@Composable
private fun LibrarySortMenu(sort: LibrarySort, onChoose: (LibrarySort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.AutoMirrored.Filled.Sort,
                contentDescription = stringResource(if (sort == LibrarySort.NAME) R.string.a11y_sort_name else R.string.a11y_sort_added),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.library_sort_name)) }, onClick = { expanded = false; onChoose(LibrarySort.NAME) })
            DropdownMenuItem(text = { Text(stringResource(R.string.library_sort_added)) }, onClick = { expanded = false; onChoose(LibrarySort.ADDED) })
        }
    }
}
