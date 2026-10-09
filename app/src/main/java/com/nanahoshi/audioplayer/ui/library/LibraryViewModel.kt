package com.nanahoshi.audioplayer.ui.library

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.lazy.LazyListState
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nanahoshi.audioplayer.asmr.AsmrException
import com.nanahoshi.audioplayer.asmr.AsmrPreview
import com.nanahoshi.audioplayer.bilibili.BiliPart
import com.nanahoshi.audioplayer.bilibili.BilibiliException
import com.nanahoshi.audioplayer.data.LibrarySort
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.WorkFill
import com.nanahoshi.audioplayer.data.isSubtitleFile
import com.nanahoshi.audioplayer.data.sortedForLibrary
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.LibraryFileEntity
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.graph
import com.nanahoshi.audioplayer.playback.pickSubtitleName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

enum class LibrarySourceFilter {
    ALL,
    BILIBILI,
    ASMR,
    LOCAL,
}

data class LibraryListing(
    val folders: List<FolderEntity>,
    val entries: List<LibraryEntry>,
    val browsing: Boolean = true,
)

sealed interface LibraryEntry {
    val name: String
    val addedAt: Long

    data class Audio(val track: TrackEntity, val hasSubtitle: Boolean = false) : LibraryEntry {
        override val name: String get() = track.title
        override val addedAt: Long get() = track.addedAt
    }

    data class File(val file: LibraryFileEntity) : LibraryEntry {
        override val name: String get() = file.name
        override val addedAt: Long get() = file.addedAt
    }
}

