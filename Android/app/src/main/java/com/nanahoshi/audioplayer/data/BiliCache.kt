package com.nanahoshi.audioplayer.data

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val BVID = Regex("BV[0-9A-Za-z]{10}")

fun biliAudioFile(filesDir: File, bvid: String, cid: Long): File? {
    if (cid <= 0L || !bvid.matches(BVID)) return null
    return File(filesDir, "bilibili/$bvid/$cid.m4s")
}

fun readyBiliAudio(filesDir: File, bvid: String?, cid: Long?): File? {
    val id = bvid?.trim().orEmpty()
    val page = cid ?: return null
    val file = biliAudioFile(filesDir, id, page) ?: return null
    return file.takeIf { it.isFile && it.length() > 0L }
}

/** 缓存的 B 站字幕和音轨放在同一目录，文件名是分集编号加 `.json`。 */
fun biliSubtitleFile(filesDir: File, bvid: String, cid: Long): File? {
    if (cid <= 0L || !bvid.matches(BVID)) return null
    return File(filesDir, "bilibili/$bvid/$cid.json")
}

fun readBiliSubtitle(filesDir: File, bvid: String, cid: Long): String? {
    val file = biliSubtitleFile(filesDir, bvid, cid) ?: return null
    if (!file.isFile) return null
    return try {
        file.readText().takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }
}

fun biliSubtitleLanguage(document: String): String? = try {
    Json.parseToJsonElement(document).jsonObject["_audioPlayerSubtitle"]?.jsonObject
        ?.get("language")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
} catch (_: Exception) { null }

fun writeBiliSubtitle(filesDir: File, bvid: String, cid: Long, document: String, language: String? = null): Boolean {
    val file = biliSubtitleFile(filesDir, bvid, cid) ?: return false
    if (document.isBlank()) return false
    return try {
        file.parentFile?.mkdirs()
        val cached = if (language.isNullOrBlank()) document else {
            val root = Json.parseToJsonElement(document).jsonObject
            JsonObject(root + ("_audioPlayerSubtitle" to JsonObject(mapOf("language" to JsonPrimitive(language))))).toString()
        }
        file.writeText(cached)
        true
    } catch (_: Exception) {
        false
    }
}

fun deleteBiliSubtitle(filesDir: File, bvid: String?, cid: Long?) {
    val id = bvid?.trim().orEmpty()
    val page = cid ?: return
    biliSubtitleFile(filesDir, id, page)?.delete()
}

/** 删下载管理里的 B 站音轨时，顺便删掉同目录下同名的字幕。 */
fun deleteBiliSubtitleBeside(audio: File) {
    val dir = audio.parentFile ?: return
    if (dir.parentFile?.name != "bilibili") return
    File(dir, audio.nameWithoutExtension + ".json").delete()
}
