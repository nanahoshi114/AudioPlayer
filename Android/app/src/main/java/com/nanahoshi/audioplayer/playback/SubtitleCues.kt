package com.nanahoshi.audioplayer.playback

import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.UserFacingIOException
import com.nanahoshi.audioplayer.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.IOException

data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val translation: String? = null,
    val sourceCount: Int = 1,
)

fun SubtitleCue.line(chinese: Boolean): String {
    if (!chinese) return text
    val translated = translation?.trim().orEmpty()
    return translated.ifEmpty { text }
}

fun List<SubtitleCue>.hasTranslation(): Boolean = any { !it.translation.isNullOrBlank() }

fun List<SubtitleCue>.textAt(positionMs: Long): String =
    textAt(positionMs, chinese = false)

fun List<SubtitleCue>.textAt(positionMs: Long, chinese: Boolean): String =
    lastOrNull { positionMs in it.startMs until it.endMs }?.line(chinese).orEmpty()

fun List<SubtitleCue>.indexAt(positionMs: Long): Int =
    indexOfLast { positionMs in it.startMs until it.endMs }

fun parseGeneratedCues(raw: String): List<SubtitleCue> = parseGeneratedCuesOrNull(raw).orEmpty()

data class SubtitleOrigin(val model: String, val endpoint: String?)

enum class SubtitleLanguageDelete { CHINESE, ORIGINAL }

internal data class GeneratedSubtitles(
    val cues: List<SubtitleCue>,
    val origin: SubtitleOrigin?,
    val revisedCues: List<SubtitleCue>? = null,
    val sourceFingerprint: String? = null,
    val language: String? = null,
) {
    val displayCues: List<SubtitleCue> get() = revisedCues ?: cues
}

internal fun GeneratedSubtitles.withoutChinese(): GeneratedSubtitles? {
    if (!displayCues.hasTranslation() || cues.isEmpty()) return null
    return GeneratedSubtitles(
        cues = cues.map { it.copy(translation = null) },
        origin = origin,
        revisedCues = null,
        sourceFingerprint = null,
        language = language.takeUnless { it.equals("zh-Hans", ignoreCase = true) },
    )
}

internal fun GeneratedSubtitles.withoutOriginal(): GeneratedSubtitles? {
    if (!displayCues.hasTranslation()) return null
    val chinese = displayCues.mapNotNull { cue ->
        cue.translation?.trim()?.takeIf { it.isNotEmpty() }?.let { SubtitleCue(cue.startMs, cue.endMs, it) }
    }
    if (chinese.isEmpty()) return null
    return GeneratedSubtitles(
        cues = chinese,
        origin = origin,
        revisedCues = null,
        sourceFingerprint = null,
        language = "zh-Hans",
    )
}

/** 解析失败返回 null，用来区分「文件损坏」和「解析成功但没有句子」。 */
fun parseGeneratedCuesOrNull(raw: String): List<SubtitleCue>? = parseGeneratedSubtitles(raw)?.displayCues

