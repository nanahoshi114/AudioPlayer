package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.text.TextRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

internal fun cuesFromQwenPayload(text: String): List<WhisperCue> {
    val root = parseJsonObjectOrNull(text)
        ?: throw WhisperException(TextRef.Res(R.string.whisper_error_failed))
    val found = mutableListOf<WhisperCue>()
    val output = root.jsonObject("output")
    addSentences(found, output?.jsonArray("sentences"))
    val transcripts = root.jsonArray("transcripts") ?: output?.jsonArray("transcripts")
    if (found.isEmpty() && transcripts != null) {
        transcripts.jsonObjects().forEach { addSentences(found, it.jsonArray("sentences")) }
    }
    if (found.isEmpty()) addSentence(found, output?.jsonObject("sentence"))
    if (found.isEmpty() && hasRecognizedText(output, transcripts)) {
        throw WhisperException(TextRef.Res(R.string.whisper_error_no_timing))
    }
    return found.sortedBy { it.startMs }
}

private fun hasRecognizedText(output: JsonObject?, transcripts: JsonArray?): Boolean {
    if (!output?.jsonString("text").isNullOrBlank()) return true
    return transcripts.jsonObjects().any { !it.jsonString("text").isNullOrBlank() }
}

internal fun qwenErrorMessage(text: String): TextRef {
    val fallback = TextRef.Res(R.string.whisper_error_failed)
    val json = parseJsonObjectOrNull(text) ?: return fallback
    json.jsonString("message")?.takeIf { it.isNotBlank() }?.take(80)?.let { return TextRef.Raw(it) }
    return when (val error = json["error"]) {
        is JsonObject -> error.jsonString("message")?.takeIf { it.isNotBlank() }?.take(80)?.let(TextRef::Raw) ?: fallback
        else -> error.jsonTextOrNull()?.takeIf { it.isNotBlank() }?.take(80)?.let(TextRef::Raw) ?: fallback
    }
}

private fun addSentences(out: MutableList<WhisperCue>, array: JsonArray?) {
    array.jsonObjects().forEach { addSentence(out, it) }
}

private fun addSentence(out: MutableList<WhisperCue>, item: JsonObject?) {
    if (item == null) return
    val line = item.jsonStringOrEmpty("text").trim()
    if (line.isEmpty()) return
    val start = item.jsonLong("begin_time") ?: -1L
    var end = item.jsonLong("end_time") ?: -1L
    if (start < 0L || end < 0L) return
    if (end <= start) end = start + 1
    out.add(WhisperCue(start, end, line))
}