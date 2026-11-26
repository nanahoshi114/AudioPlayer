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
import com.nanahoshi.audioplayer.text.UserFacingException
import com.nanahoshi.audioplayer.ui.components.SelectionState
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

    /** [kind] 在任务期间保持不变，只作互斥闸门；界面阶段由 [CollectionPhase] 表达。[onFinish] 在闸门释放后调用。 */
    fun start(
        kind: CollectionOperation,
        onFailure: (Exception) -> Unit,
        onFinish: () -> Unit = {},
        block: suspend () -> Unit,
    ) {
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
                onFinish()
            }
        }
    }

    fun cancel() { job?.cancel() }
}

internal data class IncompleteCollectionCopy(val count: Int, val total: Int)

/** 当前正在进行的操作阶段；返回键、停止按钮和会话刷新都以它为准。 */
internal sealed interface CollectionPhase {
    data object Idle : CollectionPhase
    data object Opening : CollectionPhase
    data object Refreshing : CollectionPhase
    /** 收藏／取消收藏写入中；收藏成功后转入 [Refreshing]。 */
    data object Mutating : CollectionPhase
    data object Preparing : CollectionPhase
    /** 正在确认并写入音频库；仍属于准备流程，不可取消。 */
    data object Committing : CollectionPhase
}

/** 同一时间至多一个的对话框／选择状态；复制流程的草稿随对话框一起保存。 */
internal sealed interface CollectionDialog {
    data object None : CollectionDialog
    data class CopyName(val draft: CollectionCopyDraft) : CollectionDialog
    data class Failures(val prepared: CollectionPreparation, val draft: CollectionCopyDraft?) : CollectionDialog
    data class IncompleteCopy(val range: IncompleteCollectionCopy, val draft: CollectionCopyDraft) : CollectionDialog
    data class PickPart(val parts: List<BiliPart>) : CollectionDialog
}

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
    private val selection = SelectionState<String>()
    val selected: Set<String> get() = selection.ids
    var savedId by mutableStateOf<Long?>(null); private set
    private val work = CollectionWork(viewModelScope)
    private var phase by mutableStateOf<CollectionPhase>(CollectionPhase.Idle)
    val loading get() = phase == CollectionPhase.Opening || phase == CollectionPhase.Refreshing
    val preparing get() = phase == CollectionPhase.Preparing || phase == CollectionPhase.Committing
    /** 互斥闸门；任务被取消后到清理结束前仍为 true。 */
    val running get() = work.operation != null
    val committing get() = phase == CollectionPhase.Committing
    var complete by mutableStateOf(false); private set
    /** 已有完整目录时，本次刷新未读全（值为已读条数）；保留原目录并在页头提示。 */
    private var incompleteRefresh by mutableStateOf<Int?>(null)
    val refreshIncomplete get() = incompleteRefresh != null
    val refreshIncompleteCount get() = incompleteRefresh ?: 0
    var error by mutableStateOf<String?>(null); private set
    var progress by mutableStateOf(""); private set
    var removed by mutableStateOf(false); private set
    private var dialog by mutableStateOf<CollectionDialog>(CollectionDialog.None)
    private val copyDraft: CollectionCopyDraft? get() = when (val value = dialog) {
        is CollectionDialog.CopyName -> value.draft
        is CollectionDialog.Failures -> value.draft
        is CollectionDialog.IncompleteCopy -> value.draft
        else -> null
    }
    /** 失败后回到复制命名（若有草稿），否则关闭对话框。 */
    private fun dialogAfterFailures(): CollectionDialog = copyDraft?.let(CollectionDialog::CopyName) ?: CollectionDialog.None
    internal val pending: CollectionPreparation? get() = (dialog as? CollectionDialog.Failures)?.prepared
    val singleParts: List<BiliPart> get() = (dialog as? CollectionDialog.PickPart)?.parts.orEmpty()
    internal val incompleteCopy: IncompleteCollectionCopy? get() = (dialog as? CollectionDialog.IncompleteCopy)?.range
    val copyName get() = (dialog as? CollectionDialog.CopyName)?.takeIf { !running }?.draft?.name
    private var initialized = false
    private var savedRoute = false
    val busy get() = running || dialog !is CollectionDialog.None

    init {
        viewModelScope.launch {
            graph.credentials.biliSession.drop(1).collectLatest {
                if (collection == null) return@collectLatest
                if (phase == CollectionPhase.Refreshing || phase == CollectionPhase.Preparing) work.cancel()
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
                incompleteRefresh = null
            } else if (hadComplete) {
                incompleteRefresh = snapshot.videos.size
            }
            return snapshot
        } finally {
            selection.retain(sourceVideos.map { it.bvid }.toSet())
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
    ) = work.start(
        kind,
        onFailure = { error = appContext.resolveUserError(it, failureRes) },
        onFinish = { phase = CollectionPhase.Idle },
    ) {
        phase = when (kind) {
            CollectionOperation.OPEN -> CollectionPhase.Opening
            CollectionOperation.REFRESH -> CollectionPhase.Refreshing
            CollectionOperation.PREPARE -> CollectionPhase.Preparing
            CollectionOperation.MUTATE -> CollectionPhase.Mutating
        }
        error = null
        progress = ""
        if (kind == CollectionOperation.OPEN || kind == CollectionOperation.REFRESH) incompleteRefresh = null
        block()
    }

    fun stopRefresh() { if (loading) work.cancel() }
    fun updateSort(value: CollectionSort) {
        if (busy) return
        sort = value
        if (value == CollectionSort.RANDOM) randomSeed = Random.nextLong()
    }
    fun toggle(bvid: String) {
        if (!busy) selection.toggle(bvid)
    }
    fun clearSelection() { if (!busy) selection.clear() }
    fun selectAll() {
        if (busy) return
        selection.toggleAll(videos.map { it.bvid })
    }

    fun bookmark() {
        if (busy) return
        val value = collection ?: return
        startWork(CollectionOperation.MUTATE, R.string.bilibili_bookmark_failed) {
            savedId = repository.bookmark(value)
            graph.show(R.string.bilibili_bookmarked)
            phase = CollectionPhase.Refreshing
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
        dialog = CollectionDialog.None
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
                if (result.parts.isEmpty()) throw UserFacingException(TextRef.Res(R.string.bilibili_no_playable_parts))
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
        if (failed.isNotEmpty()) dialog = CollectionDialog.Failures(result, copyDraft) else dispatch(result)
    }

    fun startCopy(selectionTargets: List<BiliVideoHit>? = null) {
        if (busy || collection == null || selectionTargets?.isEmpty() == true) return
        dialog = CollectionDialog.CopyName(
            CollectionCopyDraft(collection?.title.orEmpty(), sort, randomSeed, selectionTargets),
        )
        error = null
    }

    fun confirmCopyName(name: String) {
        val draft = (dialog as? CollectionDialog.CopyName)?.draft?.takeIf { !running && name.isNotBlank() } ?: return
        draft.name = name.trim()
        startWork(CollectionOperation.PREPARE, R.string.bilibili_copy_failed) {
            val prepared = draft.preparation
            if (prepared != null) {
                dispatch(prepared)
                return@startWork
            }
            val range = draft.freezeDirectory(sourceVideos, complete, ::readDirectory)
            if (range != null) dialog = CollectionDialog.IncompleteCopy(range, draft)
            else prepareCopy(draft, checkNotNull(draft.targets))
        }
    }

    fun continueIncompleteCopy() {
        val current = dialog as? CollectionDialog.IncompleteCopy ?: return
        if (running) return
        val draft = current.draft
        val targets = draft.targets?.takeIf { it.isNotEmpty() } ?: return
        dialog = CollectionDialog.CopyName(draft)
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
        dialog = dialogAfterFailures()
        startWork(CollectionOperation.PREPARE, R.string.bilibili_prepare_collection_failed) {
            prepareTargets(value)
        }
    }

    fun continuePending() {
        if (running) return
        val value = pending?.takeIf { it.details.isNotEmpty() } ?: return
        dialog = dialogAfterFailures()
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
                } else dialog = CollectionDialog.PickPart(parts)
            }
            CollectionAction.PLAY, CollectionAction.APPEND -> {
                val items = value.details.flatMap { it.parts }.map { repository.playable(it) }
                currentCoroutineContext().ensureActive()
                if (value.action == CollectionAction.PLAY) graph.player.replaceAndPlayItems(items) else graph.player.appendItems(items)
            }
            CollectionAction.COPY -> {
                val draft = checkNotNull(copyDraft)
                if (draft.submit(value, ::import)) dialog = CollectionDialog.None
            }
            CollectionAction.SAVE -> import(value)
        }
    }

    fun playPart(part: BiliPart) {
        if (running || part !in singleParts) return
        dialog = CollectionDialog.None
        startWork(CollectionOperation.PREPARE, R.string.bilibili_video_prepare_failed) {
            val item = repository.playable(part)
            currentCoroutineContext().ensureActive()
            graph.player.replaceAndPlayItems(listOf(item))
        }
    }
    fun dismissParts() { if (dialog is CollectionDialog.PickPart) dialog = CollectionDialog.None }
    fun dismissCopy() { if (copyName != null) cancelPreparation() }

    private suspend fun import(value: CollectionPreparation): Boolean {
        val requests = graph.biliImportRequests.choose(value.details) ?: return false
        currentCoroutineContext().ensureActive()
        phase = CollectionPhase.Committing
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
                selection.clear()
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
        } finally { phase = CollectionPhase.Preparing }
    }
}
