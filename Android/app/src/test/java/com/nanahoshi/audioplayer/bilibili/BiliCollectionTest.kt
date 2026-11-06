package com.nanahoshi.audioplayer.bilibili

import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class BiliCollectionTest {
    private val season = BiliCollection(BiliCollectionKind.SEASON, 7, 123, "合集")
    private fun video(number: Int) = BiliVideoHit("BV123456789$number", "video $number", "UP", "", 1000)

    @Test fun catalogueSeparatesEqualSeasonAndSeriesIdsAndUsesCombinedTotal() = runBlocking {
        val api = BiliCollectionApi { path, params, referer ->
            assertEquals("/x/polymer/web-space/seasons_series_list", path)
            assertEquals("123", params["mid"]); assertEquals("20", params["page_size"])
            assertEquals("https://space.bilibili.com/123", referer)
            Json.parseToJsonElement("""{"items_lists":{"page":{"total":21},"seasons_list":[{"meta":{"season_id":7,"name":"<b>A</b>","cover":"//i.example/a","total":2}}],"series_list":[{"meta":{"series_id":7,"name":"B","total":3}}]}}""")
        }
        val first = api.catalogue(123, 1)
        assertEquals(listOf("SEASON:7", "SERIES:7"), first.items.map { it.key })
        assertEquals("A", first.items[0].title); assertEquals("", first.items[0].coverUrl)
        assertTrue(first.hasMore); assertFalse(api.catalogue(123, 2).hasMore)
    }

    @Test fun contentsUseDistinctPaginationAndSecondsAreNotParsedAsMinutes() = runBlocking {
        val paths = mutableListOf<String>()
        val api = BiliCollectionApi { path, params, _ ->
            paths += path
            when {
                path.endsWith("seasons_archives_list") -> {
                    assertEquals("false", params["sort_reverse"]); assertEquals("2", params["page_num"])
                    Json.parseToJsonElement("""{"meta":{"name":"new"},"page":{"total":31},"archives":[{"bvid":"BV1234567890","title":"A","duration":61}]}""")
                }
                path.endsWith("/series/series") -> Json.parseToJsonElement("""{"meta":{"name":"renamed series"}}""")
                else -> {
                    assertEquals("asc", params["sort"]); assertEquals("1", params["pn"])
                    Json.parseToJsonElement("""{"page":{"total":1},"archives":[{"bvid":"BV1234567890","duration":61}]}""")
                }
            }
        }
        val result = api.videos(season, 2)
        assertEquals(61000L, result.videos.single().durationMs); assertFalse(result.hasMore)
        val series = api.videos(season.copy(kind = BiliCollectionKind.SERIES), 1)
        assertEquals("renamed series", series.collection.title)
        assertTrue(paths.contains("/x/series/archives"))
    }

    @Test fun completeSnapshotKeepsServerOrderAndDeduplicatesOverlappingPages() = runBlocking {
        val result = loadBiliCollection(season, { ref, page ->
            BiliCollectionPage(ref.copy(total = 3), if (page == 1) listOf(video(2), video(1)) else listOf(video(1), video(3)), page == 1)
        })
        assertEquals(listOf(video(2), video(1), video(3)), result.videos)
        assertTrue(result.complete)
    }

    @Test fun failuresAndCancellationPropagate() = runBlocking {
        for (mode in listOf("failure", "cancel")) {
            var returned = false
            try {
                loadBiliCollection(season, { ref, page ->
                    if (page == 2) {
                        if (mode == "failure") throw BilibiliException(-412, "restricted")
                        throw CancellationException()
                    }
                    BiliCollectionPage(ref.copy(total = 3), listOf(video(1)), true)
                })
                returned = true
            } catch (_: BilibiliException) { } catch (_: CancellationException) { }
            assertFalse(mode, returned)
        }
    }

    @Test fun incompleteDirectoryIsRetriedOnceAndNeverReportedComplete() = runBlocking {
        var calls = 0
        val result = loadBiliCollection(season, { ref, _ ->
            calls++
            BiliCollectionPage(ref.copy(total = 3), listOf(video(1)), false)
        })
        assertEquals(2, calls)
        assertFalse(result.complete)
        assertEquals(listOf(video(1)), result.videos)
    }

    @Test fun retryRecoversWhenSecondPassMatches() = runBlocking {
        var calls = 0
        val result = loadBiliCollection(season, { ref, _ ->
            calls++
            if (calls == 1) BiliCollectionPage(ref.copy(total = 3), listOf(video(1)), false)
            else BiliCollectionPage(ref.copy(total = 3), listOf(video(1), video(2), video(3)), false)
        })
        assertEquals(2, calls)
        assertTrue(result.complete)
        assertEquals(3, result.videos.size)
    }

    @Test fun stuckPaginationReturnsIncompleteInsteadOfLooping() = runBlocking {
        var calls = 0
        val result = loadBiliCollection(season, { ref, _ ->
            calls++
            BiliCollectionPage(ref.copy(total = 3), listOf(video(1)), true)
        })
        assertEquals(4, calls)
        assertFalse(result.complete)
    }

    @Test fun malformedContentsAreErrorsRatherThanEmptyCollections() = runBlocking {
        for (raw in listOf("{}", """{"page":{"total":1}}""", """{"page":{"total":1},"archives":[{"bvid":"invalid"}]}""")) {
            try { BiliCollectionApi { _, _, _ -> Json.parseToJsonElement(raw) }.videos(season, 1); fail(raw) }
            catch (_: BilibiliException) { }
        }
    }

    @Test fun catalogueAlwaysUsesFirstVideoCoverEvenWhenMetadataHasCover() = runBlocking {
        val api = BiliCollectionApi { _, _, _ ->
            Json.parseToJsonElement("""{"items_lists":{"page":{"total":2},
                "seasons_list":[{"meta":{"season_id":7,"name":"合集"},"archives":[{"pic":"//i.example/first.jpg"},{"pic":"//i.example/second.jpg"}]}],
                "series_list":[{"meta":{"series_id":8,"name":"系列","cover":"//i.example/custom.jpg"},"archives":[{"pic":"//i.example/first.jpg"}]}]}}""")
        }
        val items = api.catalogue(123, 1).items
        assertEquals("https://i.example/first.jpg", items[0].coverUrl)
        assertTrue(items[0].coverIsFallback)
        assertEquals("https://i.example/first.jpg", items[1].coverUrl)
        assertTrue(items[1].coverIsFallback)
    }

    @Test fun firstVideoCoverReplacesLegacyCoverAndDoesNotUseLaterPages() = runBlocking {
        val api = BiliCollectionApi { _, params, _ ->
            val page = params.getValue("page_num").toInt()
            val videos = if (page == 1) (0 until 30).map { index ->
                """{"bvid":"BV${index.toString().padStart(10, '0')}","pic":"//i.example/first-$index.jpg"}"""
            } else listOf("""{"bvid":"BV0000000030","pic":"//i.example/last-page.jpg"}""")
            Json.parseToJsonElement("""{"meta":{"name":"合集","cover":"//i.example/meta.jpg"},"page":{"total":31},"archives":[${videos.joinToString(",")}] }""")
        }
        val old = season.copy(coverUrl = "https://i.example/old-first.jpg", coverIsFallback = true)
        val result = loadBiliCollection(old, api::videos)
        assertEquals("https://i.example/first-0.jpg", result.collection.coverUrl)
        assertTrue(result.collection.coverIsFallback)
        val custom = season.copy(coverUrl = "https://i.example/custom.jpg")
        assertEquals("https://i.example/first-0.jpg", loadBiliCollection(custom, api::videos).collection.coverUrl)
    }
    @Test fun missingFirstVideoImageNeverFallsBackToMetadataCoverOrLaterVideo() = runBlocking {
        val api = BiliCollectionApi { path, _, _ ->
            if (path.endsWith("seasons_series_list")) {
                Json.parseToJsonElement("""{"items_lists":{"page":{"total":1},"seasons_list":[{"meta":{"season_id":7,"name":"合集","cover":"//i.example/meta.jpg"},"archives":[{}, {"pic":"//i.example/second.jpg"}]}],"series_list":[]}}""")
            } else Json.parseToJsonElement("""{"meta":{"name":"合集","cover":"//i.example/meta.jpg"},"page":{"total":2},"archives":[{"bvid":"BV1234567890"},{"bvid":"BV1234567891","pic":"//i.example/second.jpg"}]}""")
        }
        assertEquals("", api.catalogue(123, 1).items.single().coverUrl)
        assertEquals("", loadBiliCollection(season.copy(coverUrl = "https://i.example/legacy.jpg"), api::videos).collection.coverUrl)
    }

}
