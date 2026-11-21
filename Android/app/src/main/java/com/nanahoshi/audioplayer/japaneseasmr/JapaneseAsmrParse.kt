package com.nanahoshi.audioplayer.japaneseasmr

import com.nanahoshi.audioplayer.R
import androidx.annotation.StringRes
import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.UserFacingError
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import java.security.MessageDigest

internal fun com.nanahoshi.audioplayer.data.db.QueuedTrack.jaAudioIdentity(): String? = when {
    remoteUrl?.startsWith("japaneseasmr://file?") == true -> remoteUrl
    source == com.nanahoshi.audioplayer.data.TrackSource.JAPANESE_ASMR && workno != null && fileKey != null ->
        jaIdentity(workno, fileKey)
    else -> remoteUrl?.takeIf { it.startsWith("japaneseasmr://audio?") }
}

enum class JaMediaKind { DIRECT, HLS }

data class JaPostRef(
    val postId: String,
    val rj: String,
    val title: String,
    val publishedAt: String?,
    val url: String,
    val coverUri: String?,
)

data class JaPage(val posts: List<JaPostRef>, val next: String?)
data class JaArticle(
    val post: JaPostRef,
    val voiceActors: List<String>,
    val media: List<JaMediaRef>,
    val complete: Boolean = false,
    val downloadLinks: List<String> = emptyList(),
    val duplicateKeys: Set<String> = emptySet(),
)
data class JaCandidate(val url: String, val mime: String?) {
    val kind get() = if (
        mime?.contains("mpegurl", ignoreCase = true) == true ||
        url.toHttpUrlOrNull()?.encodedPath?.endsWith(".m3u8", ignoreCase = true) == true
    ) JaMediaKind.HLS else JaMediaKind.DIRECT
}
data class JaMediaRef(
    val key: String,
    val title: String,
    val candidates: List<JaCandidate>,
    val chapters: List<JaChapter>,
    val pathHash: String? = null,
    val hasTitle: Boolean = true,
    val hasDescription: Boolean = true,
) {
    val kind get() = candidates.first().kind
}

@Serializable
data class JaChapter(val startMs: Long, val title: String)

internal enum class JaFailure(@param:StringRes val messageRes: Int) {
    VERIFY(R.string.ja_error_verify),
    MEDIA_VERIFY(R.string.ja_error_media_verify),
    CONNECTION(R.string.ja_error_connection),
    MISSING(R.string.ja_error_missing),
    UNAVAILABLE(R.string.ja_error_unavailable),
    IDENTITY(R.string.ja_error_identity),
    LEGACY(R.string.ja_error_legacy),
    SERVER(R.string.ja_error_server),
    MEDIA(R.string.ja_error_media),
    GONE(R.string.ja_error_gone),
    MEDIA_GONE(R.string.ja_error_media_gone),
    REOPEN(R.string.ja_error_reopen),
    WORK_FILE_MISSING(R.string.ja_error_work_file_missing),
    UNSAFE(R.string.ja_error_unsafe_address),
}

internal class JaMediaKindChanged(val kind: JaMediaKind) : java.io.IOException()

internal class JapaneseAsmrException(
    val failure: JaFailure,
    val verificationPage: String? = null,
) : java.io.IOException(failure.name), UserFacingError {
    override val userText = TextRef.Res(failure.messageRes)
}

internal fun jaIdentity(postId: String, mediaKey: String): String =
    "japaneseasmr://audio?post=$postId&media=$mediaKey"

internal fun jaHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

