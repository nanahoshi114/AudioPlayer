package com.nanahoshi.audioplayer.ui.bilibili

import com.nanahoshi.audioplayer.bilibili.BiliCollectionKind
import com.nanahoshi.audioplayer.R
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nanahoshi.audioplayer.bilibili.BiliCollection
import com.nanahoshi.audioplayer.ui.CoverImage
import com.nanahoshi.audioplayer.ui.components.AppTopBar
import com.nanahoshi.audioplayer.ui.components.EmptyState
import com.nanahoshi.audioplayer.ui.formatDuration

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun BiliCollectionScreen(
    initial: BiliCollection? = null,
    playlistId: Long? = null,
    onBack: () -> Unit,
    model: BiliCollectionViewModel = viewModel(key = "collection:${playlistId ?: initial?.key}"),
) {
    LaunchedEffect(playlistId, initial?.key) { model.open(initial, playlistId) }
    LaunchedEffect(model.removed) { if (model.removed) onBack() }
    val back = {
        when {
            model.committing -> Unit
            model.preparing -> model.cancelPreparation()
            model.selected.isNotEmpty() -> model.clearSelection()
            else -> { model.stopRefresh(); onBack() }
        }
    }
    BackHandler(onBack = back)
    var menu by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    val state = rememberLazyListState()
    val collection = model.collection
    Column(Modifier.fillMaxSize()) {
        AppTopBar(stringResource(collection?.kind?.labelRes ?: R.string.bilibili_collection_fallback), onBack = back, actions = {
            IconButton(onClick = { menu = true }, enabled = !model.busy) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.bilibili_collection_actions))
            }
            DropdownMenu(menu, { menu = false }) {
                CollectionMenuItems(model.savedId != null,
                    onAction = { action -> menu = false; model.prepare(action) },
                    onUnbookmark = { menu = false; removing = true })
            }
        })
        if (model.loading || model.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(model.progress, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                if (!model.committing) TextButton(onClick = { if (model.preparing) model.cancelPreparation() else model.stopRefresh() }) { Text(stringResource(R.string.common_stop)) }
            }
        }
        LazyColumn(state = state, modifier = Modifier.weight(1f)) {
            item(key = "header") {
                if (collection != null) {
                    Row(Modifier.padding(16.dp)) {
                        CoverImage(collection.coverUrl, size = 88.dp)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(collection.title, style = MaterialTheme.typography.titleLarge)
                            Text(stringResource(R.string.bilibili_collection_owner, stringResource(collection.kind.labelRes), collection.mid), style = MaterialTheme.typography.bodySmall)
                            Text(
                                stringResource(
                                    if (model.complete) R.string.bilibili_video_count
                                    else R.string.bilibili_video_count_incomplete,
                                    model.videos.size,
                                ),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (collection.description.isNotBlank()) Text(collection.description, Modifier.padding(horizontal = 16.dp), maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (model.savedId == null) TextButton(onClick = model::bookmark, enabled = !model.busy) { Text(stringResource(R.string.bilibili_bookmark)) }
                        else Text(stringResource(R.string.bilibili_bookmarked), Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = model::refresh, enabled = !model.busy && !model.loading) { Text(stringResource(R.string.bilibili_refresh_catalogue)) }
                    }
                    CollectionPlaybackActions(model.selected.isNotEmpty(), !model.busy, onAction = { model.prepare(it) })
                }
                model.error?.let {
                    Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = model::refresh, enabled = !model.busy && !model.loading) { Text(stringResource(R.string.bilibili_retry_read)) }
                }
                if (model.complete && model.videos.isEmpty()) Text(
                    stringResource(
                        when (collection?.kind) {
                            BiliCollectionKind.SEASON -> R.string.bilibili_collection_empty_season
                            BiliCollectionKind.SERIES -> R.string.bilibili_collection_empty_series
                            null -> R.string.bilibili_collection_empty_online
                        },
                    ),
                    Modifier.padding(16.dp),
                )
            }
            item(key = "selection") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (model.selected.isEmpty()) stringResource(R.string.bilibili_videos)
                            else stringResource(R.string.bilibili_selected_count, model.selected.size),
                            Modifier.weight(1f),
                        )
                        val allSelected = model.videos.isNotEmpty() && model.selected.size == model.videos.size
                        TextButton(onClick = model::selectAll, enabled = !model.busy) {
                            Text(stringResource(if (allSelected) R.string.bilibili_deselect_all else R.string.bilibili_select_all_loaded))
                        }
                    }
                    CollectionSortSelector(model.sort, model::updateSort, enabled = !model.busy)
                    if (model.selected.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { model.prepareSelection(CollectionAction.PLAY) }, enabled = !model.busy) { Text(stringResource(R.string.bilibili_play_selected)) }
                        TextButton(onClick = { model.prepareSelection(CollectionAction.APPEND) }, enabled = !model.busy) { Text(stringResource(R.string.bilibili_add_queue)) }
                        TextButton(onClick = { model.prepareSelection(CollectionAction.SAVE) }, enabled = !model.busy) { Text(stringResource(R.string.common_add_to_library)) }
                    }
                }
            }
            items(model.videos, key = { it.bvid }) { video ->
                var rowMenu by remember { mutableStateOf(false) }
                ListItem(headlineContent = { Text(video.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("${formatDuration(video.durationMs)} · ${video.bvid}") },
                    leadingContent = {
                        if (model.selected.isEmpty()) CoverImage(video.coverUrl)
                        else Checkbox(video.bvid in model.selected, onCheckedChange = { if (!model.busy) model.toggle(video.bvid) })
                    }, trailingContent = {
                        Box {
                            IconButton(onClick = { rowMenu = true }, enabled = !model.busy) { Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.bilibili_video_actions)) }
                            DropdownMenu(rowMenu, { rowMenu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.common_add_to_library)) }, onClick = {
                                    rowMenu = false
                                    model.prepare(CollectionAction.SAVE, listOf(video))
                                })
                            }
                        }
                    }, modifier = Modifier.combinedClickable(enabled = !model.busy,
                        onClick = { if (model.selected.isNotEmpty()) model.toggle(video.bvid) else model.prepare(CollectionAction.SINGLE, listOf(video)) },
                        onLongClick = { model.toggle(video.bvid) }))
            }
        }
    }
    model.pending?.let { prepared ->
        CollectionFailureDialog(prepared, model::continuePending, model::retryPending, model::cancelPreparation)
    }
    if (model.singleParts.isNotEmpty()) AlertDialog(onDismissRequest = model::dismissParts, title = { Text(stringResource(R.string.bilibili_select_parts)) },
        text = { LazyColumn { items(model.singleParts, key = { it.cid }) { part -> TextButton(onClick = { model.playPart(part) }) { Text("P${part.page} · ${part.title}") } } } },
        confirmButton = { TextButton(onClick = model::dismissParts) { Text(stringResource(R.string.common_cancel)) } })
    model.copyName?.let { initialName ->
        var name by remember(initialName) { mutableStateOf(initialName) }
        AlertDialog(onDismissRequest = model::dismissCopy, title = { Text(stringResource(R.string.bilibili_copy_playlist)) },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.bilibili_playlist_name)) })
                    Text(stringResource(R.string.bilibili_copy_help), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { model.confirmCopy(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.bilibili_next_choose)) } },
            dismissButton = { TextButton(onClick = model::dismissCopy) { Text(stringResource(R.string.common_cancel)) } })
    }
    if (removing) AlertDialog(onDismissRequest = { removing = false }, title = { Text(stringResource(R.string.bilibili_remove_playlist)) },
        text = { Text(stringResource(R.string.bilibili_remove_playlist_message)) },
        confirmButton = { TextButton(onClick = { removing = false; model.unbookmark() }) { Text(stringResource(R.string.bilibili_remove)) } },
        dismissButton = { TextButton(onClick = { removing = false }) { Text(stringResource(R.string.common_keep)) } })
}

