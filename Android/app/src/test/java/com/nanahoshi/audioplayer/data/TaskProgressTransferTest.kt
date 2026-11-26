package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.dlsite.DlsiteArchiveClient
import com.nanahoshi.audioplayer.dlsite.DlsiteArchiveException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections

/** 纯 JVM 的传输层与 Qwen 上传/轮询测试。 */
class TaskProgressTransferTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun actualArchiveRangeResumeExcludesOffsetAndIgnoredRangeCountsRewrite() = runBlocking {
        withTimeout(15_000) {
            for (ignoreRange in listOf(false, true)) {
                val bytes = ByteArray(20_000) { (it % 127).toByte() }
                val ranges = Collections.synchronizedList(mutableListOf<String>())
                val server = LocalProgressHttp(this) { request ->
                    val range = request.headers["range"]
                    if (range == null) LocalProgressHttp.Reply(bytes = bytes, headers = mapOf("Content-Disposition" to "attachment; filename=RJ123.zip"))
                    else {
                        ranges += range
                        if (ignoreRange) LocalProgressHttp.Reply(bytes = bytes)
                        else LocalProgressHttp.Reply(206, bytes.copyOfRange(5000, bytes.size), mapOf("Content-Range" to "bytes 5000-19999/20000"))
                    }
                }
                val root = temp.newFolder()
                try {
                    File(root, "RJ123.zip.part").writeBytes(bytes.copyOf(5000))
                    val tracker = TaskProgressTracker()
                    val report = tracker.begin(TaskKey(TaskKind.DOWNLOAD, 1), ProgressPart.DOWNLOAD)
                    val client = archiveClient(server)
                    val files = client.download("RJ123456", root, taskProgress = report)
                    assertArrayEquals(bytes, files.single().readBytes())
                    assertEquals(listOf("bytes=5000-"), ranges.toList())
                    assertEquals(if (ignoreRange) 20_000L else 15_000L, report.snapshot()!!.summary.transferredBytes)
                    assertEquals(20_000L, report.snapshot()!!.summary.done)
                } finally { server.close() }
            }
        }
    }

    @Test fun actualArchiveRejectsWrongContentRangeBeforeCountingOrWriting() = runBlocking {
        withTimeout(10_000) {
            val prefix = ByteArray(5000) { 7 }
            val server = LocalProgressHttp(this) { request ->
                if (request.headers["range"] == null) LocalProgressHttp.Reply(bytes = ByteArray(20_000), headers = mapOf("Content-Disposition" to "attachment; filename=RJ123.zip"))
                else LocalProgressHttp.Reply(206, ByteArray(15_000), mapOf("Content-Range" to "bytes 5001-20000/20001"))
            }
            val root = temp.newFolder()
            try {
                val partial = File(root, "RJ123.zip.part").apply { writeBytes(prefix) }
                val report = TaskProgressTracker().begin(TaskKey(TaskKind.DOWNLOAD, 1), ProgressPart.DOWNLOAD)
                val error = runCatching { archiveClient(server).download("RJ123456", root, taskProgress = report) }.exceptionOrNull()
                assertTrue(error is DlsiteArchiveException)
                assertArrayEquals(prefix, partial.readBytes())
                assertNull(report.snapshot()!!.summary.transferredBytes)
            } finally { server.close() }
        }
    }

    @Test fun actualUploadRedirectReplaysFileAndResetsLogicalPosition() = runBlocking {
        withTimeout(10_000) {
            val bytes = ByteArray(32_000) { 9 }
            val received = Collections.synchronizedList(mutableListOf<ByteArray>())
            lateinit var server: LocalProgressHttp
            server = LocalProgressHttp(this) { request ->
                received += request.body
                if (request.path == "/first") LocalProgressHttp.Reply(307, headers = mapOf("Location" to server.url("/second")))
                else LocalProgressHttp.Reply()
            }
            try {
                val report = TaskProgressTracker().begin(TaskKey(TaskKind.WHISPER, 1), ProgressPart.TRANSCRIPTION)
                withContext(Dispatchers.IO) {
                    val request = Request.Builder().url(server.url("/first"))
                        .post(ProgressRequestBody(bytes.toRequestBody(), report, ProgressPhase.WAITING_TRANSCRIPT))
                        .taskProgress(report, false).build()
                    OkHttpClient().newCall(request).execute().use { assertEquals(200, it.code) }
                }
                assertEquals(2, received.size)
                received.forEach { assertArrayEquals(bytes, it) }
                val summary = report.snapshot()!!.summary
                assertEquals(64_000L, summary.transferredBytes)
                assertEquals(32_000L, summary.done)
                assertEquals(1, summary.retries)
                assertEquals(ProgressPhase.WAITING_TRANSCRIPT, summary.phase)
                assertNull(report.snapshot()!!.fraction())
            } finally { server.close() }
        }
    }

    @Test fun actualQwenUploadPollingAndRecoveryExposeOnlyKnownStages() = runBlocking {
        withTimeout(20_000) {
            val phases = Collections.synchronizedList(mutableListOf<ProgressPhase>())
            val tracker = TaskProgressTracker(checkpoint = { _, _, _, summary -> summary.phase?.let { phases += it } })
            val requests = Collections.synchronizedList(mutableListOf<String>())
            var poll = 0
            lateinit var server: LocalProgressHttp
            server = LocalProgressHttp(this) { request ->
                requests += request.path
                val json = when {
                    request.path.startsWith("/api/v1/uploads") -> """{"data":{"upload_host":"${server.url("/upload")}","upload_dir":"fixture"}}"""
                    request.path == "/upload" -> "{}"
                    request.path == "/api/v1/services/audio/asr/transcription" -> """{"output":{"task_id":"fixture"}}"""
                    request.path == "/api/v1/tasks/fixture" -> when (poll++) {
                        0 -> """{"output":{"task_status":"PENDING"}}"""
                        1 -> """{"output":{"task_status":"RUNNING"}}"""
                        2 -> """{"output":{"task_status":"UNKNOWN"}}"""
                        else -> """{"output":{"task_status":"SUCCEEDED","results":[{"transcription_url":"${server.url("/result")}"}]}}"""
                    }
                    request.path == "/result" -> """{"transcripts":[{"sentences":[{"begin_time":0,"end_time":1000,"text":"こんにちは"}]}]}"""
                    else -> error("unexpected fixture request")
                }
                LocalProgressHttp.Reply(bytes = json.toByteArray())
            }
            val root = temp.newFolder()
            try {
                val audio = File(root, "audio").apply { writeBytes(ByteArray(32_000) { 3 }) }
                val calls = InflightCalls()
                val client = QwenFileTranscriber(calls)
                val report = tracker.begin(TaskKey(TaskKind.WHISPER, 1), ProgressPart.TRANSCRIPTION)
                val cues = withContext(Dispatchers.IO) {
                    client.transcribe(audio, server.url("/"), calls.prepare("first", report), "fixture-key", null, null) { _, _ -> }
                }
                assertEquals("こんにちは", cues.single().text)
                assertTrue(phases.containsAll(listOf(ProgressPhase.UPLOAD_POLICY, ProgressPhase.UPLOADING, ProgressPhase.SUBMITTING,
                    ProgressPhase.REMOTE_PENDING, ProgressPhase.REMOTE_RUNNING, ProgressPhase.REMOTE_UNKNOWN, ProgressPhase.FETCHING_RESULT)))
                assertEquals(32_000L, report.snapshot()!!.summary.transferredBytes)
                assertNull(report.snapshot()!!.fraction())
                requests.clear(); phases.clear()
                val resumed = tracker.begin(TaskKey(TaskKind.WHISPER, 2), ProgressPart.TRANSCRIPTION)
                withContext(Dispatchers.IO) {
                    client.transcribe(File(root, "missing"), server.url("/"), calls.prepare("resume", resumed), "fixture-key", "fixture", System.currentTimeMillis()) { _, _ -> error("must not submit") }
                }
                assertTrue(phases.contains(ProgressPhase.RESTORING_REMOTE))
                assertFalse(phases.contains(ProgressPhase.UPLOADING))
                assertEquals(listOf("/api/v1/tasks/fixture", "/result"), requests.toList())
                assertNull(resumed.snapshot()!!.summary.transferredBytes)
            } finally { server.close() }
        }
    }

    private fun archiveClient(server: LocalProgressHttp): DlsiteArchiveClient {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val original = chain.request()
            val local = original.newBuilder().url(server.url(original.url.encodedPath)).removeHeader("Cookie").build()
            chain.proceed(local).newBuilder().request(original).build()
        }.build()
        return DlsiteArchiveClient({ "fixture=local-only" }, http)
    }
}

