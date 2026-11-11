package com.nanahoshi.audioplayer.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest

internal enum class ClipboardSource(val label: String) { BILIBILI("B 站"), ASMR("asmr.one"), YOUTUBE("YouTube") }
internal data class ClipboardTarget(val source: ClipboardSource, val id: String)
internal data class ClipboardImportCandidate(
    val target: ClipboardTarget,
    val timestamp: Long = 0,
    val requiresLookupConfirmation: Boolean = false,
) {
    val fingerprint: String
        get() {
            // 保留明确编号的旧摘要。忽略纯数字后，仍可复制明确的 RJ 号查询同一作品。
            val prefix = if (requiresLookupConfirmation) "bare-rj:" else ""
            val identity = "$prefix${target.source}:${target.id}:$timestamp"
            return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
}

internal const val MAX_IMPORT_CLIPBOARD_LENGTH = 32 * 1024
private val clipboardExplicitId = Regex("(?<![A-Za-z0-9])BV[0-9A-Za-z]{10}(?![A-Za-z0-9])|(?i:RJ)([0-9]{1,12})(?![0-9])")
private val clipboardBareRj = Regex("(?<![0-9])(?:01[0-9]{6}|[0-9]{6})(?![0-9])")
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
    var firstBv: String? = null
    for (match in clipboardExplicitId.findAll(text)) {
        val digits = match.groups[1]?.value
        if (digits == null) {
            if (firstBv == null) firstBv = match.value
        } else if (digits.any { it != '0' }) {
            return ClipboardTarget(ClipboardSource.ASMR, "RJ" + digits.padStart(6, '0'))
        }
    }
    return firstBv?.let { ClipboardTarget(ClipboardSource.BILIBILI, it) }
}

internal fun clipboardBareWorkCandidate(text: String, timestamp: Long = 0): ClipboardImportCandidate? =
    clipboardBareRj.findAll(text.take(MAX_IMPORT_CLIPBOARD_LENGTH))
        .firstOrNull { match -> match.value.any { it != '0' } }
        ?.let { match ->
            ClipboardImportCandidate(
                ClipboardTarget(ClipboardSource.ASMR, "RJ" + match.value),
                timestamp,
                requiresLookupConfirmation = true,
            )
        }