internal fun parseGeneratedSubtitles(raw: String): GeneratedSubtitles? {
    val text = raw.trim().removePrefix("\uFEFF")
    if (text.isEmpty() || !text.startsWith("{")) return null
    return try {
        val document = Json.parseToJsonElement(text).jsonObject
        val array = document["cues"]?.jsonArray ?: return null
        val origin = if ("origin" in document) {
            val fields = document["origin"] as? JsonObject ?: return null
            val model = fields["model"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: return null
            val endpoint = when (val value = fields["endpoint"]) {
                JsonNull -> null
                else -> value?.jsonPrimitive?.takeIf { it.isString }?.content ?: return null
            }
            SubtitleOrigin(model, endpoint)
        } else {
            null
        }
        val original = array.mapNotNull { (it as? JsonObject)?.toGeneratedCue() }.sortedBy { it.startMs }
        val revised = runCatching {
            document["revisedCues"]?.jsonArray?.map { (it as? JsonObject)?.toGeneratedCue() }
                ?.takeIf { it.all { cue -> cue != null } && it.any { cue -> !cue?.translation.isNullOrBlank() } }
                ?.filterNotNull()?.sortedBy { it.startMs }
        }.getOrNull()
        val sourceFingerprint = if ("sourceFingerprint" in document) {
            val value = document["sourceFingerprint"]?.jsonPrimitive
            value?.takeIf { it.isString && it.content.matches(FINGERPRINT_PATTERN) }?.content ?: return null
        } else {
            null
        }
        val language = if ("language" in document) {
            when (val value = document["language"]) {
                JsonNull -> null
                else -> value?.jsonPrimitive?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
            }
        } else {
            null
        }
        GeneratedSubtitles(original, origin, revised, sourceFingerprint, language)
    } catch (_: Exception) {
        null
    }
}

private fun JsonObject.toGeneratedCue(): SubtitleCue? {
    val line = get("text")?.jsonPrimitive?.content?.trim().orEmpty()
    if (line.isEmpty()) return null
    val start = get("startMs")?.jsonPrimitive?.content?.toLongOrNull() ?: return null
    val end = get("endMs")?.jsonPrimitive?.content?.toLongOrNull() ?: return null
    if (end <= start) return null
    val translation = get("translation")?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
    val sourceCount = get("sourceCount")?.jsonPrimitive?.content?.toIntOrNull()?.takeIf { it > 1 } ?: 1
    return SubtitleCue(start, end, line, translation, sourceCount)
}

/** 先写同目录临时文件再替换，避免中断留下半个 JSON。 */
internal fun File.writeTextAtomically(text: String) = withStagedSubtitle(text) { replace -> replace() }

internal inline fun <T> File.withStagedSubtitle(text: String, commit: (() -> Unit) -> T): T {
    parentFile?.mkdirs()
    val temp = File.createTempFile(".subtitle-", ".tmp", parentFile)
    return try {
        temp.writeText(text)
        commit {
            if (!temp.renameTo(this)) throw UserFacingIOException(TextRef.Res(R.string.player_error_subtitle_save))
        }
    } finally {
        temp.delete()
    }
}

fun encodeGeneratedCues(cues: List<SubtitleCue>): String = encodeGeneratedCues(cues, null)

internal fun encodeGeneratedCues(
    cues: List<SubtitleCue>,
    origin: SubtitleOrigin?,
    revisedCues: List<SubtitleCue>? = null,
    sourceFingerprint: String? = null,
    language: String? = null,
): String = buildJsonObject {
    if (origin != null) {
        putJsonObject("origin") {
            put("model", origin.model)
            put("endpoint", origin.endpoint?.let { JsonPrimitive(it) } ?: JsonNull)
        }
    }
    if (language != null) put("language", language)
    if (sourceFingerprint != null) put("sourceFingerprint", sourceFingerprint)
    put("cues", cueArray(cues))
    if (revisedCues != null) put("revisedCues", cueArray(revisedCues))
}.toString()

private fun cueArray(cues: List<SubtitleCue>): JsonArray = buildJsonArray {
    cues.forEach { cue ->
        add(buildJsonObject {
            put("startMs", cue.startMs)
            put("endMs", cue.endMs)
            put("text", cue.text)
            if (cue.sourceCount > 1) put("sourceCount", cue.sourceCount)
            val translation = cue.translation?.trim()?.takeIf { it.isNotEmpty() }
            if (translation != null) put("translation", translation)
        })
    }
}

internal fun subtitleSourceFingerprint(cues: List<SubtitleCue>, includeTranslation: Boolean = false): String {
    val content = if (includeTranslation) cues else cues.map { it.copy(translation = null) }
    return com.nanahoshi.audioplayer.data.sha256Hex(encodeGeneratedCues(content))
}

internal fun applySubtitleOverlay(
    source: List<SubtitleCue>,
    overlay: GeneratedSubtitles,
    allowLegacyOverlay: Boolean = true,
): List<SubtitleCue> {
    if (source.isEmpty()) return source
    overlay.sourceFingerprint?.let { fingerprint ->
        return if (fingerprint == subtitleSourceFingerprint(source, includeTranslation = true) && overlay.displayCues.hasTranslation()) {
            overlay.displayCues
        } else source
    }
    if (!allowLegacyOverlay || source.hasTranslation()) return source
    val byStart = overlay.cues.associateBy { it.startMs }
    return source.map { cue ->
        val extra = byStart[cue.startMs]?.translation
        if (extra.isNullOrBlank()) cue else cue.copy(translation = extra)
    }
}

fun parseSubtitleDocument(raw: String): List<SubtitleCue> {
    val text = raw.trim().removePrefix("\uFEFF")
    if (text.isEmpty()) return emptyList()
    if (text.startsWith("{")) {
        parseBilibiliBody(text).takeIf { it.isNotEmpty() }?.let { return it }
    }
    if (text.startsWith("WEBVTT", ignoreCase = true) || text.contains("-->")) {
        parseTimedBlocks(text).takeIf { it.isNotEmpty() }?.let { return it }
    }
    return parseLrc(text)
}

fun pickSubtitleName(audioName: String, names: List<String>): String? {
    for (extension in subtitleExtensions) {
        names.firstOrNull { it.equals("$audioName.$extension", ignoreCase = true) }?.let { return it }
    }
    val stem = audioName.substringBeforeLast('.', missingDelimiterValue = audioName)
    if (stem.equals(audioName, ignoreCase = true)) return null
    for (extension in subtitleExtensions) {
        names.firstOrNull { it.equals("$stem.$extension", ignoreCase = true) }?.let { return it }
    }
    return null
}

private val subtitleExtensions = listOf("lrc", "vtt", "srt")
private val FINGERPRINT_PATTERN = Regex("[0-9a-f]{64}")
private val BILIBILI_BREAK = Regex("(?i)<br\\s*/?>")
private val HTML_TAG = Regex("<[^>]+>")
private val LRC_STAMP = Regex("""\[(\d+):(\d{1,2})(?:[.:](\d{1,3}))?]""")
private val LRC_WORD_STAMP = Regex("""<\d+:\d{1,2}(?:[.:]\d{1,3})?>""")
private val CLOCK_PATTERN = Regex("""^(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{1,3})$""")

private fun parseBilibiliBody(text: String): List<SubtitleCue> {
    val body = try {
        Json.parseToJsonElement(text).jsonObject["body"]?.jsonArray
    } catch (_: Exception) {
        null
    } ?: return emptyList()
    return body.mapNotNull { element ->
        val item = element.jsonObject
        val line = item["content"]?.jsonPrimitive?.content.orEmpty()
            .replace(BILIBILI_BREAK, "\n")
            .replace(HTML_TAG, "")
            .trim()
        if (line.isEmpty()) return@mapNotNull null
        val start = ((item["from"]?.jsonPrimitive?.double ?: 0.0) * 1000).toLong().coerceAtLeast(0L)
        val end = ((item["to"]?.jsonPrimitive?.double ?: 0.0) * 1000).toLong().coerceAtLeast(start)
        SubtitleCue(start, if (end == start) start + 1 else end, line)
    }.sortedBy { it.startMs }
}

private fun parseTimedBlocks(text: String): List<SubtitleCue> {
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').lines()
    val cues = mutableListOf<SubtitleCue>()
    var index = 0
    while (index < lines.size) {
        val line = lines[index].trim()
        if ("-->" !in line) {
            index += 1
            continue
        }
        val parts = line.split("-->")
        val start = parseClock(parts.getOrNull(0).orEmpty())
        val end = parseClock(parts.getOrNull(1).orEmpty())
        index += 1
        if (start == null || end == null) continue
        val body = mutableListOf<String>()
        while (index < lines.size && lines[index].isNotBlank()) {
            body += lines[index].trim()
            index += 1
        }
        val spoken = body.joinToString("\n").trim()
        if (spoken.isNotEmpty()) {
            cues += SubtitleCue(start, end.coerceAtLeast(start + 1), spoken)
        }
    }
    return cues.sortedBy { it.startMs }
}

private fun parseLrc(text: String): List<SubtitleCue> {
    val stamped = mutableListOf<Pair<Long, String>>()
    for (line in text.replace("\r\n", "\n").lines()) {
        val marks = LRC_STAMP.findAll(line).toList()
        if (marks.isEmpty()) continue
        val spoken = LRC_WORD_STAMP.replace(line.substring(marks.last().range.last + 1), "").trim()
        if (spoken.isEmpty()) continue
        for (mark in marks) {
            val minute = mark.groupValues[1].toLongOrNull() ?: continue
            val second = mark.groupValues[2].toLongOrNull() ?: continue
            val fraction = fractionMillis(mark.groupValues[3])
            stamped += (minute * 60_000L + second * 1000L + fraction) to spoken
        }
    }
    val ordered = stamped.sortedBy { it.first }
    return ordered.mapIndexed { index, (start, spoken) ->
        val end = ordered.getOrNull(index + 1)?.first ?: Long.MAX_VALUE
        SubtitleCue(start, end.coerceAtLeast(start + 1), spoken)
    }
}

private fun parseClock(token: String): Long? {
    val cleaned = token.trim().substringBefore(' ')
    val match = CLOCK_PATTERN.matchEntire(cleaned) ?: return null
    val hours = match.groupValues[1].toLongOrNull() ?: 0L
    val minutes = match.groupValues[2].toLongOrNull() ?: return null
    val seconds = match.groupValues[3].toLongOrNull() ?: return null
    return hours * 3_600_000L + minutes * 60_000L + seconds * 1000L + fractionMillis(match.groupValues[4])
}

private fun fractionMillis(raw: String): Long {
    if (raw.isEmpty()) return 0L
    val digits = raw.take(3)
    val value = digits.toLongOrNull() ?: return 0L
    return when (digits.length) {
        1 -> value * 100
        2 -> value * 10
        else -> value
    }
}
