package com.nanahoshi.audioplayer.data

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AppUpdateTest {
    private fun release(
        tag: String = "android-1.3",
        name: String = tag,
        extra: String = "",
        assets: String = """[{"name":"app-release.apk","state":"uploaded","browser_download_url":"https://example.org/app.apk"}]""",
    ) = """{"tag_name":"$tag","name":"$name","draft":false,"prerelease":false,"assets":$assets$extra}"""
    private fun releases(vararg items: String) = items.joinToString(prefix = "[", postfix = "]")
    private fun response(request: Request, code: Int, body: ResponseBody) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body)

    @Test fun numericVersionsAndMissingSegmentsCompareCorrectly() {
        fun compare(local: String, remote: String) = updateRelation(local, AppRelease(remote, remote))
        assertEquals(UpdateRelation.AVAILABLE, compare("1.2", "v1.10"))
        assertEquals(UpdateRelation.CURRENT, compare("1.2", "1.2.0"))
        assertEquals(UpdateRelation.CURRENT, compare("v1.2.0+build1", "1.2+build2"))
        assertEquals(UpdateRelation.LOCAL_NEWER, compare("1.2", "v1.1"))
        assertEquals(UpdateRelation.AVAILABLE, compare("1.2.9", "1.3"))
        assertEquals(UpdateRelation.AVAILABLE, compare("1.2", "1.2.0.1"))
        assertEquals(UpdateRelation.AVAILABLE, compare("1.999999999999999999999999999999", "2.0"))
    }

    @Test fun prereleaseComparisonAndUnknownNamesDoNotSuggestDowngrades() {
        fun compare(local: String, remote: String) = updateRelation(local, AppRelease(remote, remote))
        assertEquals(UpdateRelation.AVAILABLE, compare("1.3-rc.2", "1.3"))
        assertEquals(UpdateRelation.LOCAL_NEWER, compare("1.3", "1.3-rc.2"))
        assertEquals(UpdateRelation.AVAILABLE, compare("1.3-rc.2", "1.3-rc.10"))
        assertEquals(UpdateRelation.AVAILABLE, compare("1.3-1", "1.3-beta"))
        assertEquals(UpdateRelation.AVAILABLE, compare("1.3-beta", "1.3-beta.1"))
        for (raw in listOf("", "nightly", "release-2026-11", "1.x", "1.2..3", "1.2-")) {
            assertNull(AppVersionNumber.parse(raw)); assertEquals(UpdateRelation.UNKNOWN, compare("1.2", raw))
        }
        assertEquals(UpdateRelation.UNKNOWN, compare("custom", "1.3"))
    }

    @Test fun onlyAndroidTagsAreConsideredAndVersionPrefixIsStripped() {
        val parsed = parseAndroidRelease(
            releases(
                release(tag = "desktop-1.4", name = "desktop-1.4"),
                release(tag = "v1.4", name = "v1.4"),
                release(tag = "android-1.3", name = "Android 1.3"),
                release(tag = "android-1.2", name = "Android 1.2"),
            ),
        )
        assertEquals("android-1.3", parsed.tag)
        assertEquals("1.3", parsed.versionName)
        assertEquals("https://github.com/nanahoshi114/AudioPlayer/releases/tag/android-1.3", parsed.pageUrl)

        val vPrefixed = parseAndroidRelease(releases(release(tag = "android-v1.4", name = "Audio Player")))
        assertEquals("v1.4", vPrefixed.versionName)

        val nameFallback = parseAndroidRelease(releases(release(tag = "android-release", name = "1.5")))
        assertEquals("1.5", nameFallback.versionName)
    }

    @Test fun draftPrereleaseAndDesktopReleasesAreSkipped() {
        val prerelease = parseAndroidRelease(
            releases(
                release(tag = "android-1.4").replace("\"prerelease\":false", "\"prerelease\":true"),
                release(tag = "android-1.3"),
            ),
        )
        assertEquals("android-1.3", prerelease.tag)
        val draft = parseAndroidRelease(
            releases(
                release(tag = "android-1.4").replace("\"draft\":false", "\"draft\":true"),
                release(tag = "android-1.3"),
            ),
        )
        assertEquals("android-1.3", draft.tag)
    }

    @Test fun missingApkAndMalformedResponsesAreNotReportedAsLatestVersion() {
        for (raw in listOf(
            "null", "{}", "<html>", "[]",
            releases(release(tag = "v1.3")),
            releases(release(tag = "android-1.3", assets = "[]")),
            releases(release(tag = "android-1.3", assets = """[{"name":"Desktop.zip","state":"uploaded"}]""")),
            releases(release(tag = "android-1.3", assets = """[{"name":"app.apk","state":"starter"}]""")),
            releases(release(tag = "android-1.3", assets = "[]")),
            releases(release(tag = "android-1.3").replace("\"prerelease\":false", "\"prerelease\":true")),
            releases(release(tag = "android-1.3").replace("\"draft\":false", "\"draft\":true")),
        )) {
            try { parseAndroidRelease(raw); fail(raw) } catch (_: AppUpdateException) { }
        }
        assertFalse(AppRelease("android-1.3?redirect=evil#fragment", null).pageUrl.contains("?redirect="))
    }

    @Test fun checksOnlyGithubMetadataWithoutCredentialsOrApkRequests() = runBlocking {
        val requests = mutableListOf<Request>()
        val body = releases(
            release(tag = "desktop-1.4", name = "desktop-1.4"),
            release(tag = "android-1.3", name = "Android 1.3"),
        )
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            response(chain.request(), 200, body.toResponseBody()).build()
        }.build()
        val result = AppUpdateClient(http).latest()
        assertEquals("android-1.3", result.tag); assertEquals(1, requests.size)
        val request = requests.single()
        assertEquals(APP_RELEASES_API, request.url.toString()); assertEquals("GET", request.method)
        assertNull(request.header("Cookie")); assertNull(request.header("Authorization"))
        assertEquals("application/vnd.github+json", request.header("Accept"))
    }

    @Test fun httpErrorsKeepActionableMessagesWithoutLeakingResponseBody() = runBlocking {
        for ((code, limited, word) in listOf(Triple(403, true, "次数过多"), Triple(429, false, "次数过多"), Triple(404, false, "未找到"), Triple(500, false, "失败"))) {
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                response(chain.request(), code, "Cookie=private-fixture".toResponseBody()).apply {
                    if (limited) header("X-RateLimit-Remaining", "0")
                }.build()
            }.build()
            try { AppUpdateClient(http).latest(); fail() } catch (error: AppUpdateException) {
                assertTrue(error.message!!.contains(word)); assertFalse(error.message!!.contains("private-fixture"))
            }
        }
    }

    @Test fun doesNotFollowRedirectsToAnAssetOrOtherSite() = runBlocking {
        var count = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            count++
            response(chain.request(), 302, "".toResponseBody()).header("Location", "https://example.org/app.apk").build()
        }.build()
        try { AppUpdateClient(http).latest(); fail() } catch (_: AppUpdateException) { }
        assertEquals(1, count)
    }

    @Test fun cancellationClosesAnInFlightCheck() = runBlocking {
        val started = CountDownLatch(1); val closed = CountDownLatch(1)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val source = object : okio.Source {
                override fun timeout() = okio.Timeout.NONE
                override fun close() { closed.countDown() }
                override fun read(sink: Buffer, byteCount: Long): Long {
                    started.countDown()
                    while (!chain.call().isCanceled()) Thread.sleep(5)
                    throw IOException("cancelled")
                }
            }.buffer()
            val body = object : ResponseBody() {
                override fun contentLength() = -1L
                override fun contentType(): MediaType? = null
                override fun source() = source
            }
            response(chain.request(), 200, body).build()
        }.build()
        val job = launch(Dispatchers.IO) { AppUpdateClient(http).latest() }
        assertTrue(started.await(5, TimeUnit.SECONDS)); job.cancelAndJoin()
        assertTrue(closed.await(5, TimeUnit.SECONDS))
    }
}
