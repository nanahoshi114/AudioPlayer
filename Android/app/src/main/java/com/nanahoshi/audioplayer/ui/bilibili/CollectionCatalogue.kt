package com.nanahoshi.audioplayer.ui.bilibili

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nanahoshi.audioplayer.bilibili.BiliCollection
import com.nanahoshi.audioplayer.ui.CoverImage
import com.nanahoshi.audioplayer.ui.components.EmptyState

@Composable
internal fun CollectionCatalogue(model: BiliBrowserViewModel, open: (BiliCollection) -> Unit,
                                 state: LazyListState, modifier: Modifier = Modifier) {
    val page = model.collections
    LaunchedEffect(state, page.items.size, page.loading, page.hasMore, page.error, page.paused) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= page.items.size - 3 } == true }
            .collect { if (it && page.hasMore && !page.loading && page.error == null && !page.paused) page.loadMore() }
    }
    Column(modifier) {
        if (page.loading) Row(Modifier.fillMaxWidth()) {
            Text("正在读取合集…", Modifier.weight(1f).padding(16.dp))
            TextButton(onClick = page::cancel) { Text("中止") }
        }
        if (page.items.isEmpty() && !page.loading && page.error == null) EmptyState(title = "没有读到合集或系列", message = "可以刷新后重试。")
        LazyColumn(state = state, modifier = Modifier.weight(1f)) {
            items(page.items, key = { it.key }) { collection ->
                ListItem(headlineContent = { Text(collection.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("${collection.label} · ${collection.total} 个视频") },
                    leadingContent = { CoverImage(collection.coverUrl) }, modifier = Modifier.clickable { open(collection) })
            }
            page.error?.let { message -> item(key = "error") {
                Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = page::loadMore) { Text("重试当前页") }
            } }
            if (page.hasMore && !page.loading && page.error == null) item(key = "more") {
                TextButton(onClick = page::loadMore, modifier = Modifier.fillMaxWidth()) { Text("加载更多") }
            }
        }
    }
}
