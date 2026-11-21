package com.nanahoshi.audioplayer.japaneseasmr

import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.UserFacingException
import com.nanahoshi.audioplayer.R
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URLDecoder
import java.net.URLEncoder

private val uriAttribute = Regex("""(\bURI=")([^"]+)(")""")

internal fun jaMediaUrl(url: String): String =
    "jamedia://resource?url=" + URLEncoder.encode(url, "UTF-8")

internal fun restoreJaMediaUrl(query: String?): String? =
    query?.removePrefix("url=")?.let { URLDecoder.decode(it, "UTF-8") }
        ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }

internal fun rewriteJaPlaylist(text: String, url: String): ByteArray {
    val base = url.toHttpUrl()
    fun rewrite(reference: String): String {
        val address = base.resolve(reference) ?: throw JapaneseAsmrException(JaFailure.MEDIA)
        return jaMediaUrl(address.toString())
    }
    return text.lineSequence().joinToString("\n") { raw ->
        val line = raw.trim()
        when {
            line.isEmpty() -> ""
            line.startsWith('#') -> uriAttribute.replace(line) { match ->
                match.groupValues[1] + rewrite(match.groupValues[2]) + match.groupValues[3]
            }
            else -> rewrite(line)
        }
    }.toByteArray(Charsets.UTF_8)
}

internal data class JaSegment(val url: String, val duration: String)
internal data class JaPlaylist(val sequence: Long, val segments: List<JaSegment>, val durationMs: Long) {
    val fingerprint: String get() = jaHash(buildString {
        appendLine(sequence)
        segments.forEach { appendLine("${it.duration}\t${it.url}") }
    })
}

private fun unsupportedPlaylist() =
    UserFacingException(TextRef.Res(R.string.ja_error_hls_unsupported))

internal fun parseJaPlaylist(text: String, url: String): JaPlaylist {
    val base = url.toHttpUrl()
    val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    val unsupported = setOf("#EXT-X-STREAM-INF", "#EXT-X-KEY", "#EXT-X-MAP", "#EXT-X-DISCONTINUITY",
        "#EXT-X-BYTERANGE", "#EXT-X-I-FRAME-STREAM-INF", "#EXT-X-PART", "#EXT-X-SKIP", "#EXT-X-GAP")
    if (lines.firstOrNull() != "#EXTM3U" || lines.lastOrNull() != "#EXT-X-ENDLIST" ||
        lines.any { it.substringBefore(':') in unsupported }) throw unsupportedPlaylist()
    val sequence = lines.firstOrNull { it.startsWith("#EXT-X-MEDIA-SEQUENCE:") }?.let {
        it.substringAfter(':').toLongOrNull()?.takeIf { value -> value >= 0 } ?: throw unsupportedPlaylist()
    } ?: 0L
    val segments = mutableListOf<JaSegment>()
    var duration: String? = null
    for (line in lines) {
        if (line.startsWith("#EXTINF:")) {
            if (duration != null) throw unsupportedPlaylist()
            duration = line.substringAfter(':').substringBefore(',').trim()
            if (duration.toDoubleOrNull()?.let { it.isFinite() && it > 0 } != true) throw unsupportedPlaylist()
        } else if (!line.startsWith('#')) {
            val address = base.resolve(line) ?: throw unsupportedPlaylist()
            val length = duration ?: throw unsupportedPlaylist()
            segments += JaSegment(address.toString(), length)
            duration = null
        }
    }
    if (segments.isEmpty() || duration != null) throw unsupportedPlaylist()
    return JaPlaylist(sequence, segments, (segments.sumOf { it.duration.toDouble() } * 1000 + 0.5).toLong())
}
