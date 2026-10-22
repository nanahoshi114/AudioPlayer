package com.nanahoshi.audioplayer.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

fun isOnlineWhisper(base: String?): Boolean {
    if (base.isNullOrBlank()) return false
    val path = base.toHttpUrlOrNull()?.encodedPath?.trimEnd('/').orEmpty()
    return path.endsWith("/v1")
}

class WhisperException(message: String) : Exception(message)

data class WhisperCue(val startMs: Long, val endMs: Long, val text: String)

data class WhisperCreated(val id: String, val status: String)

data class WhisperProgress(val status: String, val progress: Float, val error: String?)

sealed class WhisperCues {
    data class Ready(val cues: List<WhisperCue>) : WhisperCues()
    data object NotReady : WhisperCues()
}

class WhisperClient(
    private val credentials: CredentialStore,
    private val calls: InflightCalls,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.MINUTES)
        .build()
    private val onlineHttp = http.newBuilder()
        .readTimeout(10, TimeUnit.MINUTES)
        .build()

    fun create(
        file: File,
        fingerprint: String,
        trackId: Long?,
        base: String? = null,
        ticket: InflightCalls.Ticket,
    ): WhisperCreated {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", uploadName(file), file.asRequestBody(AUDIO.toMediaType()))
            .addFormDataPart("fingerprint", fingerprint)
            .addFormDataPart("language", "ja")
            .apply {
                if (trackId != null && trackId > 0L) addFormDataPart("trackId", trackId.toString())
            }
            .build()
        val json = post(endpoint(base, "jobs"), body, ticket)
        val id = json.optString("id")
        if (id.isBlank()) throw WhisperException("生成失败")
        return WhisperCreated(id, json.optString("status"))
    }

    fun transcribeOnline(file: File, base: String, ticket: InflightCalls.Ticket): List<WhisperCue> {
        if (file.length() > ONLINE_LIMIT) throw WhisperException("这首太大，线上模型接不了")
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", uploadName(file), file.asRequestBody(AUDIO.toMediaType()))
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("language", "ja")
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("timestamp_granularities[]", "segment")
            .build()
        val json = execute(onlineHttp, Request.Builder().url(endpoint(base, "audio", "transcriptions")).post(body), ticket) { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw WhisperException(readError(text, "生成失败"))
            parseObject(text)
        }
        return parseOnlineSegments(json)
    }

    fun job(id: String, base: String? = null, ticket: InflightCalls.Ticket): WhisperProgress {
        val json = get(endpoint(base, "jobs", id), ticket)
        val error = json.optString("error").takeIf { it.isNotBlank() }
        return WhisperProgress(
            status = json.optString("status"),
            progress = json.optDouble("progress", 0.0).toFloat(),
            error = error,
        )
    }

    fun cues(id: String, base: String? = null, ticket: InflightCalls.Ticket): WhisperCues =
        execute(http, Request.Builder().url(endpoint(base, "jobs", id, "cues")).get(), ticket) { response ->
        val text = response.body?.string().orEmpty()
        if (response.code == 409) return@execute WhisperCues.NotReady
        if (!response.isSuccessful) throw WhisperException(readError(text, "生成失败"))
        val array = try {
            JSONObject(text).optJSONArray("cues")
        } catch (_: Exception) {
            null
        } ?: throw WhisperException("生成失败")
        val cues = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val line = item.optString("text").trim()
                if (line.isEmpty()) continue
                val start = item.optLong("startMs", -1L)
                val end = item.optLong("endMs", -1L)
                if (start < 0L || end <= start) continue
                add(WhisperCue(start, end, line))
            }
        }
        WhisperCues.Ready(cues.sortedBy { it.startMs })
    }

    private fun post(url: HttpUrl, body: RequestBody, ticket: InflightCalls.Ticket): JSONObject =
        execute(http, Request.Builder().url(url).post(body), ticket) { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw WhisperException(readError(text, "生成失败"))
            parseObject(text)
        }

    private fun get(url: HttpUrl, ticket: InflightCalls.Ticket): JSONObject =
        execute(http, Request.Builder().url(url).get(), ticket) { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw WhisperException(readError(text, "生成失败"))
            parseObject(text)
        }

    private fun <T> execute(
        client: OkHttpClient,
        builder: Request.Builder,
        ticket: InflightCalls.Ticket,
        read: (Response) -> T,
    ): T {
        val token = credentials.whisperToken() ?: throw WhisperException("没有听写口令")
        val request = builder.header("Authorization", "Bearer $token").build()
        val call = client.newCall(request)
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

    private fun endpoint(base: String?, vararg parts: String): HttpUrl {
        val raw = base ?: credentials.whisperBaseUrl() ?: throw WhisperException("没有听写地址")
        val root = raw.toHttpUrlOrNull() ?: throw WhisperException("听写地址不正确")
        return root.newBuilder().apply {
            parts.forEach { addPathSegment(it) }
        }.build()
    }

    private fun parseOnlineSegments(json: JSONObject): List<WhisperCue> {
        val array = json.optJSONArray("segments") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val line = item.optString("text").trim()
                if (line.isEmpty()) continue
                val start = (item.optDouble("start", -1.0) * 1000).toLong()
                var end = (item.optDouble("end", -1.0) * 1000).toLong()
                if (start < 0L || end < 0L) continue
                if (end <= start) end = start + 1
                add(WhisperCue(start, end, line))
            }
        }.sortedBy { it.startMs }
    }

    private fun parseObject(text: String): JSONObject = try {
        JSONObject(text)
    } catch (_: Exception) {
        throw WhisperException("生成失败")
    }

    private fun readError(text: String, fallback: String): String = try {
        when (val error = JSONObject(text).opt("error")) {
            is String -> error.takeIf { it.isNotBlank() } ?: fallback
            is JSONObject -> error.optString("message").takeIf { it.isNotBlank() } ?: fallback
            else -> fallback
        }
    } catch (_: Exception) {
        fallback
    }

    private fun uploadName(file: File): String {
        val header = file.inputStream().use { input ->
            val buffer = ByteArray(16)
            val count = input.read(buffer)
            if (count <= 0) ByteArray(0) else buffer.copyOf(count)
        }
        return audioUploadName(file.name, header)
    }

    private companion object {
        const val AUDIO = "application/octet-stream"
        const val ONLINE_LIMIT = 25L * 1024L * 1024L
    }
}

