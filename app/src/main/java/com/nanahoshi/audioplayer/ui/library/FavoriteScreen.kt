package com.nanahoshi.audioplayer.ui.library

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nanahoshi.audioplayer.bilibili.FavFolder
import com.nanahoshi.audioplayer.bilibili.FavVideo
import com.nanahoshi.audioplayer.graph
import com.nanahoshi.audioplayer.ui.CoverImage
import com.nanahoshi.audioplayer.ui.EmptyHint
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class FavoriteViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
    val folders = MutableStateFlow<List<FavFolder>>(emptyList())
    val opened = MutableStateFlow<FavFolder?>(null)
    val videos = MutableStateFlow<List<FavVideo>>(emptyList())
    val selected = MutableStateFlow<Set<String>>(emptySet())
    val loading = MutableStateFlow(false)
    val importing = MutableStateFlow(false)
    val hasMore = MutableStateFlow(false)
    private var nextPage = 1
    private var paging = false

    init {
        viewModelScope.launch {
            loading.value = true
            try {
                folders.value = graph.bilibili.favoriteFolders()
            } catch (error: Exception) {
                Log.w("Favorites", "folders ${error.javaClass.simpleName}")
                graph.messages.value = error.message?.takeIf { it.isNotBlank() } ?: "无法读取收藏夹"
            } finally {
                loading.value = false
            }
        }
    }

    fun open(folder: FavFolder) {
        opened.value = folder
        videos.value = emptyList()
        selected.value = emptySet()
        hasMore.value = true
        nextPage = 1
        loadMore()
    }

    fun closeFolder() {
        opened.value = null
        videos.value = emptyList()
        selected.value = emptySet()
        hasMore.value = false
    }

    fun loadMore() {
        val folder = opened.value ?: return
        if (!hasMore.value || paging) return
        val page = nextPage
        paging = true
        loading.value = true
        viewModelScope.launch {
            try {
                val result = graph.bilibili.favoriteVideos(folder.id, page)
                if (opened.value?.id != folder.id) return@launch
                videos.value = videos.value + result.videos
                hasMore.value = result.hasMore
                nextPage = page + 1
            } catch (error: Exception) {
                hasMore.value = false
                Log.w("Favorites", "videos ${error.javaClass.simpleName}")
                graph.messages.value = error.message?.takeIf { it.isNotBlank() } ?: "无法读取收藏夹"
            } finally {
                paging = false
                loading.value = false
            }
        }
    }

    fun toggle(bvid: String) {
        val current = selected.value
        selected.value = if (bvid in current) current - bvid else current + bvid
    }

    fun importSelected(folderId: Long?, onDone: () -> Unit) {
        val bvids = selected.value.toList()
        if (bvids.isEmpty()) {
            graph.messages.value = "请至少选择一个视频"
            return
        }
        viewModelScope.launch {
            importing.value = true
            var imported = 0
            var failed = 0
            for (bvid in bvids) {
                try {
                    val parts = graph.library.lookupBvid(bvid)
                    imported += graph.library.importParts(parts, folderId).size
                } catch (_: Exception) {
                    failed += 1
                }
            }
            importing.value = false
            graph.messages.value = if (failed == 0) {
                "已导入 $imported 首"
            } else {
                "已导入 $imported 首，失败 $failed 个"
            }
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoriteScreen(onBack: () -> Unit, folderId: Long? = null, viewModel: FavoriteViewModel = viewModel()) {
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val opened by viewModel.opened.collectAsStateWithLifecycle()
    val videos by viewModel.videos.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val hasMore by viewModel.hasMore.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val folder = opened

    LaunchedEffect(folder?.id, videos.size, hasMore, loading) {
        if (folder == null || loading || !hasMore) return@LaunchedEffect
        snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@snapshotFlow false
            last >= listState.layoutInfo.totalItemsCount - 3
        }.collect { nearEnd ->
            if (nearEnd) viewModel.loadMore()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                if (folder != null) viewModel.closeFolder() else onBack()
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                folder?.title ?: "收藏夹",
                style = MaterialTheme.typography.titleLarge,
            )
        }
        when {
            folder == null && folders.isEmpty() && !loading -> EmptyHint("没有收藏夹。若刚保存过登录信息，请返回后重新进入。")
            folder == null -> LazyColumn(Modifier.weight(1f)) {
                items(folders, key = { it.id }) { item ->
                    ListItem(
                        headlineContent = { Text(item.title) },
                        supportingContent = { Text("${item.mediaCount} 个视频") },
                        modifier = Modifier.clickable { viewModel.open(item) },
                    )
                }
            }
            videos.isEmpty() && !loading -> EmptyHint("这个收藏夹里没有可导入的视频。")
            else -> LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                items(videos, key = { it.bvid }) { video ->
                    val checked = video.bvid in selected
                    ListItem(
                        headlineContent = { Text(video.title) },
                        supportingContent = { Text(video.author) },
                        leadingContent = { CoverImage(video.coverUrl) },
                        trailingContent = { Checkbox(checked = checked, onCheckedChange = { viewModel.toggle(video.bvid) }) },
                        modifier = Modifier.clickable { viewModel.toggle(video.bvid) },
                    )
                }
            }
        }
        if (loading || importing) {
            CircularProgressIndicator(Modifier.padding(16.dp).align(Alignment.CenterHorizontally))
        }
        if (folder != null) {
            Button(
                onClick = { viewModel.importSelected(folderId, onBack) },
                enabled = !importing && selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) { Text("导入到音频库") }
        }
    }
}
