package com.nanahoshi.audioplayer.data

import java.io.File

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

fun writeBiliSubtitle(filesDir: File, bvid: String, cid: Long, document: String): Boolean {
    val file = biliSubtitleFile(filesDir, bvid, cid) ?: return false
    if (document.isBlank()) return false
    return try {
        file.parentFile?.mkdirs()
        file.writeText(document)
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
