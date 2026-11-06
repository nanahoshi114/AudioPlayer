package com.nanahoshi.audioplayer.bilibili

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BiliVideoCollectionTest {
    private val bvid = "BV1234567890"
    private fun response(season: String? = null) = """{"code":0,"data":{
        "bvid":"$bvid","title":"视频","pic":"http://example.invalid/video.jpg","duration":5,
        "cid":101,"owner":{"mid":123,"name":"UP"},
        "pages":[{"cid":101,"page":1,"part":"第一 P","duration":2},{"cid":102,"page":2,"part":"第二 P","duration":3}]
        ${season?.let { ",\"ugc_season\":$it" }.orEmpty()}
    }}"""

    @Test fun videoDetailsReturnTheDeclaredSeasonAlongsideAllParts() {
        val result = parseBiliVideoDetails(response("""{"id":7,"title":"合集 &amp; 标题","mid":"456","cover":"//example.invalid/cover.jpg","intro":"介绍","ep_count":10}"""), bvid)
        val collection = result.collection!!
        assertEquals(BiliCollectionKind.SEASON, collection.kind)
        assertEquals(7L, collection.remoteId); assertEquals(456L, collection.mid)
        assertEquals("合集 & 标题", collection.title); assertEquals("", collection.coverUrl)
        assertEquals("介绍", collection.description); assertEquals(10, collection.total)
        assertEquals(listOf(101L, 102L), result.parts.map { it.cid })
        assertEquals(listOf(1, 2), result.parts.map { it.page })
        assertEquals("视频 - 第二 P", result.parts.last().title)
        assertEquals(123L, result.video.mid)
    }

    @Test fun absentOrIncompleteSeasonDoesNotBreakVideoPlaybackDetails() {
        for (raw in listOf(null, "null", "{}", "[]", """{"id":0,"title":"A","mid":123}""",
            """{"id":7,"title":" ","mid":123}""", """{"id":7,"title":"A","mid":0}""",
            """{"id":7,"title":"A","mid":"invalid"}""")) {
            val result = parseBiliVideoDetails(response(raw), bvid)
            assertNull(raw, result.collection)
            assertEquals(2, result.parts.size)
        }
    }

    @Test fun missingSeasonOwnerFallsBackToVideoOwnerAndSinglePartFallbackRemainsValid() {
        val result = parseBiliVideoDetails("""{"code":0,"data":{"cid":101,"title":"单 P","duration":5,"owner":{"mid":123},"ugc_season":{"id":7,"title":"合集"}}}""", bvid)
        assertEquals(123L, result.collection!!.mid)
        assertEquals(101L, result.parts.single().cid)
        assertEquals(5000L, result.parts.single().durationMs)
        assertEquals(bvid, result.parts.single().bvid)
    }

    @Test fun failedOrMismatchedResponsesAreNotSuccessfulMembershipLookups() {
        for (raw in listOf("<html>temporary error</html>", "{}", """{"code":-404}""",
            """{"code":0,"data":{"bvid":"BV1234567891"}}""")) {
            try { parseBiliVideoDetails(raw, bvid); fail() } catch (_: BilibiliException) { }
        }
    }

    @Test fun seasonAlwaysUsesFirstEpisodeEvenWhenItHasItsOwnCover() {
        val season = """{"id":7,"title":"合集","mid":123,"sections":[{"episodes":[]},{"episodes":[
            {"arc":{"pic":"//example.invalid/first.jpg"}},{"arc":{"pic":"//example.invalid/second.jpg"}}]}]}"""
        val result = parseBiliVideoDetails(response(season), bvid).collection!!
        assertEquals("https://example.invalid/first.jpg", result.coverUrl)
        assertTrue(result.coverIsFallback)
        val custom = season.replace("\"id\":7", "\"id\":7,\"cover\":\"//example.invalid/custom.jpg\"")
        val explicit = parseBiliVideoDetails(response(custom), bvid).collection!!
        assertEquals("https://example.invalid/first.jpg", explicit.coverUrl)
        assertTrue(explicit.coverIsFallback)
    }

    @Test fun concurrentLookupsShareOneRequestAndSuccessfulAbsenceIsCached() = runBlocking {
        val cache = BiliVideoCollectionCache()
        var calls = 0
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val first = async { cache.resolve(bvid) { calls++; started.complete(Unit); release.await(); null } }
        started.await()
        val second = async { cache.resolve(bvid) { calls++; null } }
        release.complete(Unit)
        assertNull(first.await()); assertNull(second.await())
        assertNull(cache.resolve(bvid) { calls++; null })
        assertEquals(1, calls)
        assertTrue(cache.values.value.containsKey(bvid))
    }

    @Test fun failedLookupCanBeRetriedAndCacheHasBoundedSize() = runBlocking {
        val cache = BiliVideoCollectionCache()
        try { cache.resolve(bvid) { throw BilibiliException(-412, "restricted") }; fail() } catch (_: BilibiliException) { }
        assertFalse(cache.values.value.containsKey(bvid))
        val collection = BiliCollection(BiliCollectionKind.SEASON, 7, 123, "合集")
        assertEquals(collection, cache.resolve(bvid) { collection })
        repeat(65) { cache.record("video-$it", null) }
        assertEquals(64, cache.values.value.size)
    }
}
