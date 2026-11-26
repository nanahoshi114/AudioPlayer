package com.nanahoshi.audioplayer.ui.library

import com.nanahoshi.audioplayer.text.resolveUserError
import com.nanahoshi.audioplayer.asmr.AsmrErrorKind
import com.nanahoshi.audioplayer.text.resolveNoUrl
import androidx.annotation.StringRes
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.UserFacingException

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
import com.nanahoshi.audioplayer.ui.components.SelectionState
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

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
    private val appContext = app.applicationContext
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
    private val trackSelection = SelectionState<Long>()
    private val folderSelection = SelectionState<Long>()
    private val fileSelection = SelectionState<Long>()
    val selectedTrackIds = trackSelection.flow
    val selectedFolderIds = folderSelection.flow
    val selectedFileIds = fileSelection.flow
    private val metaRequested = mutableSetOf<Long>()

    fun toggleSubtitleFiles() {
        val next = !showSubtitleFiles.value
        graph.librarySort.setShowSubtitleFiles(next)
        if (!next) {
            val visibleSubtitleIds = workListing.value?.listing?.entries.orEmpty()
                .mapNotNullTo(mutableSetOf()) { entry ->
                    (entry as? LibraryEntry.File)?.file?.takeIf { isSubtitleFile(it.name) }?.id
                }
            fileSelection.removeAll(visibleSubtitleIds)
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
                    val selected = fileSelection.ids
                    val hidden = files.filter { it.id in selected && isSubtitleFile(it.name) }.map { it.id }
                    fileSelection.removeAll(hidden)
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

    fun goToFolder(targetFolderId: Long?) {
        if (currentFolderId.value == targetFolderId) return
        searchQuery.value = ""
        pendingAsmr.value = null
        rjLookup?.cancel()
        openJob?.cancel()
        sourceCheckJob?.cancel()
        openGeneration += 1
        clearSelection()
        if (targetFolderId == null) {
            clearWorkSource()
            currentFolderId.value = null
            return
        }
        viewModelScope.launch {
            val target = graph.library.folder(targetFolderId)
            if (target == null) {
                clearWorkSource()
                currentFolderId.value = null
            } else {
                openFolder(target)
            }
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
        val dlsite = tracks.filter { it.source == TrackSource.DLSITE && !LocalAudio.hasStoredCopy(appContext, it.localUri) }
        if (dlsite.isEmpty()) return graph.downloads.enqueue(tracks)
        val works = linkedMapOf<String, String>()
        for (track in dlsite) {
            val workno = graph.downloads.requestWorkno(track) ?: throw UserFacingException(TextRef.Res(R.string.library_missing_work_id))
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

    private val cacheFlow = LibraryCacheFlow(graph, appContext, viewModelScope, { workFileSource.value }, { workLocation.value })
    val cachePrompt get() = cacheFlow.cachePrompt

    fun answerCache(answer: Any?) = cacheFlow.answerCache(answer)

    fun cancelCacheWait(prompt: CachePrompt.JapaneseWaiting) = cacheFlow.cancelCacheWait(prompt)

    fun cacheFolder(folderId: Long) = cacheFlow.cacheFolder(folderId)

    fun refreshJapaneseArticles(rootId: Long) = cacheFlow.refreshJapaneseArticles(rootId)


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
        if (trackSelection.isEmpty && folderSelection.isEmpty && fileSelection.isEmpty) {
            trackSelection.replace(listOfNotNull(trackId).toSet())
            folderSelection.replace(listOfNotNull(folderId).toSet())
            fileSelection.replace(listOfNotNull(fileId).toSet())
        } else if (trackId != null) {
            toggleTrack(trackId)
        } else if (folderId != null) {
            toggleFolder(folderId)
        } else if (fileId != null) {
            toggleFile(fileId)
        }
    }

    fun toggleTrack(id: Long) = trackSelection.toggle(id)

    fun toggleFolder(id: Long) = folderSelection.toggle(id)

    fun toggleFile(id: Long) = fileSelection.toggle(id)

    fun clearSelection() {
        trackSelection.clear()
        folderSelection.clear()
        fileSelection.clear()
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

    private val subtitleOps = LibrarySubtitleOps(graph, appContext, viewModelScope, { workFileSource.value }, { workRootId.value })

    internal suspend fun listWorkTracksForSubtitle(
        file: LibraryFileEntity,
    ): List<Pair<TrackEntity, TrackSubtitleBindingEntity?>> = subtitleOps.listWorkTracksForSubtitle(file)

    internal suspend fun bindSubtitleFileToTrack(file: LibraryFileEntity, track: TrackEntity) =
        subtitleOps.bindSubtitleFileToTrack(file, track)

    internal suspend fun loadBatchSubtitleSetup(currentFolderId: Long?): BatchSubtitleSetup =
        subtitleOps.loadBatchSubtitleSetup(currentFolderId)

    internal suspend fun commitBatchLibrarySubtitles(pairs: List<Pair<Long, Long>>) =
        subtitleOps.commitBatchLibrarySubtitles(pairs)

    internal suspend fun commitBatchExternalSubtitles(
        pairs: List<BatchExternalPair>,
        treeUri: Uri,
    ): BindExternalResult = subtitleOps.commitBatchExternalSubtitles(pairs, treeUri)

    internal suspend fun commitBatchExternalCopies(pairs: List<BatchExternalPair>): Int =
        subtitleOps.commitBatchExternalCopies(pairs)


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
