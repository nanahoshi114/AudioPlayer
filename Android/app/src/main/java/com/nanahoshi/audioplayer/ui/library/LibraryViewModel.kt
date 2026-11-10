package com.nanahoshi.audioplayer.ui.library

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.lazy.LazyListState
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nanahoshi.audioplayer.asmr.AsmrException
import com.nanahoshi.audioplayer.asmr.AsmrPreview
import com.nanahoshi.audioplayer.asmr.WorkFacts
import com.nanahoshi.audioplayer.bilibili.BilibiliException
import com.nanahoshi.audioplayer.data.CacheCandidate
import com.nanahoshi.audioplayer.data.DeleteResult
import com.nanahoshi.audioplayer.data.DlsiteDownloadMode
import com.nanahoshi.audioplayer.data.FormatConflict
import com.nanahoshi.audioplayer.data.LibrarySort
import com.nanahoshi.audioplayer.data.LocalAudio
import com.nanahoshi.audioplayer.data.LocalRemoval
import com.nanahoshi.audioplayer.data.findFormatConflicts
import com.nanahoshi.audioplayer.data.idsToDrop
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.WorkFill
import com.nanahoshi.audioplayer.data.isSubtitleFile
import com.nanahoshi.audioplayer.data.localRemoval
import com.nanahoshi.audioplayer.data.sortedForLibrary
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.LibraryFileEntity
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.graph
import com.nanahoshi.audioplayer.playback.pickSubtitleName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
    data class Source(val folderId: Long) : CachePrompt
    data class Format(val conflict: FormatConflict) : CachePrompt
}

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
    val removalPolicy = graph.library.observeAllFolders().map {
        com.nanahoshi.audioplayer.data.LibraryRemovalPolicy(it)
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), null)
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
    val workFileSource = MutableStateFlow<TrackSource?>(null)
    val workSources = MutableStateFlow(false)
    val workRootId = MutableStateFlow<Long?>(null)
    internal val workLocation = MutableStateFlow<WorkFolderLocation?>(null)
    internal val workMissing = MutableStateFlow(false)
    private var openedWorkId: Long? = null
    private var workPathJob: Job? = null
    internal val workListing = workLocation.map { it?.request }.distinctUntilChanged()
        .flatMapLatest { request ->
            if (request == null) emptyFlow()
            else graph.library.observeFolderListing(request.folderId, LibrarySort.NAME, request.source)
                .map { LoadedWorkFolder(request, it) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    private val listingSort = combine(sort, workRootId) { global, root ->
        effectiveLibrarySort(global, root)
    }
    val pendingAsmr = MutableStateFlow<AsmrPreview?>(null)
    private var rjLookup: Job? = null
    private var openJob: Job? = null
    private var sourceCheckJob: Job? = null
    private var openGeneration = 0
    val listing = combine(
        currentFolderId,
        sourceFilter,
        searchQuery,
        listingSort,
        workFileSource,
    ) { folderId, filter, query, sortMode, workSource ->
        LibraryRequest(folderId, if (folderId != null) LibrarySourceFilter.ALL else filter, query.trim(), sortMode, workSource)
    }.flatMapLatest { request ->
        when {
            request.query.isNotEmpty() -> acrossLibrary { folders, tracks, files ->
                val found = searchListing(request.query, request.sort, folders, tracks, files, request.folderId)
                if (request.filter == LibrarySourceFilter.YOUTUBE) {
                    found.copy(folders = emptyList(), entries = found.entries.filter { it is LibraryEntry.Audio && it.track.source == TrackSource.YOUTUBE })
                } else if (request.filter != LibrarySourceFilter.LOCAL) {
                    found
                } else {
                    found.copy(
                        folders = found.folders.filter { it.asmrSourceId == null && it.asmrWorkId == null && it.biliBvid == null },
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
                        .filter { it.asmrSourceId == null && it.asmrWorkId == null && it.biliBvid == null }
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
            else -> graph.library.observeFolderListing(request.folderId, request.sort, request.workSource)
        }
    }
    val biliAuthors = graph.library.observeTracks().map { tracks ->
        tracks.filter { it.source == TrackSource.BILIBILI && it.folderId != null }
            .associate { it.folderId!! to it.artist.orEmpty() }
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyMap())

    val busy = MutableStateFlow(false)
    val selectedTrackIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedFolderIds = MutableStateFlow<Set<Long>>(emptySet())
    private val metaRequested = mutableSetOf<Long>()

    init {
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
                current.folders.forEach { folder ->
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
        busy.value = false
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
        folderScroll[folder.id] = LazyListState()
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
        folderScroll[folder.id] = LazyListState()
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
            workSources.value = false
        } else if (folder.asmrWorkId == null) {
            sourceCheckJob?.cancel()
            workRootId.value = null
            workFileSource.value = null
            workSources.value = false
        }
        openJob = viewModelScope.launch {
            if (work) busy.value = true
            try {
                graph.library.ensureWorkMeta(folder.id)
                if (work && graph.library.ensureWorkFiles(folder.id) == WorkFill.EMPTY) {
                    graph.messages.value = "没有找到这个作品的文件"
                }
                if (work && generation == openGeneration && workRootId.value == folder.id) {
                    applyWorkSources(folder.id)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.messages.value = failure.message ?: "没有找到这个作品的文件"
            } finally {
                if (generation == openGeneration) busy.value = false
            }
        }
    }

    fun useWorkSource(source: TrackSource) {
        val location = workLocation.value ?: return
        if (location.request.source == source) return
        val root = location.path.first()
        folderScroll[root.id] = LazyListState()
        updateWorkLocation(location.move(listOf(root), source))
        openJob?.cancel()
        sourceCheckJob?.cancel()
        openGeneration += 1
        val generation = openGeneration
        openJob = viewModelScope.launch {
            busy.value = true
            try {
                val fill = graph.library.ensureWorkSource(root.id, source)
                if (fill == WorkFill.EMPTY) {
                    graph.messages.value = if (source == TrackSource.DLSITE) {
                        "无法读取已购买作品的文件"
                    } else {
                        "没有找到这个作品的文件"
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.messages.value = if (failure.message == "未找到该作品") {
                    "asmr.one 未收录"
                } else {
                    failure.message ?: "没有找到这个作品的文件"
                }
            } finally {
                if (generation == openGeneration) busy.value = false
            }
        }
    }

    private suspend fun applyWorkSources(folderId: Long) {
        val sources = graph.library.prepareWorkSources(folderId) ?: return
        if (sources.hasPlay) {
            showWorkSources(TrackSource.DLSITE)
            return
        }
        if (sources.purchased == true) {
            showWorkSources(TrackSource.ASMR)
            return
        }
        if (sources.purchased == false) return
        // 没有 Play 文件时，购买状态只决定要不要给出切换；放后台查，别让打开作品等它。
        sourceCheckJob = viewModelScope.launch {
            val purchased = graph.library.checkPurchased(folderId)
            if (purchased && workRootId.value == folderId) {
                showWorkSources(TrackSource.ASMR)
            }
        }
    }

    private fun showWorkSources(defaultSource: TrackSource) {
        val location = workLocation.value
        if (location != null) {
            val folderSource = location.path.last().contentSource
            val source = if (location.path.size == 1) defaultSource else when (folderSource) {
                TrackSource.ASMR.name -> TrackSource.ASMR
                TrackSource.DLSITE.name -> TrackSource.DLSITE
                else -> defaultSource
            }
            updateWorkLocation(location.move(location.path, source))
        } else {
            workFileSource.value = defaultSource
        }
        workSources.value = true
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
            graph.messages.value = "已加入音频库"
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
        workSources.value = false
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

    fun recognizeFolder(folderId: Long, input: String) = launch {
        graph.messages.value = graph.library.recognizeLocalFolder(folderId, input)
    }

    fun recognizeIntoWork(folderId: Long, tree: Uri) = launch {
        graph.messages.value = graph.library.recognizeLocalTree(folderId, tree, workFileSource.value)
    }

    fun downloadTrack(track: TrackEntity) = launch {
        val count = downloadTracks(listOf(track)) ?: return@launch
        graph.messages.value = if (count == 0) "已在本地或正在下载" else "已加入下载"
    }

    fun downloadSelection() = launch {
        val tracks = selectedTracks()
        val count = downloadTracks(tracks) ?: return@launch
        clearSelection()
        graph.messages.value = if (count == 0) "没有需要下载的音频" else "已加入 $count 个下载任务"
    }

    private suspend fun downloadTracks(tracks: List<TrackEntity>): Int? {
        val dlsite = tracks.filter { it.source == TrackSource.DLSITE && !LocalAudio.hasStoredCopy(it.localUri) }
        if (dlsite.isEmpty()) return graph.downloads.enqueue(tracks)
        val works = linkedMapOf<String, String>()
        for (track in dlsite) {
            val workno = graph.downloads.requestWorkno(track) ?: error("找不到这首音频的作品编号")
            works.putIfAbsent(workno, workno)
        }
        val mode = graph.dlsiteDownloadRequests.choose(
            works.values.toList(),
            allowPartial = dlsite.all { !it.fileHash.isNullOrBlank() },
        ) ?: return null
        if (mode == DlsiteDownloadMode.PARTIAL) return graph.downloads.enqueue(tracks)
        var count = 0
        works.forEach { (workno, title) ->
            if (graph.downloads.enqueueDlsiteArchive(workno, title) > 0L) count++
        }
        return count + graph.downloads.enqueue(tracks.filter { it.source != TrackSource.DLSITE })
    }

    internal data class LocalRemovalTargets(
        val downloads: List<TrackEntity>,
        val associations: List<TrackEntity>,
    ) {
        val label: String get() = if (downloads.isNotEmpty()) "删除已下载文件" else "取消本地关联"
    }

    internal data class LocalRemovalSelection(
        val tracks: Set<Long>,
        val folders: Set<Long>,
        val source: TrackSource?,
    )

    internal val selectedLocalRemoval = combine(
        selectedTrackIds, selectedFolderIds, workFileSource, ::LocalRemovalSelection,
    ).flatMapLatest { selection ->
        if (selection.tracks.isEmpty() && selection.folders.isEmpty()) flowOf(null)
        else graph.library.observeLocalRemovalTracks(selection.tracks, selection.folders, selection.source)
            .map { tracks ->
                val targets = localRemovalTargets(tracks)
                targets.takeUnless { it.downloads.isEmpty() && it.associations.isEmpty() }?.let { selection to it }
            }
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    internal data class PendingAudioDelete(val targets: LocalRemovalTargets, val subtitles: Int)

    internal val pendingAudioDelete = MutableStateFlow<PendingAudioDelete?>(null)

    fun deleteDownloadedTrack(track: TrackEntity) = launch {
        requestDeleteDownloaded(withContext(Dispatchers.IO) { localRemovalTargets(listOf(track)) })
    }

    internal fun deleteDownloadedSelection(selection: LocalRemovalSelection, targets: LocalRemovalTargets) = launch {
        if (selection != LocalRemovalSelection(selectedTrackIds.value, selectedFolderIds.value, workFileSource.value)) return@launch
        clearSelection()
        requestDeleteDownloaded(targets)
    }

    private suspend fun requestDeleteDownloaded(targets: LocalRemovalTargets) {
        val subtitles = targets.downloads.count { graph.subtitles.hasGenerated(it.id, it.localUri, it.remoteUrl) }
        if (subtitles > 0 || (targets.downloads.isNotEmpty() && targets.associations.isNotEmpty())) {
            pendingAudioDelete.value = PendingAudioDelete(targets, subtitles)
        } else {
            applyLocalRemoval(targets, deleteSubtitles = true)
        }
    }

    fun confirmAudioDelete(deleteSubtitles: Boolean) {
        val pending = pendingAudioDelete.value ?: return
        pendingAudioDelete.value = null
        launch { applyLocalRemoval(pending.targets, deleteSubtitles) }
    }

    private suspend fun applyLocalRemoval(targets: LocalRemovalTargets, deleteSubtitles: Boolean) {
        val tracks = targets.downloads + targets.associations
        val result = graph.downloads.deleteDownloaded(tracks, deleteSubtitles)
        graph.messages.value = deleteDownloadedNotice(result)
    }

    private fun localRemovalTargets(tracks: List<TrackEntity>): LocalRemovalTargets {
        val filesDir = getApplication<Application>().filesDir
        val byAction = tracks.groupBy { it.localRemoval(filesDir) }
        return LocalRemovalTargets(byAction[LocalRemoval.DOWNLOAD].orEmpty(), byAction[LocalRemoval.ASSOCIATION].orEmpty())
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

    private suspend fun askCache(prompt: CachePrompt): Any? {
        val answer = CompletableDeferred<Any?>()
        cacheAnswer = answer
        cachePrompt.value = prompt
        try {
            return answer.await()
        } finally {
            cachePrompt.value = null
            cacheAnswer = null
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
                graph.messages.value = error.message ?: "操作失败"
            }
        }
    }

    private suspend fun cacheWork(folderId: Long) {
        val byId = graph.library.allFolders().associateBy { it.id }
        val root = workRootOf(folderId, byId)
        val sourceFolderId = root?.id ?: folderId
        // 已购买（读缓存）且 asmr.one 也有这部作品时，让用户选来源。
        val sources = graph.library.prepareWorkSources(sourceFolderId)
        val bothKnown = sources != null && (sources.hasPlay || sources.purchased == true) && sources.hasAsmr
        var chosen: TrackSource? = null
        if (bothKnown) {
            chosen = askCache(CachePrompt.Source(sourceFolderId)) as? TrackSource ?: return
        }
        val tracks = graph.library.asmrTracksInTree(folderId)
        // 普通父目录仍下载整个子树；DLsite 子作品各自转为一个完整原始包任务。
        if (root == null) {
            val worknos = tracks.filter { it.source == TrackSource.DLSITE }
                .mapNotNull { graph.downloads.requestWorkno(it) }
                .distinctBy { com.nanahoshi.audioplayer.dlsite.DlsiteClient.canonicalWorkno(it) }
            for (workno in worknos) graph.downloads.enqueueDlsiteArchive(workno)
            val files = graph.downloads.enqueue(tracks.filter { it.source != TrackSource.DLSITE })
            graph.messages.value = if (worknos.isEmpty() && files == 0) "没有需要下载的音频"
                else "已加入 ${worknos.size + files} 个下载任务"
            return
        }
        // 没弹窗时：当前来源优先，作品外面按 Play 优先，其次 asmr.one。
        val source = chosen
            ?: workFileSource.value?.takeIf { current -> tracks.any { it.source == current } }
            ?: if (tracks.any { it.source == TrackSource.DLSITE } || (sources?.purchased == true && !sources.hasAsmr)) {
                TrackSource.DLSITE
            } else TrackSource.ASMR
        if (source == TrackSource.DLSITE) {
            val worknos = root?.asmrSourceId?.let { listOf(it) }
                ?: tracks.filter { it.source == TrackSource.DLSITE }.mapNotNull { graph.downloads.requestWorkno(it) }
                    .distinctBy { com.nanahoshi.audioplayer.dlsite.DlsiteClient.canonicalWorkno(it) }
            if (worknos.isEmpty()) error("这个作品缺少编号")
            for (workno in worknos) graph.downloads.enqueueDlsiteArchive(workno, root?.name ?: workno)
            graph.messages.value = "已加入 ${worknos.size} 个完整原始包下载任务"
            return
        }
        val pending = tracks.filter { it.source == source && !LocalAudio.hasStoredCopy(it.localUri) }
        var dropped = emptySet<Long>()
        val conflicts = findFormatConflicts(pending.map { CacheCandidate(it.id, it.folderId, it.title) })
        for (conflict in conflicts) {
            val keep = askCache(CachePrompt.Format(conflict)) as? String ?: return
            dropped = dropped + idsToDrop(conflict, keep)
        }
        val count = graph.downloads.enqueue(pending.filter { it.id !in dropped })
        graph.messages.value = if (count == 0) "没有需要下载的音频" else "已加入 $count 个下载任务"
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

    fun deleteTrack(id: Long) {
        graph.player.deleteTrack(id)
    }

    fun deleteFolder(id: Long) {
        graph.player.removeFromLibrary(emptyList(), listOf(id))
    }

    val canRemoveSelection = combine(removalPolicy, selectedTrackIds, selectedFolderIds, graph.library.observeTracks()) { policy, tracks, folders, all ->
        policy?.allows(all.filter { it.id in tracks }.map { it.folderId }, folders) == true
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), false)

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

    private suspend fun selectedTracks(): List<TrackEntity> =
        graph.library.orderedSelection(
            currentFolderId.value,
            selectedFolderIds.value,
            selectedTrackIds.value,
            effectiveLibrarySort(graph.librarySort.sort.value, workRootId.value),
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
    val workSource: TrackSource?,
)

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

internal fun deleteDownloadedNotice(result: DeleteResult): String = buildList {
    if (result.deleted > 0) add("已删除 ${result.deleted} 个已下载文件")
    if (result.unlinked > 0) add("已取消 ${result.unlinked} 首曲目的本地关联，手机原文件已保留")
    if (result.changed > 0) add("已跳过 ${result.changed} 首本地路径已变化或已移除的曲目")
}.joinToString("。").ifEmpty {
    "没有可删除的下载副本或可取消的本地关联"
}
