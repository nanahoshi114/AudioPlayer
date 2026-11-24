package com.nanahoshi.audioplayer.ui.library

import com.nanahoshi.audioplayer.text.resolveUserError
import com.nanahoshi.audioplayer.asmr.AsmrErrorKind
import com.nanahoshi.audioplayer.text.resolveNoUrl
import androidx.annotation.StringRes
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.resolve

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.lazy.LazyListState
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nanahoshi.audioplayer.asmr.AsmrException
import com.nanahoshi.audioplayer.asmr.AsmrPreview
import com.nanahoshi.audioplayer.asmr.WorkFacts
import com.nanahoshi.audioplayer.bilibili.BilibiliException
import com.nanahoshi.audioplayer.data.BatchExternalPair
import com.nanahoshi.audioplayer.data.BindExternalResult
import com.nanahoshi.audioplayer.data.CacheCandidate
import com.nanahoshi.audioplayer.data.DeleteResult
import com.nanahoshi.audioplayer.data.DlsiteDownloadMode
import com.nanahoshi.audioplayer.data.FormatConflict
import com.nanahoshi.audioplayer.data.LibrarySort
import com.nanahoshi.audioplayer.data.LocalAudio
import com.nanahoshi.audioplayer.data.LocalRemoval
import com.nanahoshi.audioplayer.data.LocalRemovalPlan
import com.nanahoshi.audioplayer.data.canonicalPath
import com.nanahoshi.audioplayer.data.findFormatConflicts
import com.nanahoshi.audioplayer.data.idsToDrop
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.WorkFill
import com.nanahoshi.audioplayer.data.isSubtitleFile
import com.nanahoshi.audioplayer.data.loadWorkSubtitleCatalog
import com.nanahoshi.audioplayer.data.localRemoval
import com.nanahoshi.audioplayer.data.resolveLibraryFileSource
import com.nanahoshi.audioplayer.data.sortedForLibrary
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.LibraryFileEntity
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.data.db.TrackSubtitleBindingEntity
import com.nanahoshi.audioplayer.graph
import com.nanahoshi.audioplayer.playback.SubtitleCandidateEntry
import com.nanahoshi.audioplayer.playback.WorkSubtitleCandidate
import com.nanahoshi.audioplayer.playback.nearestSubtitleWorkRoot
import com.nanahoshi.audioplayer.playback.pickSubtitleName
import com.nanahoshi.audioplayer.ui.components.SubtitlePickerActions
import com.nanahoshi.audioplayer.ui.components.subtitlePickerActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LibrarySourceFilter {
    ALL,
    BILIBILI,
    YOUTUBE,
    ASMR,
    LOCAL,
}

