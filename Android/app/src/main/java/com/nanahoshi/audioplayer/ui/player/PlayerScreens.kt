package com.nanahoshi.audioplayer.ui.player


import android.Manifest
import android.app.Activity
import com.nanahoshi.audioplayer.data.SubtitleTask
import com.nanahoshi.audioplayer.playback.LoadedSubtitles
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.DlsiteDownloadMode
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import com.nanahoshi.audioplayer.data.effectiveWhisperBase
import com.nanahoshi.audioplayer.data.isLocalNetworkAddress
import com.nanahoshi.audioplayer.data.isOnDeviceWhisperModel
import com.nanahoshi.audioplayer.data.WhisperStart
import com.nanahoshi.audioplayer.data.SUBTITLE_INTERRUPTED
import com.nanahoshi.audioplayer.data.SubtitleTaskStopped
import com.nanahoshi.audioplayer.graph
import com.nanahoshi.audioplayer.playback.PlayerUiState
import com.nanahoshi.audioplayer.playback.indexAt
import com.nanahoshi.audioplayer.playback.line
import com.nanahoshi.audioplayer.playback.textAt
import com.nanahoshi.audioplayer.ui.CoverImage
import com.nanahoshi.audioplayer.ui.EmptyHint
import com.nanahoshi.audioplayer.ui.formatDuration
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.launch
import com.nanahoshi.audioplayer.playback.PlayingBiliCollection
import com.nanahoshi.audioplayer.playback.PlayingBiliVideo
import com.nanahoshi.audioplayer.playback.biliVideoId
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

class PlayerViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
    val state = graph.player.state
    val queue = graph.library.observeQueue()
    val client = graph.player
    private val collectionTracker = PlayingBiliCollection(
        viewModelScope,
        combine(client.state.map { it.queueItemId }.distinctUntilChanged(), queue, graph.credentials.biliSession) { id, rows, session ->
            PlayingBiliVideo(rows.firstOrNull { it.queueItemId == id }.biliVideoId(), id, session)
        },
        graph.bilibili.videoCollections,
    ) { bvid -> graph.bilibili.videoDetails(bvid).collection }
    internal val collectionLink = collectionTracker.link
    internal val subtitles = graph.currentSubtitles.state
    internal val subtitleSelection = graph.currentSubtitles.selection
    internal val translationJobs = graph.translator.jobs
    internal var whisperPrompt by mutableStateOf<WhisperPrompt?>(null)
        private set
    internal var translatePrompt by mutableStateOf(false)
        private set

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
        graph.messages.value = "需要允许访问本地网络，才能连接你填写的听写服务"
    }

    fun requestTranslation() = requestSubtitleOperation(SubtitleTask.TRANSLATE)

    fun requestPolish() = requestSubtitleOperation(SubtitleTask.POLISH)

    private fun requestSubtitleOperation(task: SubtitleTask) {
        val itemId = client.state.value.queueItemId
        val loaded = subtitles.value.forItem(itemId) ?: return
        if (task == SubtitleTask.TRANSLATE && !loaded.canTranslate) return
        viewModelScope.launch {
            val row = graph.library.queueSnapshot().firstOrNull { it.queueItemId == itemId } ?: return@launch
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
            graph.messages.value = stopped.message ?: SUBTITLE_INTERRUPTED
        } catch (cancelled: CancellationException) {
            if (!coroutineContext.isActive) throw cancelled
        } catch (_: Exception) {
            graph.messages.value = "无法创建字幕任务，请重试"
        }
    }

    fun requestGenerate(translate: Boolean) {
        viewModelScope.launch {
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
            val row = currentRow() ?: return@launch
            val local = graph.whisper.startIfLocal(row, translate, model)
            if (local != WhisperStart.Unavailable) {
                report(local, row, translate)
                return@launch
            }
            if (row.source == TrackSource.LOCAL) {
                graph.messages.value = "这首歌没有可用的音频文件，无法生成字幕"
                return@launch
            }
            val saved = graph.downloads.libraryTrack(row)
            if (saved == null) {
                graph.messages.value = "请先加入音频库，再生成字幕"
                return@launch
            }
            val target = row.copy(trackId = saved.id)
            if (saved.source == TrackSource.DLSITE) downloadAndGenerate(target, translate, model)
            else whisperPrompt = WhisperPrompt.NeedDownload(target, translate, model)
        }
    }

    fun discardSubtitles() {
        viewModelScope.launch {
            val row = currentRow() ?: return@launch
            graph.subtitles.discard(row)
        }
    }

    fun confirmWhisperDownload() {
        val prompt = whisperPrompt as? WhisperPrompt.NeedDownload ?: return
        whisperPrompt = null
        viewModelScope.launch {
            downloadAndGenerate(prompt.row, prompt.translate, prompt.model)
        }
    }

    private suspend fun downloadAndGenerate(row: QueuedTrack, translate: Boolean, model: String) {
        var mode = DlsiteDownloadMode.PARTIAL
        val isPurchasedDlsite = row.source == TrackSource.DLSITE && ((row.trackId ?: 0L) > 0L || !row.fileKey.isNullOrBlank())
        if (isPurchasedDlsite) {
            val track = row.trackId?.takeIf { it > 0L }?.let { graph.db.tracks().getById(it) }
            mode = graph.dlsiteDownloadRequests.choose(
                listOf(row.title),
                allowPartial = if (track != null) !track.fileHash.isNullOrBlank() else !row.fileKey.isNullOrBlank(),
            ) ?: return
        }
        report(graph.whisper.startAfterDownload(row, translate, model, mode), row, translate)
    }

    private suspend fun currentRow() =
        graph.library.queueSnapshot().find { it.queueItemId == client.state.value.queueItemId }

    private suspend fun report(result: WhisperStart, row: QueuedTrack, translate: Boolean) {
        when (result) {
            WhisperStart.Started -> graph.messages.value =
                if (translate) "已开始生成字幕和翻译" else "已开始生成字幕"
            WhisperStart.AlreadyRunning -> graph.messages.value = "已经在生成字幕"
            WhisperStart.AlreadyDone -> {
                graph.currentSubtitles.refresh()
                if (translate) translateExisting(row)
            }
            WhisperStart.Unavailable -> graph.messages.value = "这首歌没有可用的音频文件，无法生成字幕"
            is WhisperStart.BackgroundUnavailable -> graph.messages.value = result.message
            is WhisperStart.NeedModelDownload -> whisperPrompt = WhisperPrompt.NeedModelDownload(result.model)
        }
    }

    private suspend fun translateExisting(row: QueuedTrack) {
        val loaded = subtitles.value.forItem(row.queueItemId)
        if (loaded?.canTranslate == false && loaded.cues.isNotEmpty()) return
        submitSubtitleOperation(SubtitleTask.TRANSLATE, row, loaded?.takeIf { it.canTranslate })
    }

}

internal sealed interface WhisperPrompt {
    data object NeedSettings : WhisperPrompt
    data class NeedModelDownload(val model: String) : WhisperPrompt
    data class NeedDownload(val row: QueuedTrack, val translate: Boolean, val model: String) : WhisperPrompt
}
