package com.nanahoshi.audioplayer.ui.asmr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePagingTest {
    @Test
    fun freshStateStartsAtPageOne() {
        val paging = SourcePaging()
        assertEquals(1, paging.next())
        assertTrue(paging.hasMore)
    }

    @Test
    fun successAdvancesToNextPage() {
        val paging = SourcePaging()
        paging.succeed(1, true)
        assertEquals(1, paging.lastPage)
        assertEquals(2, paging.next())
    }

    @Test
    fun failedPageIsRetriedNotSkipped() {
        val paging = SourcePaging()
        paging.succeed(1, true)
        // 第 2 页失败：不调用 succeed，页码和 hasMore 保持。
        assertEquals(2, paging.next())
        assertTrue(paging.hasMore)
    }

    @Test
    fun sourcesPageIndependently() {
        val asmr = SourcePaging()
        val dlsite = SourcePaging()
        asmr.succeed(1, true)
        assertEquals(2, asmr.next())
        assertEquals(1, dlsite.next())
    }

    @Test
    fun exhaustedSourceKeepsHasMoreFalse() {
        val paging = SourcePaging()
        paging.succeed(2, false)
        assertFalse(paging.hasMore)
        assertEquals(3, paging.next())
    }

    @Test
    fun resetRestoresFirstPage() {
        val paging = SourcePaging()
        paging.succeed(3, false)
        paging.reset()
        assertEquals(1, paging.next())
        assertTrue(paging.hasMore)
    }

    @Test
    fun pageHasMoreBoundaries() {
        assertTrue(pageHasMore(20, 1, 40))
        assertFalse(pageHasMore(20, 2, 40))
        assertFalse(pageHasMore(19, 1, 100))
        assertTrue(pageHasMore(20, 1, 21))
        assertFalse(pageHasMore(0, 1, 0))
    }
}