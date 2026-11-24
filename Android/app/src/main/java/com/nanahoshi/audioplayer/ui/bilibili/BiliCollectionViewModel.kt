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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlin.random.Random

enum class CollectionAction { PLAY, APPEND, SAVE, COPY, SINGLE }
internal data class CollectionPreparation(val action: CollectionAction, val details: List<BiliVideoDetails>,
    val failed: List<String>, val targets: List<BiliVideoHit>, val copyName: String? = null)

internal class CollectionCopyDraft(
    name: String,
    private val sort: CollectionSort,
    private val randomSeed: Long,
    selectionTargets: List<BiliVideoHit>?,
) {
    var name by mutableStateOf(name)
    var preparation: CollectionPreparation? = null
        private set
    var targets: List<BiliVideoHit>? = selectionTargets?.toList()
        private set

    suspend fun freezeDirectory(
        directory: List<BiliVideoHit>,
        complete: Boolean,
        readDirectory: suspend () -> BiliCollectionPage,
    ): IncompleteCollectionCopy? {
        if (targets != null) return null
        val snapshot = if (complete) null else readDirectory()
        currentCoroutineContext().ensureActive()
        val ordered = orderCollectionVideos(snapshot?.videos ?: directory, sort, randomSeed).toList()
        targets = ordered
        return if (snapshot?.complete == false) IncompleteCollectionCopy(ordered.size, snapshot.collection.total) else null
    }

    suspend fun submit(prepared: CollectionPreparation, save: suspend (CollectionPreparation) -> Boolean): Boolean {
        val current = prepared.copy(copyName = name)
        preparation = current
        return save(current)
    }
}

internal enum class CollectionOperation { OPEN, REFRESH, PREPARE, MUTATE }

internal class CollectionWork(private val scope: CoroutineScope) {
    var operation by mutableStateOf<CollectionOperation?>(null)
        private set
    private var job: Job? = null

    fun start(kind: CollectionOperation, onFailure: (Exception) -> Unit, block: suspend () -> Unit) {
        if (operation != null) return
        operation = kind
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                ensureActive()
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                ensureActive()
                onFailure(failure)
            } finally {
                operation = null
            }
        }
    }

    fun beginRefresh() { operation = CollectionOperation.REFRESH }
    fun cancel() { job?.cancel() }
}

