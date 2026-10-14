package com.nanahoshi.audioplayer.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.concurrent.TimeUnit

fun parseTranslationReply(raw: String, count: Int): List<String>? {
    if (count <= 0) return emptyList()
    val cleaned = raw
        .replace(Regex("```[a-zA-Z]*"), "")
        .replace("```", "")
    val lines = mutableMapOf<Int, String>()
    val pattern = Regex("""^\s*(\d+)\s*[|｜]\s*(.*)$""")
    for (line in cleaned.lines()) {
        val match = pattern.matchEntire(line.trim()) ?: continue
        val number = match.groupValues[1].toIntOrNull() ?: continue
        val text = match.groupValues[2].trim()
        if (number in 1..count && text.isNotEmpty()) lines[number] = text
    }
    if ((1..count).any { it !in lines }) return null
    return (1..count).map { lines.getValue(it) }
}

class TranslateReply(val lines: List<String>? = null, val raw: String? = null)

class TranslateClient(
    private val credentials: CredentialStore,
    private val calls: InflightCalls,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.MINUTES)
        .writeTimeout(1, TimeUnit.MINUTES)
        .build()

    fun translate(lines: List<String>, ticket: InflightCalls.Ticket): TranslateReply {
        if (lines.isEmpty()) return TranslateReply(lines = emptyList())
        val base = credentials.translateBaseUrl() ?: return TranslateReply(raw = "没有翻译地址")
        val model = credentials.translateModel() ?: return TranslateReply(raw = "没有模型名")
        val token = credentials.translateToken() ?: return TranslateReply(raw = "没有翻译口令")
        val url = base.toHttpUrlOrNull()?.newBuilder()?.addPathSegment("chat")?.addPathSegment("completions")?.build()
            ?: return TranslateReply(raw = "翻译地址不正确")
        val count = lines.size
        val rules = """
            把日语字幕译成简体中文。只输出译文本身。
            必须正好输出 $count 行，不能多，不能少，不能空行。
            第 i 行只能是 i|译文。编号从 1 连续排到 $count。
            竖线必须是半角字符 |。
            不要输出思考过程、解释、标题、Markdown 或引号。
            某一句很难译时，仍然写出该编号，并把意思译成简体中文。
        """.trimIndent()
        val source = buildString {
            append("按上面的格式翻译下面 $count 句。\n")
            lines.forEachIndexed { index, line ->
                val spoken = line.replace('\n', ' ').replace('\r', ' ').replace("|", " ").replace("｜", " ")
                append(index + 1)
                append('|')
                append(spoken.trim())
                append('\n')
            }
        }
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0)
            .put("enable_thinking", false)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", rules))
                    .put(JSONObject().put("role", "user").put("content", source)),
            )
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON.toMediaType()))
            .build()
        val call = http.newCall(request)
        if (!calls.begin(ticket, call)) throw CancellationException()
        val text = try {
            call.execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (!response.isSuccessful) return TranslateReply(raw = clip("HTTP ${response.code}\n$payload"))
                payload
            }
        } catch (error: IOException) {
            if (call.isCanceled()) throw CancellationException()
            return TranslateReply(raw = error.message ?: "无法连接翻译服务")
        } finally {
            calls.end(ticket, call)
        }
        val content = messageContent(text)
        if (content.isNullOrBlank()) return TranslateReply(raw = clip(text))
        return TranslateReply(lines = parseTranslationReply(content, lines.size), raw = clip(content))
    }

    private fun messageContent(payload: String): String? = try {
        val message = JSONObject(payload)
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?: return null
        when (val content = message.opt("content")) {
            is String -> content
            is JSONArray -> buildString {
                for (index in 0 until content.length()) {
                    val part = content.optJSONObject(index)
                    append(part?.optString("text").orEmpty())
                }
            }.takeIf { it.isNotBlank() }
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    private fun clip(text: String): String = text.take(8_000)

    private companion object {
        val JSON = "application/json"
    }
}
