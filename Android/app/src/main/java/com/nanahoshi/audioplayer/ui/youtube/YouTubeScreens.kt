package com.nanahoshi.audioplayer.ui.youtube

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.dlsite.RemotePlayable
import com.nanahoshi.audioplayer.graph
import com.nanahoshi.audioplayer.ui.CoverImage
import com.nanahoshi.audioplayer.ui.components.AppTopBar
import com.nanahoshi.audioplayer.ui.components.EmptyState
import com.nanahoshi.audioplayer.ui.formatDuration
import com.nanahoshi.audioplayer.youtube.*
import kotlinx.coroutines.*

class YouTubeViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
    var query by mutableStateOf("")
    var videos by mutableStateOf<List<YouTubeVideo>>(emptyList()); private set
    var selected by mutableStateOf<Set<String>>(emptySet()); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var partial by mutableStateOf(false); private set
    val tracks = graph.db.tracks().observeAll()
    val downloads = graph.downloads.observe()
    private var request: Job? = null
    private var generation = 0L
    init {
        viewModelScope.launch {
            var revision = graph.credentials.youtubeSession.value
            graph.credentials.youtubeSession.collect { next ->
                if (next != revision) {
                    revision = next
                    cancelSearch()
                    videos = emptyList(); selected = emptySet(); error = null; partial = false
                }
            }
        }
    }
    fun cancelSearch() { generation++; request?.cancel(); request = null; loading = false }
    fun search(listId: String? = null) {
        cancelSearch()
        val token = generation
        loading = true; error = null; partial = false; selected = emptySet(); videos = emptyList()
        request = viewModelScope.launch {
            try {
                val result = if (listId != null) graph.youtube.playlist(listId) else graph.youtube.lookup(query)
                if (token == generation) { videos = result.videos; partial = result.partial }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (token == generation) error = if (failure is YouTubeException) failure.message else "YouTube 读取失败，请稍后重试"
            } finally { if (token == generation) loading = false }
        }
    }
    fun toggle(id: String) { selected = if (id in selected) selected - id else selected + id }
    fun clearSelection() { selected = emptySet() }
    fun selectAll() { selected = if (selected.size == videos.size) emptySet() else videos.map { it.id }.toSet() }
    private fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { graph.messages.value = "YouTube 操作失败，请稍后重试" }
        }
    }
    fun play(video: YouTubeVideo) = action {
        val track = graph.db.tracks().findYouTube(video.id)
        if (track != null) graph.player.replaceAndPlay(listOf(track)) else graph.player.replaceAndPlayRemote(listOf(
            RemotePlayable(video.title, video.author, video.coverUrl, video.watchUrl, TrackSource.YOUTUBE,
                "https://www.youtube.com/", video.durationMs, video.id, "audio"),
        ))
    }
    fun save(video: YouTubeVideo) = action {
        graph.library.saveYouTube(video)
        graph.messages.value = "已保存到音频库"
    }
    fun download(videos: List<YouTubeVideo>) = action {
        val count = graph.downloads.enqueueYouTube(videos)
        graph.messages.value = if (count == 0) "已经在本地或正在下载" else "已加入 $count 个下载，完成后自动入库"
        selected = emptySet()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun YouTubeBrowser(
    playlistId: String? = null,
    onBack: (() -> Unit)? = null,
    viewModel: YouTubeViewModel = viewModel(key = "youtube:${playlistId ?: "search"}"),
) {
    val tracks by viewModel.tracks.collectAsStateWithLifecycle(emptyList())
    val jobs by viewModel.downloads.collectAsStateWithLifecycle(emptyList())
    val stored = tracks.filter { it.source == TrackSource.YOUTUBE }.associateBy { it.youtubeVideoId }
    val active = jobs.filter { it.source == TrackSource.YOUTUBE.name && it.status in setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING) }.map { it.workKey }.toSet()
    val listState = rememberLazyListState()
    LaunchedEffect(playlistId) { if (playlistId != null) viewModel.search(playlistId) }
    BackHandler(viewModel.selected.isNotEmpty()) { viewModel.clearSelection() }
    Column(Modifier.fillMaxSize()) {
        if (onBack != null) AppTopBar(
            title = if (playlistId == "LL") "喜欢的视频" else "稍后观看", onBack = onBack,
        )
        if (playlistId == null) {
            OutlinedTextField(
                value = viewModel.query, onValueChange = { viewModel.query = it },
                label = { Text("关键词、视频或播放列表链接") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (viewModel.loading) {
                Text("正在读取…", Modifier.weight(1f))
                TextButton(onClick = { viewModel.cancelSearch() }) { Text("中止") }
            } else {
                Text("${viewModel.videos.size} 个视频", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { viewModel.search(playlistId) }) { Text(if (playlistId == null) "查找" else "刷新") }
            }
            if (viewModel.videos.isNotEmpty()) TextButton(onClick = viewModel::selectAll) {
                Text(if (viewModel.selected.size == viewModel.videos.size) "取消全选" else "全选")
            }
        }
        if (viewModel.selected.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("已选 ${viewModel.selected.size} 个", Modifier.weight(1f))
                TextButton(onClick = viewModel::clearSelection) { Text("取消") }
                TextButton(onClick = { viewModel.download(viewModel.videos.filter { it.id in viewModel.selected }) }) { Text("下载") }
            }
        }
        if (viewModel.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        viewModel.error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        if (viewModel.partial) Text("已显示部分视频，后续列表读取失败，可刷新重试。", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
        if (!viewModel.loading && viewModel.videos.isEmpty() && viewModel.error == null) {
            EmptyState(title = "还没有视频", message = if (playlistId == null) "输入关键词、视频编号或链接，查找后可播放或下载。" else "保存登录信息后点刷新读取列表。")
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            items(viewModel.videos, key = { it.id }) { video ->
                val track = stored[video.id]
                val downloaded = track?.localUri?.let { it.startsWith("/") && java.io.File(it).isFile } == true
                val downloading = video.id in active
                var menu by remember { mutableStateOf(false) }
                ListItem(
                    headlineContent = { Text(video.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Column {
                            Text(listOf(video.author, formatDuration(video.durationMs)).filter { it.isNotBlank() }.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(when { downloaded -> "已下载"; downloading -> "下载中"; track != null -> "已在音频库"; else -> "YouTube" }, style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    leadingContent = {
                        if (viewModel.selected.isNotEmpty()) Checkbox(video.id in viewModel.selected, { viewModel.toggle(video.id) }) else CoverImage(video.coverUrl)
                    },
                    trailingContent = {
                        if (viewModel.selected.isEmpty()) {
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "更多") }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem(text = { Text(if (track != null) "已在音频库" else "保存到音频库") }, enabled = track == null,
                                        onClick = { menu = false; viewModel.save(video) })
                                    DropdownMenuItem(text = { Text(when { downloaded -> "已下载"; downloading -> "下载中"; else -> "下载" }) }, enabled = !downloaded && !downloading,
                                        onClick = { menu = false; viewModel.download(listOf(video)) })
                                }
                            }
                        }
                    },
                    modifier = Modifier.combinedClickable(
                        onClick = { if (viewModel.selected.isNotEmpty()) viewModel.toggle(video.id) else viewModel.play(video) },
                        onLongClick = { viewModel.toggle(video.id) },
                    ),
                )
            }
        }
    }
}

class YouTubeAccountViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
    var saved by mutableStateOf(!graph.credentials.youtubeCookie().isNullOrBlank()); private set
    var status by mutableStateOf(if (saved) "已保存登录信息" else "还没有保存登录信息"); private set
    var busy by mutableStateOf(false); private set
    private var job: Job? = null
    fun savedCookie(): String = graph.credentials.youtubeCookie().orEmpty()
    fun save(raw: String): Boolean {
        val cookie = normalizeYouTubeCookie(raw)
        if (cookie.isBlank() || !cookie.contains('=')) { status = "请粘贴 youtube.com 的整段 Cookie"; return false }
        job?.cancel()
        graph.credentials.saveYouTubeCookie(cookie); graph.youtube.resetSession(); saved = true; status = "已保存，正在确认登录…"
        job = viewModelScope.launch {
            busy = true
            try { status = graph.youtube.accountStatus() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status = "已保存，但连不上 YouTube" }
            finally { busy = false }
        }
        return true
    }
    fun clear() {
        job?.cancel(); graph.credentials.saveYouTubeCookie(""); graph.youtube.resetSession()
        saved = false; busy = false; status = "已清除"
    }
}

@Composable
fun YouTubeAccountCard(onOpenList: (String) -> Unit, viewModel: YouTubeAccountViewModel = viewModel()) {
    var editing by rememberSaveable { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("YouTube", style = MaterialTheme.typography.titleMedium)
            Text(viewModel.status, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
            Row {
                TextButton(onClick = { editing = true }) { Text(if (viewModel.saved) "更新 Cookie" else "保存 Cookie") }
                TextButton(onClick = viewModel::clear, enabled = viewModel.saved) { Text("清除") }
            }
            Row {
                TextButton(onClick = { onOpenList("LL") }) { Text("喜欢的视频") }
                TextButton(onClick = { onOpenList("WL") }) { Text("稍后观看") }
            }
            Text("使用浏览器里 youtube.com 的整段 Cookie。登录信息加密保存在本机，不会自动更新。", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (editing) {
        var draft by remember { mutableStateOf(viewModel.savedCookie()) }
        AlertDialog(onDismissRequest = { editing = false }, title = { Text("YouTube 登录信息") },
            text = { OutlinedTextField(draft, { draft = it }, label = { Text("整段 Cookie") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { if (viewModel.save(draft)) editing = false }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("取消") } },
        )
    }
}
