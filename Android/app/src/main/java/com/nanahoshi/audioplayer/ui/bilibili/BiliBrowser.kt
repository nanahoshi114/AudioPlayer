package com.nanahoshi.audioplayer.ui.bilibili

import com.nanahoshi.audioplayer.text.resolveNoUrl
import androidx.compose.ui.platform.LocalContext
import com.nanahoshi.audioplayer.R
import androidx.compose.ui.res.stringResource
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
                FilterChip(viewModel.mode == BiliSearchMode.VIDEO, { viewModel.switchMode(BiliSearchMode.VIDEO) }, label = { Text(stringResource(R.string.bilibili_mode_video)) })
                FilterChip(viewModel.mode == BiliSearchMode.UPLOADER, { viewModel.switchMode(BiliSearchMode.UPLOADER) }, label = { Text(stringResource(R.string.bilibili_mode_uploader)) })
            }
            OutlinedTextField(value = viewModel.query, onValueChange = viewModel::updateQuery,
                label = { Text(stringResource(if (users) R.string.bilibili_search_user_hint else R.string.bilibili_search_video_hint)) }, singleLine = true,
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
                IconButton(onClick = viewModel::closeUploader) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.bilibili_back_results)) }
                Column(Modifier.weight(1f)) {
                    Text(uploader.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.bilibili_uploader_latest, uploader.mid), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = viewModel::refreshUploader) { Text(stringResource(R.string.bilibili_refresh)) }
            }
        }
        if (uploader != null) Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!viewModel.showingCollections, { viewModel.showCollections(false) }, label = { Text(stringResource(R.string.bilibili_submissions)) })
            FilterChip(viewModel.showingCollections, { viewModel.showCollections(true) }, label = { Text(stringResource(R.string.bilibili_collections)) })
        }
        if (uploader != null && viewModel.showingCollections) {
            CollectionCatalogue(viewModel, onOpenCollection, collectionState, Modifier.weight(1f))
        } else {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (loading) stringResource(R.string.bilibili_loading)
                else stringResource(if (users) R.string.bilibili_count_uploaders else R.string.bilibili_count_videos, count),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            if (loading) TextButton(onClick = viewModel::cancelSearch) { Text(stringResource(R.string.common_stop)) }
            else if (uploader == null) TextButton(onClick = viewModel::search, enabled = !viewModel.working) { Text(stringResource(R.string.bilibili_find)) }
            if (!users && items.isNotEmpty()) TextButton(onClick = viewModel::selectAll) {
                Text(stringResource(if (viewModel.selected.size == items.size) R.string.bilibili_deselect_all else R.string.bilibili_select_all_loaded))
            }
        }
        if (viewModel.selected.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.bilibili_selected_count, viewModel.selected.size), Modifier.weight(1f))
                TextButton(onClick = viewModel::clearSelection) { Text(stringResource(R.string.common_cancel_selection)) }
                TextButton(enabled = !viewModel.resolving && !viewModel.working, onClick = {
                    viewModel.prepare(BiliAction.SAVE, items.filter { it.bvid in viewModel.selected })
                }) { Text(stringResource(R.string.common_add_to_library)) }
            }
        }
        if (viewModel.resolving) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.bilibili_reading_parts), Modifier.weight(1f))
            TextButton(onClick = viewModel::cancelParts) { Text(stringResource(R.string.common_stop)) }
        }
        if (loading || viewModel.resolving || viewModel.working) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (count == 0 && !loading && error == null) {
            EmptyState(
                title = stringResource(
                    when {
                        uploader != null -> R.string.bilibili_empty_video_title
                        searched && users -> R.string.bilibili_empty_uploader_title
                        searched -> R.string.bilibili_empty_video_title
                        else -> R.string.bilibili_empty_search_title
                    },
                ),
                message = stringResource(
                    when {
                        uploader != null -> R.string.bilibili_empty_uploader_message
                        users -> R.string.bilibili_empty_user_hint
                        else -> R.string.bilibili_empty_video_hint
                    },
                ),
            )
        }
        LazyColumn(state = state, modifier = Modifier.weight(1f)) {
            if (users) {
                items(viewModel.uploaders.items, key = { it.mid }) { user ->
                    ListItem(headlineContent = { Text(user.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Column {
                            Text(stringResource(R.string.bilibili_user_summary, user.mid, user.fans ?: "—", user.videos ?: "—"), style = MaterialTheme.typography.bodySmall)
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
                            Text(listOfNotNull(
                                if (saved.isNotEmpty()) stringResource(R.string.bilibili_saved_parts, saved.size) else null,
                                if (downloaded > 0) stringResource(R.string.bilibili_downloaded_parts, downloaded) else null,
                                if (active > 0) stringResource(R.string.bilibili_active_parts, active) else null,
                            )
                                .joinToString(" · ").ifBlank { stringResource(R.string.bilibili_bvid_label, video.bvid) }, style = MaterialTheme.typography.labelSmall)
                        } },
                        leadingContent = { if (viewModel.selected.isEmpty()) CoverImage(video.coverUrl)
                            else Checkbox(video.bvid in viewModel.selected, { viewModel.toggle(video.bvid) }) },
                        trailingContent = { if (viewModel.selected.isEmpty()) Box {
                            IconButton(onClick = { menu = true }, enabled = !busy) { Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.a11y_video_actions)) }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(BiliAction.SAVE.labelRes)) }, onClick = {
                                    menu = false; viewModel.prepare(BiliAction.SAVE, listOf(video))
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
                    Text(LocalContext.current.resolveNoUrl(error, R.string.bilibili_error_page_network), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = more) { Text(stringResource(R.string.common_retry)) }
                }
            } else if (hasMore && !loading) item(key = "more") { TextButton(onClick = more, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bilibili_load_more)) } }
        }
    }
    }
    viewModel.choice?.let { choice ->
        val allParts = choice.groups.sumOf { it.parts.size }
        AlertDialog(onDismissRequest = viewModel::cancelParts, title = {
            Text(stringResource(R.string.bilibili_select_parts_action, stringResource(choice.action.labelRes)))
        },
            text = { Column {
                if (choice.action != BiliAction.PLAY) TextButton(onClick = viewModel::selectAllParts) {
                    Text(stringResource(if (choice.selected.size == allParts) R.string.bilibili_deselect_all else R.string.bilibili_select_all))
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
                    if (choice.failed.isNotEmpty()) item(key = "failures-heading") { Text(stringResource(R.string.bilibili_failures_heading), color = MaterialTheme.colorScheme.error) }
                    items(choice.failed) { Text(it, Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodySmall) }
                }
            } }, confirmButton = { TextButton(onClick = viewModel::confirmParts, enabled = choice.selected.isNotEmpty()) { Text(stringResource(choice.action.labelRes)) } },
            dismissButton = { TextButton(onClick = viewModel::cancelParts) { Text(stringResource(R.string.common_cancel)) } })
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
            TextButton(onClick = { orderMenu = true }, enabled = enabled) { Text(stringResource(order.labelRes)) }
            DropdownMenu(expanded = orderMenu, onDismissRequest = { orderMenu = false }) {
                BiliVideoOrder.entries.forEach { option ->
                    DropdownMenuItem(text = { Text(stringResource(option.labelRes)) }, onClick = {
                        orderMenu = false
                        onOrder(option)
                    })
                }
            }
        }
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { durationMenu = true }, enabled = enabled) { Text(stringResource(duration.labelRes)) }
            DropdownMenu(expanded = durationMenu, onDismissRequest = { durationMenu = false }) {
                BiliVideoDuration.entries.forEach { option ->
                    DropdownMenuItem(text = { Text(stringResource(option.labelRes)) }, onClick = {
                        durationMenu = false
                        onDuration(option)
                    })
                }
            }
        }
    }
}

private fun localReady(path: String?): Boolean = !path.isNullOrBlank() && if (path.startsWith('/')) File(path).isFile else true
