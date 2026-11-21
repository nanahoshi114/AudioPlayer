package com.nanahoshi.audioplayer.ui.bilibili

import com.nanahoshi.audioplayer.text.resolveUserError
import android.app.Application
import androidx.annotation.StringRes
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.bilibili.*
import com.nanahoshi.audioplayer.graph
import com.nanahoshi.audioplayer.text.TextRef
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.drop
import kotlin.random.Random

enum class CollectionAction { PLAY, APPEND, SAVE, COPY, SINGLE }
internal data class CollectionPreparation(val action: CollectionAction, val details: List<BiliVideoDetails>,
    val failed: List<String>, val targets: List<BiliVideoHit>)

class BiliCollectionViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
    private val appContext = app.applicationContext
    private fun text(@StringRes id: Int, vararg args: Any): String = appContext.getString(id, *args)
    private val repository = graph.biliCollections
    var collection by mutableStateOf<BiliCollection?>(null); private set
    private var sourceVideos by mutableStateOf<List<BiliVideoHit>>(emptyList())
    var sort by mutableStateOf(CollectionSort.FORWARD); private set
    private var randomSeed by mutableLongStateOf(Random.nextLong())
    val videos: List<BiliVideoHit> by derivedStateOf { orderCollectionVideos(sourceVideos, sort, randomSeed) }
    var selected by mutableStateOf<Set<String>>(emptySet()); private set
    var savedId by mutableStateOf<Long?>(null); private set
    var loading by mutableStateOf(false); private set
    var preparing by mutableStateOf(false); private set
    var committing by mutableStateOf(false); private set
    var complete by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var progress by mutableStateOf(""); private set
    var removed by mutableStateOf(false); private set
    internal var pending by mutableStateOf<CollectionPreparation?>(null); private set
    var singleParts by mutableStateOf<List<BiliPart>>(emptyList()); private set
    var copyName by mutableStateOf<String?>(null); private set
    private var copyDetails: List<BiliVideoDetails> = emptyList()
    private var refreshJob: Job? = null
    private var actionJob: Job? = null
    @Volatile private var generation = 0L
    private var initialized = false
    private var savedRoute = false
    val busy get() = preparing || committing

    init {
        viewModelScope.launch {
            graph.credentials.biliSession.drop(1).collect {
                cancelPreparation()
                if (collection != null) refresh()
            }
        }
    }

    fun open(ref: BiliCollection?, playlistId: Long?) {
        if (initialized) return
        initialized = true
        savedRoute = playlistId != null
        viewModelScope.launch {
            val record = playlistId?.let { graph.db.collections().get(it) }
            if (playlistId != null && record == null) { removed = true; return@launch }
            collection = (record?.let(repository::decode) ?: ref)?.withFirstVideoCover(null)
            val value = collection ?: return@launch
            savedId = record?.playlistId ?: graph.db.collections().find(value.kind.name, value.remoteId)?.playlistId
            savedId?.let { repository.cached(it) }?.let { sourceVideos = it.videos; collection = it.collection; complete = true }
            refresh()
        }
    }

    private suspend fun readDirectory(): BiliCollectionPage {
        val value = checkNotNull(collection)
        val token = generation
        val session = graph.credentials.biliSession.value
        return repository.refresh(value, savedId, { token == generation && session == graph.credentials.biliSession.value }) { partial ->
            if (token == generation) {
                progress = text(R.string.bilibili_read_count, partial.size)
                if (!complete) sourceVideos = partial
            }
        }.also {
            currentCoroutineContext().ensureActive()
            check(token == generation && session == graph.credentials.biliSession.value) { text(R.string.bilibili_read_cancelled) }
            collection = it.collection
            if (it.complete) {
                sourceVideos = it.videos
                complete = true
            } else if (!complete) {
                // 不完整结果只用于展示，不覆盖已保存的完整目录。
                sourceVideos = it.videos
            }
            selected = selected.intersect(videos.map { video -> video.bvid }.toSet())
        }
    }

    fun refresh() {
        if (busy) return
        refreshJob?.cancel()
        generation++
        val token = generation
        refreshJob = viewModelScope.launch {
            loading = true; error = null
            try {
                val snapshot = readDirectory()
                if (!snapshot.complete) {
                    val count = if (snapshot.collection.total > 0) {
                        "${snapshot.videos.size}/${snapshot.collection.total}"
                    } else {
                        "${snapshot.videos.size}"
                    }
                    error = text(R.string.bilibili_catalogue_incomplete, count)
                }
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = appContext.resolveUserError(failure, R.string.bilibili_collection_read_failed) }
            finally { if (token == generation) loading = false }
        }
    }

    fun stopRefresh() { generation++; refreshJob?.cancel(); loading = false }
    fun updateSort(value: CollectionSort) {
        if (busy) return
        sort = value
        if (value == CollectionSort.RANDOM) randomSeed = Random.nextLong()
    }
    fun toggle(bvid: String) { selected = if (bvid in selected) selected - bvid else selected + bvid }
    fun clearSelection() { selected = emptySet() }
    fun selectAll() { selected = if (selected.size == videos.size) emptySet() else videos.map { it.bvid }.toSet() }

    fun bookmark() {
        if (busy) return
        val value = collection ?: return
        viewModelScope.launch {
            try {
                stopRefresh()
                savedId = repository.bookmark(value)
                graph.show(R.string.bilibili_bookmarked)
                refresh()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = appContext.resolveUserError(failure, R.string.bilibili_bookmark_failed) }
        }
    }

    fun unbookmark() {
        if (committing) return
        val id = savedId ?: return
        stopRefresh(); cancelPreparation()
        viewModelScope.launch {
            try {
                repository.unbookmark(id); savedId = null
                if (savedRoute) removed = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = text(R.string.bilibili_unbookmark_failed) }
        }
    }

    fun cancelPreparation() {
        if (committing) return
        generation++; actionJob?.cancel(); preparing = false; pending = null
        singleParts = emptyList(); copyName = null; copyDetails = emptyList()
    }

    fun prepare(action: CollectionAction, targets: List<BiliVideoHit>? = null) {
        if (busy) return
        stopRefresh(); cancelPreparation()
        val token = generation
        actionJob = viewModelScope.launch {
            preparing = true; error = null
            try {
                val items = targets ?: run {
                    if (!complete) readDirectory()
                    videos
                }
                if (items.isEmpty()) { error = text(R.string.bilibili_no_videos_in_collection); return@launch }
                val details = mutableListOf<BiliVideoDetails>()
                val failed = mutableListOf<String>()
                for ((index, video) in items.distinctBy { it.bvid }.withIndex()) {
                    progress = text(R.string.bilibili_preparing_parts, index + 1, items.size)
                    try {
                        val result = repository.details(video, savedId, allowPartialLocal = action == CollectionAction.SINGLE)
                        check(result.parts.isNotEmpty()) { text(R.string.bilibili_no_playable_parts) }
                        details += result.copy(parts = result.parts.distinctBy { it.cid }.sortedBy { it.page })
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        failed += text(
                            R.string.bilibili_item_failure,
                            video.title,
                            appContext.resolveUserError(failure, R.string.bilibili_read_failed_short),
                        )
                    }
                    currentCoroutineContext().ensureActive()
                }
                if (token != generation) return@launch
                val result = CollectionPreparation(action, details, failed, items)
                if (failed.isNotEmpty()) pending = result else dispatch(result)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = appContext.resolveUserError(failure, R.string.bilibili_prepare_collection_failed) }
            finally { if (token == generation) preparing = false }
        }
    }

    fun prepareSelection(action: CollectionAction) = prepare(action, videos.filter { it.bvid in selected })
    fun retryPending() { val value = pending ?: return; pending = null; prepare(value.action, value.targets) }
    fun continuePending() {
        val value = pending ?: return
        pending = null
        val token = generation
        actionJob = viewModelScope.launch {
            preparing = true
            try { dispatch(value) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = appContext.resolveUserError(failure, R.string.bilibili_collection_action_failed) }
            finally { if (token == generation) preparing = false }
        }
    }

    private suspend fun dispatch(value: CollectionPreparation) {
        if (value.details.isEmpty()) return
        when (value.action) {
            CollectionAction.SINGLE -> {
                val parts = value.details.single().parts
                if (parts.size == 1) {
                    val item = repository.playable(parts.single())
                    currentCoroutineContext().ensureActive()
                    graph.player.replaceAndPlayItems(listOf(item))
                } else singleParts = parts
            }
            CollectionAction.PLAY, CollectionAction.APPEND -> {
                val items = value.details.flatMap { it.parts }.map { repository.playable(it) }
                currentCoroutineContext().ensureActive()
                if (value.action == CollectionAction.PLAY) graph.player.replaceAndPlayItems(items) else graph.player.appendItems(items)
            }
            CollectionAction.COPY -> { copyDetails = value.details; copyName = collection?.title.orEmpty() }
            CollectionAction.SAVE -> import(value.details, value.action, null)
        }
    }

    fun playPart(part: BiliPart) {
        singleParts = emptyList()
        actionJob = viewModelScope.launch {
            try {
                val item = repository.playable(part)
                currentCoroutineContext().ensureActive()
                graph.player.replaceAndPlayItems(listOf(item))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = text(R.string.bilibili_video_prepare_failed) }
        }
    }
    fun dismissParts() { singleParts = emptyList() }
    fun dismissCopy() { copyName = null; copyDetails = emptyList() }
    fun confirmCopy(name: String) {
        if (name.isBlank() || copyDetails.isEmpty()) return
        val details = copyDetails; dismissCopy()
        val token = generation
        actionJob = viewModelScope.launch {
            preparing = true
            try { import(details, CollectionAction.COPY, name.trim()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = appContext.resolveUserError(failure, R.string.bilibili_copy_failed) }
            finally { if (token == generation) preparing = false }
        }
    }

    private suspend fun import(details: List<BiliVideoDetails>, action: CollectionAction, name: String?) {
        val requests = graph.biliImportRequests.choose(details) ?: return
        currentCoroutineContext().ensureActive()
        committing = true
        try {
            val failed = mutableListOf<String>()
            val saved = mutableListOf<Long>()
            val copiedParts = mutableListOf<BiliPart>()
            for ((index, request) in requests.withIndex()) {
                progress = text(R.string.bilibili_processing, index + 1, requests.size)
                try {
                    graph.library.importBili(request)
                    for (part in request.parts) saved += checkNotNull(graph.db.tracks().findByBvid(part.bvid, part.cid)).id
                    copiedParts += request.parts
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    failed += text(
                        R.string.bilibili_item_failure,
                        request.video.title,
                        appContext.resolveUserError(failure, R.string.bilibili_process_failed),
                    )
                }
            }
            if (action == CollectionAction.COPY && copiedParts.isNotEmpty()) {
                graph.library.publishBiliPlaylist(checkNotNull(name), copiedParts)
            }
            selected = emptySet()
            graph.show(
                when (action) {
                    CollectionAction.COPY -> TextRef.Res(
                        if (saved.isEmpty()) R.string.bilibili_no_audio_to_copy else R.string.bilibili_copied_playlist,
                    )
                    else -> if (saved.isEmpty()) TextRef.Res(R.string.bilibili_nothing_to_add)
                    else TextRef.Res(R.string.bilibili_added_count, listOf(saved.distinct().size))
                },
            )
            if (failed.isNotEmpty()) error = failed.joinToString("\n")
        } finally { committing = false }
    }
}