data class LibraryListing(
    val folders: List<FolderEntity>,
    val entries: List<LibraryEntry>,
    val browsing: Boolean = true,
    val hiddenSubtitleCount: Int = 0,
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

sealed interface CachePrompt {
    data class Source(val sources: List<TrackSource>) : CachePrompt
    data class JapanesePosts(
        val posts: List<com.nanahoshi.audioplayer.japaneseasmr.JaPostRef>,
        val refresh: Boolean = false,
    ) : CachePrompt
    data class JapaneseChoice(val title: String, val message: String, val options: List<Pair<Any, String>>) : CachePrompt
    data class JapaneseVerify(val token: String, val articleTitle: String? = null, val buzzheavier: Boolean = false) : CachePrompt
    class JapaneseWaiting(val title: String) : CachePrompt
    data class Format(val conflict: FormatConflict) : CachePrompt
}

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
    private val appContext = app.applicationContext
    private fun text(@StringRes id: Int, vararg args: Any): String = appContext.getString(id, *args)
    val removalPolicy = graph.library.observeAllFolders().map {
        com.nanahoshi.audioplayer.data.LibraryRemovalPolicy(it)
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), null)
    val currentFolderId = MutableStateFlow<Long?>(null)
    private val listStates = mutableMapOf<LibraryListKey, LazyListState>()

    internal fun listState(key: LibraryListKey): LazyListState =
        listStates.getOrPut(key) { LazyListState() }

    internal fun workListState(folderId: Long?): LazyListState =
        listState(LibraryListKey(folderId, LibrarySourceFilter.ALL, isSearching = false))

    private fun resetFolderScroll(folderId: Long) {
        listStates.remove(LibraryListKey(folderId, LibrarySourceFilter.ALL, isSearching = false))
    }

    val currentFolder = currentFolderId.flatMapLatest { id ->
        if (id == null) kotlinx.coroutines.flow.flowOf(null) else graph.library.observeFolder(id)
    }
    val sort = graph.librarySort.sort
    val playlists = graph.library.observePlaylists()
    val sourceFilter = MutableStateFlow(LibrarySourceFilter.ALL)
    val searchQuery = MutableStateFlow("")
    val workFileSource = MutableStateFlow<TrackSource?>(null)
    val workSources = MutableStateFlow<List<TrackSource>>(emptyList())
    val workRootId = MutableStateFlow<Long?>(null)
    internal val workLocation = MutableStateFlow<WorkFolderLocation?>(null)
    internal val workMissing = MutableStateFlow(false)
    private var openedWorkId: Long? = null
    val showSubtitleFiles = graph.librarySort.showSubtitleFiles
    private var workPathJob: Job? = null
    internal val workListing = combine(
        workLocation.map { it?.request }.distinctUntilChanged(),
        showSubtitleFiles,
    ) { request, showSubtitles -> request to showSubtitles }
        .flatMapLatest { (request, showSubtitles) ->
            if (request == null) emptyFlow()
            else graph.library.observeFolderListing(
                folderId = request.folderId,
                sort = LibrarySort.NAME,
                source = request.source,
                showSubtitleFiles = showSubtitles,
                subtitleTrackIds = graph.observeWorkSubtitleTrackIds(request.folderId, request.source),
            ).map { LoadedWorkFolder(request, it) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    private val listingSort = combine(sort, workRootId) { global, root ->
        effectiveLibrarySort(global, root)
    }
    val pendingAsmr = MutableStateFlow<AsmrPreview?>(null)
    private var rjLookup: Job? = null
    private var openJob: Job? = null
    private var sourceCheckJob: Job? = null
    private var openGeneration = 0
    internal val listing = combine(
        currentFolderId,
        sourceFilter,
        searchQuery,
        listingSort,
        workFileSource,
    ) { folderId, filter, query, sortMode, workSource ->
        LibraryRequest(libraryListKey(folderId, filter, query), query.trim(), sortMode, workSource)
    }.flatMapLatest { request ->
        val (folderId, filter, isSearching) = request.listKey
        when {
            isSearching -> acrossLibrary { folders, tracks, files ->
                val found = searchListing(request.query, request.sort, folders, tracks, files, folderId)
                applySearchSourceFilter(found, filter)
            }
            filter == LibrarySourceFilter.LOCAL -> combine(
                graph.library.observeChildFolders(folderId),
                graph.library.observeTracksIn(folderId),
                graph.library.observeFilesIn(folderId),
            ) { folders, tracks, files ->
                LibraryListing(
                    folders = folders
                        .filter { it.isOrdinaryFolder() }
                        .sortedForLibrary(request.sort),
                    entries = mixedEntries(
                        tracks.filter { it.source == TrackSource.LOCAL },
                        emptyList(),
                        request.sort,
                        files,
                    ),
                )
            }
            filter != LibrarySourceFilter.ALL -> acrossLibrary { folders, tracks, _ ->
                filteredListing(filter, request.sort, folders, tracks)
            }
            else -> graph.library.observeFolderListing(folderId, request.sort, request.workSource)
        }.map { LoadedLibraryListing(request.listKey, it) }
    }
    val biliAuthors = graph.library.observeTracks().map { tracks ->
        tracks.filter { it.source == TrackSource.BILIBILI && it.folderId != null }
            .associate { it.folderId!! to it.artist.orEmpty() }
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyMap())

    private val busyCounter = BusyCounter()
    val busy get() = busyCounter.busy
    val selectedTrackIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedFolderIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedFileIds = MutableStateFlow<Set<Long>>(emptySet())
    private val metaRequested = mutableSetOf<Long>()

    fun toggleSubtitleFiles() {
        val next = !showSubtitleFiles.value
        graph.librarySort.setShowSubtitleFiles(next)
        if (!next) {
            val visibleSubtitleIds = workListing.value?.listing?.entries.orEmpty()
                .mapNotNullTo(mutableSetOf()) { entry ->
                    (entry as? LibraryEntry.File)?.file?.takeIf { isSubtitleFile(it.name) }?.id
                }
            selectedFileIds.value = selectedFileIds.value - visibleSubtitleIds
        }
    }

    init {
        viewModelScope.launch {
            combine(workLocation, showSubtitleFiles, selectedFileIds) { location, show, ids ->
                if (location != null && !show) ids else emptySet()
            }.distinctUntilChanged().flatMapLatest { ids ->
                if (ids.isNotEmpty()) graph.library.observeAllFiles() else emptyFlow()
            }.collect { files ->
                if (workLocation.value != null && !showSubtitleFiles.value) {
                    val selected = selectedFileIds.value
                    val hidden = files.filter { it.id in selected && isSubtitleFile(it.name) }.map { it.id }
                    selectedFileIds.value = selected - hidden
                }
            }
        }
        viewModelScope.launch {
            combine(currentFolderId, currentFolder) { id, folder -> id to folder }.collect { (id, folder) ->
                if (workLocation.value != null) return@collect
                if (id == null) {
                    clearWorkSource()
                    return@collect
                }
                if (folder == null || folder.id != id) return@collect
                val inside = folder.asmrSourceId != null || folder.asmrWorkId != null
                if (!inside) clearWorkSource()
            }
        }
        viewModelScope.launch {
            val listings = workLocation.map { it == null }.distinctUntilChanged()
                .flatMapLatest { ordinary -> if (ordinary) listing else emptyFlow() }
            listings.collect { current ->
                current.listing.folders.forEach { folder ->
                    if (folder.asmrSourceId == null || !folder.workMeta.isNullOrBlank()) return@forEach
                    if (!metaRequested.add(folder.id)) return@forEach
                    viewModelScope.launch {
                        try {
                            graph.library.ensureWorkMeta(folder.id)
                        } catch (cancelled: CancellationException) {
                            metaRequested.remove(folder.id)
                            throw cancelled
                        } catch (_: Exception) {
                            // 失败后放回集合，下次列表刷新还能重试，否则作品标签会永久缺失。
                            metaRequested.remove(folder.id)
                        }
                    }
                }
            }
        }
    }

    fun openWork(folderId: Long) {
        if (openedWorkId == folderId) return
        openedWorkId = folderId
        workPathJob?.cancel()
        workMissing.value = false
        workPathJob = viewModelScope.launch {
            val folder = graph.library.folder(folderId)
            if (folder == null) {
                markWorkMissing()
                return@launch
            }
            workLocation.value = WorkFolderLocation(listOf(folder))
            openFolder(folder)
            workLocation.map { it?.request }.distinctUntilChanged()
                .flatMapLatest { request ->
                    if (request == null) emptyFlow() else graph.library.observeWorkPath(request)
                }.collect { (request, path) ->
                    val location = workLocation.value ?: return@collect
                    if (location.request !== request) return@collect
                    if (path.isEmpty()) markWorkMissing()
                    else updateWorkLocation(location.move(path))
                }
        }
    }

    private fun markWorkMissing() {
        openJob?.cancel()
        openGeneration += 1
        workMissing.value = true
        workLocation.value = null
        currentFolderId.value = null
        clearSelection()
        clearWorkSource()
    }

    private fun updateWorkLocation(location: WorkFolderLocation) {
        if (workLocation.value?.request !== location.request) {
            currentFolderId.value = location.request.folderId
            workFileSource.value = location.request.source
            clearSelection()
        }
        workLocation.value = location
    }

    internal fun openWorkFolder(request: WorkFolderRequest?, folder: FolderEntity) {
        val location = workLocation.value ?: return
        if (location.request !== request) return
        val next = location.enter(folder)
        if (next === location) return
        resetFolderScroll(folder.id)
        updateWorkLocation(next)
    }

    internal fun goToWorkAncestor(folderId: Long) {
        val location = workLocation.value ?: return
        updateWorkLocation(location.ancestor(folderId))
    }

    internal fun upInWork(): Boolean {
        val path = workLocation.value?.path ?: return false
        val parent = path.getOrNull(path.lastIndex - 1) ?: return false
        goToWorkAncestor(parent.id)
        return true
    }

    fun openFolder(folder: FolderEntity) {
        resetFolderScroll(folder.id)
        searchQuery.value = ""
        pendingAsmr.value = null
        rjLookup?.cancel()
        currentFolderId.value = folder.id
        clearSelection()
        openJob?.cancel()
        openGeneration += 1
        val generation = openGeneration
        val work = !folder.asmrSourceId.isNullOrBlank()
        if (work) {
            sourceCheckJob?.cancel()
            workRootId.value = folder.id
            workFileSource.value = null
            workSources.value = emptyList()
        } else if (folder.asmrWorkId == null) {
            sourceCheckJob?.cancel()
            workRootId.value = null
            workFileSource.value = null
            workSources.value = emptyList()
        }
        openJob = viewModelScope.launch {
            if (work) busyCounter.begin()
            try {
                graph.library.ensureWorkMeta(folder.id)
                if (work && graph.library.ensureWorkFiles(folder.id) == WorkFill.EMPTY) {
                    graph.show(R.string.library_work_files_not_found)
                }
                if (work && generation == openGeneration && workRootId.value == folder.id) {
                    applyWorkSources(folder.id)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.showError(failure, R.string.library_work_files_not_found)
            } finally {
                if (work) busyCounter.end()
            }
        }
    }

    fun useWorkSource(source: TrackSource) {
        val location = workLocation.value ?: return
        if (location.request.source == source) return
        val root = location.path.first()
        resetFolderScroll(root.id)
        updateWorkLocation(location.move(listOf(root), source))
        openJob?.cancel()
        sourceCheckJob?.cancel()
        openGeneration += 1
        openJob = viewModelScope.launch {
            busyCounter.begin()
            try {
                val fill = graph.library.ensureWorkSource(root.id, source)
                if (fill == WorkFill.EMPTY) {
                    graph.show(
                        if (source == TrackSource.DLSITE) R.string.library_purchased_files_unavailable
                        else R.string.library_work_files_not_found,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if ((failure as? AsmrException)?.kind == AsmrErrorKind.NOT_FOUND) {
                    graph.show(R.string.library_asmr_not_listed)
                } else {
                    graph.showError(failure, R.string.library_work_files_not_found)
                }
            } finally {
                busyCounter.end()
            }
        }
    }

    private suspend fun applyWorkSources(folderId: Long) {
        val sources = graph.library.prepareWorkSources(folderId) ?: return
        val choices = buildList {
            if (sources.hasPlay || sources.purchased == true) add(TrackSource.DLSITE)
            if (sources.hasAsmr) add(TrackSource.ASMR)
            if (sources.hasJapaneseAsmr) add(TrackSource.JAPANESE_ASMR)
        }
        val default = when {
            sources.hasPlay -> TrackSource.DLSITE
            sources.hasAsmr -> TrackSource.ASMR
            sources.hasJapaneseAsmr -> TrackSource.JAPANESE_ASMR
            else -> null
        }
        if (default != null) showWorkSources(default, choices)
        if (sources.hasPlay || sources.purchased != null) return
        // 购买状态在后台确认，保持当前来源。
        sourceCheckJob = viewModelScope.launch {
            val purchased = graph.library.checkPurchased(folderId)
            if (purchased && workRootId.value == folderId) {
                workSources.value = (listOf(TrackSource.DLSITE) + workSources.value).distinct()
            }
        }
    }

    private fun showWorkSources(defaultSource: TrackSource, choices: List<TrackSource>) {
        val location = workLocation.value
        if (location != null) {
            val folderSource = location.path.last().contentSource
            val source = location.request.source?.takeIf { it in choices } ?: if (location.path.size == 1) defaultSource else when (folderSource) {
                TrackSource.ASMR.name -> TrackSource.ASMR
                TrackSource.DLSITE.name -> TrackSource.DLSITE
                TrackSource.JAPANESE_ASMR.name -> TrackSource.JAPANESE_ASMR
                else -> defaultSource
            }
            updateWorkLocation(location.move(location.path, source))
        } else if (workFileSource.value != defaultSource) {
            workFileSource.value = defaultSource
            clearSelection()
        }
        workSources.value = choices
    }

    fun setSourceFilter(filter: LibrarySourceFilter) {
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
        if (currentFolderId.value != null || !RJ_QUERY.matches(digits)) return
        rjLookup = viewModelScope.launch {
            delay(350)
            if (currentFolderId.value != null || searchQuery.value.trim() != digits) return@launch
            if (graph.library.allFolders().any { it.matchesRj(digits) }) return@launch
            val preview = try {
                graph.asmr.preview(digits)
            } catch (_: Exception) {
                null
            } ?: return@launch
            if (currentFolderId.value != null || searchQuery.value.trim() != digits) return@launch
            if (graph.library.allFolders().any { it.matchesRj(digits) }) return@launch
            pendingAsmr.value = preview
        }
    }

    fun confirmAsmrPreview() {
        val preview = pendingAsmr.value ?: return
        pendingAsmr.value = null
        launch {
            graph.library.importAsmr(preview.sourceId, currentFolderId.value)
            graph.show(R.string.library_added_to_library)
        }
    }

    fun dismissAsmrPreview() {
        pendingAsmr.value = null
    }

    fun up() {
        val id = currentFolderId.value ?: return
        openJob?.cancel()
        openGeneration += 1
        viewModelScope.launch {
            currentFolderId.value = graph.library.folder(id)?.parentId
            clearSelection()
        }
    }

    private fun clearWorkSource() {
        sourceCheckJob?.cancel()
        workRootId.value = null
        workFileSource.value = null
        workSources.value = emptyList()
    }

    fun importFile(uri: Uri, play: Boolean) = launch {
        val track = graph.library.importFile(uri, currentFolderId.value)
        if (play) graph.player.playTrack(track) else graph.show(R.string.library_imported)
    }

    fun importFolder(uri: Uri, keepStructure: Boolean) = launch {
        val count = graph.library.importTree(uri, currentFolderId.value, keepStructure)
        if (count == 0) graph.show(R.string.library_folder_no_audio)
        else graph.show(R.string.library_imported_count, count)
    }

    fun createFolder(name: String) = launch {
        graph.library.createFolder(currentFolderId.value, name)
    }

    fun recognizeFolder(folderId: Long, input: String) = launch {
        graph.messages.value = graph.library.recognizeLocalFolder(folderId, input)
    }

    fun recognizeIntoWork(folderId: Long, tree: Uri) = launch {
        graph.messages.value = graph.library.recognizeLocalTree(folderId, tree, workFileSource.value)
    }

    fun downloadTrack(track: TrackEntity) = launch {
        val count = downloadTracks(listOf(track)) ?: return@launch
        if (count == 0) graph.show(R.string.library_already_local_or_downloading)
        else graph.show(R.string.library_added_download_count, count)
    }

    fun downloadSelection() = launch {
        val tracks = selectedTracks()
        val count = downloadTracks(tracks) ?: return@launch
        clearSelection()
        if (count == 0) graph.show(R.string.library_no_audio_to_download)
        else graph.show(R.string.library_added_download_count, count)
    }

    private suspend fun downloadTracks(tracks: List<TrackEntity>): Int? {
        val dlsite = tracks.filter { it.source == TrackSource.DLSITE && !LocalAudio.hasStoredCopy(it.localUri) }
        if (dlsite.isEmpty()) return graph.downloads.enqueue(tracks)
        val works = linkedMapOf<String, String>()
        for (track in dlsite) {
            val workno = graph.downloads.requestWorkno(track) ?: error(text(R.string.library_missing_work_id))
            works.putIfAbsent(workno, workno)
        }
        val mode = graph.dlsiteDownloadRequests.choose(
            works.values.toList(),
            allowPartial = dlsite.all { !it.fileHash.isNullOrBlank() },
        ) ?: return null
        if (mode == DlsiteDownloadMode.PARTIAL) return graph.downloads.enqueue(tracks)
        return graph.downloads.prepare {
            val count = works.count { (workno, title) -> graph.downloads.enqueueDlsiteArchive(workno, title).added }
            count + graph.downloads.enqueue(tracks.filter { it.source != TrackSource.DLSITE })
        }
    }

    internal data class AudioSelection(val tracks: Set<Long>, val folders: Set<Long>, val source: TrackSource?)

    internal val hasSelectedAudio = combine(
        selectedTrackIds, selectedFolderIds, workFileSource, ::AudioSelection,
    ).flatMapLatest { selection ->
        if (selection.tracks.isEmpty() && selection.folders.isEmpty()) flowOf(null)
        else graph.library.observeLocalRemovalTracks(selection.tracks, selection.folders, selection.source)
            .map { tracks -> selection.takeIf { tracks.isNotEmpty() } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    internal fun canInteractWithWorkFile(request: WorkFolderRequest?, file: LibraryFileEntity): Boolean =
        request != null && workLocation.value?.request === request && (showSubtitleFiles.value || !isSubtitleFile(file.name))

    internal data class LocalRemovalSelection(
        val tracks: Set<Long>,
        val folders: Set<Long>,
        val files: Set<Long>,
        val source: TrackSource?,
    )

    internal val selectedLocalRemoval = combine(
        selectedTrackIds, selectedFolderIds, selectedFileIds, workFileSource, ::LocalRemovalSelection,
    ).flatMapLatest { selection ->
        if (selection.tracks.isEmpty() && selection.folders.isEmpty() && selection.files.isEmpty()) flowOf(null)
        else graph.library.observeLocalRemovalTargets(selection.tracks, selection.folders, selection.files, selection.source)
            .map { plan ->
                plan.takeIf { it.hasTargets }?.let { selection to it }
            }
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    internal data class PendingAudioDelete(
        val plan: LocalRemovalPlan,
        val audioCount: Int,
        val associationCount: Int,
        val otherFileCount: Int,
        val workSubtitleCount: Int,
        val generatedSubtitleCount: Int,
        val customSubtitleCount: Int,
    )

    internal val pendingAudioDelete = MutableStateFlow<PendingAudioDelete?>(null)

    fun deleteDownloadedTrack(track: TrackEntity) = launch {
        requestDeleteDownloaded(graph.library.localRemovalPlan(setOf(track.id), emptySet(), emptySet(), track.source))
    }

    fun deleteDownloadedFile(file: LibraryFileEntity) = launch {
        requestDeleteDownloaded(graph.library.localRemovalPlan(emptySet(), emptySet(), setOf(file.id), workFileSource.value))
    }

    internal fun deleteDownloadedSelection(selection: LocalRemovalSelection, plan: LocalRemovalPlan) = launch {
        if (selection != LocalRemovalSelection(selectedTrackIds.value, selectedFolderIds.value, selectedFileIds.value, workFileSource.value)) return@launch
        clearSelection()
        requestDeleteDownloaded(plan)
    }

    private suspend fun requestDeleteDownloaded(plan: LocalRemovalPlan) {
        if (!plan.hasTargets) return
        val downloadGroups = plan.audioDownloads.groupBy { it.localUri?.let(::canonicalPath) ?: "id:${it.id}" }
        val audioCount = downloadGroups.size
        val associationCount = plan.audioAssociations.distinctBy { it.id }.size
        val otherFileCount = plan.otherFiles.distinctBy { it.localUri?.let(::canonicalPath) ?: "id:${it.id}" }.size
        val workSubtitleCount = plan.subtitleFiles.distinctBy { it.localUri?.let(::canonicalPath) ?: "id:${it.id}" }.size
        val generatedSubtitleCount = downloadGroups.values.count { tracks ->
            tracks.any { graph.subtitles.hasRemovableSubtitlesForTrack(it) }
        }
        val customSubtitleCount = plan.customSubtitlePaths.distinct().size
        val showDialog = generatedSubtitleCount > 0 ||
            workSubtitleCount > 0 ||
            customSubtitleCount > 0 ||
            otherFileCount > 0 ||
            (audioCount > 0 && associationCount > 0)
        if (showDialog) {
            pendingAudioDelete.value = PendingAudioDelete(
                plan = plan,
                audioCount = audioCount,
                associationCount = associationCount,
                otherFileCount = otherFileCount,
                workSubtitleCount = workSubtitleCount,
                generatedSubtitleCount = generatedSubtitleCount,
                customSubtitleCount = customSubtitleCount,
            )
        } else {
            applyLocalRemoval(plan, deleteSubtitles = true)
        }
    }

    fun confirmAudioDelete(deleteSubtitles: Boolean) {
        val pending = pendingAudioDelete.value ?: return
        pendingAudioDelete.value = null
        launch { applyLocalRemoval(pending.plan, deleteSubtitles) }
    }

    private suspend fun applyLocalRemoval(plan: LocalRemovalPlan, deleteSubtitles: Boolean) {
        val result = graph.downloads.deleteLocalTargets(plan, deleteSubtitles, graph.subtitleBindings)
        graph.show(deleteDownloadedNotice(result))
    }

    fun cancelAudioDelete() {
        pendingAudioDelete.value = null
    }

    val cachePrompt = MutableStateFlow<CachePrompt?>(null)
    private var cacheJob: Job? = null
    private var cacheAnswer: CompletableDeferred<Any?>? = null

    fun answerCache(answer: Any?) {
        cacheAnswer?.complete(answer)
    }

    fun cancelCacheWait(prompt: CachePrompt.JapaneseWaiting) {
        if (cachePrompt.compareAndSet(prompt, null)) cacheJob?.cancel()
    }

    private suspend fun <T> waitForJapaneseDownload(
        prompt: CachePrompt.JapaneseWaiting,
        read: suspend () -> T,
    ): T {
        cachePrompt.value = prompt
        try {
            return read()
        } finally {
            cachePrompt.compareAndSet(prompt, null)
        }
    }

    private suspend fun askCache(prompt: CachePrompt): Any? {
        val answer = CompletableDeferred<Any?>()
        cacheAnswer = answer
        cachePrompt.value = prompt
        try {
            return answer.await()
        } finally {
            if (cacheAnswer === answer) {
                cachePrompt.value = null
                cacheAnswer = null
            }
        }
    }

    fun cacheFolder(folderId: Long) {
        cacheJob?.cancel()
        cacheJob = viewModelScope.launch {
            try {
                cacheWork(folderId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                graph.showError(error, R.string.library_operation_failed)
            }
        }
    }

    private suspend fun selectJapanesePosts(
        posts: List<com.nanahoshi.audioplayer.japaneseasmr.JaPostRef>,
        refresh: Boolean = false,
    ) = if (posts.size <= 1) posts else when (val answer = askCache(CachePrompt.JapanesePosts(posts, refresh))) {
        is String -> posts.filter { it.postId == answer }
        true -> if (refresh) posts else emptyList()
        else -> emptyList()
    }

    fun refreshJapaneseArticles(rootId: Long) {
        cacheJob?.cancel()
        cacheJob = viewModelScope.launch {
            val rows = graph.db.japaneseAsmr().postsForRoot(rootId)
            val trail = workLocation.value?.path.orEmpty().map { it.id }
            val current = rows.firstOrNull { it.articleFolderId in trail }
            val selected = if (current != null) listOf(current.ref()) else selectJapanesePosts(rows.map { it.ref() }, refresh = true)
            val result = mutableListOf<String>()
            for (post in selected) {
                try {
                    val article = try {
                        graph.japaneseAsmr.article(post.postId, post.rj, refresh = true)
                    } catch (failure: com.nanahoshi.audioplayer.japaneseasmr.JapaneseAsmrException) {
                        if (failure.verificationPage == null) throw failure
                        graph.jaRefreshRequests.verify(post) { token ->
                            askCache(CachePrompt.JapaneseVerify(token, post.title)) == true
                        } ?: break
                    }
                    val updated = graph.player.refreshJaArticle(article, rootId, cleanup = true)
                    result += appContext.getString(R.string.library_ja_refresh_line, post.title, updated.message(appContext))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    val reason = appContext.resolveUserError(failure, R.string.library_refresh_failed)
                    result += appContext.getString(R.string.library_ja_refresh_line, post.title, reason)
                }
            }
            if (result.isNotEmpty()) graph.messages.value = result.joinToString("\n")
        }
    }

    private suspend fun cacheJapaneseWork(root: FolderEntity): Int {
        val posts = graph.db.japaneseAsmr().postsForRoot(root.id).map { it.ref() }
        val selected = selectJapanesePosts(posts)
        if (selected.isEmpty()) return 0
        try {
            val entries = waitForJapaneseDownload(CachePrompt.JapaneseWaiting(text(R.string.ja_download_reading_entry))) {
                graph.downloads.jaWorkEntries(selected)
            }
            val entry = if (entries.size == 1) entries.single() else {
                val titles = entries.groupingBy { it.post.title }.eachCount()
                val options = entries.mapIndexed { index, option ->
                    val title = option.post.title
                    index to if (titles[title] == 1) title else text(R.string.ja_download_entry_suffix, title, index + 1)
                }
                val pick = askCache(CachePrompt.JapaneseChoice(
                    text(R.string.ja_download_choose_entry_title),
                    text(R.string.ja_download_choose_entry_message),
                    options,
                ))
                entries.getOrNull(pick as? Int ?: return 0) ?: return 0
            }
            val readingFiles = CachePrompt.JapaneseWaiting(text(R.string.ja_download_reading_manifest))
            val manifest = waitForJapaneseDownload(readingFiles) {
                graph.downloads.prepareJaManifest(
                    entry, root.id,
                    onReadVerifiedManifest = { cachePrompt.value = readingFiles },
                    onVerify = { token, buzzheavier -> askCache(CachePrompt.JapaneseVerify(token, buzzheavier = buzzheavier)) == true },
                )
            } ?: return 0
            suspend fun checkFiles(force: Boolean) =
                waitForJapaneseDownload(CachePrompt.JapaneseWaiting(text(R.string.ja_download_checking_local))) {
                    graph.downloads.checkJaWork(manifest, force)
                }
            var check = checkFiles(force = false)
            val retained = if (check.retained > 0) text(R.string.ja_download_retained_suffix, check.retained) else ""
            if (check.pending == 0 && !check.lengthOnly) {
                graph.show(R.string.ja_download_files_local, retained)
                return 0
            }
            val total = manifest.bytes?.let {
                android.text.format.Formatter.formatShortFileSize(getApplication<Application>(), it)
            } ?: text(R.string.ja_download_total_unknown)
            val note = if (check.lengthOnly) text(R.string.ja_download_length_only_note) else ""
            val title = text(if (check.pending == 0) R.string.ja_download_files_local_title else R.string.ja_download_download_work)
            val options = buildList<Pair<Any, String>> {
                if (check.pending > 0) add(false to text(R.string.ja_download_confirm))
                if (check.lengthOnly) add(true to text(R.string.ja_download_redownload))
            }
            val force = askCache(CachePrompt.JapaneseChoice(
                title,
                text(R.string.ja_download_manifest_summary, manifest.files.size, total, check.pending, note, retained),
                options,
            )) as? Boolean ?: return 0
            if (force) check = checkFiles(force = true)
            val replace = !check.needsReplace || askCache(CachePrompt.JapaneseChoice(
                text(R.string.ja_download_replace_title),
                text(R.string.ja_download_replace_message),
                listOf(true to text(R.string.ja_download_replace), false to text(R.string.ja_download_keep_old)),
            )) == true
            val queued = graph.downloads.enqueueJaWork(root.id, manifest, replace, force)
            if (!queued.added) graph.show(R.string.ja_download_task_exists)
            return if (queued.added) 1 else 0
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val detail = (failure as? com.nanahoshi.audioplayer.japaneseasmr.JaWorkException)?.userText?.let { appContext.resolve(it) }
                ?: text(R.string.ja_download_read_failed)
            val fallback = askCache(CachePrompt.JapaneseChoice(
                text(R.string.ja_download_failed_title),
                detail,
                listOf(true to text(R.string.ja_download_use_track)),
            )) == true
            if (!fallback) return 0
            val tracks = selected.flatMap { post ->
                val article = graph.japaneseAsmr.article(post.postId, post.rj)
                graph.library.importJapaneseAsmrPost(article, root.id)
                val matches = com.nanahoshi.audioplayer.japaneseasmr.resolveStoredJaMatches(article, post.postId, graph.db.japaneseAsmr())
                matches.filterValues { it.media != null }.keys.mapNotNull { graph.db.japaneseAsmr().track(post.postId, it) }
            }
            return graph.downloads.enqueue(tracks)
        }
    }

    private suspend fun cacheWork(folderId: Long) {
        val byId = graph.library.allFolders().associateBy { it.id }
        val root = workRootOf(folderId, byId)
        val sourceFolderId = root?.id ?: folderId
        // 已购买（读缓存）且 asmr.one 也有这部作品时，让用户选来源。
        val sources = graph.library.prepareWorkSources(sourceFolderId)
        val available = buildList {
            if (sources?.hasPlay == true || sources?.purchased == true) add(TrackSource.DLSITE)
            if (sources?.hasAsmr == true) add(TrackSource.ASMR)
            if (sources?.hasJapaneseAsmr == true) add(TrackSource.JAPANESE_ASMR)
        }
        val chosen = if (available.size > 1) askCache(CachePrompt.Source(available)) as? TrackSource ?: return else null
        val tracks = if (root == null) graph.library.tracksInTree(folderId)
            else graph.library.asmrTracksInTree(folderId)
        // 普通父目录仍下载整个子树；DLsite 子作品各自转为一个完整原始包任务。
        if (root == null) {
            val worknos = tracks.filter { it.source == TrackSource.DLSITE }
                .mapNotNull { graph.downloads.requestWorkno(it) }
                .distinctBy { com.nanahoshi.audioplayer.dlsite.DlsiteClient.canonicalWorkno(it) }
            val jaWorks = tracks.filter { it.source == TrackSource.JAPANESE_ASMR }
                .mapNotNull { workRootOf(it.folderId, byId) }.distinctBy { it.id }
            var jaCount = 0
            for (work in jaWorks) jaCount += cacheJapaneseWork(work)
            val count = graph.downloads.prepare {
                val archives = worknos.count { graph.downloads.enqueueDlsiteArchive(it).added }
                archives + graph.downloads.enqueue(tracks.filter {
                    it.source != TrackSource.DLSITE && it.source != TrackSource.JAPANESE_ASMR
                })
            } + jaCount
            if (count > 0 || jaWorks.isEmpty()) {
                if (count == 0) graph.show(R.string.library_no_audio_to_download)
        else graph.show(R.string.library_added_download_count, count)
            }
            return
        }
        // 没弹窗时：当前来源优先，作品外面按 Play 优先，其次 asmr.one。
        val source = chosen
            ?: workFileSource.value?.takeIf { current -> current in available || tracks.any { it.source == current } }
            ?: available.firstOrNull()
            ?: tracks.firstOrNull()?.source ?: TrackSource.ASMR
        if (source == TrackSource.JAPANESE_ASMR) {
            val count = cacheJapaneseWork(root)
            if (count > 0) graph.show(R.string.library_added_download_count, count)
            return
        }
        if (source == TrackSource.DLSITE) {
            val worknos = root?.asmrSourceId?.let { listOf(it) }
                ?: tracks.filter { it.source == TrackSource.DLSITE }.mapNotNull { graph.downloads.requestWorkno(it) }
                    .distinctBy { com.nanahoshi.audioplayer.dlsite.DlsiteClient.canonicalWorkno(it) }
            if (worknos.isEmpty()) error(text(R.string.library_missing_work_id))
            val count = graph.downloads.prepare {
                worknos.count { graph.downloads.enqueueDlsiteArchive(it, root?.name ?: it, root?.id).added }
            }
            if (count == 0) graph.show(R.string.library_no_audio_to_download)
            else graph.show(R.string.library_added_archive_download_count, count)
            return
        }
        val sourceTracks = if (source == TrackSource.ASMR && tracks.none { it.source == source }) {
            graph.library.ensureWorkSource(root.id, source)
            graph.library.asmrTracksInTree(folderId)
        } else tracks
        val pending = sourceTracks.filter { it.source == source && !LocalAudio.hasStoredCopy(it.localUri) }
        var dropped = emptySet<Long>()
        val conflicts = findFormatConflicts(pending.map { CacheCandidate(it.id, it.folderId, it.title) })
        for (conflict in conflicts) {
            val keep = askCache(CachePrompt.Format(conflict)) as? String ?: return
            dropped = dropped + idsToDrop(conflict, keep)
        }
        val count = graph.downloads.enqueue(pending.filter { it.id !in dropped })
        if (count == 0) graph.show(R.string.library_no_audio_to_download)
        else graph.show(R.string.library_added_download_count, count)
    }

    fun addSelectionToPlaylist(playlistId: Long) = launch {
        val tracks = selectedTracks()
        clearSelection()
        if (tracks.isEmpty()) {
            graph.show(R.string.library_no_audio_to_add)
            return@launch
        }
        graph.library.addTracksToPlaylist(playlistId, tracks.map { it.id })
        graph.show(R.string.library_added_playlist)
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

    fun deleteTrack(id: Long) {
        graph.player.deleteTrack(id)
    }

    fun deleteFolder(id: Long) {
        graph.player.removeFromLibrary(emptyList(), listOf(id))
    }

    private val selectionParents = combine(
        selectedTrackIds,
        selectedFileIds,
        graph.library.observeTracks(),
        graph.library.observeAllFiles(),
    ) { tracks, files, allTracks, allFiles ->
        buildList {
            allTracks.forEach { if (it.id in tracks) add(it.folderId) }
            allFiles.forEach { if (it.id in files) add(it.folderId) }
        }
    }

    val canRemoveSelection = combine(
        removalPolicy,
        selectionParents,
        selectedFolderIds,
    ) { policy, parents, folders ->
        policy?.allows(parents, folders) == true
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), false)

    fun deleteSelection() {
        val tracks = selectedTrackIds.value.toList()
        val folders = selectedFolderIds.value.toList()
        val files = selectedFileIds.value.toList()
        clearSelection()
        graph.player.removeFromLibrary(tracks, folders, files)
    }

    fun beginSelection(trackId: Long? = null, folderId: Long? = null, fileId: Long? = null) {
        if (selectedTrackIds.value.isEmpty() && selectedFolderIds.value.isEmpty() && selectedFileIds.value.isEmpty()) {
            selectedTrackIds.value = listOfNotNull(trackId).toSet()
            selectedFolderIds.value = listOfNotNull(folderId).toSet()
            selectedFileIds.value = listOfNotNull(fileId).toSet()
        } else if (trackId != null) {
            toggleTrack(trackId)
        } else if (folderId != null) {
            toggleFolder(folderId)
        } else if (fileId != null) {
            toggleFile(fileId)
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

    fun toggleFile(id: Long) {
        val current = selectedFileIds.value
        selectedFileIds.value = if (id in current) current - id else current + id
    }

    fun clearSelection() {
        selectedTrackIds.value = emptySet()
        selectedFolderIds.value = emptySet()
        selectedFileIds.value = emptySet()
    }

    fun playNextSelection() = launch {
        val tracks = selectedTracks()
        clearSelection()
        if (tracks.isEmpty()) {
            graph.show(R.string.library_no_audio_to_add)
            return@launch
        }
        graph.player.playNext(tracks)
    }

    fun appendSelection() = launch {
        val tracks = selectedTracks()
        clearSelection()
        if (tracks.isEmpty()) {
            graph.show(R.string.library_no_audio_to_add)
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
            // 作品文件夹里同一路径会有 DLsite 和 asmr.one 两首，队列只放当前显示的那一套。
            val source = workFileSource.value
            val tracks = graph.library.tracksIn(folderId)
                .filter { source == null || it.source == source }
                .sortedForLibrary(effectiveLibrarySort(graph.librarySort.sort.value, workRootId.value))
            val index = tracks.indexOfFirst { it.id == track.id }
            if (index < 0) {
                // 搜索结果里点到不在当前文件夹或不在当前来源的曲目时，只播放这一首。
                graph.player.replaceAndPlay(listOf(track))
                return@launch
            }
            graph.player.replaceAndPlay(tracks, index)
        }
    }

    fun playNext(track: TrackEntity) {
        graph.player.playNext(listOf(track))
    }

    fun append(track: TrackEntity) {
        graph.player.append(listOf(track))
    }

    private suspend fun selectedTracks(): List<TrackEntity> {
        val tracks = graph.library.orderedSelection(
            currentFolderId.value,
            selectedFolderIds.value,
            selectedTrackIds.value,
            effectiveLibrarySort(graph.librarySort.sort.value, workRootId.value),
        )
        val source = workFileSource.value
        return if (source == null) tracks else tracks.filter { it.source == source }
    }

    fun showMessage(@StringRes id: Int) = graph.show(id)

    internal val subtitlePickerActions: SubtitlePickerActions = graph.subtitlePickerActions(viewModelScope)

    internal suspend fun listWorkTracksForSubtitle(
        file: LibraryFileEntity,
    ): List<Pair<TrackEntity, TrackSubtitleBindingEntity?>> = withContext(Dispatchers.IO) {
        val catalog = loadWorkSubtitleCatalog(graph.db, file.folderId)
        val source = workFileSource.value
        val candidateTracks = if (catalog != null) {
            val filtered = if (source == null) catalog.tracks else catalog.tracks.filter { it.source == source }
            filtered.ifEmpty { catalog.tracks }
        } else {
            val folderTracks = graph.library.tracksIn(file.folderId)
            val filtered = if (source == null) folderTracks else folderTracks.filter { it.source == source }
            filtered.ifEmpty { folderTracks }
        }.sortedForLibrary(LibrarySort.NAME)
        if (candidateTracks.isEmpty()) return@withContext emptyList()
        val bindings = candidateTracks.map { it.id }.chunked(900)
            .flatMap { graph.db.trackSubtitles().getForTracks(it) }
            .associateBy { it.trackId }
        candidateTracks.map { track -> track to bindings[track.id] }
    }

    internal suspend fun bindSubtitleFileToTrack(file: LibraryFileEntity, track: TrackEntity) {
        graph.subtitleBindings.bindLibraryFile(track.id, file.id, file.name)
        graph.show(R.string.subtitle_file_bound_notice, track.title)
    }

    internal suspend fun loadBatchSubtitleSetup(currentFolderId: Long?): BatchSubtitleSetup = withContext(Dispatchers.IO) {
        val targetFolderId = currentFolderId ?: workRootId.value
        val catalog = loadWorkSubtitleCatalog(graph.db, targetFolderId)
            ?: return@withContext BatchSubtitleSetup(emptyList(), emptyList(), null, null)
        val root = nearestSubtitleWorkRoot(targetFolderId, catalog.folders)
        val source = workFileSource.value
        val rootLabel = text(R.string.library_root_label)
        val folderPath = { folderId: Long ->
            relativeWorkPath(folderId, root?.id, catalog.folders).ifBlank { rootLabel }
        }
        val audioFolders = catalog.tracks
            .filter { source == null || it.source == source }
            .groupBy { it.folderId }
            .entries
            .mapNotNull { (folderId, tracks) ->
                folderId ?: return@mapNotNull null
                BatchAudioFolderChoice(
                    folderId = folderId,
                    relativePath = folderPath(folderId),
                    tracks = tracks.sortedForLibrary(LibrarySort.NAME),
                )
            }
            .sortedBy { it.relativePath.lowercase() }
        val subtitleFolders = catalog.files
            .mapNotNull { file ->
                file.folderId?.let { BatchSubtitleFolderKey(it, resolveLibraryFileSource(file, catalog.jaFileIds)) to file }
            }
            .groupBy({ it.first }, { it.second })
            .map { (key, files) ->
                val entries = files.sortedBy { it.name.lowercase() }
                    .map { SubtitleCandidateEntry(name = it.name, fileId = it.id) }
                BatchSubtitleFolderChoice(
                    key = key,
                    relativePath = folderPath(key.folderId),
                    candidates = entries,
                )
            }
            .sortedWith(compareBy({ it.relativePath.lowercase() }, { it.key.source?.name }))
        val preferredAudio = audioFolders.firstOrNull { it.folderId == targetFolderId }?.folderId
            ?: audioFolders.firstOrNull()?.folderId
        val preferredSub = subtitleFolders.firstOrNull { it.key.folderId == targetFolderId && it.key.source == source }?.key
            ?: subtitleFolders.firstOrNull { it.key.folderId == targetFolderId }?.key
            ?: subtitleFolders.firstOrNull()?.key
        BatchSubtitleSetup(audioFolders, subtitleFolders, preferredAudio, preferredSub)
    }

    private fun relativeWorkPath(
        folderId: Long?,
        rootId: Long?,
        folders: Map<Long, FolderEntity>,
    ): String {
        val parts = ArrayList<String>()
        var current = folderId
        while (current != null && current != rootId) {
            val folder = folders[current] ?: break
            parts += folder.name
            current = folder.parentId
        }
        return parts.asReversed().joinToString("/")
    }

    internal suspend fun commitBatchLibrarySubtitles(pairs: List<Pair<Long, Long>>) {
        if (pairs.isEmpty()) return
        graph.subtitleBindings.batchBindLibraryFiles(pairs)
        graph.show(R.string.subtitle_batch_bound_count, pairs.size)
    }

    internal suspend fun commitBatchExternalSubtitles(
        pairs: List<BatchExternalPair>,
        treeUri: Uri,
    ): BindExternalResult {
        val result = graph.subtitleBindings.batchBindExternalTree(pairs, treeUri)
        if (result == BindExternalResult.Bound) {
            graph.show(R.string.subtitle_batch_bound_count, pairs.size)
        }
        return result
    }

    /** 复制在 ViewModel 作用域中执行并返回成功数，弹层关闭或旋转不会中断批量导入。 */
    internal suspend fun commitBatchExternalCopies(pairs: List<BatchExternalPair>): Int =
        viewModelScope.async(Dispatchers.IO) {
            var imported = 0
            var failed = 0
            for (pair in pairs) {
                val ok = try {
                    graph.subtitleBindings.importAndBindCopy(pair.trackId, pair.uri, pair.displayName)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                if (ok) imported++ else failed++
            }
            when {
                imported > 0 && failed > 0 -> graph.show(R.string.subtitle_batch_imported_partial, imported, failed)
                imported > 0 -> graph.show(R.string.subtitle_batch_imported_count, imported)
                else -> graph.show(R.string.subtitle_picker_import_failed)
            }
            imported
        }.await()

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
            busyCounter.begin()
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: BilibiliException) {
                graph.showRaw(appContext.resolveNoUrl(error.userText, R.string.bilibili_error_read_failed))
            } catch (error: AsmrException) {
                graph.showRaw(appContext.resolveNoUrl(error.userText, R.string.library_refresh_failed))
            } catch (error: Exception) {
                graph.showError(error, R.string.library_operation_failed)
            } finally {
                busyCounter.end()
            }
        }
    }
}

/** 多个后台操作共用 busy；全部结束才复位，避免互相覆盖。只在主线程调用。 */
internal class BusyCounter {
    val busy = MutableStateFlow(false)
    private var count = 0

    fun begin() {
        count += 1
        busy.value = true
    }

    fun end() {
        count -= 1
        if (count == 0) busy.value = false
    }
}

private data class LibraryRequest(
    val listKey: LibraryListKey,
    val query: String,
    val sort: LibrarySort,
    val workSource: TrackSource?,
)

internal data class LibraryListKey(
    val folderId: Long?,
    val filter: LibrarySourceFilter,
    val isSearching: Boolean,
)

internal data class LoadedLibraryListing(val key: LibraryListKey, val listing: LibraryListing)

internal fun libraryListKey(folderId: Long?, filter: LibrarySourceFilter, query: String): LibraryListKey =
    LibraryListKey(folderId, if (folderId == null) filter else LibrarySourceFilter.ALL, query.isNotBlank())

private val RJ_QUERY = Regex("\\d{1,7}")

internal fun effectiveLibrarySort(global: LibrarySort, workRootId: Long?): LibrarySort =
    if (workRootId != null) LibrarySort.NAME else global

internal fun searchListing(
    query: String,
    sort: LibrarySort,
    folders: List<FolderEntity>,
    tracks: List<TrackEntity>,
    files: List<LibraryFileEntity>,
    scopeFolderId: Long? = null,
): LibraryListing {
    val byId = folders.associateBy { it.id }
    val scope = if (scopeFolderId == null) null else {
        val ids = mutableSetOf(scopeFolderId)
        var changed: Boolean
        do { changed = false; folders.forEach { if (it.parentId in ids && ids.add(it.id)) changed = true } } while (changed)
        ids
    }
    fun groupRoot(parent: Long?) = workRootOf(parent, byId)?.takeIf {
        scope == null || (it.id in scope && it.id != scopeFolderId)
    }
    val workRoots = LinkedHashMap<Long, FolderEntity>()
    val directFolders = LinkedHashMap<Long, FolderEntity>()

    fun addFolder(folder: FolderEntity) {
        // 作品内部的子文件夹和音频都归到作品根，搜索结果里一个作品只出现一次。
        val root = groupRoot(folder.id)
        if (root != null) {
            workRoots[root.id] = root
        } else {
            directFolders[folder.id] = folder
        }
    }

    val digits = RJ_QUERY.matches(query)
    for (folder in folders) {
        if (scope != null && (folder.id !in scope || folder.id == scopeFolderId)) continue
        val rjMatch = digits && folder.matchesRj(query)
        val nameMatch = folder.name.contains(query, ignoreCase = true) || folder.biliBvid?.contains(query, ignoreCase = true) == true
        val tagMatch = folder.asmrSourceId != null &&
            !rjMatch &&
            !nameMatch &&
            folder.workTags().any { it.contains(query, ignoreCase = true) }
        if (rjMatch || nameMatch || tagMatch) addFolder(folder)
    }

    val directTracks = mutableListOf<TrackEntity>()
    tracks.forEach { track ->
        if (scope != null && track.folderId !in scope) return@forEach
        if (!track.title.contains(query, ignoreCase = true)) return@forEach
        val root = groupRoot(track.folderId)
        if (root != null) workRoots[root.id] = root else directTracks += track
    }

    val directFiles = mutableListOf<LibraryFileEntity>()
    files.forEach { file ->
        if (scope != null && file.folderId !in scope) return@forEach
        if (isSubtitleFile(file.name) || !file.name.contains(query, ignoreCase = true)) return@forEach
        val root = groupRoot(file.folderId)
        if (root != null) workRoots[root.id] = root else directFiles += file
    }

    return LibraryListing(
        folders = (workRoots.values + directFolders.values).sortedForLibrary(sort),
        entries = mixedEntries(directTracks, directFiles, sort, files),
        browsing = false,
    )
}

/** 向上找最近的作品根；普通文件夹返回 null。 */
internal fun workRootOf(folderId: Long?, byId: Map<Long, FolderEntity>): FolderEntity? {
    var current = folderId?.let(byId::get)
    while (current != null) {
        if (!current.asmrSourceId.isNullOrBlank() || current.biliBvid != null) return current
        current = current.parentId?.let(byId::get)
    }
    return null
}

private fun FolderEntity.workTags(): List<String> =
    WorkFacts.fromJson(workMeta)?.tags.orEmpty()

private fun FolderEntity.isOrdinaryFolder(): Boolean =
    asmrSourceId.isNullOrBlank() && asmrWorkId == null && biliBvid == null

internal fun applySearchSourceFilter(found: LibraryListing, filter: LibrarySourceFilter): LibraryListing = when (filter) {
    LibrarySourceFilter.ALL -> found
    LibrarySourceFilter.ASMR -> found.copy(
        folders = found.folders.filter { !it.asmrSourceId.isNullOrBlank() },
        entries = emptyList(),
    )
    LibrarySourceFilter.BILIBILI -> found.copy(
        folders = found.folders.filter { it.biliBvid != null },
        entries = found.entries.filter { it is LibraryEntry.Audio && it.track.source == TrackSource.BILIBILI },
    )
    LibrarySourceFilter.YOUTUBE -> found.copy(
        folders = emptyList(),
        entries = found.entries.filter { it is LibraryEntry.Audio && it.track.source == TrackSource.YOUTUBE },
    )
    LibrarySourceFilter.LOCAL -> found.copy(
        folders = found.folders.filter { it.isOrdinaryFolder() },
        entries = found.entries.filter { it is LibraryEntry.File || (it is LibraryEntry.Audio && it.track.source == TrackSource.LOCAL) },
    )
}

private fun filteredListing(
    filter: LibrarySourceFilter,
    sort: LibrarySort,
    folders: List<FolderEntity>,
    tracks: List<TrackEntity>,
): LibraryListing = when (filter) {
    LibrarySourceFilter.BILIBILI -> LibraryListing(
        folders = folders.filter { it.biliBvid != null }.sortedForLibrary(sort),
        entries = mixedEntries(tracks.filter { it.source == TrackSource.BILIBILI &&
            workRootOf(it.folderId, folders.associateBy { folder -> folder.id })?.biliBvid == null }, emptyList(), sort),
        browsing = false,
    )
    LibrarySourceFilter.YOUTUBE -> LibraryListing(
        folders = emptyList(),
        entries = mixedEntries(tracks.filter { it.source == TrackSource.YOUTUBE }, emptyList(), sort),
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
    showSubtitleFiles: Boolean = false,
    subtitleTrackIds: Set<Long>? = null,
): List<LibraryEntry> {
    val visibleFiles = if (showSubtitleFiles) files else files.filter { !isSubtitleFile(it.name) }
    val rows = buildList {
        tracks.forEach { track ->
            val hasSubtitle = subtitleTrackIds?.contains(track.id) ?: hasSubtitle(track, subtitlePool)
            add(LibraryEntry.Audio(track, hasSubtitle))
        }
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

internal val LocalRemovalPlan.hasTargets: Boolean
    get() = audioDownloads.isNotEmpty() ||
        audioAssociations.isNotEmpty() ||
        subtitleFiles.isNotEmpty() ||
        otherFiles.isNotEmpty()

@get:StringRes
internal val LocalRemovalPlan.labelRes: Int
    get() {
        val hasDeletes = audioDownloads.isNotEmpty() || subtitleFiles.isNotEmpty() || otherFiles.isNotEmpty()
        val hasAssociations = audioAssociations.isNotEmpty()
        return when {
            hasDeletes && hasAssociations -> R.string.library_action_delete_and_unlink
            hasDeletes -> R.string.library_action_delete_downloads
            else -> R.string.library_action_unlink
        }
    }

internal fun deleteDownloadedNotice(result: DeleteResult): TextRef {
    val parts = buildList {
        if (result.deleted > 0) add(TextRef.Res(R.string.library_deleted_downloads, listOf(result.deleted)))
        if (result.deletedFiles > 0) add(TextRef.Res(R.string.library_deleted_files, listOf(result.deletedFiles)))
        if (result.unlinked > 0) add(TextRef.Res(R.string.library_unlinked_tracks, listOf(result.unlinked)))
        if (result.changed > 0) add(TextRef.Res(R.string.library_skipped_changed, listOf(result.changed)))
    }
    return if (parts.isEmpty()) TextRef.Res(R.string.library_nothing_to_delete)
    else TextRef.Joined(parts, separator = "。")
}
