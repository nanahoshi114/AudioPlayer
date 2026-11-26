package com.nanahoshi.audioplayer.ui.library

import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.resolveUserError
import androidx.compose.ui.res.stringResource
import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import com.nanahoshi.audioplayer.ui.components.AppTopBar
import com.nanahoshi.audioplayer.ui.components.CoverImage
import com.nanahoshi.audioplayer.ui.components.EmptyState
import com.nanahoshi.audioplayer.ui.components.ErrorState
import com.nanahoshi.audioplayer.ui.components.LoadMoreOnScrollEnd
import com.nanahoshi.audioplayer.ui.components.LoadingState
import com.nanahoshi.audioplayer.ui.components.SelectionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nanahoshi.audioplayer.bilibili.FavFolder
import com.nanahoshi.audioplayer.bilibili.FavVideo
import com.nanahoshi.audioplayer.graph
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class FavoriteViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
    val folders = MutableStateFlow<List<FavFolder>>(emptyList())
    val opened = MutableStateFlow<FavFolder?>(null)
    val videos = MutableStateFlow<List<FavVideo>>(emptyList())
    private val selection = SelectionState<String>()
    val selected = selection.flow
    val loading = MutableStateFlow(false)
    val importing = MutableStateFlow(false)
    val hasMore = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    private var nextPage = 1
    private var paging = false
    private var pagingJob: Job? = null
    private var generation = 0L

    init { reloadFolders() }

    fun reloadFolders() {
        if (loading.value || opened.value != null) return
        loading.value = true
        error.value = null
        viewModelScope.launch {
            try {
                folders.value = graph.bilibili.favoriteFolders()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("Favorites", "folders ${failure.javaClass.simpleName}")
                error.value = getApplication<Application>().resolveUserError(failure, R.string.bilibili_favorites_read_failed)
            } finally {
                loading.value = false
            }
        }
    }

    fun open(folder: FavFolder) {
        stopPaging()
        opened.value = folder
        videos.value = emptyList()
        selection.clear()
        hasMore.value = true
        nextPage = 1
        loadMore()
    }

    fun closeFolder() {
        stopPaging()
        opened.value = null
        videos.value = emptyList()
        selection.clear()
        hasMore.value = false
        error.value = null
    }

    /** 换收藏夹时先作废旧分页，否则旧任务的 paging/loading 会盖住新文件夹的首次加载。 */
    private fun stopPaging() {
        generation++
        pagingJob?.cancel()
        pagingJob = null
        if (paging) loading.value = false
        paging = false
    }

    fun loadMore() {
        val folder = opened.value ?: return
        if (!hasMore.value || paging) return
        val page = nextPage
        val token = generation
        paging = true
        loading.value = true
        error.value = null
        pagingJob = viewModelScope.launch {
            try {
                val result = graph.bilibili.favoriteVideos(folder.id, page)
                if (token != generation) return@launch
                videos.value = (videos.value + result.videos).distinctBy { it.bvid }
                hasMore.value = result.hasMore
                nextPage = page + 1
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (token != generation) return@launch
                Log.w("Favorites", "videos ${failure.javaClass.simpleName}")
                error.value = getApplication<Application>().resolveUserError(failure, R.string.bilibili_favorites_read_failed)
            } finally {
                if (token == generation) {
                    paging = false
                    loading.value = false
                }
            }
        }
    }

    fun toggle(bvid: String) {
        selection.toggle(bvid)
    }

    fun importSelected(onDone: () -> Unit) {
        val bvids = selected.value.toList()
        if (bvids.isEmpty()) {
            graph.show(R.string.bilibili_select_video)
            return
        }
        viewModelScope.launch {
            importing.value = true
            try {
                val details = mutableListOf<com.nanahoshi.audioplayer.bilibili.BiliVideoDetails>()
                val failed = mutableListOf<String>()
                for (bvid in bvids) {
                    try { details += graph.bilibili.videoDetails(bvid) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { failed += getApplication<Application>().getString(R.string.bilibili_part_read_failed, bvid) }
                }
                if (details.isEmpty()) { graph.show(R.string.bilibili_no_importable_videos); return@launch }
                val requests = graph.biliImportRequests.choose(details, failed) ?: return@launch
                requests.forEach { graph.library.importBili(it) }
                graph.show(
                    if (failed.isEmpty()) TextRef.Res(R.string.online_added_to_library)
                    else TextRef.Res(R.string.bilibili_added_with_failures, listOf(failed.size)),
                )
                onDone()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { graph.showError(failure, R.string.bilibili_add_failed) }
            finally { importing.value = false }
        }
    }


}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoriteScreen(onBack: () -> Unit, viewModel: FavoriteViewModel = viewModel()) {
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val opened by viewModel.opened.collectAsStateWithLifecycle()
    val videos by viewModel.videos.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val hasMore by viewModel.hasMore.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val folder = opened
    val listState = key(folder?.id) { rememberLazyListState() }
    val loadError = error

    BackHandler(enabled = folder != null) { viewModel.closeFolder() }

    LoadMoreOnScrollEnd(
        state = listState,
        itemCount = videos.size,
        canLoadMore = folder != null && hasMore,
        loading = loading,
        hasError = loadError != null,
        loadWhenEmpty = true,
        onLoadMore = viewModel::loadMore,
    )

    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            title = folder?.title ?: stringResource(R.string.bilibili_favorites_title),
            onBack = { if (folder != null) viewModel.closeFolder() else onBack() },
        )
        when {
            folder == null && loadError != null -> ErrorState(
                loadError,
                onRetry = viewModel::reloadFolders,
                modifier = Modifier.weight(1f),
            )
            folder == null && folders.isEmpty() && !loading -> EmptyState(
                title = stringResource(R.string.bilibili_favorites_empty_title),
                message = stringResource(R.string.bilibili_favorites_empty_message),
                actionLabel = stringResource(R.string.common_retry),
                onAction = viewModel::reloadFolders,
                modifier = Modifier.weight(1f),
            )
            folder == null -> LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            ) {
                items(folders, key = { it.id }) { item ->
                    ListItem(
                        headlineContent = { Text(item.title) },
                        supportingContent = { Text(stringResource(R.string.bilibili_media_count, item.mediaCount)) },
                        modifier = Modifier.clickable { viewModel.open(item) },
                    )
                }
            }
            videos.isEmpty() && loadError != null -> ErrorState(
                loadError,
                onRetry = viewModel::loadMore,
                modifier = Modifier.weight(1f),
            )
            videos.isEmpty() && !loading && !hasMore -> EmptyState(
                title = stringResource(R.string.bilibili_favorite_detail_empty_title),
                message = stringResource(R.string.bilibili_favorite_detail_empty_message),
                modifier = Modifier.weight(1f),
            )
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            ) {
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
                if (loadError != null) {
                    item("page-error") { ErrorState(loadError, onRetry = viewModel::loadMore) }
                }
            }
        }
        if (loading || importing) {
            LoadingState()
        }
        if (folder != null) {
            Button(
                onClick = { viewModel.importSelected(onBack) },
                enabled = !importing && selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            ) { Text(stringResource(R.string.common_add_to_library)) }
        }
    }
}
