package com.nanahoshi.audioplayer.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 作品详情、音频库作品页、B 站分 P 页共用的沉浸式两层滚动骨架：
 * 测量顶部沉浸式栏与 Hero 区域高度，随滚动渐显顶栏，并在多选时切换为选择顶栏。
 */
@Composable
internal fun ImmersiveWorkScaffold(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    pageState: ScrollState = rememberScrollState(),
    bottomPadding: PaddingValues = PaddingValues(bottom = 24.dp),
    loading: Boolean = false,
    selectionBar: (@Composable () -> Unit)? = null,
    topBar: @Composable (containerAlpha: Float) -> Unit,
    header: @Composable ColumnScope.(topBarInset: Dp, heroMeasureModifier: Modifier) -> Unit,
    controls: @Composable ColumnScope.() -> Unit,
    floatingActionButton: @Composable BoxScope.() -> Unit = {},
    files: LazyListScope.() -> Unit,
) {
    var topBarHeightPx by remember { mutableIntStateOf(0) }
    var heroHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val topBarInset = if (topBarHeightPx > 0) {
        with(density) { topBarHeightPx.toDp() }
    } else {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp
    }
    Box(modifier.fillMaxSize()) {
        WorkFileBrowser(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            bottomPadding = bottomPadding,
            loading = loading,
            pageState = pageState,
            pinnedTopInset = topBarInset,
            header = {
                header(topBarInset, Modifier.onSizeChanged { heroHeightPx = it.height })
            },
            controls = controls,
            files = files,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .onSizeChanged { topBarHeightPx = it.height },
        ) {
            if (selectionBar != null) {
                selectionBar()
            } else {
                topBar(
                    heroTopBarAlpha(
                        pageState.value.toFloat(),
                        heroHeightPx.toFloat(),
                        topBarHeightPx.toFloat(),
                    ),
                )
            }
        }
        floatingActionButton()
    }
}