/** 路由参数解码失败时的兜底页：保留顶栏与返回，不再白屏。 */
@Composable
internal fun BiliCollectionRouteError(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        AppTopBar(stringResource(R.string.bilibili_collection_title), onBack = onBack)
        EmptyState(
            title = stringResource(R.string.bilibili_collection_open_failed_title),
            message = stringResource(R.string.bilibili_collection_open_failed_message),
            icon = Icons.Outlined.ErrorOutline,
            actionLabel = stringResource(R.string.common_back),
            onAction = onBack,
        )
    }
}

@Composable
internal fun CollectionFailureDialog(
    prepared: CollectionPreparation,
    onContinue: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(onDismissRequest = onCancel,
        title = { Text(stringResource(if (prepared.details.isEmpty()) R.string.bilibili_video_read_failed else R.string.bilibili_video_partial_read_failed)) },
        text = { LazyColumn { items(prepared.failed) { Text(it, Modifier.padding(vertical = 4.dp)) } } },
        confirmButton = {
            if (prepared.details.isNotEmpty()) {
                TextButton(onClick = onContinue) { Text(stringResource(R.string.bilibili_continue_remaining, prepared.details.size)) }
            }
        },
        dismissButton = { Row { TextButton(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }; TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) } } })
}

@Composable
internal fun CollectionPlaybackActions(selecting: Boolean, enabled: Boolean, onAction: (CollectionAction) -> Unit) {
    if (selecting) return
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onAction(CollectionAction.PLAY) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.bilibili_play_all_sorted)) }
        OutlinedButton(onClick = { onAction(CollectionAction.APPEND) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.bilibili_add_queue)) }
    }
}

@Composable
internal fun CollectionSortSelector(sort: CollectionSort, onSort: (CollectionSort) -> Unit, enabled: Boolean = true) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled) {
            Text(stringResource(R.string.bilibili_sort_label, stringResource(sort.labelRes)))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (option in CollectionSort.entries) {
                DropdownMenuItem(text = { Text(stringResource(option.labelRes)) }, onClick = {
                    expanded = false
                    onSort(option)
                }, trailingIcon = { if (option == sort) Icon(Icons.Default.Check, contentDescription = stringResource(R.string.a11y_selected)) })
            }
        }
    }
}

@Composable
internal fun CollectionMenuItems(saved: Boolean, onAction: (CollectionAction) -> Unit, onUnbookmark: () -> Unit) {
    for ((label, action) in listOf(stringResource(R.string.bilibili_add_to_library) to CollectionAction.SAVE,
        stringResource(R.string.bilibili_copy_playlist) to CollectionAction.COPY)) {
        DropdownMenuItem(text = { Text(label) }, onClick = { onAction(action) })
    }
    if (saved) DropdownMenuItem(text = { Text(stringResource(R.string.bilibili_remove_playlist)) }, onClick = onUnbookmark)
}
