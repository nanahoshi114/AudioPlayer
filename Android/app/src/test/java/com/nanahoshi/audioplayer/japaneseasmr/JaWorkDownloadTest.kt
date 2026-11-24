package com.nanahoshi.audioplayer.japaneseasmr

import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.dlsite.extractDownloadArchive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class JaWorkDownloadTest {
    @get:Rule val temporary = TemporaryFolder()
    private val post = JaPostRef("18", "RJ01752865", "fixture", null, JapaneseAsmrClient.postUrl("18"), null)
    private val entry = jaDownloadEntry(post, null).single()

    @Test fun entryNormalizesHostQueryAndRejectsOtherWorkAndHost() {
        val article = JaArticle(post, emptyList(), emptyList(), downloadLinks = listOf(
            "https://www.japaneseasmr.com/dlc.php?slot=2&f=rj01752865#fragment",
            "https://japaneseasmr.com/dlc.php?f=RJ01752865&slot=2",
            "https://japaneseasmr.com/dlc.php?f=RJ111111",
            "https://buzzheavier.com/remote",
        ))
        val entries = jaDownloadEntry(post, article)
        assertEquals(listOf("https://japaneseasmr.com/dlc.php?f=RJ01752865&slot=2"), entries.map { it.address })
    }

    @Test fun cancellingManifestCancelsThePendingEntryRequest() = runBlocking {
        val started = CompletableDeferred<Call>()
        val cancelled = CountDownLatch(1)
        val http = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun canceled(call: Call) = cancelled.countDown()
            })
            .addInterceptor { chain ->
                started.complete(chain.call())
                if (!cancelled.await(5, TimeUnit.SECONDS)) error("Expected cancellation while awaiting the response")
                throw IOException("Fixture request cancelled")
            }
            .build()

        withTimeout(5_000) {
            val manifest = async { JaWorkClient(http).manifest(entry) }
            val call = started.await()
            assertFalse(call.isCanceled())
            manifest.cancelAndJoin()
            assertTrue(call.isCanceled())
        }
    }

    @Test fun verifiedSitePageReportsASeparateHostingSiteChallenge() = runBlocking {
        val hosts = mutableListOf<String>()
        val client = client { request ->
            hosts += request.url.host
            assertNull(request.header("Cookie"))
            reply(request, 403, "<title>Just a moment...</title>".toResponseBody())
        }
        val visible = JaVisibleDownload(entry.address, """<a href="https://buzzheavier.com/fixture">Down</a>""")

        val failure = runCatching { client.manifest(entry, visible) }.exceptionOrNull()
        assertTrue(failure is JaBuzzVerification)
        val verification = failure as JaBuzzVerification
        assertEquals(entry, verification.entry)
        assertEquals(visible, verification.sourcePage)
        assertEquals(listOf(JaBuzzPage("https://buzzheavier.com/fixture", "https://buzzheavier.com/fixture")), verification.pages)
        assertEquals(listOf("buzzheavier.com"), hosts)
    }

    @Test fun verifiedBuzzPagesResumeTheWholeFileListWithoutBrowserCookies() = runBlocking {
        val first = "https://buzzheavier.com/first"
        val second = "https://buzzheavier.com/second"
        val firstLink = "https://ts.buzzheavier.com/d/first"
        val secondLink = "https://ts.buzzheavier.com/d/second"
        val site = JaVisibleDownload(entry.address, """<a href="$firstLink">First</a><a href="$secondLink">Second</a>""")
        val requests = mutableListOf<String>()
        val client = client { request ->
            assertNull(request.header("Cookie"))
            requests += request.url.encodedPath
            if (request.url.host == "ts.buzzheavier.com") {
                val target = if (request.url.encodedPath.endsWith("/second")) second else first
                reply(request, 302, headers = mapOf("Location" to target))
            } else if (request.header("Range") == "bytes=0-4095") {
                reply(request, 206, "a".toResponseBody(), mapOf("Content-Range" to "bytes 0-0/8"))
            } else {
                reply(request, 403, headers = mapOf("cf-mitigated" to "challenge"))
            }
        }
        var prompts = 0
        val manifest = client.manifest(entry, site) { verification ->
            prompts++
            assertEquals(listOf(first, second), verification.pages.map { it.address })
            assertEquals(listOf(firstLink, secondLink), verification.pages.map { it.fileLink })
            assertTrue(verification.pages.first().accepts(first))
            assertFalse(verification.pages.first().accepts(second))
            assertFalse(verification.pages.first().accepts("https://buzzheavier.com.invalid/first"))
            verification.pages.associate { page ->
                val name = page.address.substringAfterLast('/')
                val html = """<h1>$name.m4a</h1><a href="/$name/download?t=fixture">Download</a>"""
                assertTrue(hasBuzzDownloadFile(page.address, html))
                page.fileLink to JaVisibleDownload(page.address, html)
            }
        }

        assertEquals(1, prompts)
        assertTrue(manifest.fromVerifiedPage)
        assertEquals(listOf("first.m4a", "second.m4a"), manifest.files.map { it.relativePath })
        assertEquals(16L, manifest.bytes)
        assertEquals(listOf("/d/first", "/first", "/first/download", "/second/download"), requests)
    }

    @Test fun verifiedBuzzPageWithoutDirectPromptsAgainForDownload() = runBlocking {
        val address = "https://buzzheavier.com/fixture"
        val html = """<h1>fixture.m4a</h1><a href="/fixture/download?t=fixture">Download</a>"""
        val site = JaVisibleDownload(entry.address, """<a href="$address">Download</a>""")
        val client = client { request ->
            if (request.header("Range") == "bytes=0-4095") {
                reply(request, 403, headers = mapOf("cf-mitigated" to "challenge"))
            } else {
                reply(request, 200, html.toResponseBody())
            }
        }
        var prompts = 0
        val failure = runCatching {
            client.manifest(entry, site) { verification ->
                prompts++
                assertEquals(address, verification.pages.single().address)
                assertEquals(address, verification.fileLink)
                mapOf(address to JaVisibleDownload(address, html))
            }
        }.exceptionOrNull()

        assertEquals(3, prompts)
        assertTrue(failure is JaWorkException)
        assertFalse(failure is JaBuzzVerification)
        assertEquals(TextRef.Res(R.string.ja_error_verified_address_missing), (failure as JaWorkException).userText)
    }

    @Test fun verifiedBuzzDirectSkipsTheCheckedDownloadEndpoint() = runBlocking {
        val landing = "https://buzzheavier.com/fixture"
        val direct = "https://dd.buzzheavier.com/fixture/bytes"
        val html = """<h1>fixture.m4a</h1><button hx-get="/fixture/download">Download File</button>"""
        val site = JaVisibleDownload(entry.address, """<a href="$landing">Download</a>""")
        val requests = mutableListOf<String>()
        val client = client { request ->
            assertNull(request.header("Cookie"))
            requests += request.url.encodedPath
            assertEquals(direct, request.url.toString())
            if (request.header("Range") == "bytes=0-4095") {
                reply(request, 206, "a".toResponseBody(), mapOf("Content-Range" to "bytes 0-0/8"))
            } else {
                error("unexpected request")
            }
        }

        val manifest = client.manifest(entry, site, mapOf(landing to JaVisibleDownload(landing, html, direct)))
        assertEquals("fixture.m4a", manifest.files.single().relativePath)
        assertEquals(listOf("/fixture/bytes"), requests)
    }

    @Test fun visibleBuzzUserAgentIsUsedForProbeAndTransfer() = runBlocking {
        val landing = "https://buzzheavier.com/fixture"
        val html = """<h1>fixture.m4a</h1><a href="/fixture/download">Download</a>"""
        val site = JaVisibleDownload(entry.address, """<a href="$landing">Download</a>""")
        val agents = mutableListOf<String?>()
        val media = "abcdefgh".toByteArray()
        val client = client { request ->
            agents += request.header("User-Agent")
            if (request.header("Range") == "bytes=0-4095") {
                reply(request, 206, media.copyOf(1).toResponseBody(), mapOf("Content-Range" to "bytes 0-0/8"))
            } else {
                reply(request, 200, media.toResponseBody())
            }
        }

        val manifest = client.manifest(entry, site, mapOf(landing to JaVisibleDownload(landing, html, userAgent = "WebView-UA")))
        val destination = File(temporary.newFolder(), "audio")
        client.download(manifest.files.single(), destination) {}
        assertArrayEquals(media, destination.readBytes())
        assertEquals(listOf("WebView-UA", "WebView-UA"), agents)
    }

    @Test fun copyDownloadLinkPrefersTheCurrentToken() {
        val landing = "https://buzzheavier.com/fixture"
        val html = """<button data-clipboard-text="/fixture/download?t=current">Copy download link</button>"""
        assertEquals("https://buzzheavier.com/fixture/download?t=current", buzzCopyDownloadLink(landing, html))
    }

    @Test fun visibleCopyLinkWithTokenFollowsHxRedirect() = runBlocking {
        val landing = "https://buzzheavier.com/fixture"
        val copy = "https://buzzheavier.com/fixture/download?t=current"
        val direct = "https://dd.buzzheavier.com/fixture/bytes"
        val html = """<h1>fixture.m4a</h1><button hx-get="/fixture/download">Download File</button>"""
        val site = JaVisibleDownload(entry.address, """<a href="$landing">Download</a>""")
        val requests = mutableListOf<String>()
        val client = client { request ->
            requests += request.url.toString()
            when {
                request.url.toString() == copy -> reply(request, 204, headers = mapOf("HX-Redirect" to direct))
                request.url.toString() == direct && request.header("Range") == "bytes=0-4095" ->
                    reply(request, 206, "a".toResponseBody(), mapOf("Content-Range" to "bytes 0-0/8"))
                else -> error("unexpected request")
            }
        }

        val manifest = client.manifest(entry, site,
            mapOf(landing to JaVisibleDownload(landing, html, copyLink = copy)))
        assertEquals("fixture.m4a", manifest.files.single().relativePath)
        assertEquals(copy, requests.first())
        assertTrue(direct in requests)
    }

    @Test fun buzzCookieProviderAddsCookieHeader() = runBlocking {
        val landing = "https://buzzheavier.com/fixture"
        val html = """<h1>fixture.m4a</h1><a href="/fixture/download">Download</a>"""
        val site = JaVisibleDownload(entry.address, """<a href="$landing">Download</a>""")
        var cookie: String? = null
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            cookie = request.header("Cookie")
            if (request.header("Range") == "bytes=0-4095") {
                reply(request, 206, "a".toResponseBody(), mapOf("Content-Range" to "bytes 0-0/8"))
            } else {
                reply(request, 200, html.toResponseBody())
            }
        }.build()
        val client = JaWorkClient(http, cookieProvider = { "cf_clearance=fixture" })

        client.manifest(entry, site, mapOf(landing to JaVisibleDownload(landing, html)))
        assertEquals("cf_clearance=fixture", cookie)
    }

    @Test fun browserConfirmedFileUrlMayBeTheDownloadEndpoint() = runBlocking {
        val landing = "https://buzzheavier.com/fixture"
        val file = "https://buzzheavier.com/fixture/download?t=browser"
        val html = """<h1>fixture.m4a</h1><button hx-get="/fixture/download">Download File</button>"""
        val site = JaVisibleDownload(entry.address, """<a href="$landing">Download</a>""")
        val requests = mutableListOf<String?>()
        val client = client { request ->
            requests += request.header("HX-Request")
            assertEquals(file, request.url.toString())
            assertNull(request.header("Range"))
            reply(request, 200, "a".toResponseBody())
        }

        val manifest = client.manifest(entry, site,
            mapOf(landing to JaVisibleDownload(landing, html, fileUrl = file)))
        assertEquals("fixture.m4a", manifest.files.single().relativePath)
        assertEquals(listOf<String?>(null), requests)
    }

    @Test fun refusedBuzzRequestsIdentifyTheStageWithoutOpeningVerification() = runBlocking {
        val address = "https://buzzheavier.com/fixture"
        val html = """<h1>fixture.m4a</h1><button hx-get="/fixture/download">Download File</button>"""
        val site = JaVisibleDownload(entry.address, """<a href="$address">Download</a>""")
        for (stage in listOf(R.string.ja_stage_hosting_page, R.string.ja_stage_file_download)) {
            val client = client { request ->
                if (stage == R.string.ja_stage_file_download && request.url.encodedPath == "/fixture") {
                    reply(request, 200, html.toResponseBody())
                } else {
                    reply(request, 403, "<h1>Access denied</h1>".toResponseBody())
                }
            }
            val failure = runCatching { client.manifest(entry, site) }.exceptionOrNull()
            assertTrue(failure is JaWorkException)
            assertFalse(failure is JaBuzzVerification)
            assertEquals(
                TextRef.Res(R.string.ja_work_buzz_refused, listOf(TextRef.Res(stage))),
                (failure as JaWorkException).userText,
            )
        }
    }

    @Test fun unavailableBuzzDownloadAfterReadingVisibleFilePageReports503() = runBlocking {
        val address = "https://buzzheavier.com/fixture"
        val site = JaVisibleDownload(entry.address, """<a href="$address">Download</a>""")
        val html = """<h1>fixture.m4a</h1><button hx-get="/fixture/download">Download File</button>"""
        val client = client { request ->
            reply(request, 503, "Service Temporarily Unavailable".toResponseBody())
        }

        val failure = runCatching {
            client.manifest(entry, site, mapOf(address to JaVisibleDownload(address, html)))
        }.exceptionOrNull()

        assertTrue(failure is JaWorkException)
        assertFalse(failure is JaBuzzVerification)
        assertEquals(
            TextRef.Res(R.string.ja_work_buzz_unavailable, listOf(TextRef.Res(R.string.ja_stage_file_download))),
            (failure as JaWorkException).userText,
        )
    }

    @Test fun visibleListRetainsFullTrackAndSplitsAndResumesBuzzWithRange() = runBlocking {
        val media = "abcdefgh".toByteArray()
        val hash = jaHash(String(media))
        val directRequests = mutableListOf<String?>()
        val client = client { request ->
            assertNull(request.header("Cookie"))
            val path = request.url.encodedPath
            val id = path.split('/')[1]
            when {
                request.url.host == "japaneseasmr.com" ->
                    reply(request, 403, headers = mapOf("cf-mitigated" to "challenge"))
                request.url.host == "ts.buzzheavier.com" ->
                    reply(request, 302, headers = mapOf("Location" to "https://buzzheavier.com/$id"))
                path.endsWith("/download") -> {
                    val page = "https://buzzheavier.com/$id"
                    val endpoint = "https://dd.buzzheavier.com/$id/bytes"
                    if (request.header("Range") != null || request.header("If-Range") != null ||
                        request.header("HX-Request") != "true" || request.header("HX-Current-URL") != page ||
                        request.header("Referer") != page || request.url.queryParameter("t") != null
                    ) {
                        reply(request, 503)
                    } else if (id == "two") {
                        reply(request, 200, headers = mapOf("HX-Redirect" to endpoint))
                    } else {
                        reply(request, 308, headers = mapOf("Location" to endpoint))
                    }
                }
                path.endsWith("/bytes") -> {
                    assertNull(request.header("HX-Request"))
                    assertNull(request.header("HX-Current-URL"))
                    val range = request.header("Range")
                    val headers = mapOf("ETag" to "\"fixture\"")
                    if (range == "bytes=0-4095") {
                        reply(request, 206, media.copyOf(1).toResponseBody(), headers + ("Content-Range" to "bytes 0-0/8"))
                    } else {
                        directRequests += range
                        when (range) {
                            null -> reply(request, 200, truncatedBody(media.copyOf(3), 8), headers)
                            "bytes=3-" -> {
                                assertEquals("\"fixture\"", request.header("If-Range"))
                                reply(request, 206, media.copyOfRange(3, 8).toResponseBody(),
                                    headers + ("Content-Range" to "bytes 3-7/8"))
                            }
                            else -> error("unexpected range")
                        }
                    }
                }
                request.url.host == "buzzheavier.com" ->
                    reply(request, 200, """
                        <h1>${if (id == "full") "RJ01752865.m4a" else "$id.m4a"}</h1>
                        <button hx-get="/$id/download">Download File</button>
                        <button data-clipboard-text="/$id/download?t=copy">Copy Link</button>
                        <p>SHA-256: <code>$hash</code></p>
                    """.trimIndent().toResponseBody())
                else -> error("unexpected host")
            }
        }
        assertTrue(runCatching { client.manifest(entry) }.exceptionOrNull() is JaDownloadVerification)
        val list = """
            <a href="https://ts.buzzheavier.com/one">1</a>
            <a href="https://ts.buzzheavier.com/two">2</a>
            <a href="https://ts.buzzheavier.com/full">全部</a>
            <a href="https://buzzheavier.com.invalid/file">无关</a>
        """.trimIndent()
        val manifest = client.manifest(entry, JaVisibleDownload(entry.address, list))
        assertEquals(listOf("one.m4a", "two.m4a", "RJ01752865.m4a"), manifest.files.map { it.relativePath })
        assertEquals(24L, manifest.bytes)
        val dest = File(temporary.newFolder(), "audio")
        client.download(manifest.files.first(), dest) {}
        assertArrayEquals(media, dest.readBytes())
        assertEquals(listOf(null, "bytes=3-"), directRequests)
    }

    @Test fun buzzRestartsWhenRangeIsIgnoredAndDoesNotReuseLastModifiedAlone() = runBlocking {
        val payload = "different".toByteArray()
        val ranges = mutableListOf<String?>()
        val address = "https://buzzheavier.com/a/download?t=fixture".toHttpUrl()
        suspend fun transfer(transfer: JaFileTransfer.Buzz): List<String?> {
            ranges.clear()
            val client = client { request ->
                ranges += request.header("Range")
                if (ranges.size == 1) reply(request, 200, truncatedBody(payload.copyOf(3), payload.size.toLong()))
                else reply(request, 200, payload.toResponseBody())
            }
            val dest = File(temporary.newFolder(), "audio")
            client.download(JaRemoteFile("track.m4a", payload.size.toLong(), null, transfer), dest) {}
            assertArrayEquals(payload, dest.readBytes())
            return ranges.toList()
        }
        assertEquals(listOf(null, "bytes=3-"), transfer(JaFileTransfer.Buzz(address, "\"fixture\"")))
        assertEquals(listOf(null, null), transfer(JaFileTransfer.Buzz(address, null, "yesterday")))
    }

    @Test fun rejectsDownloadWebPagesBeforeStagingBytes() = runBlocking {
        val landing = "https://buzzheavier.com/fixture"
        val address = "$landing/download?t=fixture"
        val site = JaVisibleDownload(entry.address, """<a href="$landing">Download</a>""")
        val page = JaVisibleDownload(landing, "<h1>fixture.wav</h1>", direct = "$landing.bin")
        val body = "<!doctype html><title>Unavailable</title>".toByteArray()
        val probe = client { request ->
            assertEquals("bytes=0-4095", request.header("Range"))
            reply(request, 200, body.toResponseBody())
        }
        val probeError = runCatching { probe.manifest(entry, site, mapOf(landing to page)) }.exceptionOrNull()
        assertEquals(TextRef.Res(R.string.ja_error_buzz_response_page), (probeError as JaWorkException).userText)

        val dest = File(temporary.newFolder(), "audio")
        val transfer = JaFileTransfer.Buzz(address.toHttpUrl(), "fixture")
        val file = JaRemoteFile("fixture.wav", 64, null, transfer)
        val requests = mutableListOf<String?>()
        val downloader = client { request ->
            requests += request.header("Range")
            if (requests.size == 1) reply(request, 200, truncatedBody(byteArrayOf(1, 2, 3), 64))
            else reply(request, 206, body.toResponseBody(), mapOf("Content-Range" to "bytes 3-63/64"))
        }
        val error = runCatching { downloader.download(file, dest) {} }.exceptionOrNull()
        assertEquals(TextRef.Res(R.string.ja_error_buzz_response_page), (error as JaWorkException).userText)
        assertEquals(listOf(null, "bytes=3-"), requests)
        assertArrayEquals(byteArrayOf(1, 2, 3), dest.readBytes())
    }

    @Test fun transferChallengeOnSuccessfulStatusKeepsTheOriginalFilePage() = runBlocking {
        val landing = "https://buzzheavier.com/fixture"
        val site = JaVisibleDownload(entry.address, """<a href="$landing">Download</a>""")
        val visible = JaVisibleDownload(landing, "<h1>fixture.wav</h1>", fileUrl = "https://dd.buzzheavier.com/signed")
        var count = 0
        val client = client { request ->
            if (++count == 1) reply(request, 200, byteArrayOf(1, 2, 3).toResponseBody())
            else reply(request, 200, "<html><script src='/cdn-cgi/challenge-platform/fixture'></script></html>".toResponseBody())
        }
        val manifest = client.manifest(entry, site, mapOf(landing to visible))
        assertEquals(site, manifest.sourcePage)
        assertNull((manifest.files.single().transfer as JaFileTransfer.Buzz).buttonPage)
        val dest = File(temporary.newFolder(), "audio")
        val error = runCatching { client.download(manifest.files.single(), dest) {} }.exceptionOrNull()
        assertEquals(JaBuzzPage(landing, landing), (error as JaTransferVerification).page)
        assertEquals(2, count)
        assertFalse(dest.exists())
    }

    @Test fun megaResumesByByteOffsetAndChecksIntegrity() = runBlocking {
        // The encrypted attributes, ciphertext and key are a synthetic AES test vector.
        val share = "EBAQEBAQEBAQanTjPPw3cxAREhMUFRYXGGN-6DDxOXw"
        val ciphertext = "x4eoobM-v_3_B2mHstVMHOdAttKMGGXGy8gx6gAkZCE".decodeBase64()!!.toByteArray()
        val attributes = "ziHCcIgVF8IvKsuk217bvqG9WfTtiQp_b5tA9ojPT9o"
        val fixtureUrl = "https://store.userstorage.mega.co.nz/fixture"
        val requests = mutableListOf<String>()
        val client = client { request ->
            assertNull(request.header("Cookie"))
            assertNull(request.header("Range"))
            when (request.url.host) {
                "g.api.mega.co.nz" -> {
                    val buffer = Buffer()
                    request.body!!.writeTo(buffer)
                    val api = buffer.readUtf8()
                    assertTrue(api.contains("Fixture1"))
                    assertFalse(api.contains(share))
                    reply(request, 200, """[{"s":32,"g":"$fixtureUrl","at":"$attributes"}]""".toResponseBody())
                }
                "store.userstorage.mega.co.nz" -> {
                    val path = request.url.encodedPath
                    requests += path
                    val body = when (path) {
                        "/fixture/0-31" -> truncatedBody(ciphertext.copyOf(7), 32)
                        "/fixture/7-31" -> ciphertext.copyOfRange(7, 32).toResponseBody()
                        else -> error("unexpected offset")
                    }
                    reply(request, 200, body)
                }
                else -> error("unexpected host")
            }
        }
        val manifest = client.manifest(entry, JaVisibleDownload("https://mega.nz/file/Fixture1#$share", ""))
        val file = manifest.files.single()
        assertEquals("fixture.mp3", file.relativePath)
        val destination = File(temporary.newFolder(), "plaintext")
        client.download(file, destination) {}
        assertEquals("Japanese ASMR fixture bytes v1.\n", destination.readText())
        assertEquals(listOf("/fixture/0-31", "/fixture/7-31"), requests)
        val crypto = (file.transfer as JaFileTransfer.Mega).crypto
        destination.writeText("X" + destination.readText().drop(1))
        assertFalse(crypto.verify(destination))
        val errors = listOf("https://mega.nz/folder/Fixture1#$share", "https://mega.nz/file/Fixture1#invalid")
        assertTrue(errors.all { runCatching { JaMegaShare.parse(it.toHttpUrl()) }.exceptionOrNull() is JaWorkException })
        val legacy = JaMegaShare.parse("https://mega.nz/#!Fixture1!$share".toHttpUrl()) as JaMegaShare.SingleFile
        assertEquals("fixture.mp3", legacy.crypto.name(attributes))
    }

    @Test fun folderSharesListOnceAndResumeAtTheNodeWithoutFetchingCompleteStagingAgain() = runBlocking {
        for (link in listOf(folderShare(), legacyFolderShare())) {
            val commands = mutableListOf<String>()
            val storagePaths = mutableListOf<String>()
            val client = client { request ->
                assertNull(request.header("Cookie"))
                if (request.url.host == "g.api.mega.co.nz") {
                    assertEquals(PUBLIC, request.url.queryParameter("n"))
                    val command = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                    commands += command
                    when (commands.size) {
                        1 -> reply(request, 200, folderTree(
                            directory(SUB, ROOT, "Nested"),
                            directory("Empty___", ROOT, "Empty"),
                            file(AUDIO, SUB, "voice.mp3"),
                            file("Zero____", ROOT, "zero.txt", size = 0),
                            file("BadSlash", ROOT, "not/a/path"),
                            file("Other___", "Other___", "outside"),
                        ).toResponseBody())
                        2 -> reply(request, 200, ticket("ready").toResponseBody())
                        else -> error("Unexpected MEGA API request")
                    }
                } else {
                    storagePaths += request.url.encodedPath
                    val bytes = if (storagePaths.size == 1) truncatedBody(megaCiphertext.copyOf(7), 32)
                        else megaCiphertext.copyOfRange(7, 32).toResponseBody()
                    reply(request, 200, bytes)
                }
            }
            val manifest = client.manifest(entry, JaVisibleDownload(link, ""))
            assertEquals(listOf("Nested/voice.mp3", "zero.txt"), manifest.files.map { it.relativePath })
            assertEquals(1, manifest.skipped)
            assertEquals(listOf("""[{"a":"f","c":1,"r":1,"ca":1}]"""), commands)
            val dir = temporary.newFolder()
            val destination = File(dir, "voice.mp3")
            val voice = manifest.files.first()
            client.download(voice, destination) {}
            client.download(voice, destination) {}
            val empty = File(dir, "zero.txt")
            client.download(manifest.files.last(), empty) {}
            assertTrue(empty.isFile)
            assertEquals(0L, empty.length())
            assertEquals("Japanese ASMR fixture bytes v1.\n", destination.readText())
            assertEquals(listOf("/ready/0-31", "/ready/7-31"), storagePaths)
            assertEquals("""[{"a":"g","n":"$AUDIO","g":1,"ssl":2}]""", commands.last())
            assertEquals(2, commands.size)
        }
    }

    @Test fun folderFocusUsesTheSelectorTypeAndOnlySharesDescendantsOfTheRoot() = runBlocking {
        val tree = folderTree(
            directory(SUB, ROOT, "Nested"),
            file(AUDIO, SUB, "voice.mp3"),
            file("Other___", ROOT, "other.mp3"),
            directory("Foreign_", "Missing_", "Outside"),
            file("Stray___", "Foreign_", "stray.mp3"),
            directory("Broken__", ROOT, "Broken").replace("\"a\":", "\"broken\":"),
            file("Unread__", "Broken__", "unread.mp3"),
            file("FileSub_", AUDIO, "version.mp3"),
        )
        val client = client { request -> reply(request, 200, tree.toResponseBody()) }
        for (link in listOf(folderShare("/folder/$SUB"), legacyFolderShare("!$SUB"),
            folderShare("/file/$AUDIO"), legacyFolderShare("?$AUDIO"))
        ) {
            assertEquals("voice.mp3", client.manifest(entry, JaVisibleDownload(link, "")).files.single().relativePath)
        }
        for (link in listOf(legacyFolderShare("!$AUDIO"), legacyFolderShare("?$SUB"), folderShare("/file/Stray___"),
            folderShare("/file/Unread__"), folderShare("/file/FileSub_"))
        ) {
            assertEquals(TextRef.Res(R.string.ja_error_mega_share_expired),
                manifestError(client, link).userText)
        }
    }

    @Test fun folderErrorsDistinguishInvalidRootEmptyAndUnusableBranches() = runBlocking {
        val empty = folderTree(directory(SUB, ROOT, "Empty"), file(AUDIO, AUDIO, "orphan.mp3"))
        val broken = folderTree(directory(SUB, ROOT, "Broken").replace("\"a\":", "\"broken\":"),
            file(AUDIO, SUB, "voice.mp3"))
        for ((body, link, expected) in listOf(
            Triple(empty, folderShare(), R.string.ja_error_mega_empty_folder),
            Triple(broken, folderShare(), R.string.ja_error_download_unavailable),
            Triple(empty, folderShare().replace(megaFolderKey.toByteString().base64Url().trimEnd('='), "AAAAAAAAAAAAAAAAAAAAAA"),
                R.string.ja_error_mega_share_expired),
            Triple("""[{"f":[]}]""", folderShare(), R.string.ja_error_mega_share_expired),
        )) {
            val client = client { request -> reply(request, 200, body.toResponseBody()) }
            assertEquals(TextRef.Res(expected), manifestError(client, link).userText)
        }
        val duplicate = folderTree(file(AUDIO, ROOT, "Voice.mp3"), file("Other___", ROOT, "voice.mp3"))
        val client = client { request -> reply(request, 200, duplicate.toResponseBody()) }
        val manifest = client.manifest(entry, JaVisibleDownload(folderShare(), ""))
        assertEquals(1, manifest.files.size)
        assertEquals(1, manifest.skipped)
    }

    @Test fun megaResumesOnTheRefreshedAddressAndInvalidatesOnlyAfterTheNewAddressIsRejected() = runBlocking {
        for (rejectFresh in listOf(false, true)) {
            var apiCalls = 0
            val paths = mutableListOf<String>()
            val client = client { request ->
                if (request.url.host == "g.api.mega.co.nz") {
                    apiCalls++
                    reply(request, 200, ticket("fresh").toResponseBody())
                } else {
                    paths += request.url.encodedPath
                    when (request.url.encodedPath) {
                        "/old/0-31" -> reply(request, 403)
                        "/fresh/0-31" -> if (rejectFresh) reply(request, 410)
                            else reply(request, 200, truncatedBody(megaCiphertext.copyOf(7), 32))
                        "/fresh/7-31" -> reply(request, 200, megaCiphertext.copyOfRange(7, 32).toResponseBody())
                        else -> error("Unexpected storage request")
                    }
                }
            }
            val destination = File(temporary.newFolder(), "file")
            if (rejectFresh) {
                val failure = runCatching { client.download(megaRemote("old"), destination) {} }.exceptionOrNull()
                assertTrue(failure is JaMegaManifestInvalidException)
                assertEquals(listOf("/old/0-31", "/fresh/0-31"), paths)
            } else {
                client.download(megaRemote("old"), destination) {}
                assertEquals(listOf("/old/0-31", "/fresh/0-31", "/fresh/7-31"), paths)
                assertEquals("Japanese ASMR fixture bytes v1.\n", destination.readText())
            }
            assertEquals(1, apiCalls)
        }
    }

    @Test fun megaFileLengthMismatchInvalidatesTheManifest() = runBlocking {
        for ((body, error) in listOf(
            ByteArray(33).toResponseBody() to R.string.ja_error_file_length_changed,
            truncatedBody(ByteArray(33), -1) to R.string.ja_error_file_length_check,
        )) {
            val client = client { request ->
                assertEquals("/old/0-31", request.url.encodedPath)
                reply(request, 200, body)
            }
            val destination = File(temporary.newFolder(), "file")
            val failure = runCatching { client.download(megaRemote("old"), destination) {} }.exceptionOrNull()
            assertTrue(failure is JaMegaManifestInvalidException)
            assertEquals(TextRef.Res(error), (failure as JaMegaManifestInvalidException).userText)
        }
    }

    @Test fun megaTransferSkipsNetworkWhenThePartialAlreadyHasFullLength() = runBlocking {
        var requests = 0
        val client = client { requests++; error("unexpected storage request") }
        val destination = File(temporary.newFolder(), "file").apply { writeBytes(ByteArray(32)) }
        client.transferMega(megaRemote("old"), megaRemote("old").transfer as JaFileTransfer.Mega, destination) {}
        assertEquals(0, requests)
        assertEquals(32, destination.length())
    }

    @Test fun megaTransferDeletesAnOversizedPartialAndFailsWithoutRequestingAnInvalidRange() = runBlocking {
        var requests = 0
        val client = client { requests++; error("unexpected storage request") }
        val destination = File(temporary.newFolder(), "file").apply { writeBytes(ByteArray(33)) }
        val failure = runCatching {
            client.transferMega(megaRemote("old"), megaRemote("old").transfer as JaFileTransfer.Mega, destination) {}
        }.exceptionOrNull()
        assertTrue(failure is JaWorkException)
        assertFalse(failure is JaMegaManifestInvalidException)
        assertEquals(TextRef.Res(R.string.ja_error_file_length_check), (failure as JaWorkException).userText)
        assertFalse(destination.exists())
        assertEquals(0, requests)
    }

    @Test fun megaCipherCopyTreatsLengthMismatchAndOverrunAsInvalidManifest() = runBlocking {
        var requests = 0
        val client = client { requests++; error("unexpected storage request") }
        val request = Request.Builder().url("https://store.userstorage.mega.co.nz/old/0-31").build()
        val crypto = JaMegaCrypto(megaShareKey)
        for ((body, expected) in listOf(
            ByteArray(33).toResponseBody() to R.string.ja_error_file_length_changed,
            truncatedBody(ByteArray(33), -1) to R.string.ja_error_file_length_check,
        )) {
            val destination = File(temporary.newFolder(), "file")
            val failure = runCatching {
                client.copy(reply(request, 200, body), destination, 0L, 32L, {}, crypto.cipherAt(0))
            }.exceptionOrNull()
            assertTrue(failure is JaMegaManifestInvalidException)
            assertEquals(TextRef.Res(expected), (failure as JaWorkException).userText)
        }
        assertEquals(0, requests)
    }

    @Test fun megaRefreshExhaustionKeepsTheBusyErrorAndNeverRevisitsTheOldAddress() = runBlocking {
        var apiCalls = 0
        var storageCalls = 0
        val client = client { request ->
            if (request.url.host == "g.api.mega.co.nz") {
                apiCalls++
                reply(request, 200, """[{"e":-4}]""".toResponseBody())
            } else {
                storageCalls++
                reply(request, 404)
            }
        }
        val failure = runCatching {
            client.download(megaRemote("old"), File(temporary.newFolder(), "file")) {}
        }.exceptionOrNull()
        assertTrue(failure is JaMegaTransientException)
        assertEquals(TextRef.Res(R.string.ja_error_mega_busy), (failure as JaWorkException).userText)
        assertEquals(3, apiCalls)
        assertEquals(1, storageCalls)
    }

    @Test fun megaStopsOnQuotaAndChangedSizeAndRetriesTemporaryListErrors() = runBlocking {
        for (body in listOf("""[{"s":10,"e":-17}]""", """[{"e":-17}]""", "[-17]", "-17")) {
            var calls = 0
            val client = client { request ->
                calls++
                reply(request, 200, body.toResponseBody())
            }
            assertEquals(TextRef.Res(R.string.ja_error_mega_quota), manifestError(client, folderShare()).userText)
            assertEquals(1, calls)
        }
        for ((address, response, expected) in listOf(
            Triple("old", 509, R.string.ja_error_mega_quota),
            Triple(null, 200, R.string.ja_error_mega_size_changed),
        )) {
            var calls = 0
            val client = client { request ->
                calls++
                reply(request, response, ticket("changed", 33).toResponseBody())
            }
            val failure = runCatching {
                client.download(megaRemote(address), File(temporary.newFolder(), "file")) {}
            }.exceptionOrNull() as JaWorkException
            assertEquals(TextRef.Res(expected), failure.userText)
            assertEquals(1, calls)
        }
        val responses = ArrayDeque(listOf("-3", "[-18]", folderTree(file(AUDIO, ROOT, "voice.mp3"))))
        val client = client { request -> reply(request, 200, responses.removeFirst().toResponseBody()) }
        assertEquals("voice.mp3", client.manifest(entry, JaVisibleDownload(folderShare(), "")).files.single().relativePath)
        assertTrue(responses.isEmpty())
    }

    @Test fun megaListIsLimitedByBytesAndMetaRefreshRetainsTheWholeUrl() = runBlocking {
        val folder = folderTree(file(AUDIO, ROOT, "voice.mp3"))
        val sizeInBytes = folder.toByteArray().size
        val padding = "界".repeat((8 * 1024 * 1024 - sizeInBytes) / 3)
        val padded = folder.replace("\"f\":", "\"padding\":\"$padding\",\"f\":")
        val oversized = client { request -> reply(request, 200, padded.toResponseBody()) }
        assertEquals(TextRef.Res(R.string.ja_error_download_unavailable),
            manifestError(oversized, folderShare()).userText)

        val client = client { request -> reply(request, 200, folder.toResponseBody()) }
        val url = folderShare().replace("#", "?ticket=one;two#")
        val visible = JaVisibleDownload(entry.address, """<meta http-equiv="refresh" content="0; URL = $url">""")
        assertEquals("voice.mp3", client.manifest(entry, visible).files.single().relativePath)
        assertFalse(megaRemote("a-private-ticket").toString().contains("a-private-ticket"))
        val share = JaMegaShare.parse(folderShare().toHttpUrl())
        assertFalse(share.toString().contains(folderShare().substringAfter('#')))
    }

    private suspend fun manifestError(client: JaWorkClient, link: String): JaWorkException =
        runCatching { client.manifest(entry, JaVisibleDownload(link, "")) }.exceptionOrNull() as JaWorkException

    private companion object {
        const val PUBLIC = "Public__"
        const val ROOT = "Root____"
        const val SUB = "Nested__"
        const val AUDIO = "Audio___"
        val megaFolderKey = ByteArray(16) { (it + 3).toByte() }
        val megaShareKey = "EBAQEBAQEBAQanTjPPw3cxAREhMUFRYXGGN-6DDxOXw".decodeBase64()!!.toByteArray()
        val megaCiphertext = "x4eoobM-v_3_B2mHstVMHOdAttKMGGXGy8gx6gAkZCE".decodeBase64()!!.toByteArray()

        fun folderShare(selector: String = "") = "https://mega.nz/folder/$PUBLIC#${encode(megaFolderKey)}$selector"
        fun legacyFolderShare(selector: String = "") = "https://mega.nz/#F!$PUBLIC!${encode(megaFolderKey)}$selector"
        fun folderTree(vararg nodes: String) = """[{"f":[${(listOf(directory(ROOT, "Foreign_", "Shared")) + nodes).joinToString(",")}]}]"""
        fun directory(id: String, parent: String, name: String): String =
            node(id, parent, 1, name, ByteArray(16) { (it + 31).toByte() })

        fun file(id: String, parent: String, name: String, size: Int = 32): String =
            node(id, parent, 0, name, if (size == 0) ByteArray(32) else megaShareKey, size)

        fun node(id: String, parent: String, type: Int, name: String, key: ByteArray, size: Int = 0): String {
            val attributeKey = if (type == 1) key else ByteArray(16) { (key[it].toInt() xor key[it + 16].toInt()).toByte() }
            val raw = """MEGA{"n":${JsonPrimitive(name)}}""".toByteArray()
            val attributes = Cipher.getInstance("AES/CBC/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(attributeKey, "AES"), IvParameterSpec(ByteArray(16)))
            }.doFinal(raw.copyOf((raw.size + 15) and 15.inv()))
            val wrapped = Cipher.getInstance("AES/ECB/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(megaFolderKey, "AES"))
            }.doFinal(key)
            return """{"h":"$id","p":"$parent","t":$type,"a":"${encode(attributes)}","k":"Foreign_:${encode(ByteArray(key.size))}/$ROOT:${encode(wrapped)}","s":$size}"""
        }

        fun encode(bytes: ByteArray) = bytes.toByteString().base64Url().trimEnd('=')
        fun ticket(route: String, size: Int = 32) = """[{"g":"https://store.userstorage.mega.co.nz/$route","s":$size}]"""
        fun megaRemote(route: String?) = JaRemoteFile("voice.mp3", 32, null,
            JaFileTransfer.Mega(JaMegaFileSource.FolderFile(PUBLIC, AUDIO), JaMegaCrypto(megaShareKey),
                route?.let { "https://store.userstorage.mega.co.nz/$it".toHttpUrl() }))
    }

    @Test fun archiveKeepsFirstCanonicalPathAndRejectsTraversal() {
        val folder = temporary.newFolder()
        fun archive(name: String, vararg paths: String): File = File(folder, name).also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                paths.forEachIndexed { index, path ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write("file-$index".toByteArray())
                    zip.closeEntry()
                }
            }
        }
        val output = File(folder, "unpacked")
        val archive = archive("work.zip", "Tracks/é.txt", "tracks/e\u0301.txt", "Tracks/another.txt")
        val extracted = extractDownloadArchive(listOf(archive), output, ::normalizeJaWorkPath, ::jaWorkPathKey) {}
        assertEquals(1, extracted.skipped)
        assertEquals(setOf("Tracks/é.txt", "Tracks/another.txt"),
            extracted.entries.filterNot { it.isDirectory }.map { it.relativePath }.toSet())
        assertEquals("file-0", File(output, "Tracks/é.txt").readText())
        val unsafe = archive("other.zip", "../outside.txt")
        val blocked = File(folder, "blocked")
        assertTrue(runCatching { extractDownloadArchive(listOf(unsafe), blocked, ::normalizeJaWorkPath, ::jaWorkPathKey) {} }.isFailure)
        assertFalse(File(folder, "outside.txt").exists())
        assertFalse(blocked.exists())
        assertNull(normalizeJaWorkPath("folder/CON.txt"))
    }

    private fun client(response: (Request) -> Response) =
        JaWorkClient(OkHttpClient.Builder().addInterceptor { response(it.request()) }.build())

    private fun reply(request: Request, code: Int, body: ResponseBody = "".toResponseBody(),
        headers: Map<String, String> = emptyMap()): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body)
            .apply { headers.forEach { (name, value) -> header(name, value) } }.build()

    private fun truncatedBody(bytes: ByteArray, length: Long) = object : ResponseBody() {
        private val source = Buffer().write(bytes)
        override fun contentType(): MediaType? = null
        override fun contentLength() = length
        override fun source() = source
    }
}
