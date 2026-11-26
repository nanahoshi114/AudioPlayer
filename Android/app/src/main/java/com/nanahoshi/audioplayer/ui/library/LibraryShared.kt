package com.nanahoshi.audioplayer.ui.library

import android.content.Context
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.runtime.Composable
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.data.LocalRemovalPlan
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.LibraryFileEntity
import com.nanahoshi.audioplayer.data.db.PlaylistEntity
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.data.isSubtitleFile
import com.nanahoshi.audioplayer.ui.components.DeleteAudioDialog
import com.nanahoshi.audioplayer.ui.components.FileToTrackSubtitleSheet
import com.nanahoshi.audioplayer.ui.components.SelectionAction
import com.nanahoshi.audioplayer.ui.components.TrackSubtitlePickerSheet

private val downloadableTrackSources = setOf(
    TrackSource.ASMR,
    TrackSource.DLSITE,
    TrackSource.JAPANESE_ASMR,
    TrackSource.BILIBILI,
    TrackSource.YOUTUBE,
)

internal fun LazyListScope.libraryEntries(
    entries: List<LibraryEntry>,
    selectedTracks: Set<Long>,
    selectedFiles: Set<Long>,
    selecting: Boolean,
    onTrackClick: (TrackEntity) -> Unit,
    onTrackLongClick: (TrackEntity) -> Unit,
    onTrackAppend: (TrackEntity) -> Unit,
    onTrackPlayNext: (TrackEntity) -> Unit,
    onTrackDownload: (TrackEntity) -> Unit,
    onTrackDeleteDownloaded: (TrackEntity) -> Unit,
    onTrackDelete: ((TrackEntity) -> Unit)?,
    onTrackBindSubtitle: (TrackEntity) -> Unit,
    onFileClick: (LibraryFileEntity) -> Unit,
    onFileLongClick: (LibraryFileEntity) -> Unit,
    onFileDelete: ((LibraryFileEntity) -> Unit)?,
    onFileBindSubtitle: (LibraryFileEntity) -> Unit,
    onFileDeleteDownloaded: (LibraryFileEntity) -> Unit,
    canRemoveEntry: (Long?) -> Boolean = { true },
) {
    items(
        entries,
        key = { entry ->
            when (entry) {
                is LibraryEntry.Audio -> "track-${entry.track.id}"
                is LibraryEntry.File -> "file-${entry.file.id}"
            }
        },
    ) { entry ->
        when (entry) {
            is LibraryEntry.Audio -> {
                val track = entry.track
                val downloadable = track.source in downloadableTrackSources
                TrackRow(
                    modifier = libraryMotionModifier(),
                    track = track,
                    hasSubtitle = entry.hasSubtitle,
                    selected = track.id in selectedTracks,
                    selecting = selecting,
                    onClick = { onTrackClick(track) },
                    onLongClick = { onTrackLongClick(track) },
                    onAppend = { onTrackAppend(track) },
                    onPlayNext = { onTrackPlayNext(track) },
                    onDownload = if (downloadable) ({ onTrackDownload(track) }) else null,
                    onDeleteDownloaded = if (downloadable) ({ onTrackDeleteDownloaded(track) }) else null,
                    onDelete = onTrackDelete?.takeIf { canRemoveEntry(track.folderId) }?.let { delete -> { delete(track) } },
                    onBindSubtitle = { onTrackBindSubtitle(track) },
                )
            }
            is LibraryEntry.File -> {
                val file = entry.file
                FileRow(
                    modifier = libraryMotionModifier(),
                    file = file,
                    onClick = { onFileClick(file) },
                    onDelete = onFileDelete?.takeIf { canRemoveEntry(file.folderId) }?.let { delete -> { delete(file) } },
                    selecting = selecting,
                    selected = file.id in selectedFiles,
                    onLongClick = { onFileLongClick(file) },
                    onBindSubtitle = if (isSubtitleFile(file.name)) ({ onFileBindSubtitle(file) }) else null,
                    onDeleteDownloaded = { onFileDeleteDownloaded(file) },
                )
            }
        }
    }
}

internal fun buildLibrarySelectionActions(
    context: Context,
    includeAudioActions: Boolean,
    playlists: List<PlaylistEntity>,
    removalPlan: LocalRemovalPlan?,
    canRemoveFromLibrary: Boolean = false,
    onPlayNext: () -> Unit,
    onAppend: () -> Unit,
    onDownload: () -> Unit,
    onPickPlaylist: () -> Unit,
    onNoPlaylists: () -> Unit,
    onDeleteDownloaded: (LocalRemovalPlan) -> Unit,
    onRemoveFromLibrary: () -> Unit = {},
): List<SelectionAction> = buildList {
    if (includeAudioActions) {
        add(SelectionAction(context.getString(R.string.library_play_next), Icons.Default.SkipNext, onPlayNext))
        add(SelectionAction(context.getString(R.string.library_add_queue), Icons.AutoMirrored.Filled.PlaylistAdd, onAppend))
        add(SelectionAction(context.getString(R.string.library_download_selected), Icons.Default.Download, onDownload))
        add(
            SelectionAction(
                context.getString(R.string.library_add_playlist),
                Icons.AutoMirrored.Filled.QueueMusic,
                {
                    if (playlists.isEmpty()) onNoPlaylists() else onPickPlaylist()
                },
            ),
        )
    }
    if (removalPlan != null) {
        add(
            SelectionAction(
                context.getString(removalPlan.labelRes),
                Icons.Default.Delete,
                { onDeleteDownloaded(removalPlan) },
                destructive = true,
            ),
        )
    }
    if (canRemoveFromLibrary) {
        add(
            SelectionAction(
                context.getString(R.string.library_remove_from_library),
                Icons.Default.Delete,
                onRemoveFromLibrary,
                destructive = true,
            ),
        )
    }
}

@Composable
internal fun LibrarySharedDialogs(
    viewModel: LibraryViewModel,
    cachePrompt: CachePrompt?,
    pickingPlaylist: Boolean,
    playlists: List<PlaylistEntity>,
    pendingAudioDelete: LibraryViewModel.PendingAudioDelete?,
    bindingTrack: TrackEntity?,
    bindingSubtitleFile: LibraryFileEntity?,
    onDismissPlaylist: () -> Unit,
    onDismissBindingTrack: () -> Unit,
    onDismissBindingFile: () -> Unit,
    onVerifyJaDownload: (String) -> Unit,
    onVerifyJaRefresh: (String) -> Unit = {},
) {
    CachePromptDialog(
        prompt = cachePrompt,
        onAnswer = viewModel::answerCache,
        onCancelWaiting = viewModel::cancelCacheWait,
        onVerifyJaDownload = onVerifyJaDownload,
        onVerifyJaRefresh = onVerifyJaRefresh,
    )

    if (pickingPlaylist) {
        PlaylistPickDialog(
            playlists = playlists,
            onPick = { id ->
                onDismissPlaylist()
                viewModel.addSelectionToPlaylist(id)
            },
            onDismiss = onDismissPlaylist,
        )
    }

    pendingAudioDelete?.let { pending ->
        DeleteAudioDialog(
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
            onDismiss = onDismissBindingTrack,
        )
    }

    bindingSubtitleFile?.let { file ->
        FileToTrackSubtitleSheet(
            file = file,
            loadTracks = { viewModel.listWorkTracksForSubtitle(file) },
            onBindTrack = { track -> viewModel.bindSubtitleFileToTrack(file, track) },
            onDismiss = onDismissBindingFile,
            onError = { viewModel.showMessage(R.string.library_operation_failed) },
        )
    }
}
