package com.nanahoshi.audioplayer.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

internal object SenseVoiceModelSpecs {
    // 2025-09-09 的包其实是 WSYue-ASR 粤语模型（只出汉字、识别成粤语），不要换回去。
    const val DOWNLOAD_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2"
    const val DOWNLOAD_BYTES = 163_002_883L
    const val MODEL_VERSION = "2024-07-17"
    const val MODEL_FILE = "model.int8.onnx"
    const val TOKENS_FILE = "tokens.txt"
    const val VERSION_FILE = "version.txt"
    const val MIN_MODEL_BYTES = 100L * 1024L * 1024L
}

sealed class SenseVoiceModelState {
    data object NotDownloaded : SenseVoiceModelState()
    data class Downloading(val bytesDone: Long, val bytesTotal: Long) : SenseVoiceModelState()
    data object Ready : SenseVoiceModelState()
    data class Failed(val message: String) : SenseVoiceModelState()
}

/** SenseVoiceSmall 的模型文件下载和保管：下载 tar.bz2 时边下边解，不落压缩包。 */
class SenseVoiceModelManager(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow<SenseVoiceModelState>(initialState())
    val state: StateFlow<SenseVoiceModelState> = _state

    private var downloadJob: Job? = null
    private var currentCall: okhttp3.Call? = null

    init {
        // 上次中断留下的半成品直接清掉；旧版本下载的模型（没有版本文件或版本不符）也删掉重下。
        scope.launch {
            tmpDir().deleteRecursively()
            if (modelDir().isDirectory && !isReady()) modelDir().deleteRecursively()
        }
    }

    /** 版本文件是目录里最后写入的，缺它就说明不是当前版本的完整模型。 */
    fun isReady(): Boolean =
        modelFile().isFile &&
            tokensFile().isFile &&
            versionFile().isFile &&
            versionFile().readText().trim() == SenseVoiceModelSpecs.MODEL_VERSION

    fun modelDir(): File = File(appContext.filesDir, "sense-voice")

    fun modelFile(): File = File(modelDir(), SenseVoiceModelSpecs.MODEL_FILE)

    fun tokensFile(): File = File(modelDir(), SenseVoiceModelSpecs.TOKENS_FILE)

    private fun versionFile(): File = File(modelDir(), SenseVoiceModelSpecs.VERSION_FILE)

    fun download() {
        if (downloadJob?.isActive == true) return
        if (isReady()) {
            _state.value = SenseVoiceModelState.Ready
            return
        }
        downloadJob = scope.launch { runDownload() }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        currentCall?.cancel()
    }

    fun deleteModel() {
        cancelDownload()
        scope.launch {
            modelDir().deleteRecursively()
            tmpDir().deleteRecursively()
            _state.value = SenseVoiceModelState.NotDownloaded
        }
    }

    private fun initialState(): SenseVoiceModelState =
        if (isReady()) SenseVoiceModelState.Ready else SenseVoiceModelState.NotDownloaded

    private fun tmpDir(): File = File(appContext.filesDir, "sense-voice-tmp")

    private suspend fun runDownload() {
        _state.value = SenseVoiceModelState.Downloading(0L, SenseVoiceModelSpecs.DOWNLOAD_BYTES)
        tmpDir().deleteRecursively()
        tmpDir().mkdirs()
        try {
            withContext(Dispatchers.IO) { downloadAndExtract() }
            install()
            _state.value = SenseVoiceModelState.Ready
            tmpDir().deleteRecursively()
        } catch (error: kotlin.coroutines.cancellation.CancellationException) {
            throw error
        } catch (error: Exception) {
            tmpDir().deleteRecursively()
            val cancelled = error is IOException && (error.message == "Canceled" || error.message == "closed")
            _state.value = if (cancelled) {
                SenseVoiceModelState.NotDownloaded
            } else {
                SenseVoiceModelState.Failed(error.message ?: "下载失败")
            }
        }
    }

    private fun downloadAndExtract() {
        val request = Request.Builder().url(SenseVoiceModelSpecs.DOWNLOAD_URL).get().build()
        val call = http.newCall(request)
        currentCall = call
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw WhisperException("模型下载失败（HTTP ${response.code}）")
                val bytesTotal = response.header("Content-Length")?.toLongOrNull()
                    ?: SenseVoiceModelSpecs.DOWNLOAD_BYTES
                val counting = CountingInputStream(response.body!!.byteStream()) { done ->
                    _state.value = SenseVoiceModelState.Downloading(done, bytesTotal)
                }
                TarArchiveInputStream(BZip2CompressorInputStream(counting)).use { tar ->
                    while (true) {
                        val entry = tar.nextTarEntry ?: break
                        val name = entry.name.substringAfterLast('/')
                        if (entry.isDirectory || name !in WANTED) continue
                        File(tmpDir(), name).outputStream().use { out -> tar.copyTo(out) }
                    }
                }
            }
        } finally {
            currentCall = null
        }
    }

    private fun install() {
        val model = File(tmpDir(), SenseVoiceModelSpecs.MODEL_FILE)
        val tokens = File(tmpDir(), SenseVoiceModelSpecs.TOKENS_FILE)
        if (model.length() < SenseVoiceModelSpecs.MIN_MODEL_BYTES || tokens.length() <= 0L) {
            throw WhisperException("模型文件不完整，请重试")
        }
        modelDir().mkdirs()
        modelFile().delete()
        tokensFile().delete()
        versionFile().delete()
        if (!model.renameTo(modelFile()) || !tokens.renameTo(tokensFile())) {
            throw WhisperException("模型保存失败")
        }
        // 版本文件最后写：写成功才算安装完成。
        versionFile().writeText(SenseVoiceModelSpecs.MODEL_VERSION)
    }

    private class CountingInputStream(
        private val source: InputStream,
        private val onBytes: (Long) -> Unit,
    ) : InputStream() {
        private var done = 0L
        private var lastReport = 0L

        override fun read(): Int {
            val value = source.read()
            if (value >= 0) count(1L)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = source.read(buffer, offset, length)
            if (count > 0) count(count.toLong())
            return count
        }

        override fun close() = source.close()

        private fun count(bytes: Long) {
            done += bytes
            val now = System.currentTimeMillis()
            if (now - lastReport >= 250L) {
                lastReport = now
                onBytes(done)
            }
        }
    }

    private companion object {
        val WANTED = setOf(SenseVoiceModelSpecs.MODEL_FILE, SenseVoiceModelSpecs.TOKENS_FILE)
    }
}
