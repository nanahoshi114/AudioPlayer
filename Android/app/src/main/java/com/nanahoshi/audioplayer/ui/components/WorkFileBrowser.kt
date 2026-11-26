package com.nanahoshi.audioplayer.ui.components

import com.nanahoshi.audioplayer.R
import androidx.compose.ui.res.stringResource

import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nanahoshi.audioplayer.ui.library.WorkPageScrollConnection

/** Both work screens keep file controls outside the inner scrolling list. */
@Composable
internal fun WorkFileBrowser(
    state: LazyListState,
    modifier: Modifier = Modifier,
    bottomPadding: PaddingValues = PaddingValues(bottom = 24.dp),
    loading: Boolean = false,
    pageState: ScrollState = rememberScrollState(),
    pinnedTopInset: Dp = 0.dp,
    header: @Composable ColumnScope.() -> Unit,
    controls: @Composable ColumnScope.() -> Unit,
    files: LazyListScope.() -> Unit,
) {
    BoxWithConstraints(modifier) {
        val pageHeight = maxHeight
        val decay = rememberSplineBasedDecay<Float>()
        val connection = remember(pageState, state, decay) { WorkPageScrollConnection(pageState, state, decay) }
        Column(Modifier.fillMaxSize().verticalScroll(pageState)) {
            header()
            Column(Modifier.fillMaxWidth().height((pageHeight - pinnedTopInset).coerceAtLeast(0.dp))) {
                controls()
                if (loading) {
                    LoadingState(stringResource(R.string.stored_loading), Modifier.weight(1f).fillMaxWidth())
                } else {
                    LazyColumn(
                        state = state,
                        modifier = Modifier.fillMaxWidth().weight(1f).nestedScroll(connection),
                        contentPadding = bottomPadding,
                        flingBehavior = connection.fileFlingBehavior,
                        content = files,
                    )
                }
            }
        }
    }
}
