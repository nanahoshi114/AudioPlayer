package com.nanahoshi.audioplayer.ui.library

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity

/**
 * 作品页两层滚动：
 * - 向下翻时先滚外层作品页，外层到底后才滚文件区；
 * - 向上翻时先滚文件区，文件区到顶后才滚回外层。
 */
internal class WorkPageScrollConnection(
    private val page: ScrollState,
    private val file: LazyListState,
) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || available.y >= 0f) return Offset.Zero
        if (!page.canScrollForward) return Offset.Zero
        val consumed = page.dispatchRawDelta(-available.y)
        return Offset(0f, -consumed)
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
        if (!page.canScrollBackward) return Offset.Zero
        val consumed = page.dispatchRawDelta(-available.y)
        return Offset(0f, -consumed)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (available.y < 0f && page.canScrollForward) {
            page.animateScrollTo(page.maxValue)
            return available
        }
        if (available.y > 0f && !file.canScrollBackward && page.canScrollBackward) {
            page.animateScrollTo(0)
            return available
        }
        return Velocity.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        if (available.y > 0f && page.canScrollBackward) {
            page.animateScrollTo(0)
            return available
        }
        return Velocity.Zero
    }
}
