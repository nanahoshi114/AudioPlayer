package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.userTextOr
import com.nanahoshi.audioplayer.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal enum class ModelDownloadSource(val label: String) {
    GitHub("GitHub"),
    HuggingFace("Hugging Face"),
}

internal enum class OfflineWhisperModel(
    val id: String,
    val directory: String,
    val version: String,
    private val releaseName: String,
    private val huggingFaceRepo: String,
    private val revision: String,
    private val archiveBytes: Long,
    val archiveSha256: String,
    val modelSha256: String,
    val modelBytes: Long,
    val tokenBytes: Long,
    val tokensSha256: String,
) {
    SenseVoice(
        SENSE_VOICE_MODEL, "sense-voice", "2024-07-17",
        "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17",
        "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17",
        "2365baeacb507f821a0c8120fcee3d484dba7a07", 163_002_883L,
        "7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e",
        "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51",
        239_233_841L, 315_894L,
        "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc",
    ),
    Parakeet(
        PARAKEET_MODEL, "parakeet-tdt-ctc-0.6b-ja", "35000-int8",
        "sherpa-onnx-nemo-parakeet-tdt_ctc-0.6b-ja-35000-int8",
        "sherpa-onnx-nemo-parakeet-tdt_ctc-0.6b-ja-35000-int8",
        "bef18eb066808c90bd0f5df5be685767b0732de8", 489_389_564L,
        "4b0a800ef29f4f4c8667339bf6f60d5bfdc2852ddc9dc5741aea65b6f8d1306b",
        "3addd00ef5bd1742078389e540b77394e4a508bdf2f4c9ad1b4a76d93e76598e",
        655_542_604L, 28_557L,
        "732f64c53909f2620c713f4106b487d92e6f54a6915b3cd3d1dbd32f9f4f392a",
    );

    val archiveUrl get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$releaseName.tar.bz2"
    fun fileUrl(name: String) = "https://huggingface.co/csukuangfj/$huggingFaceRepo/resolve/$revision/$name"
    val installedBytes get() = modelBytes + tokenBytes
    val installedMiB get() = toMiB(installedBytes)
    fun downloadMiB(source: ModelDownloadSource) = toMiB(downloadBytes(source))
    fun downloadBytes(source: ModelDownloadSource) = when (source) {
        ModelDownloadSource.GitHub -> archiveBytes
        ModelDownloadSource.HuggingFace -> installedBytes
    }

    private fun toMiB(bytes: Long) = (bytes + 512 * 1024) / (1024 * 1024)

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id }
    }
}

internal sealed interface OfflineWhisperModelState {
    data object NotDownloaded : OfflineWhisperModelState
    data class Downloading(val source: ModelDownloadSource, val bytesDone: Long, val bytesTotal: Long) : OfflineWhisperModelState
    data object Ready : OfflineWhisperModelState
    data class Failed(val message: TextRef) : OfflineWhisperModelState
}

