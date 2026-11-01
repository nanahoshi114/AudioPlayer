package com.nanahoshi.audioplayer.youtube

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class YouTubeClientTest {
    private val id = "jNQXAC9IVRw"
    private val solver = YouTubeSolver { _, _, _ -> error("unexpected solver") }
    private fun body(request: Request): JsonElement {
        val buffer = Buffer(); request.body!!.writeTo(buffer)
        return Json.parseToJsonElement(buffer.readUtf8())
    }
    private fun playable(id: String = this.id): String = """{
        "playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"$id","title":"Example","author":"Author","lengthSeconds":"19"},
        "streamingData":{"adaptiveFormats":[{"mimeType":"audio/mp4; codecs=mp4a.40.2","bitrate":128000,"url":"https://example.googlevideo.com/videoplayback?expire=123"}]}
    }"""
    private val homepage = """{"VISITOR_DATA":"fixture-visitor","INNERTUBE_CLIENT_VERSION":"fixture-version","STS":1,"jsUrl":"/s/player/fixture/base.js"}"""
    private fun videoRenderer(video: String = id, renderer: String = "videoRenderer") = """{"$renderer":{"videoId":"$video","title":{"runs":[{"text":"Example"}]},"ownerText":{"simpleText":"Author"},"lengthText":{"simpleText":"1:02"}}}"""

    @Test fun inputRoutingMatchesDesktopPriority() {
        for (input in listOf(id, "https://youtu.be/$id", "https://www.youtube.com/shorts/$id", "https://www.youtube.com/live/$id", "https://www.youtube.com/embed/$id")) {
            assertEquals(YouTubeInput(InputKind.VIDEO, id), inputOf(input))
        }
        assertEquals(InputKind.VIDEO, inputOf("https://www.youtube.com/watch?v=$id&list=PL1234567890123").kind)
        assertEquals(InputKind.PLAYLIST, inputOf("https://www.youtube.com/embed/$id?list=PL1234567890123").kind)
        assertEquals(YouTubeInput(InputKind.SEARCH, "hello world"), inputOf("https://www.youtube.com/results?search_query=hello+world&v=$id"))
        assertEquals(YouTubeInput(InputKind.PLAYLIST, "WL"), inputOf("WL"))
        assertEquals(YouTubeInput(InputKind.SEARCH, "words"), inputOf("words"))
    }
    @Test fun onlyIndependentMp4AudioIsRanked() {
        val root = Json.parseToJsonElement("""{"adaptiveFormats":[
            {"mimeType":"video/mp4; codecs=mp4a","bitrate":9999999,"url":"video"},
            {"mimeType":"audio/webm; codecs=opus","bitrate":9999999,"url":"opus"},
            {"mimeType":"audio/mp4","bitrate":9999999,"url":"fallback"},
            {"mimeType":"audio/mp4; codecs=mp4a","bitrate":128000,"url":"aac-low"},
            {"mimeType":"audio/mp4; codecs=mp4a","bitrate":256000,"url":"aac-high"}]}""")
        assertEquals(listOf("aac-high", "aac-low", "fallback"), audioStreams(root).map { it.url })
    }
    @Test fun cipherAndNParametersAreAppliedWithoutLosingEscapes() {
        val stream = AudioStream("audio/mp4", 1, "", "url=https%3A%2F%2Fexample.googlevideo.com%2Fv%3Fn%3Dold%26x%3Da%252Cb&s=challenge&sp=signature")
        val url = finishUrl(stream, Solved(mapOf("challenge" to "a+b"), mapOf("old" to "new")))!!
        assertTrue(url.contains("n=new")); assertTrue(url.contains("x=a%2Cb")); assertTrue(url.contains("signature=a%2Bb"))
        assertNull(finishUrl(stream, Solved(emptyMap(), emptyMap())))
        assertNull(finishUrl(AudioStream("audio/mp4", 1, "https://googlevideo.com.example.org/v", ""), Solved(emptyMap(), emptyMap())))
    }
    @Test fun malformedSolverResultsFail() {
        for (value in listOf("null", "{}", """{"responses":[{"type":"error"}]}""")) {
            try { solvedResults(value, true, false); fail() } catch (_: YouTubeException) { }
        }
        assertEquals("next", solvedResults("""{"responses":[{"type":"result","data":{"old":"next"}}]}""", false, true).n["old"])
    }
    @Test fun authHeadersUseOnlySupportedCookieField() {
        assertEquals(emptyMap<String,String>(), authHeaders("", 1))
        val actual = authHeaders("SAPISID=fixture", 123)
        val expected = java.security.MessageDigest.getInstance("SHA-1").digest("123 fixture https://www.youtube.com".toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals("SAPISIDHASH 123_$expected", actual["Authorization"])
        assertFalse(authHeaders("__Secure-1PAPISID=fixture", 123).containsKey("Authorization"))
        assertTrue(authHeaders("__Secure-3PAPISID=fixture", 123).containsKey("Authorization"))
        assertEquals("a=b", normalizeYouTubeCookie("\r\n a=b;\n"))
    }
    @Test fun clientFallsBackWithoutPersistingMediaUrlOrLeakingCookies() = runBlocking {
        val names = mutableListOf<String>()
        val client = YouTubeClient({ "SAPISID=fixture" }, solver) { request ->
            if (request.method == "GET") homepage else {
                names += body(request).child("context").child("client").text("clientName")
                assertNull(request.header("Cookie"))
                if (names.size == 1) """{"playabilityStatus":{"status":"UNPLAYABLE"}}""" else playable()
            }
        }
        val audio = client.resolveAudio(id)
        assertEquals(listOf("VISIONOS", "ANDROID"), names)
        assertEquals(19000, audio.video.durationMs)
        assertFalse(audio.headers.containsKey("Cookie")); assertFalse(audio.headers.containsKey("Authorization"))
        assertEquals("https://www.youtube.com/watch?v=$id", audio.video.watchUrl)
    }
    @Test fun cookieRetryOnlyAfterTheFinalClientNeedsLogin() = runBlocking {
        val calls = mutableListOf<Boolean>()
        val client = YouTubeClient({ "SAPISID=fixture" }, solver) { request ->
            if (request.method == "GET") homepage else {
                val auth = request.header("Cookie") != null; calls += auth
                if (auth) playable() else """{"playabilityStatus":{"status":"LOGIN_REQUIRED"}}"""
            }
        }
        client.resolveAudio(id)
        assertEquals(listOf(false, false, false, true), calls)
    }
    @Test fun searchDeduplicatesAndStopsWhenNoNewItems() = runBlocking {
        var count = 0
        val client = YouTubeClient({ null }, solver) { request ->
            if (request.method == "GET") homepage else {
                count++
                """{"contents":[${videoRenderer()},${videoRenderer()},{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"next"}}}}]}"""
            }
        }
        val result = client.lookup("keyword")
        assertEquals(2, count); assertEquals(1, result.videos.size); assertEquals(62000, result.videos.single().durationMs)
    }
    @Test fun searchLimitAndPlaylistLimitAreEnforced() = runBlocking {
        for ((query, renderer, max) in listOf(Triple("keyword", "videoRenderer", 40), Triple("PL1234567890123", "playlistVideoRenderer", 200))) {
            var page = 0
            val client = YouTubeClient({ null }, solver) { request ->
                if (request.method == "GET") homepage else {
                    val items = (0..249).joinToString(",") { videoRenderer((page * 250 + it).toString().padStart(11, '0'), renderer) }
                    page++
                    """{"contents":[$items,{"continuationItemRenderer":{"token":"next"}}]}"""
                }
            }
            assertEquals(max, client.lookup(query).videos.size); assertEquals(1, page)
        }
    }
    @Test fun partialPlaylistIsReturnedButCancellationPropagates() = runBlocking {
        var page = 0
        val client = YouTubeClient({ "a=b" }, solver) { request ->
            if (request.method == "GET") homepage else {
                if (page++ > 0) throw YouTubeException("fixture failure")
                """{"contents":[${videoRenderer(renderer="playlistVideoRenderer")},{"continuationItemRenderer":{"token":"next"}}]}"""
            }
        }
        assertTrue(client.playlist("LL").partial)
        val cancelled = YouTubeClient({ null }, solver) { throw CancellationException() }
        try { cancelled.lookup("keyword"); fail() } catch (_: CancellationException) { }
    }
    @Test fun privateListsRequireSavedCredentialsAndFilePathsAreBounded() = runBlocking {
        val client = YouTubeClient({ null }, solver) { error("unexpected request") }
        try { client.playlist("WL"); fail() } catch (_: YouTubeException) { }
        assertNull(youtubeAudioFile(File("/tmp"), "../escape"))
        assertEquals(File("/tmp/youtube/$id/audio.m4a"), youtubeAudioFile(File("/tmp"), id))
    }
    @Test fun refusedMediaIsResolvedOnlyOnceMore() = runBlocking {
        var requests = 0
        var players = 0
        val media = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(403).message("Forbidden").body(ByteArray(0).toResponseBody()).build()
        }.build()
        val client = YouTubeClient({ null }, solver, media) { request ->
            if (request.method == "GET") homepage else { players++; playable() }
        }
        val file = File.createTempFile("youtube-test", ".part")
        try {
            try { client.downloadAudio(id, file) { _, _ -> }; fail() } catch (_: YouTubeException) { }
            assertEquals(2, requests); assertEquals(2, players)
            assertFalse(file.exists())
        } finally { file.delete() }
    }

    @Test fun cancellationClosesTheTransferAndDeletesItsPartialFile() = runBlocking {
        val started = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.CountDownLatch(1)
        val media = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val source = object : okio.Source {
                override fun timeout() = okio.Timeout.NONE
                override fun close() { closed.countDown() }
                override fun read(sink: okio.Buffer, byteCount: Long): Long {
                    started.countDown()
                    while (!chain.call().isCanceled()) Thread.sleep(5)
                    throw java.io.IOException("cancelled")
                }
            }
            val body = object : okhttp3.ResponseBody() {
                override fun contentLength() = -1L
                override fun contentType(): okhttp3.MediaType? = null
                override fun source() = source.buffer()
            }
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body(body).build()
        }.build()
        val client = YouTubeClient({ null }, solver, media)
        val file = File.createTempFile("youtube-cancel-test", ".part")
        try {
            val job = launch(Dispatchers.IO) { client.copyAudio(YouTubeAudio(YouTubeVideo(id, "fixture"), "https://example.googlevideo.com/v", "audio/mp4", emptyMap()), file) { _, _ -> } }
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue(closed.await(5, java.util.concurrent.TimeUnit.SECONDS))
            // 回调关闭媒体流后清理暂存。
            withTimeout(5000) { while (file.exists()) delay(5) }
        } finally { file.delete() }
    }

    @Test fun completeIndependentAudioIsWrittenAndReported() = runBlocking {
        val bytes = ByteArray(2048)
        "ftyp".toByteArray().copyInto(bytes, 4)
        val media = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body(bytes.toResponseBody()).build()
        }.build()
        val file = File.createTempFile("youtube-complete", ".part")
        try {
            var written = 0L
            YouTubeClient({ null }, solver, media).copyAudio(YouTubeAudio(YouTubeVideo(id, "fixture"),
                "https://example.googlevideo.com/v", "audio/mp4", emptyMap()), file) { done, _ -> written = done }
            assertArrayEquals(bytes, file.readBytes()); assertEquals(2048L, written)
        } finally { file.delete() }
    }

}
