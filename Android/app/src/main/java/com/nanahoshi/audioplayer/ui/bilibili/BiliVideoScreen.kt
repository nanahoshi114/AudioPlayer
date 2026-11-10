package com.nanahoshi.audioplayer.ui.bilibili

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanahoshi.audioplayer.data.LocalRemoval
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.data.localRemoval
import com.nanahoshi.audioplayer.graph
import com.nanahoshi.audioplayer.ui.CoverImage
import com.nanahoshi.audioplayer.ui.components.*
import com.nanahoshi.audioplayer.ui.formatDuration
import com.nanahoshi.audioplayer.ui.library.PlaylistPickDialog
import com.nanahoshi.audioplayer.ui.library.TrackRow
import com.nanahoshi.audioplayer.ui.library.deleteDownloadedNotice
import com.nanahoshi.audioplayer.ui.theme.LocalSourceColors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

class BiliVideoViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
    var folder by mutableStateOf<FolderEntity?>(null); private set
    var tracks by mutableStateOf<List<TrackEntity>>(emptyList()); private set
    var loaded by mutableStateOf(false); private set
    var selected by mutableStateOf<Set<Long>>(emptySet()); private set
    val playlists = graph.library.observePlaylists()
    private var observer: Job? = null

    fun open(id: Long) {
        observer?.cancel()
        observer = viewModelScope.launch {
            combine(graph.library.observeFolder(id), graph.library.observeTracksIn(id)) { root, items -> root to items }
                .collect { (root, items) ->
                    folder = root?.takeIf { it.biliBvid != null }
                    tracks = items.filter { it.bvid == folder?.biliBvid }.sortedWith(compareBy({ it.page ?: Int.MAX_VALUE }, { it.id }))
                    selected = selected.intersect(tracks.map { it.id }.toSet())
                    loaded = true
                }
        }
    }
    fun toggle(id: Long) { selected = if (id in selected) selected - id else selected + id }
    fun clear() { selected = emptySet() }
    fun all() { selected = if (selected.size == tracks.size) emptySet() else tracks.map { it.id }.toSet() }
    private fun chosen() = tracks.filter { it.id in selected }
    fun play(track: TrackEntity) { graph.player.replaceAndPlay(tracks, tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)) }
    fun playSelection() { graph.player.replaceAndPlay(chosen()); clear() }
    fun next(items: List<TrackEntity> = chosen()) { graph.player.playNext(items); clear() }
    fun append(items: List<TrackEntity> = chosen()) { graph.player.append(items); clear() }
    private fun action(block: suspend () -> Unit) { viewModelScope.launch {
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { graph.messages.value = failure.message ?: "操作失败，请重试" }
    } }
    fun download(items: List<TrackEntity> = chosen()) = action {
        val added = graph.downloads.enqueue(items)
        clear()
        graph.messages.value = if (added > 0) "已加入 $added 个下载任务" else "已有本地文件或下载任务"
    }
    data class PendingDelete(val tracks: List<TrackEntity>, val subtitles: Int)
    var pendingDelete by mutableStateOf<PendingDelete?>(null); private set

    fun deleteDownloads(items: List<TrackEntity> = chosen()) = action {
        val downloads = withContext(Dispatchers.IO) {
            val filesDir = getApplication<Application>().filesDir
            items.filter { it.localRemoval(filesDir) == LocalRemoval.DOWNLOAD }
        }
        val subtitles = downloads.count { graph.subtitles.hasGenerated(it.id, it.localUri, it.remoteUrl) }
        if (subtitles > 0) {
            pendingDelete = PendingDelete(downloads, subtitles)
        } else {
            val result = graph.downloads.deleteDownloaded(downloads, deleteSubtitles = true)
            clear()
            graph.messages.value = deleteDownloadedNotice(result)
        }
    }
    fun confirmDelete(deleteSubtitles: Boolean) {
        val pending = pendingDelete ?: return
        pendingDelete = null
        action {
            val result = graph.downloads.deleteDownloaded(pending.tracks, deleteSubtitles)
            clear()
            graph.messages.value = deleteDownloadedNotice(result)
        }
    }
    fun cancelDelete() { pendingDelete = null }
    fun addToPlaylist(id: Long) = action { graph.library.addTracksToPlaylist(id, chosen().map { it.id }); clear() }
    fun remove() { folder?.let { graph.player.removeFromLibrary(emptyList(), listOf(it.id)) } }
}

