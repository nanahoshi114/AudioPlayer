package com.nanahoshi.audioplayer.ui.bilibili

import com.nanahoshi.audioplayer.text.resolveNoUrl
import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.bilibili.*
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.readyBiliAudio
import com.nanahoshi.audioplayer.dlsite.RemotePlayable
import com.nanahoshi.audioplayer.graph
import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.resolve
import kotlinx.coroutines.*

class BiliBrowserViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
    private val appContext = app.applicationContext
    var mode by mutableStateOf(BiliSearchMode.VIDEO); private set
    private var videoQuery by mutableStateOf("")
    private var upQuery by mutableStateOf("")
    var videoOrder by mutableStateOf(BiliVideoOrder.TOTAL_RANK); private set
    var videoDuration by mutableStateOf(BiliVideoDuration.ALL); private set
    val query get() = if (mode == BiliSearchMode.VIDEO) videoQuery else upQuery
    internal val videos = BiliPagedState<BiliVideoHit, String>(viewModelScope) { it.bvid }
    internal val uploaders = BiliPagedState<BiliUploader, Long>(viewModelScope) { it.mid }
    internal val uploads = BiliPagedState<BiliVideoHit, String>(viewModelScope) { it.bvid }
    internal var collections by mutableStateOf(BiliPagedState<BiliCollection, String>(viewModelScope) { it.key }); private set
    var showingCollections by mutableStateOf(false); private set
    fun showCollections(value: Boolean) {
        if (showingCollections == value) return
        selected = emptySet(); cancelParts(); showingCollections = value
        if (value && !collections.searched) uploader?.let { user -> collections.start { graph.bilibili.collections.catalogue(user.mid, it) } }
    }
    var uploader by mutableStateOf<BiliUploader?>(null); private set
    var selected by mutableStateOf<Set<String>>(emptySet()); private set
    var resolving by mutableStateOf(false); private set
    var working by mutableStateOf(false); private set
    internal var choice by mutableStateOf<BiliPartChoice?>(null); private set
    private var partJob: Job? = null
    private var partGeneration = 0L
    init {
        viewModelScope.launch {
            graph.credentials.biliSession.collect { version ->
                if (version > 0) { cancelSearch(); cancelParts(); if (uploader != null) refreshUploader() }
            }
        }
    }
    val tracks = graph.db.tracks().observeAll()
    val downloads = graph.downloads.observe()
    internal val shownVideos get() = if (uploader != null) uploads else videos

    fun updateQuery(value: String) {
        cancelSearch(); cancelParts(); selected = emptySet()
        if (mode == BiliSearchMode.VIDEO) videoQuery = value else upQuery = value
    }
    fun switchMode(value: BiliSearchMode) {
        if (mode == value) return
        cancelSearch(); cancelParts(); selected = emptySet(); mode = value
    }
    fun updateVideoOrder(value: BiliVideoOrder) {
        if (videoOrder == value) return
        videoOrder = value
        refreshVideoFilters()
    }
    fun updateVideoDuration(value: BiliVideoDuration) {
        if (videoDuration == value) return
        videoDuration = value
        refreshVideoFilters()
    }
    private fun refreshVideoFilters() {
        if (mode != BiliSearchMode.VIDEO || uploader != null) return
        if (videoQuery.isNotBlank()) search()
    }
    fun cancelSearch() { videos.cancel(); uploaders.cancel(); uploads.cancel(); collections.cancel() }
    fun search() {
        cancelSearch(); cancelParts(); selected = emptySet()
        try {
            when (val input = biliInput(query, mode)) {
                is BiliSearchInput.Uploader -> openUploader(BiliUploader(input.mid, appContext.getString(R.string.bilibili_uploader_default, input.mid)))
                is BiliSearchInput.Video -> {
                    videoQuery = query
                    uploader = null; mode = BiliSearchMode.VIDEO
                    videos.start {
                        val parts = graph.bilibili.lookup(input.bvid)
                        val first = parts.firstOrNull() ?: throw BilibiliException(0, appContext.getString(R.string.bilibili_no_parts))
                        BiliPage(listOf(BiliVideoHit(first.bvid, first.title, first.artist, first.coverUrl, parts.sumOf { it.durationMs })), false)
                    }
                }
                is BiliSearchInput.Keyword -> if (input.mode == BiliSearchMode.VIDEO) {
                    // 本次查询及所有后续页固定使用提交时的筛选，不读取变化中的界面状态。
                    val order = videoOrder
                    val duration = videoDuration
                    videos.start { graph.bilibili.searchVideos(input.text, it, order, duration) }
                } else uploaders.start { graph.bilibili.searchUploaders(input.text, it) }
            }
        } catch (error: BilibiliException) { graph.showRaw(appContext.resolveNoUrl(error.userText, R.string.bilibili_error_read_failed)) }
    }
    fun openUploader(value: BiliUploader) {
        cancelSearch(); cancelParts(); selected = emptySet(); uploader = value; showingCollections = false
        collections.cancel()
        collections = BiliPagedState(viewModelScope) { it.key }
        uploads.start { graph.bilibili.uploaderVideos(value.mid, it) }
    }
    fun closeUploader() { collections.cancel(); uploads.cancel(); cancelParts(); uploader = null; selected = emptySet() }
    fun refreshUploader() {
        val user = uploader ?: return
        if (showingCollections) collections.start { graph.bilibili.collections.catalogue(user.mid, it) }
        else uploads.start { graph.bilibili.uploaderVideos(user.mid, it) }
    }
    fun toggle(id: String) { selected = if (id in selected) selected - id else selected + id }
    fun clearSelection() { selected = emptySet() }
    fun selectAll() { selected = if (selected.size == shownVideos.items.size) emptySet() else shownVideos.items.map { it.bvid }.toSet() }
    fun cancelParts() { partGeneration++; partJob?.cancel(); partJob = null; resolving = false; choice = null }

    internal fun prepare(action: BiliAction, targets: List<BiliVideoHit>) {
        if (working || targets.isEmpty()) return
        cancelParts()
        val token = partGeneration
        resolving = true
        partJob = viewModelScope.launch {
            val groups = mutableListOf<BiliPartGroup>()
            val failed = mutableListOf<String>()
            try {
                for (video in targets.distinctBy { it.bvid }) {
                    try {
                        val details = graph.bilibili.videoDetails(video.bvid)
                        val parts = details.parts.distinctBy { it.bvid to it.cid }
                        ensureActive()
                        if (parts.isEmpty()) throw BilibiliException(0, appContext.getString(R.string.bilibili_no_playable_parts))
                        groups += BiliPartGroup(details.video, parts)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        failed += appContext.getString(
                            R.string.bilibili_item_failure,
                            video.title,
                            if (error is BilibiliException) appContext.resolveNoUrl(error.userText, R.string.bilibili_error_read_failed)
                            else appContext.getString(R.string.bilibili_part_read_failed_short),
                        )
                    }
                }
                if (token != partGeneration) return@launch
                if (action != BiliAction.PLAY) {
                    if (groups.isEmpty()) { graph.messages.value = failed.joinToString("\n"); return@launch }
                    val requests = graph.biliImportRequests.choose(groups.map { BiliVideoDetails(it.video, it.parts) }, failed) ?: return@launch
                    working = true
                    try {
                        requests.forEach { graph.library.importBili(it) }
                        graph.show(
                        if (failed.isEmpty()) TextRef.Res(R.string.online_added_to_library)
                        else TextRef.Res(R.string.bilibili_added_with_failures, listOf(failed.size)),
                    )
                        selected = emptySet()
                    } finally { working = false }
                    return@launch
                }
                if (targets.size == 1 && groups.singleOrNull()?.parts?.size == 1) {
                    runAction(action, groups.single().parts)
                } else {
                    choice = BiliPartChoice.create(action, groups, failed)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { graph.showError(failure, R.string.bilibili_operation_failed) }
            finally { if (token == partGeneration) resolving = false }
        }
    }
    internal fun togglePart(part: BiliPart) {
        val current = choice ?: return
        choice = current.toggle(part)
    }
    internal fun selectAllParts() {
        val current = choice ?: return
        choice = current.toggleAll()
    }
    internal fun confirmParts() {
        val current = choice ?: return
        val parts = current.chosen
        if (parts.isEmpty()) return
        choice = null; runAction(current.action, parts)
    }
    private fun runAction(action: BiliAction, parts: List<BiliPart>) {
        working = true
        viewModelScope.launch {
            try {
                when (action) {
                    BiliAction.PLAY -> {
                        val part = parts.single()
                        val old = graph.db.tracks().findByBvid(part.bvid, part.cid)
                        if (old != null) graph.player.replaceAndPlay(listOf(old)) else {
                            val cached = readyBiliAudio(getApplication<Application>().filesDir, part.bvid, part.cid)
                            graph.player.replaceAndPlayRemote(listOf(RemotePlayable(
                                part.title, part.artist, part.coverUrl, cached?.absolutePath ?: "https://www.bilibili.com/video/${part.bvid}",
                                TrackSource.BILIBILI, "https://www.bilibili.com/video/${part.bvid}", part.durationMs, part.bvid, part.cid.toString(),
                            )))
                        }
                    }
                    BiliAction.SAVE -> error(appContext.getString(R.string.bilibili_choose_import_mode))
                }
                selected = emptySet()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { graph.show(R.string.bilibili_operation_failed) }
            finally { working = false }
        }
    }
}
