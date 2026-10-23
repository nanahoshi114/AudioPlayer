package com.nanahoshi.audioplayer.data

import org.json.JSONArray
import org.json.JSONObject

internal fun cuesFromQwenPayload(text: String): List<WhisperCue> {
    val root = try {
        JSONObject(text)
    } catch (_: Exception) {
        throw WhisperException("生成失败")
    }
    val found = mutableListOf<WhisperCue>()
    val output = root.optJSONObject("output")
    addSentences(found, output?.optJSONArray("sentences"))
    if (found.isEmpty()) {
        val transcripts = root.optJSONArray("transcripts")
        if (transcripts != null) {
            for (index in 0 until transcripts.length()) {
                addSentences(found, transcripts.optJSONObject(index)?.optJSONArray("sentences"))
            }
        }
    }
    if (found.isEmpty()) addSentence(found, output?.optJSONObject("sentence"))
    if (found.isEmpty() && output?.optString("text")?.trim().orEmpty().isNotEmpty()) {
        throw WhisperException("没有分段时间")
    }
    return found.sortedBy { it.startMs }
}

internal fun qwenErrorMessage(text: String): String {
    val fallback = "生成失败"
    return try {
        val json = JSONObject(text)
        json.optString("message").takeIf { it.isNotBlank() }?.take(80)?.let { return it }
        when (val error = json.opt("error")) {
            is String -> error.takeIf { it.isNotBlank() }?.take(80) ?: fallback
            is JSONObject -> error.optString("message").takeIf { it.isNotBlank() }?.take(80) ?: fallback
            else -> fallback
        }
    } catch (_: Exception) {
        fallback
    }
}

private fun addSentences(out: MutableList<WhisperCue>, array: JSONArray?) {
    if (array == null) return
    for (index in 0 until array.length()) {
        addSentence(out, array.optJSONObject(index))
    }
}

private fun addSentence(out: MutableList<WhisperCue>, item: JSONObject?) {
    if (item == null) return
    if (item.has("sentence_end") && !item.optBoolean("sentence_end")) return
    val line = item.optString("text").trim()
    if (line.isEmpty()) return
    val start = item.optLong("begin_time", -1L)
    var end = item.optLong("end_time", -1L)
    if (start < 0L || end < 0L) return
    if (end <= start) end = start + 1
    out.add(WhisperCue(start, end, line))
}
