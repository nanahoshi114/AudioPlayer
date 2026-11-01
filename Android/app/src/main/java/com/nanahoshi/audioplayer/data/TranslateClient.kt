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
        if ('|' in text || '｜' in text) return null
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
    private val http = client(3)
    private val thinkingHttp = client(10)

    fun translate(lines: List<String>, ticket: InflightCalls.Ticket): TranslateReply {
        if (lines.isEmpty()) return TranslateReply(lines = emptyList())
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
                append(index + 1)
                append('|')
                append(plain(line))
                append('\n')
            }
        }
        return complete(rules, source, count, ticket)
    }

    fun polish(pairs: List<Pair<String, String>>, ticket: InflightCalls.Ticket): TranslateReply {
        if (pairs.isEmpty()) return TranslateReply(lines = emptyList())
        val count = pairs.size
        val rules = """
            把已经译成简体中文的字幕再润色一遍。只输出润色后的译文。
            必须正好输出 $count 行，不能多，不能少，不能空行。
            第 i 行只能是 i|润色后的译文。编号从 1 连续排到 $count。
            竖线必须是半角字符 |。
            语句要通顺，同一段里的用词保持一致。
            不要改编号，不要加解释、标题、Markdown 或引号。
            不要输出思考过程。
        """.trimIndent()
        val source = buildString {
            append("按上面的格式润色下面 $count 句。每句是「编号|日文|当前译文」。\n")
            pairs.forEachIndexed { index, (japanese, chinese) ->
                append(index + 1)
                append('|')
                append(plain(japanese))
                append('|')
                append(plain(chinese))
                append('\n')
            }
        }
        return complete(rules, source, count, ticket)
    }

    private fun complete(rules: String, source: String, count: Int, ticket: InflightCalls.Ticket): TranslateReply {
        val model = credentials.translateModel()
        val token = credentials.translateToken() ?: return TranslateReply(raw = "没有翻译口令")
        val url = credentials.translateBaseUrl().toHttpUrlOrNull()
            ?.newBuilder()?.addPathSegment("chat")?.addPathSegment("completions")?.build()
            ?: return TranslateReply(raw = "翻译地址不正确")
        val thinking = credentials.translateThinking()
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", rules))
                    .put(JSONObject().put("role", "user").put("content", source)),
            )
        putThinking(
            body,
            thinkingFields(
                credentials.translatePlatform(),
                thinking,
                credentials.translateEffort(),
            ),
        )
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON.toMediaType()))
            .build()
        val call = (if (thinking) thinkingHttp else http).newCall(request)
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
        return TranslateReply(lines = parseTranslationReply(content, count), raw = clip(content))
    }

    private fun client(readMinutes: Long): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(readMinutes, TimeUnit.MINUTES)
        .writeTimeout(1, TimeUnit.MINUTES)
        .build()

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

    private fun plain(text: String): String =
        text.replace('\n', ' ').replace('\r', ' ').replace("|", " ").replace("｜", " ").trim()

    private fun putThinking(body: JSONObject, fields: Map<String, Any>) {
        for ((key, value) in fields) {
            when (value) {
                is Map<*, *> -> {
                    val nested = JSONObject()
                    value.forEach { (child, childValue) -> nested.put(child.toString(), childValue) }
                    body.put(key, nested)
                }
                else -> body.put(key, value)
            }
        }
    }

    private companion object {
        val JSON = "application/json"
    }
}
