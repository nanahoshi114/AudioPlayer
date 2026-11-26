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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nanahoshi.audioplayer.bilibili.*
import com.nanahoshi.audioplayer.ui.asmr.PendingOnlineSearch
import com.nanahoshi.audioplayer.ui.components.CoverImage
import com.nanahoshi.audioplayer.ui.components.DropdownFilterButton
import com.nanahoshi.audioplayer.ui.components.EmptyState
import com.nanahoshi.audioplayer.ui.components.ErrorState
import com.nanahoshi.audioplayer.ui.components.LoadMoreOnScrollEnd
import com.nanahoshi.audioplayer.ui.components.ResetOnNewSearch
import com.nanahoshi.audioplayer.ui.components.RoundedProgress
import com.nanahoshi.audioplayer.ui.components.SearchField
import com.nanahoshi.audioplayer.ui.components.SelectableLeading
import com.nanahoshi.audioplayer.ui.components.SelectionAction
import com.nanahoshi.audioplayer.ui.components.SelectionTopBar
import com.nanahoshi.audioplayer.ui.components.TopBarOverride
import com.nanahoshi.audioplayer.ui.components.formatDuration

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BiliBrowser(
    topBarOwnerKey: String,
    onOpenCollection: (BiliCollection) -> Unit = {},
    pendingUploaderSearch: PendingOnlineSearch.BiliUploader? = null,
    onConsumeSearch: (Long) -> Unit = {},
    viewModel: BiliBrowserViewModel = viewModel(),
) {
    LaunchedEffect(pendingUploaderSearch?.id) {
        pendingUploaderSearch?.let { request ->
            viewModel.searchUploader(request.name)
            onConsumeSearch(request.id)
        }
    }
    val videoStats by viewModel.videoStats.collectAsStateWithLifecycle()
    val videoState = rememberLazyListState()
    val userState = rememberLazyListState()
    val collectionState = key(viewModel.uploader?.mid) { rememberLazyListState() }
    ResetOnNewSearch(collectionState, viewModel.collections.revision)
    val uploadState = key(viewModel.uploader?.mid) { rememberLazyListState() }
    val uploader = viewModel.uploader
    val users = uploader == null && viewModel.mode == BiliSearchMode.UPLOADER
    val videoPage = viewModel.shownVideos
    val items = videoPage.items
    val selecting = viewModel.selected.isNotEmpty()
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
    LoadMoreOnScrollEnd(
        state = state,
        itemCount = count,
        canLoadMore = hasMore && !paused,
        loading = loading,
        hasError = error != null,
        onLoadMore = more,
    )
    BackHandler(selecting || uploader != null || viewModel.resolving || viewModel.choice != null) {
        when {
            viewModel.resolving || viewModel.choice != null -> viewModel.cancelParts()
            viewModel.selected.isNotEmpty() -> viewModel.clearSelection()
            else -> viewModel.closeUploader()
        }
    }
    if (selecting) {
        TopBarOverride(topBarOwnerKey) {
            val allSelected = items.isNotEmpty() && items.all { it.bvid in viewModel.selected }
            SelectionTopBar(
                count = viewModel.selected.size,
                onClose = viewModel::clearSelection,
                actions = listOf(
                    SelectionAction(
                        stringResource(if (allSelected) R.string.bilibili_deselect_all else R.string.bilibili_select_all_loaded),
                        if (allSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
                        viewModel::selectAll,
                    ),
                    SelectionAction(
                        stringResource(R.string.common_add_to_library),
                        Icons.Default.LibraryAdd,
                        onClick = { viewModel.prepare(BiliAction.SAVE, items.filter { it.bvid in viewModel.selected }) },
                        enabled = !viewModel.resolving && !viewModel.working,
                    ),
                ),
            )
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (uploader == null) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(viewModel.mode == BiliSearchMode.VIDEO, { viewModel.switchMode(BiliSearchMode.VIDEO) }, label = { Text(stringResource(R.string.bilibili_mode_video)) })
                FilterChip(viewModel.mode == BiliSearchMode.UPLOADER, { viewModel.switchMode(BiliSearchMode.UPLOADER) }, label = { Text(stringResource(R.string.bilibili_mode_uploader)) })
            }
            SearchField(
                value = viewModel.query,
                onValueChange = viewModel::updateQuery,
                placeholder = stringResource(if (users) R.string.bilibili_search_user_hint else R.string.bilibili_search_video_hint),
                onSearch = { viewModel.search() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
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
            if (!selecting && !users && items.isNotEmpty()) TextButton(onClick = viewModel::selectAll) {
                Text(stringResource(R.string.bilibili_select_all_loaded))
            }
        }
        if (viewModel.resolving) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.bilibili_reading_parts), Modifier.weight(1f))
            TextButton(onClick = viewModel::cancelParts) { Text(stringResource(R.string.common_stop)) }
        }
        if (loading || viewModel.resolving || viewModel.working) RoundedProgress(progress = null)
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
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
        LazyColumn(
            state = state,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (users) {
                items(viewModel.uploaders.items, key = { it.mid }) { user ->
                    ListItem(headlineContent = { Text(user.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Column {
                            Text(stringResource(R.string.bilibili_user_summary, user.mid, user.fans ?: "—", user.videos ?: "—"), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            if (user.signature.isNotBlank()) Text(user.signature, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        } }, leadingContent = { CoverImage(user.avatar, shape = MaterialTheme.shapes.small) },
                        modifier = Modifier.clickable { viewModel.openUploader(user) })
                }
            } else {
                items(items, key = { it.bvid }) { video ->
                    val stats = videoStats[video.bvid]
                    val savedCount = stats?.savedCount ?: 0
                    val downloaded = stats?.downloadedCount ?: 0
                    val active = stats?.activeCount ?: 0
                    var menu by remember { mutableStateOf(false) }
                    val busy = viewModel.resolving || viewModel.working
                    ListItem(headlineContent = { Text(video.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Column {
                            Text(listOf(video.author, formatDuration(video.durationMs)).filter { it.isNotBlank() }.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(
                                if (savedCount > 0) stringResource(R.string.bilibili_saved_parts, savedCount) else null,
                                if (downloaded > 0) stringResource(R.string.bilibili_downloaded_parts, downloaded) else null,
                                if (active > 0) stringResource(R.string.bilibili_active_parts, active) else null,
                            )
                                .joinToString(" · ").ifBlank { stringResource(R.string.bilibili_bvid_label, video.bvid) }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                        } },
                        leadingContent = {
                            SelectableLeading(
                                selecting = selecting,
                                selected = video.bvid in viewModel.selected,
                                onToggle = { viewModel.toggle(video.bvid) },
                                label = "biliVideoLead",
                            ) {
                                CoverImage(video.coverUrl, shape = MaterialTheme.shapes.small)
                            }
                        },
                        trailingContent = { if (!selecting) Box {
                            IconButton(onClick = { menu = true }, enabled = !busy) { Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.a11y_video_actions)) }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(BiliAction.SAVE.labelRes)) }, onClick = {
                                    menu = false; viewModel.prepare(BiliAction.SAVE, listOf(video))
                                })
                            }
                        } }, modifier = Modifier.combinedClickable(
                            onClick = { if (selecting) viewModel.toggle(video.bvid)
                                else if (!busy) viewModel.prepare(BiliAction.PLAY, listOf(video)) },
                            onLongClick = { viewModel.toggle(video.bvid) }))
                }
            }
            if (error != null) item(key = "error") {
                ErrorState(
                    message = LocalContext.current.resolveNoUrl(error, R.string.bilibili_error_page_network),
                    onRetry = more,
                )
            } else if (hasMore && !loading) item(key = "more") { TextButton(onClick = more, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bilibili_load_more)) } }
        }
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
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { viewModel.togglePart(part) }, verticalAlignment = Alignment.CenterVertically) {
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
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        DropdownFilterButton(
            options = BiliVideoOrder.entries,
            selected = order,
            optionLabel = { stringResource(it.labelRes) },
            onSelect = onOrder,
            modifier = Modifier.weight(1f),
            enabled = enabled,
        )
        DropdownFilterButton(
            options = BiliVideoDuration.entries,
            selected = duration,
            optionLabel = { stringResource(it.labelRes) },
            onSelect = onDuration,
            modifier = Modifier.weight(1f),
            enabled = enabled,
        )
    }
}
