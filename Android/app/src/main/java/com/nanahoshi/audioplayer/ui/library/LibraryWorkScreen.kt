package com.nanahoshi.audioplayer.ui.library

import com.nanahoshi.audioplayer.R
import androidx.compose.ui.res.stringResource
import com.nanahoshi.audioplayer.ui.asmr.workLabel
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nanahoshi.audioplayer.asmr.WorkFacts
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.LibraryFileEntity
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.data.isSubtitleFile
import com.nanahoshi.audioplayer.ui.components.AppTopBar
import com.nanahoshi.audioplayer.ui.components.EmptyState
import com.nanahoshi.audioplayer.ui.components.FileToTrackSubtitleSheet
import com.nanahoshi.audioplayer.ui.components.LoadingState
import com.nanahoshi.audioplayer.ui.components.SectionHeader
import com.nanahoshi.audioplayer.ui.components.SelectionAction
import com.nanahoshi.audioplayer.ui.components.SelectionTopBar
import com.nanahoshi.audioplayer.ui.components.TrackSubtitlePickerSheet
import com.nanahoshi.audioplayer.ui.components.WorkHeader

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryWorkScreen(
    folderId: Long,
    onBack: () -> Unit,
    onOpenPreview: (Long) -> Unit,
    onOpenOnline: (String) -> Unit,
    onVerifyJaDownload: (String) -> Unit = {},
    onVerifyJaRefresh: (String) -> Unit = {},
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
                count = selectedTracks.size + selectedFolders.size + selectedFiles.size,
                onClose = viewModel::clearSelection,
                actions = buildList {
                    if (hasSelectedAudio) {
                        add(SelectionAction(context.getString(R.string.library_play_next), Icons.Default.SkipNext, viewModel::playNextSelection))
                        add(SelectionAction(context.getString(R.string.library_add_queue), Icons.AutoMirrored.Filled.PlaylistAdd, viewModel::appendSelection))
                        add(SelectionAction(context.getString(R.string.library_download_selected), Icons.Default.Download, viewModel::downloadSelection))
                        add(
                            SelectionAction(context.getString(R.string.library_add_playlist), Icons.AutoMirrored.Filled.QueueMusic, {
                                if (playlists.isEmpty()) viewModel.note(context.getString(R.string.library_create_playlist_first)) else pickingPlaylist = true
                            }),
                        )
                    }
                    removalPlan?.let { plan ->
                        add(
                            SelectionAction(context.getString(plan.labelRes), Icons.Default.Delete, {
                                viewModel.deleteDownloadedSelection(removalSelection, plan)
                            }, destructive = true),
                        )
                    }
                },
            )
        } else {
            AppTopBar(
                title = stringResource(R.string.library_work_title),
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
                        if (headerFolder != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.library_batch_bind_subtitles)) },
                                onClick = {
                                    menu = false
                                    batchBindingOpen = true
                                },
                            )
                        }
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
        }
        if (workMissing) {
            EmptyState(
                stringResource(R.string.library_work_not_found),
                modifier = Modifier.fillMaxSize(),
                actionLabel = stringResource(R.string.common_back),
                onAction = onBack,
            )
        } else if (headerFolder == null || facts == null) {
            LoadingState(stringResource(R.string.library_work_loading), Modifier.weight(1f).fillMaxWidth())
        } else {
            com.nanahoshi.audioplayer.ui.components.WorkFileBrowser(
                state = viewModel.workListState(request?.folderId),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                loading = listing == null || (busy && folders.isEmpty() && entries.isEmpty()),
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
                ) { Text(stringResource(R.string.library_download_whole_work)) }
                if (TrackSource.JAPANESE_ASMR in workSources) {
                    OutlinedButton(onClick = { viewModel.refreshJapaneseArticles(headerFolder.id) }) { Text(stringResource(R.string.library_refresh_ja)) }
                }
                if (workFileSource != TrackSource.JAPANESE_ASMR) {
                    OutlinedButton(onClick = { openTree.launch(null) }) { Text(stringResource(R.string.library_link_local_folder)) }
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
            SingleChoiceSegmentedButtonRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                workSources.forEachIndexed { index, source ->
                    SegmentedButton(
                        selected = workFileSource == source,
                        onClick = { viewModel.useWorkSource(source) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = workSources.size),
                    ) { Text(source.workLabel(), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                }
            }
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
                                    track.source == TrackSource.JAPANESE_ASMR ||
                                    (track.source == TrackSource.BILIBILI || track.source == TrackSource.YOUTUBE)
                                ) {
                                    { viewModel.downloadTrack(track) }
                                } else {
                                    null
                                },
                                onDeleteDownloaded = if (
                                    track.source == TrackSource.ASMR ||
                                    track.source == TrackSource.DLSITE ||
                                    track.source == TrackSource.JAPANESE_ASMR ||
                                    (track.source == TrackSource.BILIBILI || track.source == TrackSource.YOUTUBE)
                                ) {
                                    { viewModel.deleteDownloadedTrack(track) }
                                } else {
                                    null
                                },
                                onDelete = null,
                                onBindSubtitle = { bindingTrack = track },
                            )
                        }
                        is LibraryEntry.File -> {
                            val file = entry.file
                            FileRow(
                                modifier = libraryMotionModifier(),
                                file = file,
                                onClick = {
                                    if (viewModel.canInteractWithWorkFile(request, file)) {
                                        if (selecting) viewModel.toggleFile(file.id) else onOpenPreview(file.id)
                                    }
                                },
                                onDelete = null,
                                selecting = selecting,
                                selected = file.id in selectedFiles,
                                onLongClick = {
                                    if (viewModel.canInteractWithWorkFile(request, file)) {
                                        viewModel.beginSelection(fileId = file.id)
                                    }
                                },
                                onBindSubtitle = if (isSubtitleFile(file.name)) ({ bindingSubtitleFile = file }) else null,
                                onDeleteDownloaded = { viewModel.deleteDownloadedFile(file) },
                            )
                        }
                    }
                }
            }
        }
    }

    CachePromptDialog(cachePrompt, viewModel::answerCache, viewModel::cancelCacheWait, onVerifyJaDownload, onVerifyJaRefresh)

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
            message = stringResource(R.string.library_remove_work_message),
            onConfirm = {
                confirmRemoveWork = false
                viewModel.deleteFolder(headerFolder?.id ?: folderId)
                onBack()
            },
            onDismiss = { confirmRemoveWork = false },
        )
    }
    pendingAudioDelete?.let { pending ->
        com.nanahoshi.audioplayer.ui.components.DeleteAudioDialog(
            itemCount = pending.audioCount,
            associationCount = pending.associationCount,
            otherFileCount = pending.otherFileCount,
            workSubtitleCount = pending.workSubtitleCount,
            subtitleCount = pending.generatedSubtitleCount,
            customSubtitleCount = pending.customSubtitleCount,
            onConfirm = viewModel::confirmAudioDelete,
            onCancel = viewModel::cancelAudioDelete,
        )
    }
    bindingTrack?.let { track ->
        TrackSubtitlePickerSheet(
            track = track,
            trackTitle = track.title,
            actions = viewModel.subtitlePickerActions,
            onDismiss = { bindingTrack = null },
        )
    }
    bindingSubtitleFile?.let { file ->
        FileToTrackSubtitleSheet(
            file = file,
            loadTracks = { viewModel.listWorkTracksForSubtitle(file) },
            onBindTrack = { track -> viewModel.bindSubtitleFileToTrack(file, track) },
            onDismiss = { bindingSubtitleFile = null },
            onError = { viewModel.showMessage(com.nanahoshi.audioplayer.R.string.library_operation_failed) },
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