private val rjPattern = Regex("""(?<![A-Za-z0-9])RJ[0-9]+(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE)
private val whitespace = Regex("""[\s\p{Z}]+""")
private fun String.normalized() = trim().replace(whitespace, " ")
private fun String.rj() = rjPattern.findAll(this).map { it.value.uppercase() }
    .filter { it.drop(2).any { digit -> digit != '0' } }.distinct().singleOrNull()

private fun String.httpUrl(): String? = toHttpUrlOrNull()?.toString()

internal fun isJaBrowserPage(url: String): Boolean {
    val parsed = url.toHttpUrlOrNull() ?: return false
    return parsed.isHttps && parsed.port == 443 && parsed.username.isEmpty() && parsed.password.isEmpty() &&
        parsed.host in setOf("japaneseasmr.com", "www.japaneseasmr.com")
}

internal fun jaPostId(url: String): String? {
    val parsed = url.toHttpUrlOrNull() ?: return null
    if (parsed.host !in setOf("japaneseasmr.com", "www.japaneseasmr.com")) return null
    return Regex("""/([0-9]+)/?""").matchEntire(parsed.encodedPath)?.groupValues?.get(1)
}

internal fun parseJaSearch(html: String, url: String): JaPage {
    val page = Jsoup.parse(html, url)
    val posts = page.select(".entry-preview").mapNotNull { card ->
        val anchor = card.selectFirst(".entry-title a") ?: return@mapNotNull null
        val postId = jaPostId(anchor.absUrl("href")) ?: return@mapNotNull null
        val rj = card.text().rj() ?: return@mapNotNull null
        JaPostRef(
            postId, rj, anchor.text(), card.selectFirst("time[datetime]")?.attr("datetime"),
            JapaneseAsmrClient.postUrl(postId),
            card.selectFirst("img[data-src]")?.absUrl("data-src")?.httpUrl()
                ?: card.selectFirst("img[src]")?.absUrl("src")?.httpUrl(),
        )
    }.distinctBy { it.postId }
    val next = page.selectFirst("nav.navigation.pagination a.next.page-numbers")?.absUrl("href")
        ?.toHttpUrlOrNull()?.takeIf { it.host in setOf("japaneseasmr.com", "www.japaneseasmr.com") }?.toString()
    return JaPage(posts, next)
}

internal fun parseJaArticle(
    html: String,
    postId: String,
    pageUrl: String = JapaneseAsmrClient.postUrl(postId),
    allowLegacy: Boolean = false,
): JaArticle {
    val url = JapaneseAsmrClient.postUrl(postId)
    val page = Jsoup.parse(html, pageUrl)
    val heading = page.selectFirst("h1.page-title")?.text().orEmpty()
    val rjFields = page.select(".entry-content p").map { it.text() }
        .filter { it.startsWith("RJ Code:", ignoreCase = true) }
    val identity = if (rjFields.isEmpty()) heading else rjFields.joinToString(" ") { field ->
        val raw = field.substringAfter(':').trim()
        if (raw.isNotEmpty() && raw.all { it in '0'..'9' }) "RJ$raw" else raw
    }
    val rj = identity.rj()
        ?: throw JapaneseAsmrException(JaFailure.IDENTITY)
    val image = page.selectFirst("meta[property=og:image]")?.absUrl("content")?.httpUrl()
        ?: page.selectFirst(".entry-content img[data-src]")?.absUrl("data-src")?.httpUrl()
    val actors = page.selectFirst("#voice_actors")?.text()?.replace(Regex("^CV:\\s*", RegexOption.IGNORE_CASE), "")
        ?.split('、', ',', '／')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
    val post = JaPostRef(postId, rj, heading.ifBlank { rj }, page.selectFirst("time[datetime]")?.attr("datetime"), url, image)
    val players = page.select(".cleanPlayer video, .cleanPlayer audio")
    val chapters = if (players.size == 1) page.select("#plyr-chapter-playlist tr").mapNotNull { row ->
        val title = row.selectFirst("td.chapter_list.chapter_title")?.text()?.takeIf(String::isNotBlank)
            ?: return@mapNotNull null
        val start = row.selectFirst("td.chapter_list.start_time a[data-value], td.chapter_list.chapter_title a[data-value]")
            ?.attr("data-value")?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return@mapNotNull null
        JaChapter((start * 1000).toLong(), title)
    }.distinctBy { it.startMs }.sortedBy { it.startMs } else emptyList()
    val signatures = players.map { player -> player.attr("title").normalized() to player.attr("descr").normalized() }
    val counts = signatures.groupingBy { it }.eachCount()
    val parsed = players.mapIndexedNotNull { index, player ->
        val candidates = (player.select("source[src]").map { it.absUrl("src") to it.attr("type") } +
            listOf(player.absUrl("src") to player.attr("type"))).mapNotNull { (address, mime) ->
            address.httpUrl()?.let { JaCandidate(it, mime.ifBlank { null }) }
        }.distinctBy { it.url }
        val first = candidates.firstOrNull() ?: return@mapIndexedNotNull null
        val (title, description) = signatures[index]
        val hasTitle = title.isNotEmpty() && !rjPattern.matches(title)
        val pathHash = jaHash(first.url.toHttpUrlOrNull()!!.encodedPath).take(16)
        val key = if (hasTitle && description.isNotEmpty() && counts[title to description] == 1) {
            "t:" + jaHash("$title\u0000$description").take(16)
        } else {
            "p:$pathHash"
        }
        val fallback = heading.takeIf { players.size == 1 && it.isNotBlank() && !rjPattern.matches(it) }
        JaMediaRef(key, if (hasTitle) title else fallback ?: "音轨 ${index + 1}", candidates, chapters,
            pathHash, hasTitle, description.isNotEmpty())
    }
    val media = parsed.distinctBy { it.key }
    val legacy = page.selectFirst("#basic-playlist li a[data-url]") != null
    if (media.isEmpty() && legacy && !allowLegacy) {
        throw JapaneseAsmrException(JaFailure.LEGACY)
    }
    val downloadLinks = page.select(".entry-content a[href], a[href*=dlc.php]").mapNotNull { anchor ->
        val address = anchor.absUrl("href").toHttpUrlOrNull() ?: return@mapNotNull null
        address.toString().takeIf {
            isJaBrowserPage(it) && (address.encodedPath == "/dlc.php" ||
                (anchor.text().contains("Download", ignoreCase = true) && address.encodedPath != "/"))
        }
    }.distinct()
    val complete = media.isNotEmpty() && players.size == parsed.size && parsed.size == media.size && !legacy
    val duplicates = parsed.groupingBy { it.key }.eachCount().filterValues { it > 1 }.keys
    return JaArticle(post, actors, media, complete, downloadLinks, duplicates)
}