internal class LocalProgressHttp(scope: CoroutineScope, private val handler: suspend (Input) -> Reply) {
    data class Input(val path: String, val headers: Map<String, String>, val body: ByteArray)
    data class Reply(val code: Int = 200, val bytes: ByteArray = ByteArray(0), val headers: Map<String, String> = emptyMap())
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val listener = scope.launch(Dispatchers.IO) {
        try {
            while (isActive) {
                val socket = server.accept()
                launch {
                    socket.use {
                        socket.soTimeout = 5000
                        val input = socket.getInputStream().buffered()
                        fun line(): String {
                            val out = java.io.ByteArrayOutputStream()
                            while (true) { val byte = input.read(); if (byte < 0 || byte == 10) break; if (byte != 13) out.write(byte) }
                            return out.toString("US-ASCII")
                        }
                        val path = line().split(' ')[1]
                        val headers = linkedMapOf<String, String>()
                        while (true) { val header = line(); if (header.isEmpty()) break; headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim() }
                        val body = ByteArray(headers["content-length"]?.toInt() ?: 0)
                        var offset = 0
                        while (offset < body.size) { val count = input.read(body, offset, body.size - offset); if (count < 0) throw IOException("short fixture body"); offset += count }
                        val reply = handler(Input(path, headers, body))
                        val extra = reply.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
                        val head = "HTTP/1.1 ${reply.code} Fixture\r\nContent-Length: ${reply.bytes.size}\r\nConnection: close\r\n$extra\r\n"
                        socket.getOutputStream().apply { write(head.toByteArray(Charsets.US_ASCII)); write(reply.bytes); flush() }
                    }
                }
            }
        } catch (error: IOException) { if (isActive) throw error }
    }
    fun url(path: String) = "http://127.0.0.1:${server.localPort}$path"
    suspend fun close() { listener.cancel(); server.close(); listener.join() }
}