package com.nanahoshi.audioplayer.ui.library

import com.nanahoshi.audioplayer.AppGraph
import com.nanahoshi.audioplayer.data.LibraryRepository
import com.nanahoshi.audioplayer.data.LibrarySort
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.isSubtitleFile
import com.nanahoshi.audioplayer.data.resolveLibraryFileSource
import com.nanahoshi.audioplayer.data.sortedForLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Each visit owns its results, including when the same directory is entered again. */
internal class WorkFolderRequest(val pathIds: List<Long>, val source: TrackSource?) {
    val folderId: Long get() = pathIds.last()
}

internal data class LoadedWorkFolder(val request: WorkFolderRequest, val listing: LibraryListing)

internal data class WorkFolderLocation(
    val path: List<FolderEntity>,
    val request: WorkFolderRequest = WorkFolderRequest(path.map { it.id }, null),
) {
    fun enter(folder: FolderEntity): WorkFolderLocation =
        if (folder.parentId == request.folderId) move(path + folder) else this

    fun ancestor(folderId: Long): WorkFolderLocation {
        val index = path.indexOfFirst { it.id == folderId }
        return if (index in 0 until path.lastIndex) move(path.take(index + 1)) else this
    }

    fun move(path: List<FolderEntity>, source: TrackSource? = request.source): WorkFolderLocation {
        val ids = path.map { it.id }
        val next = if (request.pathIds == ids && request.source == source) request
        else WorkFolderRequest(ids, source)
        return WorkFolderLocation(path, next)
    }
}

internal fun LibraryRepository.observeWorkPath(request: WorkFolderRequest) =
    observeVisibleWorkPath(request.pathIds, request.source).map { request to it }

internal fun AppGraph.observeWorkSubtitleTrackIds(
    folderId: Long,
    source: TrackSource?,
): Flow<Set<Long>> = combine(
    db.invalidationTracker.createFlow(
        "folders",
        "tracks",
        "track_subtitles",
        "library_files",
        "whisper_jobs",
        "dlsite_original_entries",
        "japaneseasmr_download_entries",
    ),
    subtitleDisplay.crossSourceFallback,
    subtitles.revision,
) { _, _, _ ->
    subtitleBindings.resolveWorkSubtitleTrackIds(folderId, source)
}.flowOn(Dispatchers.IO).distinctUntilChanged()

internal fun LibraryRepository.observeFolderListing(
    folderId: Long?,
    sort: LibrarySort,
    source: TrackSource?,
    showSubtitleFiles: Boolean = false,
    subtitleTrackIds: Flow<Set<Long>?> = flowOf(null),
) = combine(
    observeChildFolders(folderId, source),
    observeTracksIn(folderId),
    observeFilesIn(folderId),
    observeJaFileIds(),
    subtitleTrackIds,
) { folders, tracks, files, jaFileIds, resolvedSubtitleTrackIds ->
    val shownTracks = if (source == null) tracks else tracks.filter { it.source == source }
    val shownFiles = if (source == null) files else files.filter { resolveLibraryFileSource(it, jaFileIds) == source }
    val hiddenSubtitleCount = if (showSubtitleFiles) 0 else shownFiles.count { isSubtitleFile(it.name) }
    LibraryListing(
        folders = folders.sortedForLibrary(sort),
        entries = mixedEntries(
            tracks = shownTracks,
            files = shownFiles,
            sort = sort,
            subtitlePool = shownFiles,
            showSubtitleFiles = showSubtitleFiles,
            subtitleTrackIds = resolvedSubtitleTrackIds,
        ),
        hiddenSubtitleCount = hiddenSubtitleCount,
    )
}
