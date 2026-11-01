package com.nanahoshi.audioplayer.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest

internal enum class ClipboardSource(val label: String) { BILIBILI("B站"), ASMR("asmr.one"), YOUTUBE("YouTube") }
internal data class ClipboardTarget(val source: ClipboardSource, val id: String)
internal data class ClipboardImportCandidate(val target: ClipboardTarget, val timestamp: Long = 0) {
    // 只持久化内容身份和系统复制时间的摘要，不保存剪贴板原文或链接参数。
    val fingerprint: String get() = MessageDigest.getInstance("SHA-256")
        .digest("${target.source}:${target.id}:$timestamp".toByteArray()).joinToString("") { "%02x".format(it) }
}

internal const val MAX_IMPORT_CLIPBOARD_LENGTH = 32 * 1024
private val clipboardBv = Regex("(?<![A-Za-z0-9])BV[0-9A-Za-z]{10}(?![A-Za-z0-9])")
private val clipboardRj = Regex("(?<![A-Za-z0-9])(?i:RJ)([0-9]{1,12})(?![A-Za-z0-9])")
private val clipboardUrl = Regex("(?:https?://|(?<![A-Za-z0-9./])(?:www\\.|m\\.|music\\.)?(?:youtube\\.com|youtu\\.be)/)[^\\s<>\"'（）【】]+", RegexOption.IGNORE_CASE)
private val youtubeId = Regex("[A-Za-z0-9_-]{11}")

/** 分享文字中的明确链接优先于标题里附带的编号；一次只处理一个条目。 */
internal fun clipboardImportTarget(raw: String): ClipboardTarget? {
    val text = raw.take(MAX_IMPORT_CLIPBOARD_LENGTH)
    for (match in clipboardUrl.findAll(text)) {
        val address = match.value.trimEnd('.', ',', '，', '。', ';', '；', ')', ']', '}')
        val url = (if (address.startsWith("http", true)) address else "https://$address").toHttpUrlOrNull() ?: continue
        val host = url.host
        if (host == "bilibili.com" || host.endsWith(".bilibili.com")) {
            val path = url.pathSegments
            val index = path.indexOf("video")
            path.getOrNull(index + 1)?.takeIf { index >= 0 && Regex("BV[0-9A-Za-z]{10}").matches(it) }?.let {
                return ClipboardTarget(ClipboardSource.BILIBILI, it)
            }
        }
        val video = when {
            host == "youtu.be" || host == "www.youtu.be" -> url.pathSegments.firstOrNull()
            host == "youtube.com" || host.endsWith(".youtube.com") || host == "www.youtube-nocookie.com" -> {
                when (url.pathSegments.firstOrNull()) {
                    "watch" -> url.queryParameter("v")
                    "shorts", "embed", "live", "v" -> url.pathSegments.getOrNull(1)
                    else -> null
                }
            }
            else -> null
        }
        if (video != null && youtubeId.matches(video)) return ClipboardTarget(ClipboardSource.YOUTUBE, video)
    }
    val bv = clipboardBv.find(text)
    val rj = clipboardRj.findAll(text).firstOrNull { (it.groupValues[1].toLongOrNull() ?: 0) > 0 }
    if (bv != null && (rj == null || bv.range.first < rj.range.first)) return ClipboardTarget(ClipboardSource.BILIBILI, bv.value)
    return rj?.let { ClipboardTarget(ClipboardSource.ASMR, "RJ" + it.groupValues[1].toLong().toString().padStart(6, '0')) }
}
