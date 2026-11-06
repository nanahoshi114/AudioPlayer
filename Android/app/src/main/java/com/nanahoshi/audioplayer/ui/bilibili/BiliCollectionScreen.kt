package com.nanahoshi.audioplayer.ui.bilibili

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
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
        AppTopBar(collection?.label ?: "合集", onBack = back, actions = {
            IconButton(onClick = { menu = true }, enabled = !model.busy) { Icon(Icons.Default.MoreVert, "更多") }
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
                if (!model.committing) TextButton(onClick = { if (model.preparing) model.cancelPreparation() else model.stopRefresh() }) { Text("中止") }
            }
        }
        LazyColumn(state = state, modifier = Modifier.weight(1f)) {
            item(key = "header") {
                if (collection != null) {
                    Row(Modifier.padding(16.dp)) {
                        CoverImage(collection.coverUrl, size = 88.dp)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(collection.title, style = MaterialTheme.typography.titleLarge)
                            Text("${collection.label} · UID ${collection.mid}", style = MaterialTheme.typography.bodySmall)
                            Text(if (model.complete) "${model.videos.size} 个视频" else "已读取 ${model.videos.size} 个视频 · 目录尚未完整",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (collection.description.isNotBlank()) Text(collection.description, Modifier.padding(horizontal = 16.dp), maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (model.savedId == null) TextButton(onClick = model::bookmark, enabled = !model.busy) { Text("收藏到播放列表") }
                        else Text("已收藏到播放列表", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = model::refresh, enabled = !model.busy && !model.loading) { Text("刷新") }
                    }
                    CollectionPlaybackActions(model.selected.isNotEmpty(), !model.busy, onAction = { model.prepare(it) })
                }
                model.error?.let {
                    Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = model::refresh, enabled = !model.busy && !model.loading) { Text("重试读取目录") }
                }
                if (model.complete && model.videos.isEmpty()) Text("这个合集没有视频", Modifier.padding(16.dp))
            }
            item(key = "selection") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (model.selected.isEmpty()) "视频" else "已选 ${model.selected.size} 个", Modifier.weight(1f))
                        TextButton(onClick = model::selectAll, enabled = !model.busy) { Text(if (model.selected.isEmpty()) "全选已显示" else "全选 / 取消") }
                    }
                    CollectionSortSelector(model.sort, model::updateSort, enabled = !model.busy)
                    if (model.selected.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { model.prepareSelection(CollectionAction.PLAY) }, enabled = !model.busy) { Text("播放") }
                        TextButton(onClick = { model.prepareSelection(CollectionAction.APPEND) }, enabled = !model.busy) { Text("追加") }
                        TextButton(onClick = { model.prepareSelection(CollectionAction.SAVE) }, enabled = !model.busy) { Text("保存") }
                        TextButton(onClick = { model.prepareSelection(CollectionAction.DOWNLOAD) }, enabled = !model.busy) { Text("下载") }
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
                            IconButton(onClick = { rowMenu = true }, enabled = !model.busy) { Icon(Icons.Default.MoreVert, "视频操作") }
                            DropdownMenu(rowMenu, { rowMenu = false }) {
                                for ((label, action) in listOf("保存到音频库" to CollectionAction.SAVE, "下载" to CollectionAction.DOWNLOAD)) {
                                    DropdownMenuItem(text = { Text(label) }, onClick = { rowMenu = false; model.prepare(action, listOf(video)) })
                                }
                            }
                        }
                    }, modifier = Modifier.combinedClickable(enabled = !model.busy,
                        onClick = { if (model.selected.isNotEmpty()) model.toggle(video.bvid) else model.prepare(CollectionAction.SINGLE, listOf(video)) },
                        onLongClick = { model.toggle(video.bvid) }))
            }
        }
    }
    model.pending?.let { prepared ->
        AlertDialog(onDismissRequest = model::cancelPreparation, title = { Text("部分视频读取失败") },
            text = { LazyColumn { items(prepared.failed) { Text(it, Modifier.padding(vertical = 4.dp)) } } },
            confirmButton = { TextButton(onClick = model::continuePending, enabled = prepared.details.isNotEmpty()) { Text("继续处理 ${prepared.details.size} 个成功项") } },
            dismissButton = { Row { TextButton(onClick = model::retryPending) { Text("重试") }; TextButton(onClick = model::cancelPreparation) { Text("取消") } } })
    }
    if (model.singleParts.isNotEmpty()) AlertDialog(onDismissRequest = model::dismissParts, title = { Text("选择分 P") },
        text = { LazyColumn { items(model.singleParts, key = { it.cid }) { part -> TextButton(onClick = { model.playPart(part) }) { Text("P${part.page} · ${part.title}") } } } },
        confirmButton = { TextButton(onClick = model::dismissParts) { Text("取消") } })
    model.copyName?.let { initialName ->
        var name by remember(initialName) { mutableStateOf(initialName) }
        AlertDialog(onDismissRequest = model::dismissCopy, title = { Text("复制为自建播放列表") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("列表名称") }) },
            confirmButton = { TextButton(onClick = { model.confirmCopy(name) }, enabled = name.isNotBlank()) { Text("选择入库方式") } },
            dismissButton = { TextButton(onClick = model::dismissCopy) { Text("取消") } })
    }
    if (removing) AlertDialog(onDismissRequest = { removing = false }, title = { Text("取消收藏") },
        text = { Text("仅移除合集收藏和目录缓存；已保存曲目、下载文件及当前队列会保留。") },
        confirmButton = { TextButton(onClick = { removing = false; model.unbookmark() }) { Text("取消收藏") } },
        dismissButton = { TextButton(onClick = { removing = false }) { Text("返回") } })
}

@Composable
internal fun CollectionPlaybackActions(selecting: Boolean, enabled: Boolean, onAction: (CollectionAction) -> Unit) {
    if (selecting) return
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onAction(CollectionAction.PLAY) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("顺序播放全部") }
        OutlinedButton(onClick = { onAction(CollectionAction.APPEND) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("加到末尾") }
    }
}

@Composable
internal fun CollectionSortSelector(sort: CollectionSort, onSort: (CollectionSort) -> Unit, enabled: Boolean = true) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled) {
            Text("排序：${sort.label}")
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (option in CollectionSort.entries) {
                DropdownMenuItem(text = { Text(option.label) }, onClick = {
                    expanded = false
                    onSort(option)
                }, trailingIcon = { if (option == sort) Icon(Icons.Default.Check, contentDescription = "已选") })
            }
        }
    }
}

@Composable
internal fun CollectionMenuItems(saved: Boolean, onAction: (CollectionAction) -> Unit, onUnbookmark: () -> Unit) {
    for ((label, action) in listOf("保存到音频库" to CollectionAction.SAVE, "下载全部音轨" to CollectionAction.DOWNLOAD,
        "复制为自建播放列表" to CollectionAction.COPY)) {
        DropdownMenuItem(text = { Text(label) }, onClick = { onAction(action) })
    }
    if (saved) DropdownMenuItem(text = { Text("取消收藏") }, onClick = onUnbookmark)
}
