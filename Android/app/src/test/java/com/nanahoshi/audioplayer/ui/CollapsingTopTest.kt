package com.nanahoshi.audioplayer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollapsingTopTest {
    @Test
    fun contentThatFitsOnlyWhenCollapsedKeepsTheHeaderCollapsible() {
        assertTrue(canCollapseLazyList(0, 0, true, 400, 420, 24, 0f))
        assertTrue(canCollapseLazyList(0, 0, false, 500, 420, 24, 100f))
    }

    @Test
    fun respectsTheExpandedBoundaryAndExistingScrollPosition() {
        assertFalse(canCollapseLazyList(0, 0, false, 500, 376, 24, 100f))
        assertTrue(canCollapseLazyList(0, 1, false, 500, 376, 24, 100f))
        assertTrue(canCollapseLazyList(0, 0, true, 500, null, 24, 100f))
    }

    @Test
    fun contentTallerThanTheFullViewportStaysCollapsible() {
        // 内容 420dp，展开视口 376dp，完整视口 400dp：始终可折叠。
        for (collapsed in listOf(0f, 8f, 16f, 24f, 40f)) {
            assertTrue(canCollapseLazyList(0, 0, false, 400, 420, 0, collapsed))
        }
    }

    @Test
    fun contentShorterThanTheExpandedViewportCannotCollapse() {
        assertFalse(canCollapseLazyList(0, 0, false, 376, 350, 0, 0f))
        assertFalse(canCollapseLazyList(0, 0, false, 376, 350, 0, 24f))
    }

    @Test
    fun middleBandRemainsCollapsibleAsTheHeaderCollapsesWithoutOscillating() {
        // 内容 390dp 落在 (展开视口 376, 完整视口 400] 之间；
        // 一旦开始折叠，collapsedPx 增长只会让判断更宽松，不会反向弹回。
        for (collapsed in 0..24 step 4) {
            assertTrue(canCollapseLazyList(0, 0, false, 376, 390, 0, collapsed.toFloat()))
        }
    }

    @Test
    fun emptyContentAndAlreadyScrolledListsFollowTheGuard() {
        assertFalse(canCollapseLazyList(0, 0, false, 400, null, 0, 0f))
        assertTrue(canCollapseLazyList(0, 0, true, 400, null, 0, 0f))
        assertTrue(canCollapseLazyList(1, 0, false, 400, 100, 0, 100f))
        assertTrue(canCollapseLazyList(0, 1, false, 400, 100, 0, 100f))
    }
}