internal class OfflineWhisperModelManager(
    filesDir: File,
    val model: OfflineWhisperModel,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followSslRedirects(false)
        .build(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val calls = InflightCalls()
    private val directory = File(filesDir, model.directory)
    private val staging = File(filesDir, "${model.directory}-tmp")
    private val _state = MutableStateFlow<OfflineWhisperModelState>(installedState())
    val state = _state.asStateFlow()

    private var attempt: Download? = null
    private var inUse = false
    private val cleanup = scope.launch { staging.deleteRecursively() }

    private class Download(val source: ModelDownloadSource, val ticket: InflightCalls.Ticket) {
        lateinit var job: Job
        var interruption: TextRef? = null
        @Volatile var cancelled = false

        fun checkActive() {
            if (cancelled) throw CancellationException()
        }
    }

    fun isReady(): Boolean = runCatching {
        val weights = File(directory, MODEL_FILE)
        val tokens = File(directory, TOKENS_FILE)
        weights.isFile && weights.length() == model.modelBytes &&
            tokens.isFile && tokens.length() == model.tokenBytes &&
            File(directory, VERSION_FILE).readText().trim() == model.version
    }.getOrDefault(false)

    suspend fun <T> useModel(block: suspend (modelFile: File, tokensFile: File) -> T): T {
        synchronized(lock) {
            check(!inUse)
            if (!isReady()) throw WhisperException(missingOfflineWhisperModelMessage(model.id))
            inUse = true
        }
        try {
            return block(File(directory, MODEL_FILE), File(directory, TOKENS_FILE))
        } finally {
            synchronized(lock) { inUse = false }
        }
    }

    fun download(source: ModelDownloadSource): Job? = synchronized(lock) {
        if (attempt != null || isReady()) return@synchronized null
        val current = Download(source, calls.prepare(model.id))
        attempt = current
        report(current, 0L, model.downloadBytes(source))
        current.job = scope.launch {
            cleanup.join()
            runDownload(current)
        }
        current.job
    }

    fun cancelDownload() = synchronized(lock) {
        attempt?.let { cancelLocked(it, interruption = null) }
    }

    fun cancelDownload(job: Job, interruption: TextRef? = null) = synchronized(lock) {
        val current = attempt?.takeIf { it.job === job } ?: return@synchronized
        cancelLocked(current, interruption)
    }

    private fun cancelLocked(current: Download, interruption: TextRef?) {
        if (current.cancelled) return
        current.interruption = interruption
        current.cancelled = true
        calls.cancel(model.id)
    }

    /** 调用者在同一把任务锁内检查排队任务与删除。 */
    fun deleteModel(): Boolean = synchronized(lock) {
        if (inUse || attempt != null) return@synchronized false
        val marker = File(directory, VERSION_FILE)
        val removed = (!marker.exists() || marker.delete()) && directory.deleteRecursively()
        _state.value = installedState()
        if (!removed) throw WhisperException(TextRef.Res(R.string.settings_model_error_delete))
        true
    }

    private fun installedState() = if (isReady()) OfflineWhisperModelState.Ready else OfflineWhisperModelState.NotDownloaded

    private fun runDownload(current: Download) {
        var failure: TextRef? = null
        try {
            current.checkActive()
            if (!staging.deleteRecursively() || !staging.mkdirs()) throw WhisperException(TextRef.Res(R.string.settings_model_error_staging))
            if (staging.usableSpace in 1 until model.installedBytes) throw WhisperException(TextRef.Res(R.string.settings_model_error_space))

            when (current.source) {
                ModelDownloadSource.GitHub -> downloadArchive(current)
                ModelDownloadSource.HuggingFace -> downloadFiles(current)
            }
            File(staging, VERSION_FILE).writeText(model.version)
            synchronized(lock) {
                current.checkActive()
                if (!directory.deleteRecursively() || !staging.renameTo(directory)) {
                    throw WhisperException(TextRef.Res(R.string.settings_model_error_save))
                }
            }
        } catch (_: CancellationException) {
            // 取消后的状态在清理完暂存后发布。
        } catch (error: Exception) {
            failure = error.userTextOr(R.string.settings_model_error_download)
        } finally {
            staging.deleteRecursively()
            synchronized(lock) {
                attempt = null
                val error = if (current.cancelled) current.interruption else failure
                _state.value = when {
                    isReady() -> OfflineWhisperModelState.Ready
                    error != null -> OfflineWhisperModelState.Failed(error)
                    else -> OfflineWhisperModelState.NotDownloaded
                }
            }
        }
    }

    private fun downloadArchive(current: Download) {
        fetch(current, model.archiveUrl) { body, contentLength ->
            val digest = MessageDigest.getInstance("SHA-256")
            val total = model.downloadBytes(current.source)
            if (contentLength >= 0 && contentLength != total) invalidFiles()
            val counted = CountingInputStream(DigestInputStream(body.buffered(), digest)) { done ->
                report(current, done, total)
            }
            val sizes = mapOf(MODEL_FILE to model.modelBytes, TOKENS_FILE to model.tokenBytes)
            val extracted = mutableSetOf<String>()
            val uncompressed = BZip2CompressorInputStream(counted)
            TarArchiveInputStream(uncompressed).use { tar ->
                while (true) {
                    current.checkActive()
                    val entry = tar.nextEntry ?: break
                    val name = entry.name.substringAfterLast('/')
                    val expected = sizes[name] ?: continue
                    if (!entry.isFile || entry.isSymbolicLink || entry.isLink ||
                        entry.size != expected || !extracted.add(name)
                    ) invalidFiles()
                    copyFile(current, tar, File(staging, name), expected)
                }
                drain(current, uncompressed)
                drain(current, counted)
            }
            if (extracted != sizes.keys || !digest.matches(model.archiveSha256)) invalidFiles()
        }
    }

    private fun downloadFiles(current: Download) {
        val files = listOf(
            Triple(MODEL_FILE, model.modelBytes, model.modelSha256),
            Triple(TOKENS_FILE, model.tokenBytes, model.tokensSha256),
        )
        val total = model.downloadBytes(current.source)
        var completed = 0L
        for ((name, expectedBytes, sha256) in files) {
            fetch(current, model.fileUrl(name)) { body, length ->
                if (length >= 0L && length != expectedBytes) invalidFiles()
                val digest = MessageDigest.getInstance("SHA-256")
                val counted = CountingInputStream(DigestInputStream(body, digest)) { done ->
                    report(current, completed + done, total)
                }
                val file = File(staging, name)
                copyFile(current, counted, file, expectedBytes)
                if (!digest.matches(sha256)) invalidFiles()
                completed += file.length()
            }
        }
    }

    private fun fetch(current: Download, url: String, consume: (InputStream, Long) -> Unit) {
        current.checkActive()
        val call = http.newCall(Request.Builder().url(url).header("Accept-Encoding", "identity").build())
        if (!calls.begin(current.ticket, call)) throw CancellationException()
        try {
            call.execute().use { response ->
                if (response.code != 200) throw WhisperException("HTTP ${response.code}")
                val body = response.body ?: invalidFiles()
                consume(body.byteStream(), body.contentLength())
            }
        } finally {
            calls.end(current.ticket, call)
        }
    }

    private fun copyFile(current: Download, input: InputStream, file: File, expectedBytes: Long) {
        file.outputStream().buffered().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var bytes = 0L
            while (true) {
                current.checkActive()
                val count = input.read(buffer)
                if (count < 0) break
                bytes += count
                if (bytes > expectedBytes) invalidFiles()
                output.write(buffer, 0, count)
            }
            if (bytes != expectedBytes) invalidFiles()
        }
    }

    private fun drain(current: Download, input: InputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (input.read(buffer) >= 0) current.checkActive()
    }

    private fun report(current: Download, done: Long, total: Long) {
        _state.value = OfflineWhisperModelState.Downloading(current.source, done, total)
    }

    private fun invalidFiles(): Nothing = throw WhisperException(TextRef.Res(R.string.settings_model_error_invalid))

    private fun MessageDigest.matches(expected: String) =
        digest().joinToString("") { "%02x".format(it) } == expected

    private class CountingInputStream(
        private val source: InputStream,
        private val onBytes: (Long) -> Unit,
    ) : InputStream() {
        private var done = 0L
        private var lastReport = 0L

        override fun read(): Int = source.read().also { if (it >= 0) count(1L) }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            source.read(buffer, offset, length).also { if (it > 0) count(it.toLong()) }

        override fun close() = source.close()

        private fun count(bytes: Long) {
            done += bytes
            val now = System.nanoTime()
            if (now - lastReport >= 250_000_000L) {
                lastReport = now
                onBytes(done)
            }
        }
    }

    private companion object {
        const val MODEL_FILE = "model.int8.onnx"
        const val TOKENS_FILE = "tokens.txt"
        const val VERSION_FILE = "version.txt"
    }
}
