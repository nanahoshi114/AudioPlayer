package com.nanahoshi.audioplayer.bilibili

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class BiliSearchTest {
    private val bvid = "BV1234567890"
    @Test fun videoFiltersMapToServerParametersAndDoNotAffectUploaderSearch() = runBlocking {
        val requests = mutableListOf<Map<String, String>>()
        val api = BiliSearchApi { _, params, _, _ ->
            requests += params.toMap()
            """{"code":0,"data":{"numPages":0,"result":[]}}"""
        }
        api.videos("x", 1)
        assertEquals("totalrank", requests.last()["order"])
        assertEquals("0", requests.last()["duration"])
        val orders = listOf("totalrank", "click", "pubdate", "dm", "stow", "scores")
        BiliVideoOrder.entries.forEachIndexed { index, order ->
            BiliVideoDuration.entries.forEachIndexed { durationIndex, duration ->
                api.videos("x", 2, order, duration)
                assertEquals(orders[index], requests.last()["order"])
                assertEquals("$durationIndex", requests.last()["duration"])
                assertEquals("2", requests.last()["page"])
            }
        }
        api.uploaders("UP", 1)
        assertEquals("0", requests.last()["order"])
        assertFalse(requests.last().containsKey("duration"))
    }

    @Test fun changedFilterResetsPagesAndIgnoresOldResultsWhileRetriesKeepTheSnapshot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val state = BiliPagedState<String, String>(scope) { it }
            lateinit var old: Continuation<BiliPage<String>>
            state.start { suspendCoroutine { old = it } }
            val revision = state.revision
            val requests = mutableListOf<Triple<Int, BiliVideoOrder, BiliVideoDuration>>()
            var failure = true
            val order = BiliVideoOrder.FAVORITES
            val duration = BiliVideoDuration.OVER_SIXTY
            state.start { page ->
                requests += Triple(page, order, duration)
                if (page == 2 && failure) { failure = false; throw BilibiliException(-412, "受限") }
                BiliPage(listOf("$page"), page < 2)
            }
            assertTrue(state.revision > revision)
            old.resume(BiliPage(listOf("old"), false))
            assertEquals(listOf("1"), state.items)
            state.loadMore()
            assertEquals(listOf("1"), state.items)
            state.loadMore()
            assertEquals(listOf("1", "2"), state.items)
            assertEquals(listOf(1, 2, 2), requests.map { it.first })
            assertTrue(requests.all { it.second == order && it.third == duration })
        } finally { scope.cancel() }
    }
    @Test fun structuredInputsTakePriorityAndNumbersDependOnMode() {
        for (mode in BiliSearchMode.entries) {
            assertEquals(BiliSearchInput.Video(bvid), biliInput("https://www.bilibili.com/video/$bvid?p=2", mode))
            assertEquals(BiliSearchInput.Video(bvid), biliInput("分享 $bvid", mode))
            assertEquals(BiliSearchInput.Uploader(123), biliInput("https://space.bilibili.com/123/upload/video", mode))
            assertEquals(BiliSearchInput.Uploader(123), biliInput("UID:123", mode))
        }
        assertEquals(BiliSearchInput.Keyword("123", BiliSearchMode.VIDEO), biliInput("123", BiliSearchMode.VIDEO))
        assertEquals(BiliSearchInput.Uploader(123), biliInput("123", BiliSearchMode.UPLOADER))
        assertEquals(BiliSearchInput.Keyword("昵称", BiliSearchMode.UPLOADER), biliInput(" 昵称 ", BiliSearchMode.UPLOADER))
        assertTrue(biliInput("https://space.bilibili.com.evil.test/123", BiliSearchMode.VIDEO) is BiliSearchInput.Keyword)
        for (raw in listOf("", "UID:0", "999999999999999999999999999999999")) {
            try { biliInput(raw, BiliSearchMode.UPLOADER); fail(raw) } catch (_: BilibiliException) { }
        }
    }
    @Test fun videoResultsRemoveMarkupNormalizeCoverAndParseDuration() = runBlocking {
        val api = BiliSearchApi { path, params, _, refresh ->
            assertEquals("/x/web-interface/wbi/search/type", path)
            assertEquals("video", params["search_type"]); assertEquals("20", params["page_size"])
            assertEquals("2", params["page"]); assertFalse(refresh)
            """{"code":0,"data":{"numPages":3,"result":[
              {"bvid":"$bvid","title":"A <em class='keyword'>B</em> &amp; C","pic":"//i0.hdslb.com/x.jpg","author":"UP","mid":2,"duration":"1:02:03"},
              {"bvid":"$bvid","title":"duplicate"},{"bvid":"invalid"}]}}"""
        }
        val result = api.videos("B", 2)
        val video = result.items.single()
        assertEquals("A B & C", video.title); assertEquals("https://i0.hdslb.com/x.jpg", video.coverUrl)
        assertEquals(3723000L, video.durationMs); assertEquals(2L, video.mid); assertTrue(result.hasMore)
        assertEquals(62000L, biliDuration(" 1:02 ")); assertEquals(0L, biliDuration("直播"))
    }
    @Test fun uploaderResultsAndUploadsUseSeparateEndpointsAndPagination() = runBlocking {
        val api = BiliSearchApi { path, params, referer, _ ->
            if (path.contains("search/type")) {
                assertEquals("bili_user", params["search_type"])
                """{"code":0,"data":{"numPages":1,"result":[{"mid":123,"uname":"<em>昵称</em>","upic":"http://i0.hdslb.com/a.jpg","usign":"简介","fans":99,"videos":31}]}}"""
            } else {
                assertEquals("123", params["mid"]); assertEquals("30", params["ps"]); assertEquals("pubdate", params["order"])
                assertTrue(referer.startsWith("https://space.bilibili.com/123/"))
                """{"code":0,"data":{"page":{"count":31},"list":{"vlist":[{"bvid":"$bvid","title":"投稿","length":"02:30","author":"昵称"}]}}}"""
            }
        }
        val users = api.uploaders("昵称", 1)
        assertFalse(users.hasMore); assertEquals("昵称", users.items.single().name)
        assertEquals(99L, users.items.single().fans); assertEquals(31L, users.items.single().videos)
        assertEquals("https://i0.hdslb.com/a.jpg", users.items.single().avatar)
        assertTrue(api.uploads(123, 1).hasMore); assertFalse(api.uploads(123, 2).hasMore)
        assertEquals(150000L, api.uploads(123, 1).items.single().durationMs)
    }
    @Test fun invalidWbiRefreshesOnceAndOtherErrorsNeverBecomeEmptyResults() = runBlocking {
        val refreshes = mutableListOf<Boolean>()
        val api = BiliSearchApi { _, _, _, refresh ->
            refreshes += refresh
            if (!refresh) """{"code":-403}""" else """{"code":0,"data":{"numResults":0,"numPages":0,"result":[]}}"""
        }
        assertTrue(api.videos("test",1).items.isEmpty()); assertEquals(listOf(false,true), refreshes)
        for (code in listOf(-403,-352,-412,-101,412)) {
            var requests = 0
            val failing = BiliSearchApi { _,_,_,_ -> requests++; throw BilibiliException(code,biliSearchError(code)) }
            try { failing.uploads(123,1);fail() } catch (error: BilibiliException) { assertEquals(code,error.code) }
            assertEquals(if (code == -403) 2 else 1,requests)
        }
    }
    @Test fun malformedDataAndCancellationPropagate() = runBlocking {
        for (raw in listOf("<html>", "{}", """{"code":0}""", """{"code":0,"data":{}}""")) {
            try { BiliSearchApi { _,_,_,_ -> raw }.videos("x",1);fail() } catch (_: BilibiliException) { }
        }
        var calls = 0
        try { BiliSearchApi { _,_,_,_ -> calls++;throw CancellationException() }.videos("x",1);fail() }
        catch (_: CancellationException) { }
        assertEquals(1,calls)
    }
    @Test fun pagesDeduplicateWithoutLosingOrderOrLoadingForever() {
        val merged = mergeBiliPage(listOf("a","b"),BiliPage(listOf("b","c","c"),true)) { it }
        assertEquals(listOf("a","b","c"),merged.items);assertTrue(merged.hasMore)
        assertFalse(mergeBiliPage(merged.items,BiliPage(listOf("a","b"),true)) { it }.hasMore)
    }
    @Test fun failedPageCanRetryAndKeepsAlreadyLoadedResults() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val state = BiliPagedState<String,String>(scope) { it }
            val pages = mutableListOf<Int>();var fail = true
            state.start { page ->
                pages += page
                if (page == 2 && fail) { fail = false;throw BilibiliException(-412,biliSearchError(-412)) }
                BiliPage(listOf("$page"),page < 2)
            }
            state.loadMore();assertEquals(listOf("1"),state.items);assertNotNull(state.error)
            state.loadMore();assertEquals(listOf("1","2"),state.items);assertNull(state.error)
            assertEquals(listOf(1,2,2),pages);assertFalse(state.hasMore)
        } finally { scope.cancel() }
    }
    @Test fun staleResponseCannotReplaceNewSearchAndCancellationKeepsPageRetryable() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val state = BiliPagedState<String,String>(scope) { it }
            lateinit var old: Continuation<BiliPage<String>>
            state.start { suspendCoroutine { old = it } }
            state.start { BiliPage(listOf("new"),false) }
            old.resume(BiliPage(listOf("old"),false));assertEquals(listOf("new"),state.items)
            val gate = CompletableDeferred<Unit>()
            state.start { gate.await();BiliPage(listOf("resumed"),false) }
            state.cancel();assertFalse(state.loading);assertTrue(state.paused)
            gate.complete(Unit);state.loadMore();assertEquals(listOf("resumed"),state.items);assertFalse(state.paused)
        } finally { scope.cancel() }
    }
    @Test fun persistedImportMetadataContainsOnlyThePartAndChecksIdentity() {
        val part = BiliPart(bvid,42,2,"title","UP",1000,"https://i0.hdslb.com/a.jpg")
        assertEquals(part,biliImportPart(part.importMetadata(),bvid,42))
        assertFalse(part.importMetadata().contains("Cookie"));assertFalse(part.importMetadata().contains("videoplayback"))
        for (raw in listOf("{}",part.copy(cid=43).importMetadata(),part.copy(page=0).importMetadata())) {
            try { biliImportPart(raw,bvid,42);fail() } catch (_: BilibiliException) { }
        }
    }
}
