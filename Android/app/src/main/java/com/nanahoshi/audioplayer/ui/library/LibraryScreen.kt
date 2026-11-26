package com.nanahoshi.audioplayer.ui.library

import com.nanahoshi.audioplayer.R
import androidx.compose.ui.res.stringResource
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.key
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nanahoshi.audioplayer.ui.components.CoverImage
import com.nanahoshi.audioplayer.ui.components.DropdownFilterButton
import com.nanahoshi.audioplayer.ui.components.EmptyState
import com.nanahoshi.audioplayer.ui.components.LoadingState
import com.nanahoshi.audioplayer.ui.components.SearchField
import com.nanahoshi.audioplayer.ui.components.SelectionTopBar
import com.nanahoshi.audioplayer.ui.components.TopBarOverride
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nanahoshi.audioplayer.data.LibrarySort
import com.nanahoshi.audioplayer.data.db.LibraryFileEntity
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.ui.CollapsingTop
import com.nanahoshi.audioplayer.ui.canCollapseHeader

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    topBarOwnerKey: String,
    onOpenPreview: (Long) -> Unit,
    onOpenLibraryWork: (Long) -> Unit,
    onOpenBiliVideo: (Long) -> Unit,
    onImportOnline: () -> Unit,
    onVerifyJaDownload: (String) -> Unit = {},
    viewModel: LibraryViewModel = viewModel(),
) {
    val loaded by viewModel.listing.collectAsStateWithLifecycle(null)
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val sourceFilter by viewModel.sourceFilter.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val pendingAsmr by viewModel.pendingAsmr.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle(emptyList())
    val biliAuthors by viewModel.biliAuthors.collectAsStateWithLifecycle()
    val currentFolder by viewModel.currentFolder.collectAsStateWithLifecycle(null)
    val folderId by viewModel.currentFolderId.collectAsStateWithLifecycle()
    val listKey = libraryListKey(folderId, sourceFilter, searchQuery)
    val listing = loaded?.takeIf { it.key == listKey }?.listing
    val folders = listing?.folders.orEmpty()
    val entries = listing?.entries.orEmpty()
    val isEmpty = listing != null && folders.isEmpty() && entries.isEmpty()
    val cachePrompt by viewModel.cachePrompt.collectAsStateWithLifecycle()
    val pendingAudioDelete by viewModel.pendingAudioDelete.collectAsStateWithLifecycle()
    val selectedTracks by viewModel.selectedTrackIds.collectAsStateWithLifecycle()
    val selectedFolders by viewModel.selectedFolderIds.collectAsStateWithLifecycle()
    val selectedFiles by viewModel.selectedFileIds.collectAsStateWithLifecycle()
    val workFileSource by viewModel.workFileSource.collectAsStateWithLifecycle()
    val audioSelection by viewModel.hasSelectedAudio.collectAsStateWithLifecycle()
    val hasSelectedAudio = audioSelection == LibraryViewModel.AudioSelection(selectedTracks, selectedFolders, workFileSource)
    val selecting = selectedTracks.isNotEmpty() || selectedFolders.isNotEmpty() || selectedFiles.isNotEmpty()
    val selectedLocalRemoval by viewModel.selectedLocalRemoval.collectAsStateWithLifecycle()
    val removalSelection = LibraryViewModel.LocalRemovalSelection(selectedTracks, selectedFolders, selectedFiles, workFileSource)
    val removalPlan = selectedLocalRemoval?.takeIf { it.first == removalSelection }?.second
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var pickingPlaylist by remember { mutableStateOf(false) }
    var pendingTree by remember { mutableStateOf<Uri?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmFolderDelete by remember { mutableStateOf<Long?>(null) }
    var recognizeFolder by remember { mutableStateOf<Long?>(null) }
    var recognizeCode by remember { mutableStateOf("") }
    var importKeepStructure by rememberSaveable { mutableStateOf(true) }
    var confirmTrackDelete by remember { mutableStateOf<TrackEntity?>(null) }
    var confirmFileDelete by remember { mutableStateOf<LibraryFileEntity?>(null) }
    var bindingTrack by remember { mutableStateOf<TrackEntity?>(null) }
    var bindingSubtitleFile by remember { mutableStateOf<LibraryFileEntity?>(null) }

    val removalPolicy by viewModel.removalPolicy.collectAsStateWithLifecycle()
    val canRemoveSelection by viewModel.canRemoveSelection.collectAsStateWithLifecycle()
    val listState = viewModel.listState(listKey)
    val fabExpanded by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 48 }
    }
    var trail by rememberSaveable(stateSaver = LibraryTrailSaver) { mutableStateOf(listOf<LibraryCrumb>()) }
    if (selecting) {
        val selectedCount = selectedTracks.size + selectedFolders.size + selectedFiles.size
        // 选中了作品时，播放和加入列表的键都收起来，作品要进作品页再操作。
        val selectsWork = folders.any { it.id in selectedFolders && (!it.asmrSourceId.isNullOrBlank() || it.biliBvid != null) }
        TopBarOverride(topBarOwnerKey) {
            SelectionTopBar(
                count = selectedCount,
                onClose = viewModel::clearSelection,
                actions = buildLibrarySelectionActions(
                    context = context,
                    includeAudioActions = !selectsWork && hasSelectedAudio,
                    playlists = playlists,
                    removalPlan = removalPlan,
                    canRemoveFromLibrary = canRemoveSelection,
                    onPlayNext = viewModel::playNextSelection,
                    onAppend = viewModel::appendSelection,
                    onDownload = viewModel::downloadSelection,
                    onPickPlaylist = { pickingPlaylist = true },
                    onNoPlaylists = { viewModel.note(context.getString(R.string.library_create_playlist_first)) },
                    onDeleteDownloaded = { plan -> viewModel.deleteDownloadedSelection(removalSelection, plan) },
                    onRemoveFromLibrary = { confirmDelete = true },
                ),
            )
        }
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
        resetKey = folderId,
        isEmpty = isEmpty,
        canCollapse = { collapsedPx ->
            listing == null || listState.canCollapseHeader(collapsedPx)
        },
        header = {
            SearchField(
                value = searchQuery,
                onValueChange = viewModel::updateSearch,
                placeholder = stringResource(if (folderId == null) R.string.library_search_hint_root else R.string.library_search_hint_folder),
                trailing = if (folderId != null) ({ LibrarySortMenu(sort) { if (sort != it) viewModel.toggleSort() } }) else null,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (folderId == null) {
                val filters = listOf(
                    LibrarySourceFilter.ALL to stringResource(R.string.library_filter_all),
                    LibrarySourceFilter.BILIBILI to stringResource(R.string.library_filter_bilibili),
                    LibrarySourceFilter.YOUTUBE to "YouTube",
                    LibrarySourceFilter.ASMR to stringResource(R.string.library_filter_online),
                    LibrarySourceFilter.LOCAL to stringResource(R.string.library_filter_local),
                )
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LazyRow(
                        Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(filters, key = { it.first.name }) { (filter, label) ->
                            FilterChip(
                                selected = sourceFilter == filter,
                                onClick = { viewModel.setSourceFilter(filter) },
                                label = {
                                    Text(
                                        label,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                            )
                        }
                    }
                    LibrarySortMenu(sort) { if (sort != it) viewModel.toggleSort() }
                }
            }
        },
        pinned = {
            if (folderId != null && trail.isNotEmpty()) LibraryBreadcrumb(
                trail = trail,
                onRoot = { viewModel.goToFolder(null) },
                onCrumb = { index -> viewModel.goToFolder(trail[index].id) },
            )
        },
    ) { topInset ->
        if (listing == null) {
            Box(topInset.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingState() }
        } else if (isEmpty) {
            Box(topInset.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                when {
                    listKey.isSearching -> EmptyState(
                        title = stringResource(R.string.library_empty_search_title),
                        message = stringResource(R.string.library_empty_search_message),
                    )
                    folderId == null -> EmptyState(
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
            key(listKey) {
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
                libraryEntries(
                    entries = entries,
                    selectedTracks = selectedTracks,
                    selectedFiles = selectedFiles,
                    selecting = selecting,
                    onTrackClick = { track -> if (selecting) viewModel.toggleTrack(track.id) else viewModel.play(track) },
                    onTrackLongClick = { track -> viewModel.beginSelection(trackId = track.id) },
                    onTrackAppend = viewModel::append,
                    onTrackPlayNext = viewModel::playNext,
                    onTrackDownload = viewModel::downloadTrack,
                    onTrackDeleteDownloaded = viewModel::deleteDownloadedTrack,
                    onTrackDelete = { confirmTrackDelete = it },
                    onTrackBindSubtitle = { track -> bindingTrack = track },
                    onFileClick = { file -> if (selecting) viewModel.toggleFile(file.id) else onOpenPreview(file.id) },
                    onFileLongClick = { file -> viewModel.beginSelection(fileId = file.id) },
                    onFileDelete = { confirmFileDelete = it },
                    onFileBindSubtitle = { file -> bindingSubtitleFile = file },
                    onFileDeleteDownloaded = viewModel::deleteDownloadedFile,
                    canRemoveEntry = { targetFolderId -> removalPolicy?.canRemoveEntry(targetFolderId) == true },
                )
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
                        importKeepStructure = true
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

    LibrarySharedDialogs(
        viewModel = viewModel,
        cachePrompt = cachePrompt,
        pickingPlaylist = pickingPlaylist,
        playlists = playlists,
        pendingAudioDelete = pendingAudioDelete,
        bindingTrack = bindingTrack,
        bindingSubtitleFile = bindingSubtitleFile,
        onDismissPlaylist = { pickingPlaylist = false },
        onDismissBindingTrack = { bindingTrack = null },
        onDismissBindingFile = { bindingSubtitleFile = null },
        onVerifyJaDownload = onVerifyJaDownload,
    )

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

    pendingTree?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingTree = null },
            title = { Text(stringResource(R.string.library_import_folder_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.library_import_folder_help))
                    Column(Modifier.padding(top = 8.dp)) {
                        ImportModeRow(
                            label = stringResource(R.string.library_import_keep_structure),
                            selected = importKeepStructure,
                            onClick = { importKeepStructure = true },
                        )
                        ImportModeRow(
                            label = stringResource(R.string.library_import_audio_only),
                            selected = !importKeepStructure,
                            onClick = { importKeepStructure = false },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingTree = null
                    viewModel.importFolder(uri, keepStructure = importKeepStructure)
                }) { Text(stringResource(R.string.library_import)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingTree = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (confirmDelete) {
        ConfirmRemoveDialog(
            message = stringResource(R.string.library_remove_selection_message, selectedTracks.size + selectedFolders.size + selectedFiles.size),
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

    confirmTrackDelete?.let { track ->
        ConfirmRemoveDialog(
            message = stringResource(R.string.library_remove_track_message, track.title),
            onConfirm = {
                confirmTrackDelete = null
                viewModel.deleteTrack(track.id)
            },
            onDismiss = { confirmTrackDelete = null },
        )
    }

    confirmFileDelete?.let { file ->
        ConfirmRemoveDialog(
            message = stringResource(R.string.library_remove_file_message, file.name),
            onConfirm = {
                confirmFileDelete = null
                viewModel.deleteFile(file.id)
            },
            onDismiss = { confirmFileDelete = null },
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

internal class OpenPersistableDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
}

internal class OpenPersistableTree : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent =
        super.createIntent(context, input).addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
}

@Composable
private fun ImportModeRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun LibrarySortMenu(sort: LibrarySort, onChoose: (LibrarySort) -> Unit) {
    DropdownFilterButton(
        options = LibrarySort.entries,
        selected = sort,
        optionLabel = { stringResource(if (it == LibrarySort.NAME) R.string.library_sort_name else R.string.library_sort_added) },
        onSelect = onChoose,
        icon = Icons.AutoMirrored.Filled.Sort,
        iconContentDescription = stringResource(if (sort == LibrarySort.NAME) R.string.a11y_sort_name else R.string.a11y_sort_added),
    )
}
