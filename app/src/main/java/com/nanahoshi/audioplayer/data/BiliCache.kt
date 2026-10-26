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
