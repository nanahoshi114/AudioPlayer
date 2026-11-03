package com.nanahoshi.audioplayer.ui.components

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
import androidx.compose.ui.unit.dp
import com.nanahoshi.audioplayer.ui.library.WorkPageScrollConnection

/** Both work screens keep file controls outside the inner scrolling list. */
@Composable
internal fun WorkFileBrowser(
    state: LazyListState,
    modifier: Modifier = Modifier,
    bottomPadding: PaddingValues = PaddingValues(bottom = 24.dp),
    header: @Composable ColumnScope.() -> Unit,
    controls: @Composable ColumnScope.() -> Unit,
    files: LazyListScope.() -> Unit,
) {
    BoxWithConstraints(modifier) {
        val pageHeight = maxHeight
        val page = rememberScrollState()
        val connection = remember(page, state) { WorkPageScrollConnection(page, state) }
        Column(Modifier.fillMaxSize().verticalScroll(page)) {
            header()
            Column(Modifier.fillMaxWidth().height(pageHeight).nestedScroll(connection)) {
                controls()
                LazyColumn(state = state, modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = bottomPadding, content = files)
            }
        }
    }
}
