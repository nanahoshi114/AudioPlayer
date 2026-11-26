package com.nanahoshi.audioplayer.ui.bilibili

import com.nanahoshi.audioplayer.text.resolveNoUrl
import androidx.compose.ui.platform.LocalContext
import com.nanahoshi.audioplayer.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nanahoshi.audioplayer.bilibili.BiliCollection
import com.nanahoshi.audioplayer.ui.components.CoverImage
import com.nanahoshi.audioplayer.ui.components.EmptyState
import com.nanahoshi.audioplayer.ui.components.ErrorState
import com.nanahoshi.audioplayer.ui.components.LoadMoreOnScrollEnd

@Composable
internal fun CollectionCatalogue(model: BiliBrowserViewModel, open: (BiliCollection) -> Unit,
                                 state: LazyListState, modifier: Modifier = Modifier) {
    val page = model.collections
    LoadMoreOnScrollEnd(
        state = state,
        itemCount = page.items.size,
        canLoadMore = page.hasMore && !page.paused,
        loading = page.loading,
        hasError = page.error != null,
        onLoadMore = page::loadMore,
    )
    Column(modifier) {
        if (page.loading) Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.bilibili_reading_collections), Modifier.weight(1f).padding(16.dp))
            TextButton(onClick = page::cancel) { Text(stringResource(R.string.common_stop)) }
        }
        if (page.items.isEmpty() && !page.loading && page.error == null) EmptyState(
            title = stringResource(R.string.bilibili_catalogue_empty_title),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) else LazyColumn(
            state = state,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(page.items, key = { it.key }) { collection ->
                ListItem(headlineContent = { Text(collection.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(stringResource(R.string.bilibili_collection_summary, stringResource(collection.kind.labelRes), collection.total)) },
                    leadingContent = { CoverImage(collection.coverUrl) }, modifier = Modifier.clickable { open(collection) })
            }
            page.error?.let { message ->
                item(key = "error") {
                    ErrorState(
                        message = LocalContext.current.resolveNoUrl(message, R.string.bilibili_error_page_network),
                        onRetry = page::loadMore,
                    )
                }
            }
            if (page.hasMore && !page.loading && page.error == null) item(key = "more") {
                TextButton(onClick = page::loadMore, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bilibili_load_more)) }
            }
        }
    }
}
