package com.nanahoshi.audioplayer.dlsite

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DlsiteArchiveRoutingTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun downloadsEachVolumeBeforeAuthorizingTheNextOnAStatefulFileEndpoint() = runBlocking {
        val events = mutableListOf<String>()
        val contents = listOf(ByteArray(20) { 1 }, ByteArray(21) { 2 }, ByteArray(13) { 3 })
        var authorizedVolume = 0
        val client = client { chain ->
            val request = chain.request()
            when (request.url.host) {
                "play.dlsite.com" -> redirect(chain, "https://www.dlsite.com/home/download/split/=/product_id/RJ300957.html")
                "www.dlsite.com" -> if (request.url.encodedPath.contains("/split/")) {
                    reply(chain, 200, (1..3).joinToString("") {
                        "<a href='/home/download/=/number/$it/product_id/RJ300957.html'>$it</a>"
                    })
                } else {
                    val segments = request.url.pathSegments
                    authorizedVolume = segments[segments.indexOf("number") + 1].toInt()
                    events += "authorize $authorizedVolume"
                    redirect(chain, "https://download.dlsite.com/stream")
                }
                else -> {
                    val name = "RJ300957.part$authorizedVolume." + if (authorizedVolume == 1) "exe" else "rar"
                    val bytes = contents[authorizedVolume - 1]
                    val offset = request.header("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()
                    if (offset != null) events += "download $authorizedVolume from $offset"
                    val response = binary(chain, if (offset == null) bytes else bytes.copyOfRange(offset, bytes.size))
                        .newBuilder().header("Content-Disposition", "attachment; filename=$name")
                    if (offset != null) response.code(206).header("Content-Range", "bytes $offset-${bytes.lastIndex}/${bytes.size}")
                    response.build()
                }
            }
        }
        val staging = temporary.newFolder()
        File(staging, "RJ300957.part1.exe.part").writeBytes(contents[0].copyOf(5))
        val files = client.download("RJ300957", staging)
        assertEquals(listOf("authorize 1", "download 1 from 5", "authorize 2", "download 2 from 0", "authorize 3", "download 3 from 0"), events)
        files.forEachIndexed { index, file -> assertArrayEquals(contents[index], file.readBytes()) }
    }

    @Test fun requestsDesktopArchiveFlowForBothSingleAndSplitDownloads() = runBlocking {
        for (workno in listOf("RJ300957", "RJ01042521")) {
            var mediaRequests = 0
            val client = client { chain ->
                val request = chain.request()
                val agent = request.header("User-Agent").orEmpty()
                if (agent.contains("Mobile") || agent.contains("Android")) {
                    reply(chain, 200, "<html><title>ファイル一覧</title><a href='/maniaxtouch/download/filelist/=/product_id/$workno.html'>files</a></html>")
                } else {
                    assertEquals(DlsiteArchiveClient.ARCHIVE_USER_AGENT, agent)
                    when (request.url.host) {
                        "play.dlsite.com" -> redirect(chain,
                            if (workno == "RJ300957") "https://www.dlsite.com/home/download/split/=/product_id/$workno.html"
                            else "https://download.dlsite.com/get/=/file/$workno.zip/_/temporary")
                        "www.dlsite.com" -> {
                            if (request.url.encodedPath.contains("/split/")) {
                                reply(chain, 200, "<a class='btn_dl split' href='/home/download/=/number/1/product_id/$workno.html'>part 1</a>")
                            } else redirect(chain, "https://download.dlsite.com/get/=/file/$workno.part1.exe/_/temporary")
                        }
                        else -> { mediaRequests++; binary(chain, "archive fixture".toByteArray()) }
                    }
                }
            }
            val files = client.download(workno, temporary.newFolder())
            assertEquals(1, files.size)
            assertTrue(files.single().isFile)
            assertTrue(mediaRequests > 0)
        }
    }

    @Test fun restoresLeadingZeroBeforeRequestingNewWorkAndNeverRangesControlPages() = runBlocking {
        val requestedIds = mutableListOf<String>()
        val bytes = "original package".toByteArray()
        val client = client { chain ->
            val request = chain.request()
            when (request.url.host) {
                "play.dlsite.com" -> {
                    assertNull(request.header("Range"))
                    val id = request.url.queryParameter("workno")!!
                    requestedIds += id
                    if (id != "RJ01042521") reply(chain, 400, "bad work number")
                    else redirect(chain, "https://www.dlsite.com/maniax/download/=/product_id/$id.html")
                }
                "www.dlsite.com" -> {
                    assertNull(request.header("Range"))
                    redirect(chain, "https://download.dlsite.com/get/=/file/RJ01042521.zip/_/temporary")
                }
                else -> binary(chain, bytes)
            }
        }
        assertArrayEquals(bytes, client.download("RJ1042521", temporary.newFolder()).single().readBytes())
        assertEquals(listOf("RJ01042521"), requestedIds)
    }

    @Test fun readsFullSplitPageAndResolvesEachExeOrRarPartWithoutRangingTheStore() = runBlocking {
        val parts = mutableListOf<Int>()
        val client = client { chain ->
            val request = chain.request()
            when (request.url.host) {
                "play.dlsite.com" -> {
                    assertNull(request.header("Range"))
                    redirect(chain, "https://www.dlsite.com/home/split/=/product_id/RJ300957.html")
                }
                "www.dlsite.com" -> {
                    assertNull(request.header("Range"))
                    if (request.url.encodedPath.contains("/split/")) {
                        reply(chain, 200, """
                            <a href="/maniax/download/=/number/2/product_id/RJ300957.html">part 2</a>
                            <button onclick="location.href='/maniax/download/=/number/1/product_id/RJ300957.html'">part 1</button>
                        """.trimIndent())
                    } else {
                        val segments = request.url.pathSegments
                        val part = segments[segments.indexOf("number") + 1].toInt()
                        parts += part
                        val name = if (part == 1) "RJ300957.part1.exe" else "RJ300957.part2.rar"
                        redirect(chain, "https://download.dlsite.com/get/=/file/$name/_/temporary")
                    }
                }
                else -> binary(chain, request.url.pathSegments.joinToString().toByteArray())
            }
        }
        val files = client.download("RJ300957", temporary.newFolder())
        assertEquals(listOf("RJ300957.part1.exe", "RJ300957.part2.rar"), files.map { it.name })
        assertEquals(listOf(1, 2), parts)
        assertTrue(files.all { it.length() > 0 })
    }

    @Test fun ignoresLocaleNavigationAndDownloadsEachNumberedVolumeExactlyOnce() = runBlocking {
        val split = "https://www.dlsite.com/home/download/split/=/product_id/RJ300957.html"
        val requestedVolumes = mutableListOf<Int>()
        var splitRequests = 0
        val client = client { chain ->
            val request = chain.request()
            when (request.url.host) {
                "play.dlsite.com" -> redirect(chain, split)
                "www.dlsite.com" -> {
                    if (request.url.encodedPath.contains("/split/")) {
                        splitRequests++
                        reply(chain, 200, """
                            <a href='$split'>current page</a>
                            <a href='$split/?locale=ja_JP'>日本語</a>
                            <a href='$split/?locale=zh_CN'>简体中文</a>
                            <a href='$split/?locale=en_US'>English</a>
                            <a class='btn_dl split' href='/home/download/=/number/3/product_id/RJ300957.html'>3</a>
                            <a class='btn_dl split' href='/home/download/=/number/1/product_id/RJ300957.html'>1</a>
                            <a class='btn_dl split' href='/home/download/=/number/2/product_id/RJ300957.html'>2</a>
                            <script>const duplicate = "/home/download/=/number/1/product_id/RJ300957.html?locale=zh_CN";</script>
                            <a href='/home/download/=/number/1/product_id/RJ999999.html'>other work</a>
                        """.trimIndent())
                    } else {
                        assertNull(request.url.queryParameter("locale"))
                        val segments = request.url.pathSegments
                        val number = segments[segments.indexOf("number") + 1].toInt()
                        requestedVolumes += number
                        val name = "RJ300957.part$number." + if (number == 1) "exe" else "rar"
                        redirect(chain, "https://download.dlsite.com/get/=/file/$name/_/temporary")
                    }
                }
                else -> binary(chain, "archive fixture".toByteArray())
            }
        }
        val files = client.download("RJ300957", temporary.newFolder())
        assertEquals(1, splitRequests)
        assertEquals(listOf(1, 2, 3), requestedVolumes)
        assertEquals(listOf("RJ300957.part1.exe", "RJ300957.part2.rar", "RJ300957.part3.rar"), files.map { it.name })
    }

    @Test fun splitPageWithOnlyNavigationDoesNotTreatAnotherLocaleAsAFile() {
        val page = "https://www.dlsite.com/home/download/split/=/product_id/RJ300957.html".toHttpUrl()
        val links = DlsiteArchiveClient.parseDownloadLinks(page, """
            <a href='$page/?locale=ja_JP'>日本語</a>
            <a href='/home/download/=/number/0/product_id/RJ300957.html'>invalid zero</a>
            <a href='/home/download/=/number/not-a-number/product_id/RJ300957.html'>invalid number</a>
        """.trimIndent(), "RJ300957")
        assertTrue(links.isEmpty())
    }

    @Test fun serialPageSelectsDownloadTargetInsteadOfLocaleNavigation() {
        val page = "https://www.dlsite.com/home/download/serial/=/product_id/VJ123456.html".toHttpUrl()
        val links = DlsiteArchiveClient.parseDownloadLinks(page, """
            <a href='$page/?locale=ja_JP'>日本語</a>
            <a class='btn_dl' href='/home/download/=/product_id/VJ123456.html'>download</a>
        """.trimIndent(), "VJ123456")
        assertEquals(listOf("https://www.dlsite.com/home/download/=/product_id/VJ123456.html"), links.map { it.toString() })
    }

    @Test fun stillRejectsActualHttpRedirectLoops() = runBlocking {
        var requests = 0
        val client = client { chain ->
            requests++
            redirect(chain, "https://www.dlsite.com/home/download/=/product_id/RJ300957.html")
        }
        val error = runCatching { client.download("RJ300957", temporary.newFolder()) }.exceptionOrNull()
        assertTrue(error is DlsiteArchiveException)
        assertTrue(error!!.message.orEmpty().contains("重定向次数过多"))
        assertTrue(requests <= 9)
    }

    @Test fun decodesQuotedDownloadAttributesButRejectsOtherWorksAndExternalHosts() {
        val page = "https://www.dlsite.com/maniax/split/=/product_id/RJ300957.html".toHttpUrl()
        val links = DlsiteArchiveClient.parseDownloadLinks(page, """
            <button data-url="/maniax/download/=/number/1/product_id/RJ300957.html?x=1&amp;y=2">download</button>
            <a href="https://evil.example/maniax/download/=/product_id/RJ300957.html">external</a>
            <a href="/maniax/download/=/product_id/RJ111111.html">another work</a>
            <button data-url="&#47;home&#47;download&#47;=&#47;number&#47;2&#47;product_id&#47;RJ300957.html">2</button>
        """.trimIndent(), "RJ300957")
        assertEquals(2, links.size)
        assertEquals("2", links.first().queryParameter("y"))
        assertEquals(setOf("maniax", "home"), links.map { it.pathSegments.first() }.toSet())
    }

    @Test fun distinguishesVerificationLoginUnknownPageAndBadRequestWithoutLeakingBodies() = runBlocking {
        for ((html, expected) in listOf(
            "<html><script src='/cdn-cgi/challenge-platform/test'></script></html>" to "验证页面",
            "<html><input type='password' name='password'></html>" to "商店登录尚未生效",
            "<html><title>unrecognized</title>private text</html>" to "没有找到完整原始包的下载地址",
        )) {
            val error = runCatching { client { reply(it, 200, html) }.download("RJ300957", temporary.newFolder()) }.exceptionOrNull()
            assertTrue(error is DlsiteArchiveException)
            assertTrue(error!!.message.orEmpty().contains(expected))
            assertFalse(error.message.orEmpty().contains("private text"))
        }
        var count = 0
        val error = runCatching { client { count++; reply(it, 400, "secret server body") }
            .download("RJ01042521", temporary.newFolder()) }.exceptionOrNull()
        assertEquals(1, count)
        assertTrue(error!!.message.orEmpty().contains("原始下载入口"))
        assertTrue(error.message.orEmpty().contains("400"))
        assertFalse(error.message.orEmpty().contains("secret"))
    }

    private fun client(handler: (Interceptor.Chain) -> Response) = DlsiteArchiveClient(
        { "session=fixture" }, OkHttpClient.Builder().addInterceptor { handler(it) }.build(),
    )

    private fun redirect(chain: Interceptor.Chain, url: String) = reply(chain, 302, "")
        .newBuilder().header("Location", url).build()

    private fun reply(chain: Interceptor.Chain, code: Int, body: String) = Response.Builder()
        .request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
        .body(body.toResponseBody("text/html".toMediaType())).build()

    private fun binary(chain: Interceptor.Chain, bytes: ByteArray) = Response.Builder()
        .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
        .body(bytes.toResponseBody("application/octet-stream".toMediaType())).build()
}
