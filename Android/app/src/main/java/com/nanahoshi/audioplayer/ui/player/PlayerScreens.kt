package com.nanahoshi.audioplayer.ui.player

import com.nanahoshi.audioplayer.youtube.YouTubeVideo
import com.nanahoshi.audioplayer.youtube.readyYouTubeAudio

import android.Manifest
import android.app.Activity
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
import com.nanahoshi.audioplayer.graph
import com.nanahoshi.audioplayer.playback.PlayerUiState
import com.nanahoshi.audioplayer.playback.hasTranslation
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
import kotlinx.coroutines.flow.collectLatest
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
    var offerBiliCache by mutableStateOf(false)
        private set
    var translating by mutableStateOf(false)
        private set
    var polishing by mutableStateOf(false)
        private set
    internal var whisperPrompt by mutableStateOf<WhisperPrompt?>(null)
        private set
    internal var translatePrompt by mutableStateOf(false)
        private set
    private var generateWithTranslation = false

    init {
        viewModelScope.launch {
            client.state.map { it.queueItemId }.distinctUntilChanged().collectLatest { queueItemId ->
                offerBiliCache = false
                offerBiliCache = biliNeedsCache(queueItemId)
            }
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
        graph.messages.value = "需要允许访问本地网络，才能连接你填写的听写服务"
    }

    fun requestTranslation() {
        val queueItemId = client.state.value.queueItemId
        if (subtitles.value.forItem(queueItemId)?.canTranslate != true) return
        viewModelScope.launch {
            if (!graph.credentials.hasTranslate()) {
                translatePrompt = true
                return@launch
            }
            if (translating) return@launch
            val row = currentRow()?.takeIf { it.queueItemId == queueItemId } ?: return@launch
            translating = true
            try {
                val translated = graph.translator.translateRow(row)
                if (translated) {
                    showChinese(row.queueItemId, true)
                } else {
                    graph.messages.value = "翻译失败，请重试"
                }
            } catch (cancelled: CancellationException) {
                if (!isActive) throw cancelled
            } finally {
                translating = false
            }
        }
    }

    fun cancelTranslation() {
        viewModelScope.launch {
            val row = currentRow() ?: return@launch
            graph.translator.cancelRow(row)
        }
    }

    fun requestPolish() {
        viewModelScope.launch {
            if (!graph.credentials.hasTranslate()) {
                translatePrompt = true
                return@launch
            }
            if (polishing) return@launch
            val row = currentRow() ?: return@launch
            polishing = true
            try {
                val polished = graph.translator.polishRow(row)
                if (polished) {
                    showChinese(row.queueItemId, true)
                } else {
                    graph.messages.value = "润色失败，已保留原译文"
                }
            } catch (cancelled: CancellationException) {
                if (!isActive) throw cancelled
            } finally {
                polishing = false
            }
        }
    }

    fun cancelPolish() {
        viewModelScope.launch {
            val row = currentRow() ?: return@launch
            graph.translator.cancelPolish(row)
        }
    }

    fun cacheCurrent() {
        viewModelScope.launch {
            val row = currentRow() ?: return@launch
            if (row.source == TrackSource.YOUTUBE) {
                val id = row.youtubeVideoId ?: row.workno ?: return@launch
                try {
                    val count = graph.downloads.enqueueYouTube(listOf(YouTubeVideo(
                        id, row.title, row.artist.orEmpty(), row.coverUri ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg", row.durationMs,
                    )))
                    offerBiliCache = false
                    graph.messages.value = if (count == 0) "已在本地或正在下载" else "已加入下载"
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { graph.messages.value = "YouTube 下载失败，请重试" }
                return@launch
            }
            // 没入库的队列项把 bvid/cid 放在 workno/fileKey。
            val bvid = row.bvid ?: row.workno ?: return@launch
            val cid = row.cid ?: row.fileKey?.toLongOrNull() ?: return@launch
            val added = if (row.trackId != null && row.trackId > 0L) {
                val track = graph.db.tracks().getById(row.trackId)
                if (track != null) graph.downloads.enqueue(listOf(track)) else 0
            } else {
                graph.downloads.enqueueBili(
                    listOf(
                        com.nanahoshi.audioplayer.bilibili.BiliPart(
                            bvid = bvid,
                            cid = cid,
                            page = row.page ?: 1,
                            title = row.title,
                            artist = row.artist.orEmpty(),
                            durationMs = row.durationMs,
                            coverUrl = row.coverUri.orEmpty(),
                        ),
                    ),
                )
            }
            offerBiliCache = false
            graph.messages.value = if (added == 0) "已在本地或正在下载" else "已加入下载"
        }
    }

    private suspend fun biliNeedsCache(queueItemId: Long): Boolean {
        val row = graph.library.queueSnapshot().find { it.queueItemId == queueItemId } ?: return false
        if (row.source == TrackSource.YOUTUBE) {
            return readyYouTubeAudio(getApplication<Application>().filesDir, row.youtubeVideoId ?: row.workno) == null
        }
        if (row.source != com.nanahoshi.audioplayer.data.TrackSource.BILIBILI) return false
        // 没入库的队列项没有 trackId，本地路径会放在 remoteUrl，bvid/cid 会放在 workno/fileKey。
        val path = row.localUri ?: row.remoteUrl
        if (!path.isNullOrBlank() && path.startsWith("/") && java.io.File(path).isFile) return false
        val bvid = row.bvid ?: row.workno
        val cid = row.cid ?: row.fileKey?.toLongOrNull()
        return com.nanahoshi.audioplayer.data.readyBiliAudio(
            getApplication<Application>().filesDir,
            bvid,
            cid,
        ) == null
    }

    fun requestGenerate(translate: Boolean) {
        viewModelScope.launch {
            val model = graph.credentials.resolvedWhisperModel()
            // SenseVoiceSmall 在本机运行，hasWhisper 一直为 false，按模型是否就绪判断。
            if (isOnDeviceWhisperModel(model)) {
                if (!graph.senseVoice.isReady()) {
                    whisperPrompt = WhisperPrompt.NeedModelDownload
                    return@launch
                }
            } else if (!graph.credentials.hasWhisper()) {
                whisperPrompt = WhisperPrompt.NeedSettings
                return@launch
            }
            generateWithTranslation = translate
            val row = currentRow() ?: return@launch
            val file = graph.whisper.existingFile(row)
            if (file != null) {
                report(graph.whisper.start(row, file, translate), row, translate)
                return@launch
            }
            val canDownload = row.source == TrackSource.ASMR || row.source == TrackSource.DLSITE || row.source == TrackSource.YOUTUBE
            val hasTarget = (row.trackId != null && row.trackId > 0L) || !row.remoteUrl.isNullOrBlank()
            if (canDownload && hasTarget) {
                if (row.source == TrackSource.DLSITE && ((row.trackId ?: 0L) > 0L || !row.fileKey.isNullOrBlank())) {
                    downloadAndGenerate(row, translate)
                    return@launch
                }
                whisperPrompt = WhisperPrompt.NeedDownload
                return@launch
            }
            graph.messages.value = "这首歌没有可用的音频文件，无法生成字幕"
        }
    }

    fun discardSubtitles() {
        viewModelScope.launch {
            val row = currentRow() ?: return@launch
            graph.subtitles.discard(row)
        }
    }

    fun confirmWhisperDownload() {
        whisperPrompt = null
        val translate = generateWithTranslation
        viewModelScope.launch {
            val row = currentRow() ?: return@launch
            downloadAndGenerate(row, translate)
        }
    }

    private suspend fun downloadAndGenerate(row: QueuedTrack, translate: Boolean) {
        var mode = DlsiteDownloadMode.PARTIAL
        val isPurchasedDlsite = row.source == TrackSource.DLSITE && ((row.trackId ?: 0L) > 0L || !row.fileKey.isNullOrBlank())
        if (isPurchasedDlsite && graph.whisper.existingFile(row) == null) {
            val track = row.trackId?.takeIf { it > 0L }?.let { graph.db.tracks().getById(it) }
            mode = graph.dlsiteDownloadRequests.choose(
                listOf(row.title),
                allowPartial = if (track != null) !track.fileHash.isNullOrBlank() else !row.fileKey.isNullOrBlank(),
            ) ?: return
        }
        report(graph.whisper.startAfterDownload(row, translate, mode), row, translate)
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
        }
    }

    private suspend fun translateExisting(row: QueuedTrack) {
        val cues = graph.subtitles.load(row).cues
        if (cues.isEmpty() || cues.hasTranslation()) return
        if (!graph.credentials.hasTranslate()) {
            translatePrompt = true
            return
        }
        if (translating) return
        translating = true
        try {
            val translated = graph.translator.translateRow(row)
            if (translated) {
                showChinese(row.queueItemId, true)
            } else {
                graph.messages.value = "翻译失败，请重试"
            }
        } catch (cancelled: CancellationException) {
            if (!coroutineContext.isActive) throw cancelled
        } finally {
            translating = false
        }
    }
}

internal enum class WhisperPrompt {
    NeedSettings,
    NeedModelDownload,
    NeedDownload,
}
