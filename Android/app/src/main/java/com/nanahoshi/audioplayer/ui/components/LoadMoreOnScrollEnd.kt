package com.nanahoshi.audioplayer.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun LoadMoreOnScrollEnd(
    state: LazyListState,
    itemCount: Int,
    canLoadMore: Boolean,
    loading: Boolean = false,
    hasError: Boolean = false,
    threshold: Int = 3,
    loadWhenEmpty: Boolean = false,
    onLoadMore: () -> Unit,
) {
    LaunchedEffect(state, itemCount, canLoadMore, loading, hasError, threshold, loadWhenEmpty) {
        if (!canLoadMore || loading || hasError) return@LaunchedEffect
        snapshotFlow {
            if (itemCount == 0) return@snapshotFlow loadWhenEmpty
            val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@snapshotFlow false
            last >= itemCount - threshold
        }
            .distinctUntilChanged()
            .collect { nearEnd ->
                if (nearEnd) onLoadMore()
            }
    }
}
