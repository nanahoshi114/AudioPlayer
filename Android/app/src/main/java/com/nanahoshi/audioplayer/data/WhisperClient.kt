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
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.UserFacingError
import java.util.concurrent.TimeUnit

class WhisperException(
    override val userText: TextRef,
    cause: Throwable? = null,
) : Exception(userText.debugString(), cause), UserFacingError {
    constructor(message: String) : this(TextRef.Raw(message))
}

data class WhisperCue(val startMs: Long, val endMs: Long, val text: String)

class WhisperClient(
    private val credentials: CredentialStore,
    private val calls: InflightCalls,
) {
    private val http = OkHttpClient.Builder()
        .eventListenerFactory(ProgressNetworkEvents.factory)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.MINUTES)
        .build()
    private val onlineHttp = http.newBuilder()
        .readTimeout(10, TimeUnit.MINUTES)
        .build()

    fun transcribeQwen3(
        file: File,
        base: String,
        durationMs: Long,
        ticket: InflightCalls.Ticket,
        credentialModel: String? = null,
    ): List<WhisperCue> {
        ticket.progress?.phase(ProgressPhase.MODEL_INFO)
        val model = resolveQwen3Model(listedModelIds(base, ticket, credentialModel))
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", uploadName(file), file.asRequestBody(AUDIO.toMediaType()).withUploadProgress(ticket.progress, ProgressPhase.WAITING_TRANSCRIPT))
            .addFormDataPart("model", model)
            .addFormDataPart("language", "ja")
            .addFormDataPart("response_format", "json")
            .build()
        val json = execute(
            onlineHttp,
            Request.Builder().url(endpoint(base, credentialModel, "audio", "transcriptions")).post(body),
            ticket,
            credentialModel,
        ) { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw WhisperException(readError(text, TextRef.Res(R.string.whisper_error_failed)))
            parseObject(text)
        }
        return cuesFromQwen3(json, durationMs)
    }

    private fun <T> execute(
        client: OkHttpClient,
        builder: Request.Builder,
        ticket: InflightCalls.Ticket,
        credentialModel: String? = null,
        read: (Response) -> T,
    ): T {
        val token = credentials.whisperToken(credentialModel)
            ?: throw WhisperException(TextRef.Res(R.string.whisper_error_missing_key))
        val request = builder.header("Authorization", "Bearer $token").taskProgress(ticket.progress, false).build()
        val call = client.newCall(request)
        if (!calls.begin(ticket, call)) throw CancellationException()
        try {
            return call.execute().use(read)
        } catch (error: WhisperException) {
            throw error
        } catch (error: IOException) {
            if (call.isCanceled()) throw CancellationException()
            throw WhisperException(TextRef.Res(R.string.whisper_error_connection))
        } finally {
            calls.end(ticket, call)
        }
    }

    private fun endpoint(base: String?, credentialModel: String?, vararg parts: String): HttpUrl {
        val raw = base ?: credentials.whisperBaseUrl(credentialModel)
            ?: throw WhisperException(TextRef.Res(R.string.whisper_error_missing_base))
        val root = raw.toHttpUrlOrNull()
            ?: throw WhisperException(TextRef.Res(R.string.whisper_error_invalid_base))
        return root.newBuilder().apply {
            parts.forEach { addPathSegment(it) }
        }.build()
    }

    private fun listedModelIds(
        base: String,
        ticket: InflightCalls.Ticket,
        credentialModel: String?,
    ): List<String> = try {
        execute(onlineHttp, Request.Builder().url(endpoint(base, credentialModel, "models")).get(), ticket, credentialModel) { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) return@execute emptyList()
            val array = try {
                JSONObject(text).optJSONArray("data")
            } catch (_: Exception) {
                null
            } ?: return@execute emptyList()
            buildList {
                for (index in 0 until array.length()) {
                    val id = array.optJSONObject(index)?.optString("id").orEmpty()
                    if (id.isNotBlank()) add(id)
                }
            }
        }
    } catch (error: WhisperException) {
        when (error.userText) {
            TextRef.Res(R.string.whisper_error_connection),
            TextRef.Res(R.string.whisper_error_missing_key),
            TextRef.Res(R.string.whisper_error_invalid_base),
            TextRef.Res(R.string.whisper_error_missing_base) -> throw error
            else -> emptyList()
        }
    }

    private fun cuesFromQwen3(json: JSONObject, durationMs: Long): List<WhisperCue> {
        val segments = parseOnlineSegments(json)
        if (segments.isNotEmpty()) return segments
        val words = parseWordPieces(json)
        if (words.isNotEmpty()) return groupTimedPieces(words)
        return cueFromWholeTranscript(json.optString("text"), durationMs)
    }

    private fun parseWordPieces(json: JSONObject): List<WhisperCue> {
        val array = json.optJSONArray("words") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val line = item.optString("word").ifBlank { item.optString("text") }
                if (line.isEmpty()) continue
                val start = (item.optDouble("start", -1.0) * 1000).toLong()
                var end = (item.optDouble("end", -1.0) * 1000).toLong()
                if (start < 0L || end < 0L) continue
                if (end <= start) end = start + 1
                add(WhisperCue(start, end, line))
            }
        }
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
        throw WhisperException(TextRef.Res(R.string.whisper_error_failed))
    }

    private fun readError(text: String, fallback: TextRef): TextRef = try {
        when (val error = JSONObject(text).opt("error")) {
            is String -> error.takeIf { it.isNotBlank() }?.let(TextRef::Raw) ?: fallback
            is JSONObject -> error.optString("message").takeIf { it.isNotBlank() }?.let(TextRef::Raw) ?: fallback
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
    }
}

internal fun audioContainerFormat(fileName: String, header: ByteArray): String {
    val named = audioUploadName(fileName, header)
    val ext = named.substringAfterLast('.', "").lowercase()
    return if (ext in AUDIO_EXTENSIONS) ext else "mp3"
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

