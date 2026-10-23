package com.nanahoshi.audioplayer.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class QwenFileTranscriber(
    private val credentials: CredentialStore,
    private val calls: InflightCalls,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.MINUTES)
        .build()

    suspend fun transcribe(file: File, base: String, ticket: InflightCalls.Ticket): List<WhisperCue> {
        if (file.length() > QWEN_TEMP_BYTES) throw WhisperException("这首超过 1GB，千问接不了")
        val origin = qwenHttpOrigin(base)?.toHttpUrlOrNull() ?: throw WhisperException("听写地址不正确")
        val policy = policy(origin, ticket)
        val key = upload(policy, file, ticket)
        val taskId = submit(origin, "oss://$key", ticket)
        val resultUrl = poll(origin, taskId, ticket)
        val payload = download(resultUrl, ticket)
        return cuesFromQwenPayload(payload)
    }

    private fun policy(origin: HttpUrl, ticket: InflightCalls.Ticket): JSONObject {
        val url = origin.newBuilder()
            .addPathSegment("api")
            .addPathSegment("v1")
            .addPathSegment("uploads")
            .addQueryParameter("action", "getPolicy")
            .addQueryParameter("model", QWEN_FILE_MODEL)
            .build()
        val json = get(url, ticket)
        return json.optJSONObject("data") ?: throw WhisperException("生成失败")
    }

    private fun upload(policy: JSONObject, file: File, ticket: InflightCalls.Ticket): String {
        val host = policy.optString("upload_host").toHttpUrlOrNull() ?: throw WhisperException("生成失败")
        val dir = policy.optString("upload_dir").trim().trim('/')
        if (dir.isEmpty()) throw WhisperException("生成失败")
        val header = file.inputStream().use { input ->
            val buffer = ByteArray(16)
            val count = input.read(buffer)
            if (count <= 0) ByteArray(0) else buffer.copyOf(count)
        }
        val name = audioUploadName(file.name, header).replace("/", "_")
        val key = "$dir/$name"
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("OSSAccessKeyId", policy.optString("oss_access_key_id"))
            .addFormDataPart("Signature", policy.optString("signature"))
            .addFormDataPart("policy", policy.optString("policy"))
            .addFormDataPart("x-oss-object-acl", policy.optString("x_oss_object_acl"))
            .addFormDataPart("x-oss-forbid-overwrite", policy.optString("x_oss_forbid_overwrite"))
            .addFormDataPart("key", key)
            .addFormDataPart("success_action_status", "200")
            .addFormDataPart("file", name, file.asRequestBody(AUDIO.toMediaType()))
            .build()
        execute(Request.Builder().url(host).post(body), ticket, authorize = false) { response ->
            if (!response.isSuccessful) throw WhisperException("生成失败")
        }
        return key
    }

    private fun submit(origin: HttpUrl, ossUrl: String, ticket: InflightCalls.Ticket): String {
        val url = origin.newBuilder()
            .addPathSegment("api")
            .addPathSegment("v1")
            .addPathSegment("services")
            .addPathSegment("audio")
            .addPathSegment("asr")
            .addPathSegment("transcription")
            .build()
        val payload = JSONObject()
            .put("model", QWEN_FILE_MODEL)
            .put("input", JSONObject().put("file_urls", JSONArray().put(ossUrl)))
            .put("parameters", JSONObject().put("language_hints", JSONArray().put("ja")))
            .toString()
        val json = execute(
            Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .header("X-DashScope-Async", "enable")
                .header("X-DashScope-OssResourceResolve", "enable")
                .post(payload.toRequestBody(JSON.toMediaType())),
            ticket,
            authorize = true,
        ) { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw WhisperException(qwenErrorMessage(text))
            parseObject(text)
        }
        val output = json.optJSONObject("output") ?: throw WhisperException(qwenErrorMessage(json.toString()))
        val id = output.optString("task_id")
        if (id.isBlank()) throw WhisperException("生成失败")
        return id
    }

    private suspend fun poll(origin: HttpUrl, taskId: String, ticket: InflightCalls.Ticket): String {
        val url = origin.newBuilder()
            .addPathSegment("api")
            .addPathSegment("v1")
            .addPathSegment("tasks")
            .addPathSegment(taskId)
            .build()
        while (true) {
            val json = get(url, ticket)
            val output = json.optJSONObject("output") ?: throw WhisperException(qwenErrorMessage(json.toString()))
            when (output.optString("task_status")) {
                "PENDING", "RUNNING", "UNKNOWN" -> delay(2_000)
                "SUCCEEDED" -> {
                    val result = output.optJSONArray("results")?.optJSONObject(0)
                        ?: throw WhisperException("生成失败")
                    if (result.optString("subtask_status") == "FAILED") {
                        throw WhisperException(result.optString("message").takeIf { it.isNotBlank() }?.take(80) ?: "生成失败")
                    }
                    return result.optString("transcription_url").takeIf { it.isNotBlank() }
                        ?: throw WhisperException("生成失败")
                }
                else -> {
                    val result = output.optJSONArray("results")?.optJSONObject(0)
                    val message = result?.optString("message").takeIf { !it.isNullOrBlank() }
                        ?: output.optString("message").takeIf { it.isNotBlank() }
                    throw WhisperException(message?.take(80) ?: "生成失败")
                }
            }
        }
    }

    private fun download(url: String, ticket: InflightCalls.Ticket): String {
        val parsed = url.toHttpUrlOrNull() ?: throw WhisperException("生成失败")
        return execute(Request.Builder().url(parsed).get(), ticket, authorize = false) { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw WhisperException("生成失败")
            text
        }
    }

    private fun get(url: HttpUrl, ticket: InflightCalls.Ticket): JSONObject =
        execute(Request.Builder().url(url).get(), ticket, authorize = true) { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw WhisperException(qwenErrorMessage(text))
            parseObject(text)
        }

    private fun <T> execute(
        builder: Request.Builder,
        ticket: InflightCalls.Ticket,
        authorize: Boolean,
        read: (Response) -> T,
    ): T {
        if (authorize) {
            val token = credentials.whisperToken() ?: throw WhisperException("没有听写口令")
            builder.header("Authorization", "Bearer $token")
        }
        val call = http.newCall(builder.build())
        if (!calls.begin(ticket, call)) throw CancellationException()
        try {
            return call.execute().use(read)
        } catch (error: WhisperException) {
            throw error
        } catch (error: IOException) {
            if (call.isCanceled()) throw CancellationException()
            throw WhisperException("无法连接听写服务")
        } finally {
            calls.end(ticket, call)
        }
    }

    private fun parseObject(text: String): JSONObject = try {
        JSONObject(text)
    } catch (_: Exception) {
        throw WhisperException("生成失败")
    }

    private companion object {
        const val AUDIO = "application/octet-stream"
        const val JSON = "application/json"
    }
}