internal data class IncompleteCollectionCopy(val count: Int, val total: Int)

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
    private val work = CollectionWork(viewModelScope)
    val loading get() = work.operation == CollectionOperation.OPEN || work.operation == CollectionOperation.REFRESH
    val preparing get() = work.operation == CollectionOperation.PREPARE
    val running get() = work.operation != null
    var committing by mutableStateOf(false); private set
    var complete by mutableStateOf(false); private set
    /** 已有完整目录时，本次刷新未读全；保留原目录并在页头提示。 */
    var refreshIncomplete by mutableStateOf(false); private set
    var refreshIncompleteCount by mutableStateOf(0); private set
    var error by mutableStateOf<String?>(null); private set
    var progress by mutableStateOf(""); private set
    var removed by mutableStateOf(false); private set
    internal var pending by mutableStateOf<CollectionPreparation?>(null); private set
    var singleParts by mutableStateOf<List<BiliPart>>(emptyList()); private set
    private var copyDraft by mutableStateOf<CollectionCopyDraft?>(null)
    internal var incompleteCopy by mutableStateOf<IncompleteCollectionCopy?>(null); private set
    val copyName get() = if (!running && pending == null && incompleteCopy == null) {
        copyDraft?.name
    } else null
    private var initialized = false
    private var savedRoute = false
    val busy get() = running || copyDraft != null || pending != null || singleParts.isNotEmpty()

    init {
        viewModelScope.launch {
            graph.credentials.biliSession.drop(1).collectLatest {
                if (collection == null) return@collectLatest
                if (work.operation == CollectionOperation.REFRESH || (preparing && !committing)) work.cancel()
                snapshotFlow { busy }.first { !it }
                refresh()
            }
        }
    }

    fun open(ref: BiliCollection?, playlistId: Long?) {
        if (initialized) return
        savedRoute = playlistId != null
        startWork(CollectionOperation.OPEN, R.string.bilibili_collection_read_failed) {
            val record = playlistId?.let { graph.db.collections().get(it) }
            if (playlistId != null && record == null) { removed = true; return@startWork }
            collection = (record?.let(repository::decode) ?: ref)?.withFirstVideoCover(null)
            val value = collection ?: return@startWork
            savedId = record?.playlistId ?: graph.db.collections().find(value.kind.name, value.remoteId)?.playlistId
            savedId?.let { repository.cached(it) }?.let { sourceVideos = it.videos; collection = it.collection; complete = true }
            refreshDirectory()
            // 只有初始解析真正成功后才锁定；失败或取消时允许页面重新打开时重试。
            initialized = true
        }
    }

    private suspend fun readDirectory(): BiliCollectionPage {
        val value = checkNotNull(collection)
        val job = currentCoroutineContext().job
        val session = graph.credentials.biliSession.value
        val isCurrent = { job.isActive && session == graph.credentials.biliSession.value }
        progress = text(R.string.bilibili_read_count, 0)
        try {
            val snapshot = repository.refresh(value, savedId, isCurrent) { partial ->
                if (isCurrent()) {
                    progress = text(R.string.bilibili_read_count, partial.size)
                    if (!complete) sourceVideos = partial
                }
            }
            if (!isCurrent()) throw CancellationException()
            val hadComplete = complete
            // 不完整结果只用于展示，不覆盖已保存的完整目录。
            if (snapshot.complete || !complete) {
                collection = snapshot.collection
                sourceVideos = snapshot.videos
                complete = snapshot.complete
            }
            if (snapshot.complete) {
                refreshIncomplete = false
                refreshIncompleteCount = 0
            } else if (hadComplete) {
                refreshIncomplete = true
                refreshIncompleteCount = snapshot.videos.size
            }
            return snapshot
        } finally {
            selected = selected.intersect(sourceVideos.map { it.bvid }.toSet())
        }
    }

    fun refresh() {
        if (busy || collection == null) return
        startWork(CollectionOperation.REFRESH, R.string.bilibili_collection_read_failed) {
            refreshDirectory()
        }
    }

    private suspend fun refreshDirectory() {
        val snapshot = readDirectory()
        // 已有完整目录时只显示刷新不完整的非破坏性提示，不覆盖为错误状态。
        if (!snapshot.complete && !complete) error = text(R.string.bilibili_catalogue_incomplete, snapshot.videos.size)
    }

    private fun startWork(
        kind: CollectionOperation,
        @StringRes failureRes: Int,
        block: suspend () -> Unit,
    ) = work.start(kind, { error = appContext.resolveUserError(it, failureRes) }) {
        error = null
        progress = ""
        if (kind == CollectionOperation.OPEN || kind == CollectionOperation.REFRESH) {
            refreshIncomplete = false
            refreshIncompleteCount = 0
        }
        block()
    }

    fun stopRefresh() { if (loading) work.cancel() }
    fun updateSort(value: CollectionSort) {
        if (busy) return
        sort = value
        if (value == CollectionSort.RANDOM) randomSeed = Random.nextLong()
    }
    fun toggle(bvid: String) {
        if (!busy) selected = if (bvid in selected) selected - bvid else selected + bvid
    }
    fun clearSelection() { if (!busy) selected = emptySet() }
    fun selectAll() {
        if (busy) return
        val ids = videos.map { it.bvid }.toSet()
        selected = if (ids.isNotEmpty() && ids.all { it in selected }) selected - ids else selected + ids
    }

    fun bookmark() {
        if (busy) return
        val value = collection ?: return
        startWork(CollectionOperation.MUTATE, R.string.bilibili_bookmark_failed) {
            savedId = repository.bookmark(value)
            graph.show(R.string.bilibili_bookmarked)
            work.beginRefresh()
            // 收藏已成功；后续目录刷新失败只提示读取失败，不能误报为收藏失败。
            try {
                refreshDirectory()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = appContext.resolveUserError(failure, R.string.bilibili_collection_read_failed)
            }
        }
    }

    fun unbookmark() {
        if (busy) return
        val id = savedId ?: return
        startWork(CollectionOperation.MUTATE, R.string.bilibili_unbookmark_failed) {
            repository.unbookmark(id)
            savedId = null
            if (savedRoute) removed = true
        }
    }

    fun cancelPreparation() {
        if (committing || (running && !preparing)) return
        if (preparing) work.cancel()
        pending = null
        singleParts = emptyList()
        incompleteCopy = null
        copyDraft = null
    }

    fun prepare(action: CollectionAction, targets: List<BiliVideoHit>? = null) {
        if (busy) return
        if (action == CollectionAction.COPY) {
            startCopy(targets)
            return
        }
        startWork(CollectionOperation.PREPARE, R.string.bilibili_prepare_collection_failed) {
            val items = targets ?: run {
                if (!complete) readDirectory()
                videos
            }
            prepareTargets(CollectionPreparation(action, emptyList(), emptyList(), items.toList()))
        }
    }

    private suspend fun prepareTargets(value: CollectionPreparation) {
        if (value.targets.isEmpty()) {
            error = text(R.string.bilibili_no_videos_in_collection)
            return
        }
        val known = value.details.associateBy { it.video.bvid }
        val details = mutableListOf<BiliVideoDetails>()
        val failed = mutableListOf<String>()
        val items = value.targets.distinctBy { it.bvid }
        for ((index, video) in items.withIndex()) {
            progress = text(R.string.bilibili_preparing_parts, index + 1, items.size)
            try {
                val result = known[video.bvid] ?: repository.details(
                    video, savedId, allowPartialLocal = value.action == CollectionAction.SINGLE,
                )
                check(result.parts.isNotEmpty()) { text(R.string.bilibili_no_playable_parts) }
                details += result.copy(parts = result.parts.distinctBy { it.cid }.sortedBy { it.page })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failed += text(
                    R.string.bilibili_item_failure,
                    video.title,
                    appContext.resolveUserError(failure, R.string.bilibili_read_failed_short),
                )
            }
            currentCoroutineContext().ensureActive()
        }
        val result = value.copy(details = details, failed = failed)
        if (failed.isNotEmpty()) pending = result else dispatch(result)
    }

    fun startCopy(selectionTargets: List<BiliVideoHit>? = null) {
        if (busy || collection == null || selectionTargets?.isEmpty() == true) return
        copyDraft = CollectionCopyDraft(collection?.title.orEmpty(), sort, randomSeed, selectionTargets)
        error = null
    }

    fun confirmCopyName(name: String) {
        val draft = copyDraft?.takeIf { copyName != null && name.isNotBlank() } ?: return
        draft.name = name.trim()
        startWork(CollectionOperation.PREPARE, R.string.bilibili_copy_failed) {
            val prepared = draft.preparation
            if (prepared != null) {
                dispatch(prepared)
                return@startWork
            }
            incompleteCopy = draft.freezeDirectory(sourceVideos, complete, ::readDirectory)
            if (incompleteCopy == null) prepareCopy(draft, checkNotNull(draft.targets))
        }
    }

    fun continueIncompleteCopy() {
        val draft = copyDraft ?: return
        if (running || incompleteCopy == null) return
        val targets = draft.targets?.takeIf { it.isNotEmpty() } ?: return
        incompleteCopy = null
        startWork(CollectionOperation.PREPARE, R.string.bilibili_copy_failed) {
            prepareCopy(draft, targets)
        }
    }

    private suspend fun prepareCopy(draft: CollectionCopyDraft, targets: List<BiliVideoHit>) =
        prepareTargets(CollectionPreparation(CollectionAction.COPY, emptyList(), emptyList(), targets, draft.name))

    fun prepareSelection(action: CollectionAction) = prepare(action, videos.filter { it.bvid in selected })
    fun retryPending() {
        if (running) return
        val value = pending ?: return
        pending = null
        startWork(CollectionOperation.PREPARE, R.string.bilibili_prepare_collection_failed) {
            prepareTargets(value)
        }
    }

    fun continuePending() {
        if (running) return
        val value = pending?.takeIf { it.details.isNotEmpty() } ?: return
        pending = null
        startWork(CollectionOperation.PREPARE, R.string.bilibili_collection_action_failed) {
            dispatch(value)
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
            CollectionAction.COPY -> {
                val draft = checkNotNull(copyDraft)
                if (draft.submit(value, ::import)) copyDraft = null
            }
            CollectionAction.SAVE -> import(value)
        }
    }

    fun playPart(part: BiliPart) {
        if (running || part !in singleParts) return
        singleParts = emptyList()
        startWork(CollectionOperation.PREPARE, R.string.bilibili_video_prepare_failed) {
            val item = repository.playable(part)
            currentCoroutineContext().ensureActive()
            graph.player.replaceAndPlayItems(listOf(item))
        }
    }
    fun dismissParts() { singleParts = emptyList() }
    fun dismissCopy() { if (copyName != null) cancelPreparation() }

    private suspend fun import(value: CollectionPreparation): Boolean {
        val requests = graph.biliImportRequests.choose(value.details) ?: return false
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
            val copied = value.action == CollectionAction.COPY && copiedParts.isNotEmpty() &&
                graph.library.publishBiliPlaylist(checkNotNull(value.copyName), copiedParts) != null
            if (value.action != CollectionAction.COPY || copied) {
                selected = emptySet()
            }
            graph.show(
                when (value.action) {
                    CollectionAction.COPY -> TextRef.Res(
                        if (copied) R.string.bilibili_copied_playlist else R.string.bilibili_no_audio_to_copy,
                    )
                    else -> if (saved.isEmpty()) TextRef.Res(R.string.bilibili_nothing_to_add)
                    else TextRef.Res(R.string.bilibili_added_count, listOf(saved.distinct().size))
                },
            )
            if (failed.isNotEmpty()) error = failed.joinToString("\n")
            return copied
        } finally { committing = false }
    }
}
