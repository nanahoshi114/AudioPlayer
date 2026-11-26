package com.nanahoshi.audioplayer.ui.library

import com.nanahoshi.audioplayer.R
import androidx.compose.ui.res.stringResource
import com.nanahoshi.audioplayer.ui.asmr.workLabel
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.LibraryFileEntity
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.data.isSubtitleFile
import com.nanahoshi.audioplayer.ui.asmr.SearchPiece
import com.nanahoshi.audioplayer.ui.asmr.searchPieceForCircle
import com.nanahoshi.audioplayer.ui.asmr.searchPieceForTag
import com.nanahoshi.audioplayer.ui.asmr.searchPieceForVa
import com.nanahoshi.audioplayer.ui.components.AppTopBar
import com.nanahoshi.audioplayer.ui.components.EmptyState
import com.nanahoshi.audioplayer.ui.components.ImmersiveTopBar
import com.nanahoshi.audioplayer.ui.components.ImmersiveWorkScaffold
import com.nanahoshi.audioplayer.ui.components.LoadingState
import com.nanahoshi.audioplayer.ui.components.SectionHeader
import com.nanahoshi.audioplayer.ui.components.SegmentedTabBar
import com.nanahoshi.audioplayer.ui.components.SelectionTopBar
import com.nanahoshi.audioplayer.ui.components.WorkHeader
import com.nanahoshi.audioplayer.ui.components.toWorkFacts

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryWorkScreen(
    folderId: Long,
    onBack: () -> Unit,
    onOpenPreview: (Long) -> Unit,
    onOpenOnline: (String) -> Unit,
    onVerifyJaDownload: (String) -> Unit = {},
    onVerifyJaRefresh: (String) -> Unit = {},
    onSearchWorkPiece: (SearchPiece) -> Unit = {},
    viewModel: LibraryViewModel = viewModel(),
) {
    LaunchedEffect(folderId) { viewModel.openWork(folderId) }
    val location by viewModel.workLocation.collectAsStateWithLifecycle()
    val loaded by viewModel.workListing.collectAsStateWithLifecycle()
    val workMissing by viewModel.workMissing.collectAsStateWithLifecycle()
    val request = location?.request
    val listing = loaded?.takeIf { it.request === request }?.listing
    val folders = listing?.folders.orEmpty()
    val showSubtitleFiles by viewModel.showSubtitleFiles.collectAsStateWithLifecycle()
    val listedEntries = listing?.entries.orEmpty()
    val entries = if (showSubtitleFiles) listedEntries else listedEntries.filterNot {
        it is LibraryEntry.File && isSubtitleFile(it.file.name)
    }
    val hiddenSubtitleCount = if (showSubtitleFiles) 0
        else (listing?.hiddenSubtitleCount ?: 0) + listedEntries.size - entries.size
    val workFileSource = request?.source
    val workSources by viewModel.workSources.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val cachePrompt by viewModel.cachePrompt.collectAsStateWithLifecycle()
    val pendingAudioDelete by viewModel.pendingAudioDelete.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle(emptyList())
    val selectedTracks by viewModel.selectedTrackIds.collectAsStateWithLifecycle()
    val selectedFolders by viewModel.selectedFolderIds.collectAsStateWithLifecycle()
    val selectedFiles by viewModel.selectedFileIds.collectAsStateWithLifecycle()
    val audioSelection by viewModel.hasSelectedAudio.collectAsStateWithLifecycle()
    val hasSelectedAudio = audioSelection == LibraryViewModel.AudioSelection(selectedTracks, selectedFolders, workFileSource)
    val selecting = selectedTracks.isNotEmpty() || selectedFolders.isNotEmpty() || selectedFiles.isNotEmpty()
    val selectedLocalRemoval by viewModel.selectedLocalRemoval.collectAsStateWithLifecycle()
    val removalSelection = LibraryViewModel.LocalRemovalSelection(selectedTracks, selectedFolders, selectedFiles, workFileSource)
    val removalPlan = selectedLocalRemoval?.takeIf { it.first == removalSelection }?.second
    var menu by remember { mutableStateOf(false) }
    var pickingPlaylist by remember { mutableStateOf(false) }
    var confirmRemoveWork by remember { mutableStateOf(false) }
    var bindingTrack by remember { mutableStateOf<TrackEntity?>(null) }
    var bindingSubtitleFile by remember { mutableStateOf<LibraryFileEntity?>(null) }
    var batchBindingOpen by remember { mutableStateOf(false) }
    val headerFolder = location?.path?.firstOrNull()
    val context = LocalContext.current
    val openTree = rememberLauncherForActivityResult(OpenPersistableTree()) { uri ->
        if (uri != null) {
            persistRead(context, uri)
            viewModel.recognizeIntoWork(folderId, uri)
        }
    }
    fun leaveOrUp() {
        if (selecting) {
            viewModel.clearSelection()
        } else if (!viewModel.upInWork()) {
            onBack()
        }
    }
    BackHandler { leaveOrUp() }

    val facts = headerFolder?.toWorkFacts()
    val sourceId = headerFolder?.asmrSourceId.orEmpty()

    val title = stringResource(R.string.library_work_title)
    val pageState = rememberScrollState()
    if (workMissing || headerFolder == null || facts == null) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(title, onBack = { leaveOrUp() })
            if (workMissing) {
                EmptyState(
                    stringResource(R.string.library_work_not_found),
                    modifier = Modifier.fillMaxSize(),
                    actionLabel = stringResource(R.string.common_back),
                    onAction = onBack,
                )
            } else {
                LoadingState(stringResource(R.string.library_work_loading), Modifier.weight(1f).fillMaxWidth())
            }
        }
    } else {
        ImmersiveWorkScaffold(
            listState = viewModel.workListState(request?.folderId),
            pageState = pageState,
            loading = listing == null || (busy && folders.isEmpty() && entries.isEmpty()),
            selectionBar = if (selecting) {
                {
                    SelectionTopBar(
                        count = selectedTracks.size + selectedFolders.size + selectedFiles.size,
                        onClose = viewModel::clearSelection,
                        actions = buildLibrarySelectionActions(
                            context = context,
                            includeAudioActions = hasSelectedAudio,
                            playlists = playlists,
                            removalPlan = removalPlan,
                            onPlayNext = viewModel::playNextSelection,
                            onAppend = viewModel::appendSelection,
                            onDownload = viewModel::downloadSelection,
                            onPickPlaylist = { pickingPlaylist = true },
                            onNoPlaylists = { viewModel.note(context.getString(R.string.library_create_playlist_first)) },
                            onDeleteDownloaded = { plan -> viewModel.deleteDownloadedSelection(removalSelection, plan) },
                        ),
                    )
                }
            } else {
                null
            },
            topBar = { containerAlpha ->
                ImmersiveTopBar(
                    title = title,
                    containerAlpha = containerAlpha,
                    onBack = { leaveOrUp() },
                    actions = {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.a11y_work_actions))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (sourceId.isNotBlank()) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.library_work_online_details)) },
                                    onClick = {
                                        menu = false
                                        onOpenOnline(sourceId)
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.library_batch_bind_subtitles)) },
                                onClick = {
                                    menu = false
                                    batchBindingOpen = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.library_remove_from_library)) },
                                onClick = {
                                    menu = false
                                    confirmRemoveWork = true
                                },
                            )
                        }
                    },
                )
            },
            header = { topBarInset, heroMeasureModifier ->
                WorkHeader(
                    facts = facts,
                    coverUrl = headerFolder.coverUri,
                    topBarInset = topBarInset,
                    onSearchCircle = { searchPieceForCircle(it)?.let(onSearchWorkPiece) },
                    onSearchVa = { searchPieceForVa(it)?.let(onSearchWorkPiece) },
                    onSearchTag = { searchPieceForTag(it)?.let(onSearchWorkPiece) },
                    modifier = heroMeasureModifier,
                )
                if (headerFolder.asmrWorkId != null) {
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        FilledTonalButton(onClick = { viewModel.cacheFolder(headerFolder.id) }) {
                            Text(stringResource(R.string.library_download_whole_work))
                        }
                        if (TrackSource.JAPANESE_ASMR in workSources) {
                            OutlinedButton(onClick = { viewModel.refreshJapaneseArticles(headerFolder.id) }) {
                                Text(stringResource(R.string.library_refresh_ja))
                            }
                        }
                        if (workFileSource != TrackSource.JAPANESE_ASMR) {
                            OutlinedButton(onClick = { openTree.launch(null) }) {
                                Text(stringResource(R.string.library_link_local_folder))
                            }
                        }
                    }
                }
            },
            controls = {
                val toggleSubDesc = stringResource(R.string.a11y_show_subtitle_files)
                SectionHeader(stringResource(R.string.library_files_section)) {
                    FilterChip(
                        selected = showSubtitleFiles,
                        onClick = viewModel::toggleSubtitleFiles,
                        leadingIcon = { Icon(Icons.Default.Subtitles, contentDescription = null) },
                        label = {
                            Text(
                                stringResource(R.string.library_show_subtitle_files),
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .semantics { contentDescription = toggleSubDesc },
                    )
                }
                if (workSources.size > 1) {
                    SegmentedTabBar(
                        options = workSources,
                        selected = workFileSource,
                        label = { it.workLabel() },
                        onSelect = viewModel::useWorkSource,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                val subfolderTrail = location?.path.orEmpty().drop(1).map { LibraryCrumb(it.id, it.name) }
                LibraryBreadcrumb(
                    trail = subfolderTrail,
                    rootLabel = stringResource(R.string.library_root_label),
                    onRoot = { viewModel.goToWorkAncestor(headerFolder.id) },
                    onCrumb = { index -> viewModel.goToWorkAncestor(subfolderTrail[index].id) },
                    revealCurrent = true,
                )
            },
        ) {
            if (folders.isEmpty() && entries.isEmpty()) {
                item(key = "empty") {
                    if (hiddenSubtitleCount > 0) {
                        EmptyState(
                            title = stringResource(R.string.library_hidden_subtitles_title),
                            message = stringResource(R.string.library_hidden_subtitles_message, hiddenSubtitleCount),
                            actionLabel = stringResource(R.string.library_show_subtitle_files),
                            onAction = viewModel::toggleSubtitleFiles,
                        )
                    } else {
                        EmptyState(
                            title = stringResource(R.string.library_empty_folder_title),
                            message = stringResource(R.string.library_work_folder_empty_message),
                        )
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
                        if (selecting) viewModel.toggleFolder(folder.id) else viewModel.openWorkFolder(request, folder)
                    },
                    onLongClick = { viewModel.beginSelection(folderId = folder.id) },
                    onCache = null,
                    onRecognize = null,
                    onDelete = null,
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
                onTrackDelete = null,
                onTrackBindSubtitle = { track -> bindingTrack = track },
                onFileClick = { file ->
                    if (viewModel.canInteractWithWorkFile(request, file)) {
                        if (selecting) viewModel.toggleFile(file.id) else onOpenPreview(file.id)
                    }
                },
                onFileLongClick = { file ->
                    if (viewModel.canInteractWithWorkFile(request, file)) {
                        viewModel.beginSelection(fileId = file.id)
                    }
                },
                onFileDelete = null,
                onFileBindSubtitle = { file -> bindingSubtitleFile = file },
                onFileDeleteDownloaded = viewModel::deleteDownloadedFile,
            )
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
        onVerifyJaRefresh = onVerifyJaRefresh,
    )
    if (confirmRemoveWork) {
        ConfirmRemoveDialog(
            message = stringResource(R.string.library_remove_work_message),
            onConfirm = {
                confirmRemoveWork = false
                viewModel.deleteFolder(headerFolder?.id ?: folderId)
                onBack()
            },
            onDismiss = { confirmRemoveWork = false },
        )
    }
    if (batchBindingOpen && headerFolder != null) {
        BatchSubtitleMatchSheet(
            currentFolderId = request?.folderId ?: headerFolder.id,
            viewModel = viewModel,
            onDismiss = { batchBindingOpen = false },
        )
    }
}
