package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.data.db.FileKind
import com.nanahoshi.audioplayer.dlsite.RemotePreview
import com.nanahoshi.audioplayer.dlsite.SignedPlayFile
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ExternalFileDownloadTest {
    @get:Rule val temp = TemporaryFolder()
    private fun preview(authed: Boolean = false) = RemotePreview("book.pdf", "https://stale.invalid/private", FileKind.OTHER,
        "https://play.dlsite.com/", authed, "RJ123456", "book.pdf")
    private fun response(chain: Interceptor.Chain, status: Int = 200, location: String? = null) = Response.Builder()
        .request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
        .body("%PDF-fixture".toResponseBody("application/pdf".toMediaType()))
        .apply { location?.let { header("Location", it) } }.build()

    @Test fun refreshesExpiredAuthorizationOnceAndUsesFreshIdentity() = runBlocking {
        var signs = 0
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            assertEquals("media.dlsite.com", chain.request().url.host)
            assertEquals("fixture=$signs", chain.request().header("Cookie"))
            response(chain, if (calls == 1) 403 else 200)
        }.build()
        val downloader = ExternalFileDownloader(client) { work, key ->
            assertEquals("RJ123456", work); assertEquals("book.pdf", key)
            signs++
            SignedPlayFile("https://media.dlsite.com/optimized/book.pdf", "fixture=$signs")
        }
        val target = temp.newFile()
        downloader.download(preview(true), target) { _, _ -> }
        assertEquals(2, signs)
        assertEquals(2, calls)
        assertEquals("%PDF-fixture", target.readText())
    }

    @Test fun redirectNeverForwardsMediaCookiesToAnotherHost() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url.host == "media.dlsite.com") {
                assertEquals("fixture=value", chain.request().header("Cookie"))
                response(chain, 302, "https://cdn.example.invalid/book.pdf")
            } else {
                assertNull(chain.request().header("Cookie"))
                response(chain)
            }
        }.build()
        val downloader = ExternalFileDownloader(client) { _, _ -> SignedPlayFile("https://media.dlsite.com/book.pdf", "fixture=value") }
        downloader.download(preview(true), temp.newFile()) { _, _ -> }
    }

    @Test fun stopsBeforeRequestingAnInsecureRedirect() = runBlocking {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain -> calls++; response(chain, 302, "http://example.invalid/book.pdf") }.build()
        val downloader = ExternalFileDownloader(client) { _, _ -> error("anonymous request must not sign") }
        assertTrue(runCatching { downloader.download(preview(), temp.newFile()) { _, _ -> } }.isFailure)
        assertEquals(1, calls)
    }

    @Test fun insufficientSpaceDoesNotWriteAResponseBody() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { response(it) }.build()
        val downloader = ExternalFileDownloader(client, usableSpace = { 0L }) { _, _ -> error("must not sign") }
        val target = temp.newFile()
        val failure = runCatching { downloader.download(preview(), target) { _, _ -> } }.exceptionOrNull()
        assertTrue(failure is ExternalFileException)
        assertEquals(0, target.length())
    }

    @Test fun cancellationStopsCopyingBeforeTheRemainingFileIsPublished() = runBlocking {
        val output = ByteArrayOutputStream()
        val job = launch(start = CoroutineStart.LAZY) {
            val active = currentCoroutineContext()[Job]!!
            copyExternalBytes(ByteArrayInputStream(ByteArray(1024 * 1024)), output, 1024L * 1024) { _, _ ->
                active.cancel()
            }
        }
        job.start(); job.join()
        assertTrue(job.isCancelled)
        assertTrue(output.size() in 1 until 1024 * 1024)
    }

    @Test fun truncatedResponseCannotBeAcceptedAsACompleteFile() = runBlocking {
        val failure = runCatching { copyExternalBytes(ByteArrayInputStream(byteArrayOf(1)), ByteArrayOutputStream(), 10) { _, _ -> } }
        assertTrue(failure.isFailure)
    }
}