@Composable
fun BiliVideoScreen(folderId: Long, onBack: () -> Unit, viewModel: BiliVideoViewModel = viewModel()) {
    LaunchedEffect(folderId) { viewModel.open(folderId) }
    LaunchedEffect(viewModel.loaded, viewModel.folder) { if (viewModel.loaded && viewModel.folder == null) onBack() }
    val selecting = viewModel.selected.isNotEmpty()
    BackHandler(selecting) { viewModel.clear() }
    var menu by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var pickingPlaylist by remember { mutableStateOf(false) }
    val playlists by viewModel.playlists.collectAsStateWithLifecycle(emptyList())
    val state = rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        if (selecting) SelectionTopBar(viewModel.selected.size, viewModel::clear, listOf(
            SelectionAction("播放所选", Icons.Default.PlayArrow, viewModel::playSelection),
            SelectionAction("接下来播放", Icons.Default.SkipNext, { viewModel.next() }),
            SelectionAction("加入播放队列", Icons.AutoMirrored.Filled.PlaylistAdd, { viewModel.append() }),
            SelectionAction("下载音轨", Icons.Default.Download, { viewModel.download() }),
            SelectionAction("加入播放列表", Icons.AutoMirrored.Filled.QueueMusic, { pickingPlaylist = true }),
            SelectionAction("删除已下载文件", Icons.Default.Delete, { viewModel.deleteDownloads() }, destructive = true),
        )) else AppTopBar("视频", onBack = onBack, actions = {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "更多") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("从音频库移除") }, onClick = { menu = false; removing = true })
            }
        })
        val folder = viewModel.folder
        if (folder == null) LoadingState("正在读取视频…") else WorkFileBrowser(
            state = state, modifier = Modifier.weight(1f).fillMaxWidth(),
            header = {
                Row(Modifier.padding(16.dp)) {
                    CoverImage(folder.coverUri, size = 112.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(folder.name, style = MaterialTheme.typography.titleLarge)
                        Text(viewModel.tracks.firstOrNull()?.artist.orEmpty(), Modifier.padding(top = 8.dp))
                        BadgeLine(listOf(BadgeItem("B 站", LocalSourceColors.current.bilibili), BadgeItem("多 P", MaterialTheme.colorScheme.secondary)),
                            listOf(BadgeItem(folder.biliBvid.orEmpty(), MaterialTheme.colorScheme.onSurfaceVariant)), modifier = Modifier.padding(top = 8.dp))
                        Text("${viewModel.tracks.size} P · ${formatDuration(viewModel.tracks.sumOf { it.durationMs })}", Modifier.padding(top = 8.dp))
                    }
                }
                FilledTonalButton(onClick = { viewModel.download(viewModel.tracks) }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("下载全部音轨") }
            },
            controls = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    SectionHeader("分 P", Modifier.weight(1f))
                    TextButton(onClick = viewModel::all) { Text(if (selecting && viewModel.selected.size == viewModel.tracks.size) "取消全选" else "全选") }
                }
            },
        ) {
            items(viewModel.tracks, key = { it.id }) { track ->
                TrackRow(track = track.copy(title = "P${track.page ?: 1} · ${track.title.removePrefix(folder.name + " - ")}"),
                    hasSubtitle = false, selected = track.id in viewModel.selected, selecting = selecting,
                    onClick = { if (selecting) viewModel.toggle(track.id) else viewModel.play(track) },
                    onLongClick = { viewModel.toggle(track.id) }, onAppend = { viewModel.append(listOf(track)) },
                    onPlayNext = { viewModel.next(listOf(track)) }, onDownload = { viewModel.download(listOf(track)) },
                    onDeleteDownloaded = { viewModel.deleteDownloads(listOf(track)) }, onDelete = null)
            }
        }
    }
    if (pickingPlaylist) PlaylistPickDialog(playlists,
        onPick = { pickingPlaylist = false; viewModel.addToPlaylist(it) }, onDismiss = { pickingPlaylist = false })
    if (removing) AlertDialog(onDismissRequest = { removing = false }, title = { Text("从音频库移除") },
        text = { Text("移除整个视频及其分 P？已下载文件会保留。") },
        confirmButton = { TextButton(onClick = { removing = false; viewModel.remove() }) { Text("移除") } },
        dismissButton = { TextButton(onClick = { removing = false }) { Text("取消") } })
    viewModel.pendingDelete?.let { pending ->
        com.nanahoshi.audioplayer.ui.components.DeleteAudioDialog(
            itemCount = pending.tracks.size,
            subtitleCount = pending.subtitles,
            onConfirm = viewModel::confirmDelete,
            onCancel = viewModel::cancelDelete,
        )
    }
}
