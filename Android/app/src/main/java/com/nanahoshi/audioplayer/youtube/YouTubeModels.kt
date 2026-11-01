package com.nanahoshi.audioplayer.youtube

import kotlinx.serialization.json.*
import java.net.URLDecoder
import java.security.MessageDigest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File

class YouTubeException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)

/** 仅记录阶段、错误类型和计数；不包含网址、请求头、凭证或异常原文。 */
data class YouTubeDiagnostic(
    val stage: String,
    val errorTypes: String,
    val responseCode: Int?,
    val bytesRead: Long,
    val bytesTotal: Long,
    val elapsedMs: Long,
)

data class YouTubeVideo(
    val id: String,
    val title: String,
    val author: String = "",
    val coverUrl: String = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
    val durationMs: Long = 0,
) {
    val watchUrl: String get() = "https://www.youtube.com/watch?v=$id"
}

data class YouTubeAudio(val video: YouTubeVideo, val url: String, val mimeType: String, val headers: Map<String, String>)
data class YouTubeResults(val videos: List<YouTubeVideo>, val partial: Boolean = false)
internal enum class InputKind { VIDEO, PLAYLIST, SEARCH }
internal data class YouTubeInput(val kind: InputKind, val value: String)
internal val videoIdPattern = Regex("^[A-Za-z0-9_-]{11}$")
internal fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")
internal fun form(value: String): Map<String, String> = value.split('&').filter { it.isNotEmpty() }.associate {
    decode(it.substringBefore('=')) to decode(it.substringAfter('=', ""))
}
internal fun inputOf(raw: String): YouTubeInput {
    val text = raw.trim()
    if (text.isEmpty()) throw YouTubeException("请输入关键词、视频或播放列表链接")
    Regex("[?&]search_query=([^&]+)", RegexOption.IGNORE_CASE).find(text)?.let {
        val query = decode(it.groupValues[1]).trim()
        if (query.isNotEmpty()) return YouTubeInput(InputKind.SEARCH, query)
    }
    val video = Regex("(?:v=|/shorts/|/embed/|/live/|/v/|youtu\\.be/)([A-Za-z0-9_-]{11})").find(text)?.groupValues?.get(1)
        ?: text.takeIf { videoIdPattern.matches(it) }
    val list = Regex("[?&]list=([A-Za-z0-9_-]{2,80})").find(text)?.groupValues?.get(1)
        ?: text.takeIf { it == "LL" || it == "WL" || Regex("PL[A-Za-z0-9_-]{11,78}").matches(it) }
    if (video != null && (list == null || listOf("watch", "youtu.be", "shorts").any { text.contains(it, true) })) {
        return YouTubeInput(InputKind.VIDEO, video)
    }
    return when {
        list != null -> YouTubeInput(InputKind.PLAYLIST, list)
        video != null -> YouTubeInput(InputKind.VIDEO, video)
        else -> YouTubeInput(InputKind.SEARCH, text)
    }
}

fun normalizeYouTubeCookie(raw: String): String = raw.replace("\r", "").replace("\n", "").trim().trim(';').trim()
internal fun authHeaders(cookie: String, seconds: Long): Map<String, String> {
    val value = normalizeYouTubeCookie(cookie)
    if (value.isEmpty()) return emptyMap()
    val fields = value.split(';').associate { it.trim().substringBefore('=').trim() to it.trim().substringAfter('=', "") }
    val sapisid = fields["SAPISID"]?.takeIf { it.isNotEmpty() } ?: fields["__Secure-3PAPISID"]?.takeIf { it.isNotEmpty() }
    return buildMap {
        put("Cookie", value)
        put("X-Goog-AuthUser", "0")
        if (sapisid != null) {
            val hash = MessageDigest.getInstance("SHA-1").digest("$seconds $sapisid https://www.youtube.com".toByteArray())
                .joinToString("") { "%02x".format(it) }
            put("Authorization", "SAPISIDHASH ${seconds}_$hash")
        }
    }
}