internal fun audioUploadName(fileName: String, header: ByteArray): String {
    val cleaned = fileName.replace(Regex("""[\r\n"]"""), "_").ifBlank { "audio" }
    val current = cleaned.substringAfterLast('.', "").lowercase()
    if ('.' in cleaned && current in AUDIO_EXTENSIONS) {
        val base = cleaned.substringBeforeLast('.')
        return "$base.$current"
    }
    val sniffed = sniffAudioExtension(header) ?: return cleaned
    return "${cleaned.substringBeforeLast('.').ifBlank { cleaned }}.$sniffed"
}

internal fun sniffAudioExtension(header: ByteArray): String? {
    if (header.size >= 3 && header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()) {
        return "mp3"
    }
    if (header.size >= 2 && header[0] == 0xFF.toByte()) {
        val second = header[1].toInt() and 0xFF
        if (second and 0xE0 == 0xE0 && second and 0x06 == 0x02) return "mp3"
        if (second and 0xF6 == 0xF0) return "aac"
    }
    if (startsWith(header, "fLaC")) return "flac"
    if (startsWith(header, "OggS")) return "ogg"
    if (startsWith(header, "RIFF") && header.size >= 12 && header.copyOfRange(8, 12).decodeToString() == "WAVE") return "wav"
    if (header.size >= 8 && header.copyOfRange(4, 8).decodeToString() == "ftyp") return "m4a"
    return null
}

private fun startsWith(header: ByteArray, text: String): Boolean {
    val bytes = text.encodeToByteArray()
    return header.size >= bytes.size && header.copyOfRange(0, bytes.size).contentEquals(bytes)
}

private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "ogg", "flac")

