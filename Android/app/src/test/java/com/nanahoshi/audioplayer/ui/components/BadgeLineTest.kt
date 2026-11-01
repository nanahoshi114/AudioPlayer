package com.nanahoshi.audioplayer.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class BadgeLineTest {
    private val gap = 6f
    private val overflow = { _: Int -> 30f }

    @Test
    fun showsAllWhenEverythingFits() {
        val shown = fitBadgeCount(
            widths = listOf(80f, 30f, 30f, 30f),
            leadingCount = 1,
            totalCount = 4,
            available = 300f,
            gap = gap,
            overflowWidth = overflow,
        )
        assertEquals(4, shown)
    }

    @Test
    fun reservesSpaceForOverflowBadge() {
        val shown = fitBadgeCount(
            widths = listOf(80f, 30f, 30f, 30f),
            leadingCount = 1,
            totalCount = 4,
            available = 180f,
            gap = gap,
            overflowWidth = overflow,
        )
        assertEquals(2, shown)
    }

    @Test
    fun keepsPinnedBadges() {
        val shown = fitBadgeCount(
            widths = listOf(80f, 60f, 30f),
            leadingCount = 2,
            totalCount = 3,
            available = 170f,
            gap = gap,
            overflowWidth = overflow,
        )
        assertEquals(2, shown)
    }

    @Test
    fun unmeasuredTagsStillCount() {
        val shown = fitBadgeCount(
            widths = listOf(80f, 30f),
            leadingCount = 1,
            totalCount = 10,
            available = 200f,
            gap = gap,
            overflowWidth = overflow,
        )
        assertEquals(2, shown)
    }

    @Test
    fun pinnedWiderThanAvailableIsStillShown() {
        val shown = fitBadgeCount(
            widths = listOf(100f),
            leadingCount = 1,
            totalCount = 1,
            available = 50f,
            gap = gap,
            overflowWidth = overflow,
        )
        assertEquals(1, shown)
    }
}
