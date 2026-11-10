package com.nanahoshi.audioplayer.dlsite

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DlsiteArchiveTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun downloadsOriginalArchiveThroughOfficialRedirectAndReusesCompletedFile() = runBlocking {
        val bytes = zip("作品/wav/01.wav" to "original")
        val downloads = mutableListOf<String>()
        val client = fakeClient { chain ->
            val request = chain.request()
            if (request.url.host == "play.dlsite.com") {
                response(chain, 302, "", "Location" to "https://download.dlsite.com/get/=/file/RJ123.zip/_/1")
            } else {
                val range = request.header("Range").orEmpty()
                downloads += range
                if (range == "bytes=0-0") binary(chain, bytes.copyOf(1), 206, "Content-Range" to "bytes 0-0/${bytes.size}")
                else binary(chain, bytes, 206, "Content-Range" to "bytes 0-${bytes.lastIndex}/${bytes.size}")
            }
        }
        val staging = temp.newFolder()
        val files = client.download("RJ123", staging)
        assertArrayEquals(bytes, files.single().readBytes())
        assertEquals(1, downloads.count { it == "bytes=0-" })
        client.download("RJ123", staging)
        assertEquals(1, downloads.count { it == "bytes=0-" })
    }

    @Test fun resumesPartialButRestartsWhenServerIgnoresRange() = runBlocking {
        val bytes = zip("01.wav" to "original")
        for (ignoreRange in listOf(false, true)) {
            val ranges = mutableListOf<String>()
            val client = fakeClient { chain ->
                val range = chain.request().header("Range").orEmpty()
                ranges += range
                when (range) {
                    "" -> binary(chain, bytes, 200, "Content-Disposition" to "attachment; filename=RJ123.zip")
                    "bytes=0-0" -> binary(chain, bytes.copyOf(1), 206,
                        "Content-Disposition" to "attachment; filename=RJ123.zip",
                        "Content-Range" to "bytes 0-0/${bytes.size}")
                    else -> if (ignoreRange) binary(chain, bytes, 200) else binary(chain, bytes.copyOfRange(5, bytes.size), 206,
                        "Content-Range" to "bytes 5-${bytes.lastIndex}/${bytes.size}")
                }
            }
            val staging = temp.newFolder()
            File(staging, "RJ123.zip.part").writeBytes(bytes.copyOf(5))
            val files = client.download("RJ123", staging)
            assertTrue(ranges.contains("bytes=5-"))
            assertArrayEquals(bytes, files.single().readBytes())
        }
    }

    @Test fun refusesWrongResumeRangeAndNeverLeaksServerAddress() = runBlocking {
        val client = fakeClient { chain ->
            binary(chain, byteArrayOf(1), 206,
                "Content-Disposition" to "attachment; filename=RJ123.zip",
                "Content-Range" to if (chain.request().header("Range") == "bytes=0-0") "bytes 0-0/20" else "bytes 0-19/20")
        }
        val staging = temp.newFolder()
        File(staging, "RJ123.zip.part").writeBytes(ByteArray(5))
        val error = runCatching { client.download("RJ123", staging) }.exceptionOrNull()
        assertTrue(error is DlsiteArchiveException)
        assertTrue(error!!.message.orEmpty().contains("续传位置"))
        assertFalse(error.message.orEmpty().contains("http"))
        assertFalse(File(staging, "RJ123.zip").exists())
    }

    @Test fun refusesChangedArchiveSizeWithoutAppendingOrDiscardingThePartial() = runBlocking {
        val prefix = ByteArray(5) { 7 }
        val client = fakeClient { chain ->
            if (chain.request().header("Range") == null) binary(chain, ByteArray(20), 200,
                "Content-Disposition" to "attachment; filename=RJ123.zip")
            else binary(chain, ByteArray(16), 206, "Content-Range" to "bytes 5-20/21")
        }
        val staging = temp.newFolder()
        val partial = File(staging, "RJ123.zip.part").apply { writeBytes(prefix) }
        val error = runCatching { client.download("RJ123", staging) }.exceptionOrNull()
        assertTrue(error is DlsiteArchiveException)
        assertTrue(error!!.message.orEmpty().contains("在下载过程中发生变化"))
        assertArrayEquals(prefix, partial.readBytes())
        assertFalse(File(staging, "RJ123.zip").exists())
    }

    @Test fun refusesAnotherVolumeWithTheSameSizeBeforeWritingItsBody() = runBlocking {
        for (status in listOf(200, 206)) {
            val prefix = ByteArray(5) { 7 }
            val client = fakeClient { chain ->
                if (chain.request().header("Range") == null) binary(chain, ByteArray(20), 200,
                    "Content-Disposition" to "attachment; filename=RJ123.part1.rar")
                else binary(chain, ByteArray(if (status == 206) 15 else 20), status,
                    "Content-Disposition" to "attachment; filename=RJ123.part2.rar",
                    "Content-Range" to "bytes 5-19/20")
            }
            val staging = temp.newFolder()
            val partial = File(staging, "RJ123.part1.rar.part").apply { writeBytes(prefix) }
            val error = runCatching { client.download("RJ123", staging) }.exceptionOrNull()
            assertTrue(error is DlsiteArchiveException)
            assertTrue(error!!.message.orEmpty().contains("文件不完整"))
            assertArrayEquals(prefix, partial.readBytes())
            assertFalse(File(staging, "RJ123.part1.rar").exists())
        }
    }

    @Test fun reusesUnknownLengthOnlyAfterWritingCompletionMarker() = runBlocking {
        val bytes = zip("01.wav" to "original")
        var transfers = 0
        val client = fakeClient { chain ->
            if (chain.request().header("Range") == "bytes=0-") transfers++
            val body = object : ResponseBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = -1L
                override fun source() = okio.Buffer().write(bytes)
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("test")
                .header("Content-Disposition", "attachment; filename=RJ123.zip").body(body).build()
        }
        val staging = temp.newFolder()
        client.download("RJ123", staging)
        client.download("RJ123", staging)
        assertEquals(1, transfers)
        assertEquals(bytes.size.toString(), File(staging, "RJ123.zip.complete").readText())
        File(staging, "RJ123.zip.complete").delete()
        client.download("RJ123", staging)
        assertEquals(2, transfers)
    }

    @Test fun blocksExternalRedirectBeforeRequestingIt() = runBlocking {
        var requested = 0
        val client = fakeClient { chain ->
            requested++
            response(chain, 302, "", "Location" to "https://example.com/private?token=secret")
        }
        val error = runCatching { client.download("RJ123", temp.newFolder()) }.exceptionOrNull()
        assertTrue(error is DlsiteArchiveException)
        assertFalse(error!!.message.orEmpty().contains("secret"))
        assertEquals(1, requested)
    }

    @Test fun triesEightDigitWorkNumberOnlyWhenSixDigitSpellingIsAbsent() = runBlocking {
        val bytes = zip("01.wav" to "original")
        val requested = mutableListOf<String>()
        val client = fakeClient { chain ->
            val id = chain.request().url.queryParameter("workno").orEmpty()
            requested += id
            if (id == "RJ000123") response(chain, 404, "not found")
            else if (chain.request().header("Range") == "bytes=0-0") binary(chain, bytes.copyOf(1), 206,
                "Content-Disposition" to "attachment; filename=RJ00000123.zip",
                "Content-Range" to "bytes 0-0/${bytes.size}")
            else binary(chain, bytes, 200, "Content-Disposition" to "attachment; filename=RJ00000123.zip")
        }
        assertArrayEquals(bytes, client.download("RJ123", temp.newFolder()).single().readBytes())
        assertEquals(listOf("RJ000123", "RJ00000123"), requested.distinct())
        var unauthorizedRequests = 0
        val unauthorized = fakeClient { chain -> unauthorizedRequests++; response(chain, 401, "denied") }
        assertTrue(runCatching { unauthorized.download("RJ123", temp.newFolder()) }.exceptionOrNull() is DlsiteArchiveException)
        assertEquals(1, unauthorizedRequests)
    }

    @Test fun parsesOfficialSplitLinksInNumericOrderAndMatchesWorkIdentity() {
        val links = DlsiteArchiveClient.parseDownloadLinks(
            "https://www.dlsite.com/home/split/=/product_id/RJ00000123.html".toHttpUrl(),
            """
                <a href='/home/download/=/number/10/product_id/RJ00000123.html'>10</a>
                <a href='/home/download/=/number/2/product_id/RJ00000123.html'>2</a>
                <a href='/home/download/=/number/2/product_id/RJ00000123.html'>duplicate</a>
                <a href='/home/download/=/number/1/product_id/RJ456.html'>other work</a>
                <a href='https://evil.example/home/download/=/number/1/product_id/RJ123.html'>external</a>
            """.trimIndent(), "RJ123",
        )
        assertEquals(listOf("2", "10"), links.map { it.pathSegments[4] })
    }

    @Test fun extractsFullDirectoryIncludingDuplicateAudioFormatsAndNonAudioFiles() {
        val source = temp.newFile("original.zip").apply { writeBytes(zip(
            "RJ123/音声/01.wav" to "wav", "RJ123/音声/01.mp3" to "mp3", "RJ123/readme.txt" to "説明",
        )) }
        val destination = File(temp.root, "out")
        val entries = DlsiteArchiveExtractor.extract(listOf(source), destination)
        assertTrue(source.exists())
        assertEquals("wav", File(destination, "RJ123/音声/01.wav").readText())
        assertEquals("mp3", File(destination, "RJ123/音声/01.mp3").readText())
        assertTrue(entries.any { it.relativePath == "RJ123" && it.isDirectory })
        assertTrue(entries.any { it.relativePath == "RJ123/readme.txt" && !it.isDirectory })
    }

    @Test fun rejectsTraversalAndRollsBackOnlyNewExtraction() {
        val source = temp.newFile("original.zip").apply { writeBytes(zip("../escape.txt" to "escape")) }
        val destination = File(temp.root, "out")
        assertTrue(runCatching { DlsiteArchiveExtractor.extract(listOf(source), destination) }.exceptionOrNull() is DlsiteArchiveException)
        assertFalse(File(temp.root, "escape.txt").exists())
        assertFalse(destination.exists())
        assertTrue(source.exists())
    }

    @Test fun cancellationLeavesArchiveButRemovesUnpublishedFiles() {
        val source = temp.newFile("original.zip").apply { writeBytes(zip("folder/01.wav" to "audio")) }
        val destination = File(temp.root, "out")
        var checks = 0
        val error = runCatching {
            DlsiteArchiveExtractor.extract(listOf(source), destination) {
                if (++checks > 3) throw CancellationException("cancelled")
            }
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertTrue(source.exists())
        assertFalse(destination.exists())
    }

    @Test fun refusesExistingOutputWithoutDeletingUserFiles() {
        val source = temp.newFile("original.zip").apply { writeBytes(zip("01.wav" to "audio")) }
        val destination = temp.newFolder()
        val existing = File(destination, "existing.txt").apply { writeText("keep") }
        assertTrue(runCatching { DlsiteArchiveExtractor.extract(listOf(source), destination) }.exceptionOrNull() is DlsiteArchiveException)
        assertEquals("keep", existing.readText())
    }

    @Test fun checksumFailureIsTypedSoRetryCanDiscardCorruptPackage() {
        val bytes = zip("01.wav" to "original")
        val central = bytes.indices.first { index ->
            index + 4 <= bytes.size && bytes.copyOfRange(index, index + 4).contentEquals(byteArrayOf(0x50, 0x4B, 1, 2))
        }
        bytes[central + 16] = (bytes[central + 16].toInt() xor 1).toByte()
        val source = temp.newFile("bad.zip").apply { writeBytes(bytes) }
        val destination = File(temp.root, "out")
        val error = runCatching { DlsiteArchiveExtractor.extract(listOf(source), destination) }.exceptionOrNull()
        assertTrue(error is DlsiteArchiveIntegrityException)
        assertFalse(destination.exists())
        assertTrue(source.exists())
    }

    @Test fun extractsRar4Rar5AndSfxWithoutExecutingIt() {
        // Small upstream fixtures, distributed under the accompanying UnRAR license.
        // https://github.com/junrar/junrar/tree/v8.1.2/src/test/resources/com/github/junrar
        val fixtures = listOf(
            "UmFyIRoHAM+QcwAADQAAAAAAAAAJl3QggCkABwAAAAcAAAACun0Zem4DYz0dMAkAIAAAAEZJTEUxLlRYVGZpbGUxDQo8d3QggCkABwAAAAcAAAAC48NfeHEDYz0dMAkAIAAAAEZJTEUyLlRYVGZpbGUyDQrEPXsAQAcA",
            "UmFyIRoHAQDz4YLrCwEFBwAGAQGAgIAATS800SUCAwuHAASHACC6fRl6gAAACUZJTEUxLlRYVAoDAgDwWYPlessBZmlsZTENCqOo3u8lAgMLhwAEhwAg48NfeIAAAAlGSUxFMi5UWFQKAwIAd+2G5XrLAWZpbGUyDQodd1ZRAwUEAA==",
        )
        for ((index, fixture) in fixtures.withIndex()) {
            for (sfx in listOf(false, true)) {
                val source = temp.newFile("rar$index-$sfx.${if (sfx) "exe" else "rar"}").apply {
                    writeBytes((if (sfx) byteArrayOf(0x4D, 0x5A) + ByteArray(254) else ByteArray(0)) + Base64.getDecoder().decode(fixture))
                }
                val destination = temp.newFolder()
                val files = DlsiteArchiveExtractor.extract(listOf(source), destination).filterNot { it.isDirectory }
                assertEquals(2, files.size)
                assertEquals("file1\r\n", File(destination, "FILE1.TXT").readText())
                assertEquals("file2\r\n", File(destination, "FILE2.TXT").readText())
            }
        }
    }

    @Test fun extractsCompleteRarVolumeSetAndRejectsMissingVolume() {
        val packages = (1..3).map { part ->
            temp.newFile("test-documents.part$part.rar").apply {
                DlsiteArchiveTest::class.java.getResourceAsStream("/dlsite-archive/test-documents.part$part.rar")!!.use { input ->
                    outputStream().use(input::copyTo)
                }
            }
        }
        val destination = temp.newFolder()
        val entries = DlsiteArchiveExtractor.extract(packages, destination).filterNot { it.isDirectory }
        assertEquals(9, entries.size)
        assertTrue(entries.all { File(destination, it.relativePath).length() == it.size && it.size > 0 })
        val incomplete = temp.newFolder()
        assertTrue(runCatching {
            DlsiteArchiveExtractor.extract(listOf(packages[0], packages[2]), incomplete)
        }.exceptionOrNull() is DlsiteArchiveException)
        assertFalse(incomplete.exists())
        assertTrue(packages.all { it.exists() })
    }

    private fun fakeClient(intercept: (Interceptor.Chain) -> Response) = DlsiteArchiveClient(
        { "session=test" }, OkHttpClient.Builder().addInterceptor { chain -> intercept(chain) }.build(),
    )

    private fun response(chain: Interceptor.Chain, code: Int, text: String, vararg headers: Pair<String, String>): Response =
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
            .body(text.toResponseBody("text/html".toMediaType())).apply { headers.forEach { (name, value) -> header(name, value) } }.build()

    private fun binary(chain: Interceptor.Chain, bytes: ByteArray, code: Int, vararg headers: Pair<String, String>): Response =
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
            .body(bytes.toResponseBody("application/octet-stream".toMediaType())).apply { headers.forEach { (name, value) -> header(name, value) } }.build()

    private fun zip(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            for ((name, text) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
    }.toByteArray()
}