internal fun JsonElement.child(name: String): JsonElement = (this as? JsonObject)?.get(name) ?: JsonNull
internal fun JsonElement.text(name: String): String = (child(name) as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonElement.runs(name: String): String {
    val value = child(name)
    if (value is JsonPrimitive) return value.contentOrNull.orEmpty()
    return value.text("simpleText").ifBlank {
        (value.child("runs") as? JsonArray)?.joinToString("") { it.text("text") }.orEmpty()
    }.trim()
}
internal fun JsonElement.walk(): Sequence<JsonObject> = sequence {
    when (val element = this@walk) {
        is JsonObject -> { yield(element); element.values.forEach { yieldAll(it.walk()) } }
        is JsonArray -> element.forEach { yieldAll(it.walk()) }
        else -> Unit
    }
}
internal fun durationOf(value: String): Long {
    val fields = value.split(':').map { it.toLongOrNull() ?: return 0L }
    return if (fields.size in 1..3 && fields.all { it in 0..99999 }) fields.fold(0L) { seconds, part -> seconds * 60 + part } * 1000 else 0L
}
internal fun collectVideos(root: JsonElement, renderer: String, limit: Int, seen: MutableSet<String>): List<YouTubeVideo> =
    root.walk().mapNotNull { it[renderer] as? JsonObject }.mapNotNull { video ->
        val id = video.text("videoId")
        if (!videoIdPattern.matches(id) || !seen.add(id)) null else YouTubeVideo(
            id, video.runs("title").ifBlank { id },
            video.runs("shortBylineText").ifBlank { video.runs("longBylineText") }.ifBlank { video.runs("ownerText") },
            durationMs = durationOf(video.runs("lengthText")),
        )
    }.take(limit).toList()
internal fun continuationOf(root: JsonElement): String? = root.walk().mapNotNull { it["continuationItemRenderer"] }
    .flatMap { it.walk() }.map { it.text("token") }.firstOrNull { it.isNotBlank() }

internal data class AudioStream(val mime: String, val bitrate: Long, val url: String, val cipher: String)
internal fun audioStreams(root: JsonElement): List<AudioStream> = listOf("adaptiveFormats", "formats").flatMap { name ->
    (root.child(name) as? JsonArray).orEmpty().mapNotNull { item ->
        val mime = item.text("mimeType")
        if (!mime.startsWith("audio/mp4", true)) null else AudioStream(
            mime, (item.child("bitrate") as? JsonPrimitive)?.longOrNull ?: 0,
            item.text("url"), item.text("signatureCipher").ifBlank { item.text("cipher") },
        )
    }
}.filter { it.url.isNotBlank() || it.cipher.isNotBlank() }
    .sortedWith(compareByDescending<AudioStream> { it.mime.contains("mp4a", true) }.thenByDescending { it.bitrate })

internal data class Solved(val sig: Map<String, String>, val n: Map<String, String>)
internal fun solvedResults(value: String, hasSig: Boolean, hasN: Boolean): Solved {
    val root = try { Json.parseToJsonElement(value) } catch (_: Exception) { throw YouTubeException("YouTube 签名解析失败") }
    val responses = root.child("responses") as? JsonArray ?: throw YouTubeException("YouTube 签名解析失败")
    var index = 0
    fun next(): Map<String, String> {
        val result = responses.getOrNull(index++) ?: throw YouTubeException("YouTube 签名解析失败")
        if (result.text("type") != "result") throw YouTubeException("YouTube 签名解析失败")
        return (result.child("data") as? JsonObject)?.mapNotNull { (key, value) ->
            (value as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { key to it }
        }?.toMap() ?: throw YouTubeException("YouTube 签名解析失败")
    }
    return Solved(if (hasSig) next() else emptyMap(), if (hasN) next() else emptyMap())
}
internal fun finishUrl(stream: AudioStream, solved: Solved): String? {
    val fields = form(stream.cipher)
    val raw = if (stream.cipher.isNotBlank()) {
        val sig = solved.sig[fields["s"]] ?: return null
        val builder = fields["url"]?.toHttpUrlOrNull()?.newBuilder() ?: return null
        builder.addQueryParameter(fields["sp"].orEmpty().ifBlank { "sig" }, sig).build().toString()
    } else stream.url
    val url = raw.toHttpUrlOrNull() ?: return null
    if (!mediaHostAllowed(url.host) || url.scheme != "https") return null
    val n = url.queryParameter("n")
    return if (n != null && !solved.n[n].isNullOrBlank()) url.newBuilder().setQueryParameter("n", solved.n[n]).build().toString() else url.toString()
}
internal fun mediaHostAllowed(host: String): Boolean = host == "googlevideo.com" || host.endsWith(".googlevideo.com")
fun youtubeAudioFile(filesDir: File, videoId: String): File? =
    videoId.takeIf { videoIdPattern.matches(it) }?.let { File(filesDir, "youtube/$it/audio.m4a") }
fun readyYouTubeAudio(filesDir: File, videoId: String?): File? = videoId?.let { youtubeAudioFile(filesDir, it) }?.takeIf { it.isFile && it.length() > 0 }

internal data class MediaByteRange(val start: Long, val end: Long, val total: Long)
internal fun parseMediaRange(header: String?): MediaByteRange? {
    val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE).matchEntire(header?.trim().orEmpty()) ?: return null
    val values = match.groupValues.drop(1).map { it.toLongOrNull() ?: return null }
    val (start, end, total) = values
    if (start < 0 || end < start || total <= end) return null
    return MediaByteRange(start, end, total)
}