data class PendingBiliImport(
    val parts: List<BiliPart>,
    val selected: Set<Int>,
    val play: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
    val currentFolderId = MutableStateFlow<Long?>(null)
    private val folderScroll = HashMap<Long?, LazyListState>()
    private val filteredScroll = LazyListState()

    fun listState(folderId: Long?, browsing: Boolean): LazyListState {
        if (!browsing) return filteredScroll
        return folderScroll.getOrPut(folderId) { LazyListState() }
    }
    val currentFolder = currentFolderId.flatMapLatest { id ->
        if (id == null) kotlinx.coroutines.flow.flowOf(null) else graph.library.observeFolder(id)
    }
    val sort = graph.librarySort.sort
    val playlists = graph.library.observePlaylists()
    val sourceFilter = MutableStateFlow(LibrarySourceFilter.ALL)
    val searchQuery = MutableStateFlow("")
    val pendingAsmr = MutableStateFlow<AsmrPreview?>(null)
    private var rjLookup: Job? = null
    private var openJob: Job? = null
    private var openGeneration = 0
    val listing = combine(currentFolderId, sourceFilter, searchQuery, sort) { folderId, filter, query, sortMode ->
        LibraryRequest(folderId, filter, query.trim(), sortMode)
    }.flatMapLatest { request ->
        when {
            request.query.isNotEmpty() -> acrossLibrary { folders, tracks, files ->
                val found = searchListing(request.query, request.sort, folders, tracks, files)
                if (request.filter != LibrarySourceFilter.LOCAL) {
                    found
                } else {
                    found.copy(
                        folders = found.folders.filter { it.asmrSourceId == null && it.asmrWorkId == null },
                        entries = found.entries.filter { entry ->
                            entry !is LibraryEntry.Audio || entry.track.source == TrackSource.LOCAL
                        },
                    )
                }
            }
            request.filter == LibrarySourceFilter.LOCAL -> combine(
                graph.library.observeChildFolders(request.folderId),
                graph.library.observeTracksIn(request.folderId),
                graph.library.observeFilesIn(request.folderId),
            ) { folders, tracks, files ->
                LibraryListing(
                    folders = folders
                        .filter { it.asmrSourceId == null && it.asmrWorkId == null }
                        .sortedForLibrary(request.sort),
                    entries = mixedEntries(
                        tracks.filter { it.source == TrackSource.LOCAL },
                        emptyList(),
                        request.sort,
                        files,
                    ),
                )
            }
            request.filter != LibrarySourceFilter.ALL -> acrossLibrary { folders, tracks, _ ->
                filteredListing(request.filter, request.sort, folders, tracks)
            }
            else -> combine(
                graph.library.observeChildFolders(request.folderId),
                graph.library.observeTracksIn(request.folderId),
                graph.library.observeFilesIn(request.folderId),
            ) { folders, tracks, files ->
                LibraryListing(folders.sortedForLibrary(request.sort), mixedEntries(tracks, files, request.sort))
            }
        }
    }
    val busy = MutableStateFlow(false)
    val pendingBili = MutableStateFlow<PendingBiliImport?>(null)
    val selectedTrackIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedFolderIds = MutableStateFlow<Set<Long>>(emptySet())
    private val metaRequested = mutableSetOf<Long>()

    init {
        viewModelScope.launch {
            listing.collect { current ->
                current.folders.forEach { folder ->
                    if (folder.asmrSourceId == null || !folder.workMeta.isNullOrBlank()) return@forEach
                    if (!metaRequested.add(folder.id)) return@forEach
                    viewModelScope.launch {
                        try {
                            graph.library.ensureWorkMeta(folder.id)
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }
    }

    fun openFolder(folder: FolderEntity) {
        folderScroll[folder.id] = LazyListState()
        if (sourceFilter.value != LibrarySourceFilter.LOCAL) {
            sourceFilter.value = LibrarySourceFilter.ALL
        }
        searchQuery.value = ""
        pendingAsmr.value = null
        rjLookup?.cancel()
        currentFolderId.value = folder.id
        clearSelection()
        openJob?.cancel()
        openGeneration += 1
        val generation = openGeneration
        val work = !folder.asmrSourceId.isNullOrBlank()
        openJob = viewModelScope.launch {
            if (work) busy.value = true
            try {
                graph.library.ensureWorkMeta(folder.id)
                if (work && graph.library.ensureWorkFiles(folder.id) == WorkFill.EMPTY) {
                    graph.messages.value = "没有读到这个作品的文件"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.messages.value = failure.message ?: "没有读到这个作品的文件"
            } finally {
                if (generation == openGeneration) busy.value = false
            }
        }
    }

    fun setSourceFilter(filter: LibrarySourceFilter) {
        searchQuery.value = ""
        pendingAsmr.value = null
        rjLookup?.cancel()
        sourceFilter.value = filter
        clearSelection()
        if (filter == LibrarySourceFilter.LOCAL) leaveOnlineWork()
    }

    private fun leaveOnlineWork() {
        viewModelScope.launch {
            var id = currentFolderId.value
            while (id != null) {
                val folder = graph.library.folder(id) ?: break
                if (folder.asmrSourceId == null && folder.asmrWorkId == null) break
                id = folder.parentId
            }
            if (currentFolderId.value != id) currentFolderId.value = id
        }
    }

    fun updateSearch(text: String) {
        searchQuery.value = text
        pendingAsmr.value = null
        rjLookup?.cancel()
        clearSelection()
        val digits = text.trim()
        if (!RJ_QUERY.matches(digits)) return
        rjLookup = viewModelScope.launch {
            delay(350)
            if (searchQuery.value.trim() != digits) return@launch
            if (graph.library.allFolders().any { it.matchesRj(digits) }) return@launch
            val preview = try {
                graph.asmr.preview(digits)
            } catch (_: Exception) {
                null
            } ?: return@launch
            if (searchQuery.value.trim() != digits) return@launch
            if (graph.library.allFolders().any { it.matchesRj(digits) }) return@launch
            pendingAsmr.value = preview
        }
    }

    fun confirmAsmrPreview() {
        val preview = pendingAsmr.value ?: return
        pendingAsmr.value = null
        launch {
            graph.library.importAsmr(preview.sourceId, currentFolderId.value)
            graph.messages.value = "已导入作品"
        }
    }

    fun dismissAsmrPreview() {
        pendingAsmr.value = null
    }

    fun up() {
        val id = currentFolderId.value ?: return
        viewModelScope.launch {
            currentFolderId.value = graph.library.folder(id)?.parentId
            clearSelection()
        }
    }

    fun importFile(uri: Uri, play: Boolean) = launch {
        val track = graph.library.importFile(uri, currentFolderId.value)
        if (play) graph.player.playTrack(track) else graph.messages.value = "已导入"
    }

    fun importFolder(uri: Uri, keepStructure: Boolean) = launch {
        val count = graph.library.importTree(uri, currentFolderId.value, keepStructure)
        graph.messages.value = if (count == 0) "这个文件夹里没有音频" else "已导入 $count 首"
    }

    fun createFolder(name: String) = launch {
        graph.library.createFolder(currentFolderId.value, name)
    }

    fun importRj(input: String) = launch {
        graph.library.importAsmr(input, currentFolderId.value)
        graph.messages.value = "已导入作品"
    }

    fun downloadTrack(track: TrackEntity) = launch {
        val count = graph.downloads.enqueue(listOf(track))
        graph.messages.value = if (count == 0) "已经在本地或正在下载" else "已加入下载"
    }

    fun downloadSelection() = launch {
        val tracks = selectedTracks()
        clearSelection()
        val count = graph.downloads.enqueue(tracks)
        graph.messages.value = if (count == 0) "没有需要下载的音频" else "已加入 $count 个下载"
    }

    fun cacheFolder(folderId: Long) = launch {
        val count = graph.downloads.enqueue(graph.library.asmrTracksInTree(folderId))
        graph.messages.value = if (count == 0) "没有需要下载的音频" else "已加入 $count 个下载"
    }

    fun addSelectionToPlaylist(playlistId: Long) = launch {
        val tracks = selectedTracks()
        clearSelection()
        if (tracks.isEmpty()) {
            graph.messages.value = "没有可加入的音频"
            return@launch
        }
        graph.library.addTracksToPlaylist(playlistId, tracks.map { it.id })
        graph.messages.value = "已加入播放列表"
    }

    fun toggleSort() {
        graph.librarySort.toggle()
    }

    fun note(text: String) {
        graph.messages.value = text
    }

    fun deleteFile(id: Long) = launch {
        graph.library.deleteFile(id)
    }

    fun lookupBvid(input: String, play: Boolean) = launch {
        val parts = graph.library.lookupBvid(input)
        if (parts.size == 1) {
            finishBili(parts, play)
        } else {
            pendingBili.value = PendingBiliImport(parts, parts.indices.toSet(), play)
        }
    }

    fun togglePart(index: Int) {
        val current = pendingBili.value ?: return
        val selected = current.selected.toMutableSet()
        if (!selected.add(index)) selected.remove(index)
        pendingBili.value = current.copy(selected = selected)
    }

    fun confirmBili() {
        val current = pendingBili.value ?: return
        val chosen = current.parts.filterIndexed { index, _ -> index in current.selected }
        pendingBili.value = null
        if (chosen.isEmpty()) {
            graph.messages.value = "请至少选择一段"
            return
        }
        launch { finishBili(chosen, current.play) }
    }

    fun dismissBili() {
        pendingBili.value = null
    }

    fun deleteTrack(id: Long) {
        graph.player.deleteTrack(id)
    }

    fun deleteFolder(id: Long) {
        graph.player.removeFromLibrary(emptyList(), listOf(id))
    }

    fun deleteSelection() {
        val tracks = selectedTrackIds.value.toList()
        val folders = selectedFolderIds.value.toList()
        clearSelection()
        graph.player.removeFromLibrary(tracks, folders)
    }

    fun beginSelection(trackId: Long? = null, folderId: Long? = null) {
        if (selectedTrackIds.value.isEmpty() && selectedFolderIds.value.isEmpty()) {
            selectedTrackIds.value = listOfNotNull(trackId).toSet()
            selectedFolderIds.value = listOfNotNull(folderId).toSet()
        } else if (trackId != null) {
            toggleTrack(trackId)
        } else if (folderId != null) {
            toggleFolder(folderId)
        }
    }

    fun toggleTrack(id: Long) {
        val current = selectedTrackIds.value
        selectedTrackIds.value = if (id in current) current - id else current + id
    }

    fun toggleFolder(id: Long) {
        val current = selectedFolderIds.value
        selectedFolderIds.value = if (id in current) current - id else current + id
    }

    fun clearSelection() {
        selectedTrackIds.value = emptySet()
        selectedFolderIds.value = emptySet()
    }

    fun playNextSelection() = launch {
        val tracks = selectedTracks()
        clearSelection()
        if (tracks.isEmpty()) {
            graph.messages.value = "没有可加入的音频"
            return@launch
        }
        graph.player.playNext(tracks)
    }

    fun appendSelection() = launch {
        val tracks = selectedTracks()
        clearSelection()
        if (tracks.isEmpty()) {
            graph.messages.value = "没有可加入的音频"
            return@launch
        }
        graph.player.append(tracks)
    }

    fun play(track: TrackEntity) {
        viewModelScope.launch {
            val folderId = currentFolderId.value
            if (folderId == null) {
                graph.player.playTrack(track)
                return@launch
            }
            val tracks = graph.library.tracksIn(folderId).sortedForLibrary(graph.librarySort.sort.value)
            val index = tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            graph.player.replaceAndPlay(tracks.ifEmpty { listOf(track) }, index)
        }
    }

    fun playNext(track: TrackEntity) {
        graph.player.playNext(listOf(track))
    }

    fun append(track: TrackEntity) {
        graph.player.append(listOf(track))
    }

    private suspend fun finishBili(parts: List<BiliPart>, play: Boolean) {
        val tracks = graph.library.importParts(parts, currentFolderId.value)
        if (play) {
            graph.player.playTracks(tracks)
        } else {
            graph.messages.value = "已导入 ${tracks.size} 首"
        }
    }

    private suspend fun selectedTracks(): List<TrackEntity> =
        graph.library.orderedSelection(
            currentFolderId.value,
            selectedFolderIds.value,
            selectedTrackIds.value,
            graph.librarySort.sort.value,
        )

    private fun acrossLibrary(
        block: (List<FolderEntity>, List<TrackEntity>, List<LibraryFileEntity>) -> LibraryListing,
    ) = combine(
        graph.library.observeAllFolders(),
        graph.library.observeTracks(),
        graph.library.observeAllFiles(),
        block,
    )

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy.value = true
            try {
                block()
            } catch (error: BilibiliException) {
                graph.messages.value = error.message
            } catch (error: AsmrException) {
                graph.messages.value = error.message
            } catch (error: Exception) {
                graph.messages.value = error.message ?: "操作失败"
            } finally {
                busy.value = false
            }
        }
    }
}

private data class LibraryRequest(
    val folderId: Long?,
    val filter: LibrarySourceFilter,
    val query: String,
    val sort: LibrarySort,
)

private val RJ_QUERY = Regex("\\d{1,7}")

private fun searchListing(
    query: String,
    sort: LibrarySort,
    folders: List<FolderEntity>,
    tracks: List<TrackEntity>,
    files: List<LibraryFileEntity>,
): LibraryListing {
    val rjFolders = if (RJ_QUERY.matches(query)) {
        folders.filter { it.matchesRj(query) }.sortedForLibrary(sort)
    } else {
        emptyList()
    }
    val rjIds = rjFolders.map { it.id }.toSet()
    val namedFolders = folders
        .filter { it.id !in rjIds && it.name.contains(query, ignoreCase = true) }
        .sortedForLibrary(sort)
    val matchedTracks = tracks.filter { it.title.contains(query, ignoreCase = true) }
    val matchedFiles = files.filter { it.name.contains(query, ignoreCase = true) && !isSubtitleFile(it.name) }
    return LibraryListing(
        folders = rjFolders + namedFolders,
        entries = mixedEntries(matchedTracks, matchedFiles, sort, files),
        browsing = false,
    )
}

private fun filteredListing(
    filter: LibrarySourceFilter,
    sort: LibrarySort,
    folders: List<FolderEntity>,
    tracks: List<TrackEntity>,
): LibraryListing = when (filter) {
    LibrarySourceFilter.BILIBILI -> LibraryListing(
        folders = emptyList(),
        entries = mixedEntries(tracks.filter { it.source == TrackSource.BILIBILI }, emptyList(), sort),
        browsing = false,
    )
    LibrarySourceFilter.ASMR -> LibraryListing(
        folders = folders.filter { it.asmrSourceId != null }.sortedForLibrary(sort),
        entries = emptyList(),
        browsing = false,
    )
    LibrarySourceFilter.LOCAL,
    LibrarySourceFilter.ALL,
    -> LibraryListing(emptyList(), emptyList(), browsing = false)
}

internal fun mixedEntries(
    tracks: List<TrackEntity>,
    files: List<LibraryFileEntity>,
    sort: LibrarySort,
    subtitlePool: List<LibraryFileEntity> = files,
): List<LibraryEntry> {
    val visibleFiles = files.filter { !isSubtitleFile(it.name) }
    val rows = buildList {
        tracks.forEach { add(LibraryEntry.Audio(it, hasSubtitle(it, subtitlePool))) }
        visibleFiles.forEach { add(LibraryEntry.File(it)) }
    }
    return when (sort) {
        LibrarySort.NAME -> rows.sortedBy { it.name.lowercase() }
        LibrarySort.ADDED -> rows.sortedByDescending { it.addedAt }
    }
}

private fun hasSubtitle(track: TrackEntity, files: List<LibraryFileEntity>): Boolean {
    val names = files.filter { it.folderId == track.folderId }.map { it.name }
    return pickSubtitleName(track.title, names) != null
}

private fun FolderEntity.matchesRj(digits: String): Boolean {
    val sourceId = asmrSourceId ?: return false
    return rjNumber(sourceId) == rjNumber(digits)
}

private fun rjNumber(raw: String): String =
    raw.uppercase().removePrefix("RJ").removePrefix("VJ").filter { it.isDigit() }.trimStart('0').ifEmpty { "0" }
