package com.nanahoshi.audioplayer.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OfflineWhisperModelManagerTest {
    @get:Rule
    val root = TemporaryFolder()

    @Test
    fun modelsUseSeparateDirectoriesAndRequireTheCorrectVersionAndFiles() = runBlocking {
        val senseVoice = OfflineWhisperModel.SenseVoice
        val parakeet = OfflineWhisperModel.Parakeet
        val oldInstallation = installedModel(senseVoice)
        val senseManager = OfflineWhisperModelManager(root.root, senseVoice)
        val parakeetManager = OfflineWhisperModelManager(root.root, parakeet)
        assertTrue(senseManager.isReady())
        assertEquals(OfflineWhisperModelState.Ready, senseManager.state.value)
        assertFalse(parakeetManager.isReady())
        installedModel(parakeet).resolve("version.txt").writeText(senseVoice.version)
        assertFalse(parakeetManager.isReady())
        installedModel(parakeet).resolve("tokens.txt").writeText("")
        assertFalse(parakeetManager.isReady())
        installedModel(parakeet)
        assertTrue(parakeetManager.isReady())

        senseManager.useModel { _, _ ->
            assertFalse(senseManager.deleteModel())
            assertTrue(parakeetManager.deleteModel())
        }
        assertTrue(oldInstallation.isDirectory)
        assertFalse(parakeetManager.isReady())
        assertTrue(senseManager.deleteModel())
        assertFalse(senseManager.isReady())
    }

    @Test
    fun invalidDownloadCleansTemporaryFilesAndAllowsRetryFromAnotherSource() = runBlocking {
        val model = OfflineWhisperModel.Parakeet
        val staging = File(root.root, "${model.directory}-tmp").apply { mkdir() }
        val orphan = File(staging, "orphan").apply { writeText("old") }
        val hosts = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            hosts += chain.request().url.host
            response(chain.request(), 503)
        }.build()
        val manager = OfflineWhisperModelManager(root.root, model, client)

        manager.download(ModelDownloadSource.GitHub)
        awaitFailure(manager)
        assertFalse(orphan.exists())
        assertFalse(staging.exists())
        assertFalse(manager.isReady())

        manager.download(ModelDownloadSource.HuggingFace)
        awaitFailure(manager)
        assertEquals(listOf("github.com", "huggingface.co"), hosts)
        assertFalse(staging.exists())
    }

    @Test
    fun cancelDuringFileTransferCleansPartialFileAndPreventsOverlappingRetry() = runBlocking {
        val model = OfflineWhisperModel.Parakeet
        val reading = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val requests = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            response(chain.request(), 200, object : ResponseBody() {
                private val input = object : Source {
                    private var started = false

                    override fun read(sink: okio.Buffer, byteCount: Long): Long {
                        if (!started) {
                            started = true
                            val count = minOf(byteCount, 8192).toInt()
                            sink.write(ByteArray(count))
                            return count.toLong()
                        }
                        reading.countDown()
                        check(unblock.await(10, TimeUnit.SECONDS))
                        throw IOException("interrupted")
                    }

                    override fun timeout() = Timeout.NONE
                    override fun close() = Unit
                }.buffer()

                override fun contentLength() = model.modelBytes
                override fun contentType(): MediaType? = null
                override fun source(): BufferedSource = input
            })
        }.build()
        val manager = OfflineWhisperModelManager(root.root, model, client)

        val job = checkNotNull(manager.download(ModelDownloadSource.HuggingFace))
        try {
            assertTrue(reading.await(10, TimeUnit.SECONDS))
            manager.cancelDownload(job)
            assertNull(manager.download(ModelDownloadSource.GitHub))
        } finally {
            unblock.countDown()
        }
        withTimeout(10_000) { manager.state.first { it == OfflineWhisperModelState.NotDownloaded } }
        assertEquals(1, requests.get())
        assertFalse(File(root.root, "${model.directory}-tmp").exists())
        assertFalse(manager.isReady())
    }

    @Test
    fun serviceInterruptionOnlyStopsItsOwnAttemptAndReportsFailureAfterCleanup() = runBlocking {
        val model = OfflineWhisperModel.SenseVoice
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url.host == "huggingface.co") {
                entered.countDown()
                check(unblock.await(10, TimeUnit.SECONDS))
            }
            response(chain.request(), 503)
        }.build()
        val manager = OfflineWhisperModelManager(root.root, model, client)

        val first = checkNotNull(manager.download(ModelDownloadSource.GitHub))
        withTimeout(10_000) { first.join() }
        val second = checkNotNull(manager.download(ModelDownloadSource.HuggingFace))
        val message = "后台下载中断，请重试"
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            manager.cancelDownload(first, message)
            manager.cancelDownload(second, "后台下载超时，请重试")
            assertTrue(manager.state.value is OfflineWhisperModelState.Downloading)
        } finally {
            unblock.countDown()
        }
        withTimeout(10_000) { second.join() }
        assertEquals(OfflineWhisperModelState.Failed("后台下载超时，请重试"), manager.state.value)
        assertFalse(File(root.root, "${model.directory}-tmp").exists())
    }

    private suspend fun awaitFailure(manager: OfflineWhisperModelManager) {
        withTimeout(10_000) { manager.state.first { it is OfflineWhisperModelState.Failed } }
    }

    private fun installedModel(model: OfflineWhisperModel): File =
        File(root.root, model.directory).apply {
            mkdirs()
            RandomAccessFile(File(this, "model.int8.onnx"), "rw").use { it.setLength(model.modelBytes) }
            RandomAccessFile(File(this, "tokens.txt"), "rw").use { it.setLength(model.tokenBytes) }
            File(this, "version.txt").writeText(model.version)
        }

    private fun response(request: okhttp3.Request, code: Int, body: ResponseBody? = null): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
            .message("test").body(body ?: object : ResponseBody() {
                override fun contentLength() = 0L
                override fun contentType(): MediaType? = null
                override fun source(): BufferedSource = okio.Buffer()
            }).build()
}
