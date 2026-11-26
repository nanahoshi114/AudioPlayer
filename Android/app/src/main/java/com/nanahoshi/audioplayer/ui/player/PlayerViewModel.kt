package com.nanahoshi.audioplayer.ui.player

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.data.DlsiteDownloadMode
import com.nanahoshi.audioplayer.data.SubtitleTask
import com.nanahoshi.audioplayer.data.SubtitleTaskStopped
import com.nanahoshi.audioplayer.data.TaskKey
import com.nanahoshi.audioplayer.data.TaskProgressSnapshot
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.WhisperStart
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.data.db.WhisperJobEntity
import com.nanahoshi.audioplayer.data.effectiveWhisperBase
import com.nanahoshi.audioplayer.data.isLocalNetworkAddress
import com.nanahoshi.audioplayer.data.isOnDeviceWhisperModel
import com.nanahoshi.audioplayer.data.matchesActiveWhisper
import com.nanahoshi.audioplayer.data.matchesFailedWhisper
import com.nanahoshi.audioplayer.graph
import com.nanahoshi.audioplayer.playback.LoadedSubtitles
import com.nanahoshi.audioplayer.playback.PlayingBiliCollection
import com.nanahoshi.audioplayer.playback.PlayingBiliVideo
import com.nanahoshi.audioplayer.playback.SubtitleLanguageDelete
import com.nanahoshi.audioplayer.playback.SubtitleLanguageDeleteResult
import com.nanahoshi.audioplayer.playback.biliVideoId
import com.nanahoshi.audioplayer.ui.components.SubtitlePickerActions
import com.nanahoshi.audioplayer.ui.components.subtitlePickerActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel(app: Application) : AndroidViewModel(app) {
    private data class WhisperMatches(
        val queueItemId: Long,
        val active: WhisperJobEntity?,
        val failed: WhisperJobEntity?,
    )

    private val graph = app.graph()
    val state = graph.player.state
    val queue = graph.library.observeQueue()
    val client = graph.player
    private val currentQueueItemId = state.map { it.queueItemId }.distinctUntilChanged()
    private val collectionTracker = PlayingBiliCollection(
        viewModelScope,
        combine(currentQueueItemId, queue, graph.credentials.biliSession) { id, rows, session ->
            PlayingBiliVideo(rows.firstOrNull { it.queueItemId == id }.biliVideoId(), id, session)
        },
        graph.bilibili.videoCollections,
    ) { bvid -> graph.bilibili.videoDetails(bvid).collection }
    internal val collectionLink = collectionTracker.link
    internal val subtitles = graph.currentSubtitles.state
    internal val subtitleSelection = graph.currentSubtitles.selection
    internal val translationJobs = graph.translator.jobs
    val taskProgress: StateFlow<Map<TaskKey, TaskProgressSnapshot>> = graph.taskProgress.snapshots

    private val currentTrackFlow = combine(currentQueueItemId, queue) { id, rows ->
        rows.firstOrNull { it.queueItemId == id }?.copy(position = 0)
    }.distinctUntilChanged()
    private val whisperSharing = SharingStarted.WhileSubscribed(replayExpirationMillis = 0)
    private val whisperMatches = combine(currentTrackFlow, subtitles, graph.whisper.observe(), ::Triple)
        .mapLatest { (row, subtitleState, jobs) ->
            if (row == null || jobs.isEmpty()) return@mapLatest null
            val targets = graph.whisper.resolveReadOnlyMatchTargets(row, subtitleState.loaded, subtitleState.queueItemId)
            WhisperMatches(
                queueItemId = row.queueItemId,
                active = jobs.firstOrNull { matchesActiveWhisper(it, targets) },
                failed = jobs.firstOrNull { matchesFailedWhisper(it, targets) },
            )
        }.flowOn(Dispatchers.IO).shareIn(viewModelScope, whisperSharing, replay = 1)

    val activeWhisperJob: StateFlow<WhisperJobEntity?> = combine(currentQueueItemId, whisperMatches) { id, matches ->
        matches?.takeIf { it.queueItemId == id }?.active
    }.stateIn(viewModelScope, whisperSharing, null)
    val failedWhisperJob: StateFlow<WhisperJobEntity?> = combine(currentQueueItemId, whisperMatches) { id, matches ->
        matches?.takeIf { it.queueItemId == id }?.failed
    }.stateIn(viewModelScope, whisperSharing, null)

    internal var whisperPrompt by mutableStateOf<WhisperPrompt?>(null)
        private set
    internal var translatePrompt by mutableStateOf(false)
        private set

    fun seekBack(): Job = client.seekBack()

    fun seekForward(): Job = client.seekForward()

    fun cancelWhisper(id: Long): Unit = graph.whisper.cancel(id)

    internal suspend fun chapters(row: QueuedTrack?): List<com.nanahoshi.audioplayer.japaneseasmr.JaChapter> {
        if (row?.source != TrackSource.JAPANESE_ASMR) return emptyList()
        val post = row.workno ?: return emptyList()
        val key = row.fileKey ?: return emptyList()
        val saved = graph.db.japaneseAsmr().media(post, key)
        if (saved?.chapters != null) {
            return runCatching {
                kotlinx.serialization.json.Json.decodeFromString<List<com.nanahoshi.audioplayer.japaneseasmr.JaChapter>>(saved.chapters)
            }.getOrDefault(emptyList())
        }
        if (saved != null) return emptyList()
        return try {
            graph.japaneseAsmr.article(post).media.firstOrNull { it.key == key }?.chapters.orEmpty()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun showChinese(queueItemId: Long, chinese: Boolean) =
        graph.currentSubtitles.showChinese(queueItemId, chinese)

    fun dismissWhisperPrompt() {
        whisperPrompt = null
    }

    fun dismissTranslatePrompt() {
        translatePrompt = false
    }

    fun generateNeedsLocalNetwork(): Boolean {
        val whisperBase = effectiveWhisperBase(
            graph.credentials.resolvedWhisperModel(),
            graph.credentials.whisperBaseUrl(),
        )
        return isLocalNetworkAddress(whisperBase)
    }

    fun reportLocalNetworkDenied() {
        graph.show(R.string.whisper_error_local_network)
    }

    fun requestTranslation(target: QueuedTrack? = null) = requestSubtitleOperation(SubtitleTask.TRANSLATE, target)

    fun requestPolish(target: QueuedTrack? = null) = requestSubtitleOperation(SubtitleTask.POLISH, target)

    private fun requestSubtitleOperation(task: SubtitleTask, target: QueuedTrack? = null) {
        // 点击瞬间的曲目身份；此后自动切歌不得改变操作对象。
        val itemId = target?.queueItemId ?: client.state.value.queueItemId
        val loaded = subtitles.value.forItem(itemId) ?: return
        if (task == SubtitleTask.TRANSLATE && !loaded.canTranslate) return
        viewModelScope.launch {
            val row = target ?: graph.library.queueSnapshot().firstOrNull { it.queueItemId == itemId } ?: return@launch
            submitSubtitleOperation(task, row, loaded)
        }
    }

    fun cancelSubtitleOperation(id: Long) = graph.translator.cancel(id)

    private suspend fun submitSubtitleOperation(task: SubtitleTask, row: QueuedTrack, loaded: LoadedSubtitles?) {
        if (!graph.credentials.hasTranslate()) {
            translatePrompt = true
            return
        }
        try {
            if (graph.translator.submit(task, row, loaded)) showChinese(row.queueItemId, true)
        } catch (stopped: SubtitleTaskStopped) {
            graph.show(stopped.userText)
        } catch (cancelled: CancellationException) {
            if (!coroutineContext.isActive) throw cancelled
        } catch (_: Exception) {
            graph.show(R.string.player_subtitle_create_failed)
        }
    }

    fun requestGenerate(
        translate: Boolean,
        forceRegenerate: Boolean = false,
        targetRow: QueuedTrack? = null,
    ) {
        // 在进入协程前记录请求时间：只有此前关闭/改选的字幕关联才应被新结果覆盖。
        val requestedAt = System.currentTimeMillis()
        viewModelScope.launch {
            // 先固定曲目，再进行任何挂起查询；自动切歌不能改变本次生成对象。
            val row = targetRow ?: currentRow() ?: return@launch
            val model = graph.credentials.resolvedWhisperModel()
            if (isOnDeviceWhisperModel(model)) {
                graph.whisper.missingOfflineModel(model)?.let {
                    whisperPrompt = WhisperPrompt.NeedModelDownload(it)
                    return@launch
                }
            } else if (!graph.credentials.hasWhisper(model)) {
                whisperPrompt = WhisperPrompt.NeedSettings
                return@launch
            }
            val local = graph.whisper.startIfLocal(row, translate, model, forceRegenerate, requestedAt)
            if (local != WhisperStart.Unavailable) {
                report(local, row, translate, requestedAt)
                return@launch
            }
            if (row.source == TrackSource.LOCAL) {
                graph.show(R.string.player_no_audio_for_subtitle)
                return@launch
            }
            val saved = graph.downloads.libraryTrack(row)
            if (saved == null && graph.downloads.japaneseRoot(row) == null) {
                graph.show(R.string.player_add_to_library_first)
                return@launch
            }
            val target = row.copy(trackId = saved?.id ?: row.trackId)
            if (saved?.source == TrackSource.DLSITE) {
                downloadAndGenerate(target, translate, model, forceRegenerate, requestedAt)
            } else {
                whisperPrompt = WhisperPrompt.NeedDownload(target, translate, model, forceRegenerate, requestedAt)
            }
        }
    }

    fun discardGeneratedSubtitles(target: QueuedTrack) {
        viewModelScope.launch {
            graph.subtitles.discardGeneratedOnly(target)
        }
    }

    fun deleteSubtitleLanguage(
        generatedFingerprint: String?,
        overlayOutputName: String?,
        target: SubtitleLanguageDelete,
    ) {
        viewModelScope.launch {
            val result = when {
                generatedFingerprint != null -> graph.subtitles.deleteGeneratedLanguage(generatedFingerprint, target)
                overlayOutputName != null && target == SubtitleLanguageDelete.CHINESE ->
                    graph.subtitles.deleteTranslationOverlayByName(overlayOutputName)
                else -> SubtitleLanguageDeleteResult.NOT_APPLICABLE
            }
            when (result) {
                SubtitleLanguageDeleteResult.BUSY -> graph.show(R.string.subtitle_delete_language_busy)
                SubtitleLanguageDeleteResult.FAILED -> graph.show(R.string.player_error_subtitle_delete)
                SubtitleLanguageDeleteResult.DELETED, SubtitleLanguageDeleteResult.NOT_APPLICABLE -> Unit
            }
        }
    }

    fun clearSubtitleBinding(trackId: Long) {
        if (trackId <= 0L) return
        subtitlePickerActions.offsetWriter.clear(trackId)
        viewModelScope.launch {
            graph.subtitleBindings.clearBinding(trackId)
        }
    }

    internal suspend fun trackForQueueItem(row: QueuedTrack?): TrackEntity? = withContext(Dispatchers.IO) {
        row?.trackId?.takeIf { it > 0L }?.let { graph.db.tracks().getById(it) }
    }

    internal val subtitlePickerActions: SubtitlePickerActions = graph.subtitlePickerActions(viewModelScope)

    fun confirmWhisperDownload() {
        val prompt = whisperPrompt as? WhisperPrompt.NeedDownload ?: return
        whisperPrompt = null
        viewModelScope.launch {
            // 确认下载仍落在发起时快照的曲目上。
            downloadAndGenerate(
                prompt.row, prompt.translate, prompt.model, prompt.forceRegenerate, prompt.requestedAt,
            )
        }
    }

    private suspend fun downloadAndGenerate(
        row: QueuedTrack,
        translate: Boolean,
        model: String,
        forceRegenerate: Boolean = false,
        requestedAt: Long = System.currentTimeMillis(),
    ) {
        var mode = DlsiteDownloadMode.PARTIAL
        val isPurchasedDlsite = row.source == TrackSource.DLSITE && ((row.trackId ?: 0L) > 0L || !row.fileKey.isNullOrBlank())
        if (isPurchasedDlsite) {
            val track = row.trackId?.takeIf { it > 0L }?.let { graph.db.tracks().getById(it) }
            mode = graph.dlsiteDownloadRequests.choose(
                listOf(row.title),
                allowPartial = if (track != null) !track.fileHash.isNullOrBlank() else !row.fileKey.isNullOrBlank(),
            ) ?: return
        }
        report(graph.whisper.startAfterDownload(row, translate, model, mode, forceRegenerate, requestedAt), row, translate, requestedAt)
    }

    private suspend fun currentRow() =
        graph.library.queueSnapshot().find { it.queueItemId == client.state.value.queueItemId }

    private suspend fun report(result: WhisperStart, row: QueuedTrack, translate: Boolean, requestedAt: Long) {
        when (result) {
            WhisperStart.Started -> graph.show(
                if (translate) R.string.player_started_subtitle_translate else R.string.player_started_subtitle,
            )
            WhisperStart.AlreadyRunning -> graph.show(R.string.player_subtitle_already_running)
            WhisperStart.AlreadyDone -> {
                // 已有结果复用时也把此前关闭/改选的字幕关联切回生成字幕。
                val trackId = row.trackId?.takeIf { it > 0L }
                val switched = trackId != null &&
                    graph.subtitleBindings.bindGeneratedIfOlder(trackId, requestedAt)
                graph.currentSubtitles.refresh()
                if (switched) graph.show(R.string.player_switched_to_generated_subtitle)
                if (translate) translateExisting(row)
            }
            WhisperStart.Unavailable -> graph.show(R.string.player_no_audio_for_subtitle)
            is WhisperStart.BackgroundUnavailable -> graph.show(result.message)
            is WhisperStart.NeedModelDownload -> whisperPrompt = WhisperPrompt.NeedModelDownload(result.model)
        }
    }

    private suspend fun translateExisting(row: QueuedTrack) {
        // 刚切回生成字幕或已在后台切歌时，直接加载最新字幕状态再提交翻译。
        val loaded = graph.subtitles.load(row)
        if (!loaded.canTranslate && loaded.cues.isNotEmpty()) return
        submitSubtitleOperation(SubtitleTask.TRANSLATE, row, loaded.takeIf { it.canTranslate })
    }

}

internal sealed interface WhisperPrompt {
    data object NeedSettings : WhisperPrompt
    data class NeedModelDownload(val model: String) : WhisperPrompt
    data class NeedDownload(
        val row: QueuedTrack,
        val translate: Boolean,
        val model: String,
        val forceRegenerate: Boolean = false,
        val requestedAt: Long = System.currentTimeMillis(),
    ) : WhisperPrompt
}
