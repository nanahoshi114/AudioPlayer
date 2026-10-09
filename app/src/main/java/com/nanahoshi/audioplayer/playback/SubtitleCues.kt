package com.nanahoshi.audioplayer.playback

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

fun List<SubtitleCue>.textAt(positionMs: Long): String =
    lastOrNull { positionMs in it.startMs until it.endMs }?.text.orEmpty()

fun parseSubtitleDocument(raw: String): List<SubtitleCue> {
    val text = raw.trim().removePrefix("\uFEFF")
    if (text.isEmpty()) return emptyList()
    if (text.startsWith("{")) {
        parseBilibiliBody(text).takeIf { it.isNotEmpty() }?.let { return it }
    }
    if (text.startsWith("WEBVTT", ignoreCase = true) || text.contains("-->")) {
        return parseTimedBlocks(text)
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

private fun parseBilibiliBody(text: String): List<SubtitleCue> {
    val body = try {
        Json.parseToJsonElement(text).jsonObject["body"]?.jsonArray
    } catch (_: Exception) {
        null
    } ?: return emptyList()
    return body.mapNotNull { element ->
        val item = element.jsonObject
        val line = item["content"]?.jsonPrimitive?.content.orEmpty()
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("<[^>]+>"), "")
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
    val stamp = Regex("""\[(\d+):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    val wordStamp = Regex("""<\d+:\d{1,2}(?:[.:]\d{1,3})?>""")
    val stamped = mutableListOf<Pair<Long, String>>()
    for (line in text.replace("\r\n", "\n").lines()) {
        val marks = stamp.findAll(line).toList()
        if (marks.isEmpty()) continue
        val spoken = wordStamp.replace(line.substring(marks.last().range.last + 1), "").trim()
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
    val match = Regex("""^(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{1,3})$""").matchEntire(cleaned) ?: return null
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
