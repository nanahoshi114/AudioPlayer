package com.nanahoshi.audioplayer.ui.bilibili

import com.nanahoshi.audioplayer.bilibili.BiliVideoHit
import org.junit.Assert.*
import org.junit.Test

class BiliCollectionOrderTest {
    private fun video(number: Int) = BiliVideoHit(
        "BV${number.toString().padStart(10, '0')}", "视频 $number", "UP",
        "https://example.invalid/$number.jpg", 1000,
    )

    @Test fun forwardAndReverseFollowRemoteOrderWithoutChangingCanonicalVideos() {
        val source = listOf(video(3), video(1), video(2))
        assertEquals(source, orderCollectionVideos(source, CollectionSort.FORWARD, 42))
        assertEquals(listOf(video(2), video(1), video(3)), orderCollectionVideos(source, CollectionSort.REVERSE, 42))
        assertEquals(listOf(video(3), video(1), video(2)), source)
        assertEquals("https://example.invalid/3.jpg", source.first().coverUrl)
    }

    @Test fun randomOrderIsRepeatableCompleteAndChangesWithSeed() {
        val source = (1..20).map(::video)
        val ordered = orderCollectionVideos(source, CollectionSort.RANDOM, 42)
        assertEquals(source.toSet(), ordered.toSet())
        assertEquals(source.size, ordered.size)
        assertEquals(ordered, orderCollectionVideos(source, CollectionSort.RANDOM, 42))
        assertNotEquals(ordered, orderCollectionVideos(source, CollectionSort.RANDOM, 43))
        assertNotEquals(source, ordered)
        assertEquals((1..20).map(::video), source)
    }

    @Test fun randomOrderSurvivesPagingAndMetadataRefreshByVideoIdentity() {
        val source = (1..20).map(::video)
        val partial = orderCollectionVideos(source.take(8), CollectionSort.RANDOM, 42)
        val updated = orderCollectionVideos(source.reversed().map { it.copy(title = "已更新") }, CollectionSort.RANDOM, 42)
        val partialIds = partial.map { it.bvid }
        assertEquals(partialIds, updated.filter { it.bvid in partialIds }.map { it.bvid })
        val removed = updated.filterIndexed { index, _ -> index % 2 == 0 }
        assertEquals(removed, orderCollectionVideos(removed, CollectionSort.RANDOM, 42))
    }

    @Test fun emptyAndSingleVideoWorkInEveryOrder() {
        for (sort in CollectionSort.entries) {
            assertTrue(orderCollectionVideos(emptyList(), sort, 42).isEmpty())
            assertEquals(listOf(video(1)), orderCollectionVideos(listOf(video(1)), sort, 42))
        }
    }
}
