package com.nanahoshi.audioplayer.data

import android.content.Context
import com.alibaba.idst.nui.AsrResult
import com.alibaba.idst.nui.CommonUtils
import com.alibaba.idst.nui.Constants
import com.alibaba.idst.nui.INativeFileTransCallback
import com.alibaba.idst.nui.NativeNui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.minutes

internal class QwenFlashTranscriber(context: Context) {
    private val appContext = context.applicationContext

    suspend fun transcribe(file: File, base: String, token: String): List<WhisperCue> = try {
        withContext(Dispatchers.IO) {
            val socket = qwenFlashSocketUrl(base) ?: throw WhisperException("听写地址不正确")
            if (token.isBlank()) throw WhisperException("没有听写口令")
            if (!file.isFile) throw WhisperException("找不到音频文件")
            withTimeout(10.minutes) {
                transcribeOnSocket(file, socket, token)
            }
        }
    } catch (timeout: TimeoutCancellationException) {
        throw WhisperException("生成失败")
    }

    private suspend fun transcribeOnSocket(file: File, socket: String, token: String): List<WhisperCue> =
        suspendCancellableCoroutine { cont ->
                val nui = NativeNui.GetInstance()
                val released = AtomicBoolean(false)
                var taskId: String? = null
                fun finish(cancelTask: Boolean) {
                    if (!released.compareAndSet(false, true)) return
                    if (cancelTask) {
                        try {
                            taskId?.let { nui.cancelFileTranscriber(it) }
                        } catch (_: Exception) {
                        }
                    }
                    try {
                        nui.release()
                    } catch (_: Exception) {
                    }
                }
                cont.invokeOnCancellation { finish(cancelTask = true) }
                val callback = object : INativeFileTransCallback {
                    override fun onFileTransEventCallback(
                        event: Constants.NuiEvent,
                        resultCode: Int,
                        arg2: Int,
                        asrResult: AsrResult?,
                        id: String?,
                    ) {
                        if (!id.isNullOrBlank()) taskId = id
                        when (event) {
                            Constants.NuiEvent.EVENT_FILE_TRANS_RESULT -> {
                                if (!cont.isActive) return
                                try {
                                    val payload = asrResult?.asrResult?.takeIf { it.isNotBlank() }
                                        ?: asrResult?.allResponse.orEmpty()
                                    cont.resume(cuesFromQwenPayload(payload))
                                } catch (error: WhisperException) {
                                    cont.resumeWithException(error)
                                } catch (_: Exception) {
                                    cont.resumeWithException(WhisperException("生成失败"))
                                } finally {
                                    finish(cancelTask = false)
                                }
                            }
                            Constants.NuiEvent.EVENT_ASR_ERROR -> {
                                if (!cont.isActive) return
                                val message = if (resultCode == Constants.NuiResultCode.FILE_ACCESS_FAIL) {
                                    "找不到音频文件"
                                } else {
                                    qwenErrorMessage(asrResult?.allResponse ?: asrResult?.asrResult.orEmpty())
                                }
                                cont.resumeWithException(WhisperException(message))
                                finish(cancelTask = false)
                            }
                            else -> Unit
                        }
                    }
                }
                try {
                    CommonUtils.copyAssetsData(appContext)
                    var code = nui.initialize(
                        callback,
                        initParams(socket),
                        Constants.LogLevel.LOG_LEVEL_NONE,
                        false,
                    )
                    if (code == Constants.NuiResultCode.SDK_ALREADY_INIT) {
                        try {
                            nui.release()
                        } catch (_: Exception) {
                        }
                        code = nui.initialize(
                            callback,
                            initParams(socket),
                            Constants.LogLevel.LOG_LEVEL_NONE,
                            false,
                        )
                    }
                    if (code != Constants.NuiResultCode.SUCCESS) {
                        finish(cancelTask = false)
                        if (cont.isActive) cont.resumeWithException(WhisperException("生成失败"))
                        return@suspendCancellableCoroutine
                    }
                    if (!cont.isActive) {
                        finish(cancelTask = true)
                        return@suspendCancellableCoroutine
                    }
                    val started = nui.startFileTranscriber(startParams(file, token), null)
                    if (started != Constants.NuiResultCode.SUCCESS) {
                        finish(cancelTask = false)
                        if (cont.isActive) cont.resumeWithException(WhisperException("生成失败"))
                    }
                } catch (error: WhisperException) {
                    finish(cancelTask = false)
                    if (cont.isActive) cont.resumeWithException(error)
                } catch (_: Exception) {
                    finish(cancelTask = false)
                    if (cont.isActive) cont.resumeWithException(WhisperException("生成失败"))
                }
            }

    private fun initParams(socket: String): String = JSONObject()
        .put("url", socket)
        .put("device_id", "audioplayer")
        .put("service_mode", Constants.ModeFullCloud)
        .toString()

    private fun startParams(file: File, token: String): String {
        val header = file.inputStream().use { input ->
            val buffer = ByteArray(16)
            val count = input.read(buffer)
            if (count <= 0) ByteArray(0) else buffer.copyOf(count)
        }
        val format = audioContainerFormat(file.name, header)
        val nls = JSONObject()
            .put("model", QWEN_FLASH_MODEL)
            .put("format", format)
            .put("language_hints", JSONArray().put("ja"))
        val audio = JSONObject().put("data", file.absolutePath)
        val content = JSONArray().put(
            JSONObject()
                .put("type", "input_audio")
                .put("input_audio", audio),
        )
        val messages = JSONArray().put(
            JSONObject()
                .put("role", "user")
                .put("content", content),
        )
        return JSONObject()
            .put("apikey", token)
            .put("messages", messages)
            .put("nls_config", nls)
            .toString()
    }
}
