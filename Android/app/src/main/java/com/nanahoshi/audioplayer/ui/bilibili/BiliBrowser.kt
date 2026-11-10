package com.nanahoshi.audioplayer.ui.bilibili

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nanahoshi.audioplayer.bilibili.*
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.ui.CoverImage
import com.nanahoshi.audioplayer.ui.components.ResetOnNewSearch
import com.nanahoshi.audioplayer.ui.components.EmptyState
import com.nanahoshi.audioplayer.ui.formatDuration
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BiliBrowser(onOpenCollection: (BiliCollection) -> Unit = {}, viewModel: BiliBrowserViewModel = viewModel()) {
    val tracks by viewModel.tracks.collectAsStateWithLifecycle(emptyList())
    val jobs by viewModel.downloads.collectAsStateWithLifecycle(emptyList())
    val videoState = rememberLazyListState()
    val userState = rememberLazyListState()
    val collectionState = key(viewModel.uploader?.mid) { rememberLazyListState() }
    ResetOnNewSearch(collectionState, viewModel.collections.revision)
    val uploadState = key(viewModel.uploader?.mid) { rememberLazyListState() }
    val uploader = viewModel.uploader
    val users = uploader == null && viewModel.mode == BiliSearchMode.UPLOADER
    val videoPage = viewModel.shownVideos
    val items = videoPage.items
    val loading = if (users) viewModel.uploaders.loading else videoPage.loading
    val error = if (users) viewModel.uploaders.error else videoPage.error
    val hasMore = if (users) viewModel.uploaders.hasMore else videoPage.hasMore
    val paused = if (users) viewModel.uploaders.paused else videoPage.paused
    val count = if (users) viewModel.uploaders.items.size else items.size
    val searched = if (users) viewModel.uploaders.searched else videoPage.searched
    val state = if (uploader != null) uploadState else if (users) userState else videoState
    val more = { if (users) viewModel.uploaders.loadMore() else videoPage.loadMore() }
    ResetOnNewSearch(videoState, viewModel.videos.revision)
    ResetOnNewSearch(userState, viewModel.uploaders.revision)
    ResetOnNewSearch(uploadState, viewModel.uploads.revision)
    LaunchedEffect(state, count, hasMore, error, loading, paused) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= count - 3 } == true }
            .collect { if (it && hasMore && error == null && !loading && !paused) more() }
    }
    BackHandler(viewModel.selected.isNotEmpty() || uploader != null || viewModel.resolving || viewModel.choice != null) {
        when {
            viewModel.resolving || viewModel.choice != null -> viewModel.cancelParts()
            viewModel.selected.isNotEmpty() -> viewModel.clearSelection()
            else -> viewModel.closeUploader()
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (uploader == null) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(viewModel.mode == BiliSearchMode.VIDEO, { viewModel.switchMode(BiliSearchMode.VIDEO) }, label = { Text("视频") })
                FilterChip(viewModel.mode == BiliSearchMode.UPLOADER, { viewModel.switchMode(BiliSearchMode.UPLOADER) }, label = { Text("UP 主") })
            }
            OutlinedTextField(value = viewModel.query, onValueChange = viewModel::updateQuery,
                label = { Text(if (users) "UP 主昵称、UID 或主页链接" else "关键词、BV 号或视频链接") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            if (!users) BiliSearchFilters(
                order = viewModel.videoOrder,
                duration = viewModel.videoDuration,
                onOrder = viewModel::updateVideoOrder,
                onDuration = viewModel::updateVideoDuration,
                enabled = !viewModel.working,
            )
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = viewModel::closeUploader) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回搜索结果") }
                Column(Modifier.weight(1f)) {
                    Text(uploader.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    Text("UID ${uploader.mid} · 最新投稿", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = viewModel::refreshUploader) { Text("刷新") }
            }
        }
        if (uploader != null) Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!viewModel.showingCollections, { viewModel.showCollections(false) }, label = { Text("投稿") })
            FilterChip(viewModel.showingCollections, { viewModel.showCollections(true) }, label = { Text("合集·系列") })
        }
        if (uploader != null && viewModel.showingCollections) {
            CollectionCatalogue(viewModel, onOpenCollection, collectionState, Modifier.weight(1f))
        } else {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (loading) "正在读取…" else "$count ${if (users) "位 UP 主" else "个视频"}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            if (loading) TextButton(onClick = viewModel::cancelSearch) { Text("中止") }
            else if (uploader == null) TextButton(onClick = viewModel::search, enabled = !viewModel.working) { Text("查找") }
            if (!users && items.isNotEmpty()) TextButton(onClick = viewModel::selectAll) {
                Text(if (viewModel.selected.size == items.size) "取消全选" else "全选已加载")
            }
        }
        if (viewModel.selected.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("已选 ${viewModel.selected.size} 个", Modifier.weight(1f))
                TextButton(onClick = viewModel::clearSelection) { Text("取消") }
                TextButton(enabled = !viewModel.resolving && !viewModel.working, onClick = {
                    viewModel.prepare(BiliAction.SAVE, items.filter { it.bvid in viewModel.selected })
                }) { Text("加入音频库") }
                TextButton(enabled = !viewModel.resolving && !viewModel.working, onClick = {
                    viewModel.prepare(BiliAction.DOWNLOAD, items.filter { it.bvid in viewModel.selected })
                }) { Text("下载") }
            }
        }
        if (viewModel.resolving) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("正在读取分 P…", Modifier.weight(1f))
            TextButton(onClick = viewModel::cancelParts) { Text("中止") }
        }
        if (loading || viewModel.resolving || viewModel.working) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (count == 0 && !loading && error == null) {
            EmptyState(title = if (searched) (if (users) "没有找到 UP 主" else "没有找到视频") else "搜索 B 站音频",
                message = if (uploader != null) "这位 UP 主暂时没有投稿。"
                    else if (users) "输入昵称查找 UP 主，也可以输入 UID 或主页链接。" else "输入关键词、BV 号或视频链接，查找后可播放、加入音频库或下载。")
        }
        LazyColumn(state = state, modifier = Modifier.weight(1f)) {
            if (users) {
                items(viewModel.uploaders.items, key = { it.mid }) { user ->
                    ListItem(headlineContent = { Text(user.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Column {
                            Text("UID ${user.mid} · 粉丝 ${user.fans ?: "—"} · 投稿 ${user.videos ?: "—"}", style = MaterialTheme.typography.bodySmall)
                            if (user.signature.isNotBlank()) Text(user.signature, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        } }, leadingContent = { CoverImage(user.avatar) }, modifier = Modifier.clickable { viewModel.openUploader(user) })
                }
            } else {
                items(items, key = { it.bvid }) { video ->
                    val saved = tracks.filter { it.source == TrackSource.BILIBILI && it.bvid == video.bvid }
                    val videoJobs = jobs.filter { it.source == TrackSource.BILIBILI.name && it.workKey == video.bvid }
                    val active = videoJobs.count { it.status in setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING) }
                    val downloaded = (saved.filter { localReady(it.localUri) }.map { it.cid.toString() } +
                        videoJobs.filter { it.status == DownloadStatus.DONE && localReady(it.localPath) }.map { it.fileKey }).toSet().size
                    var menu by remember { mutableStateOf(false) }
                    val busy = viewModel.resolving || viewModel.working
                    ListItem(headlineContent = { Text(video.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Column {
                            Text(listOf(video.author, formatDuration(video.durationMs)).filter { it.isNotBlank() }.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(if (saved.isNotEmpty()) "已加入 ${saved.size} 个分 P" else null,
                                if (downloaded > 0) "已下载 $downloaded 个分 P" else null, if (active > 0) "下载中 $active 个分 P" else null)
                                .joinToString(" · ").ifBlank { "B 站 · ${video.bvid}" }, style = MaterialTheme.typography.labelSmall)
                        } },
                        leadingContent = { if (viewModel.selected.isEmpty()) CoverImage(video.coverUrl)
                            else Checkbox(video.bvid in viewModel.selected, { viewModel.toggle(video.bvid) }) },
                        trailingContent = { if (viewModel.selected.isEmpty()) Box {
                            IconButton(onClick = { menu = true }, enabled = !busy) { Icon(Icons.Default.MoreVert, "更多") }
                            DropdownMenu(menu, { menu = false }) {
                                for (action in listOf(BiliAction.SAVE, BiliAction.DOWNLOAD)) DropdownMenuItem(text = { Text(action.label) }, onClick = {
                                    menu = false; viewModel.prepare(action, listOf(video))
                                })
                            }
                        } }, modifier = Modifier.combinedClickable(
                            onClick = { if (viewModel.selected.isNotEmpty()) viewModel.toggle(video.bvid)
                                else if (!busy) viewModel.prepare(BiliAction.PLAY, listOf(video)) },
                            onLongClick = { viewModel.toggle(video.bvid) }))
                }
            }
            if (error != null) item(key = "error") {
                Column(Modifier.padding(16.dp)) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = more) { Text("重试") }
                }
            } else if (hasMore && !loading) item(key = "more") { TextButton(onClick = more, modifier = Modifier.fillMaxWidth()) { Text("加载更多") } }
        }
    }
    }
    viewModel.choice?.let { choice ->
        val allParts = choice.groups.sumOf { it.parts.size }
        AlertDialog(onDismissRequest = viewModel::cancelParts, title = { Text("选择分 P · ${choice.action.label}") },
            text = { Column {
                if (choice.action != BiliAction.PLAY) TextButton(onClick = viewModel::selectAllParts) {
                    Text(if (choice.selected.size == allParts) "取消全选" else "全选")
                }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    for (group in choice.groups) {
                        item(key = group.video.bvid) { Text(group.video.title, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.titleSmall) }
                        items(group.parts, key = { "${it.bvid}:${it.cid}" }) { part ->
                            val checked = (part.bvid to part.cid) in choice.selected
                            Row(Modifier.fillMaxWidth().clickable { viewModel.togglePart(part) }, verticalAlignment = Alignment.CenterVertically) {
                                if (choice.action == BiliAction.PLAY) RadioButton(checked, { viewModel.togglePart(part) })
                                else Checkbox(checked, { viewModel.togglePart(part) })
                                Column(Modifier.weight(1f)) {
                                    Text("P${part.page} · ${part.title}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(formatDuration(part.durationMs), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    if (choice.failed.isNotEmpty()) item(key = "failures-heading") { Text("以下视频读取失败，其余内容可继续：", color = MaterialTheme.colorScheme.error) }
                    items(choice.failed) { Text(it, Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodySmall) }
                }
            } }, confirmButton = { TextButton(onClick = viewModel::confirmParts, enabled = choice.selected.isNotEmpty()) { Text(choice.action.label) } },
            dismissButton = { TextButton(onClick = viewModel::cancelParts) { Text("取消") } })
    }
}

@Composable
internal fun BiliSearchFilters(
    order: BiliVideoOrder,
    duration: BiliVideoDuration,
    onOrder: (BiliVideoOrder) -> Unit,
    onDuration: (BiliVideoDuration) -> Unit,
    enabled: Boolean = true,
) {
    var orderMenu by remember { mutableStateOf(false) }
    var durationMenu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { orderMenu = true }, enabled = enabled) { Text(order.label) }
            DropdownMenu(expanded = orderMenu, onDismissRequest = { orderMenu = false }) {
                BiliVideoOrder.entries.forEach { option ->
                    DropdownMenuItem(text = { Text(option.label) }, onClick = {
                        orderMenu = false
                        onOrder(option)
                    })
                }
            }
        }
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { durationMenu = true }, enabled = enabled) { Text(duration.label) }
            DropdownMenu(expanded = durationMenu, onDismissRequest = { durationMenu = false }) {
                BiliVideoDuration.entries.forEach { option ->
                    DropdownMenuItem(text = { Text(option.label) }, onClick = {
                        durationMenu = false
                        onDuration(option)
                    })
                }
            }
        }
    }
}

private fun localReady(path: String?): Boolean = !path.isNullOrBlank() && if (path.startsWith('/')) File(path).isFile else true
