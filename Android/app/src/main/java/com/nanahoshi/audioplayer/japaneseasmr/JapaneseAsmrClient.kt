package com.nanahoshi.audioplayer.japaneseasmr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.nanahoshi.audioplayer.data.withResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class JapaneseAsmrClient(
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val pageClient: OkHttpClient = newSiteClient(),
    internal val mediaClient: OkHttpClient = newMediaClient(),
) {
    private data class CachedArticle(val article: JaArticle, val at: Long, val verified: Boolean)

    internal val pageRequests = Mutex()
    private var lastListRequestAt: Long? = null
    private val articles = object : LinkedHashMap<String, CachedArticle>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedArticle>?): Boolean = size > 64
    }
    private val postRefs = object : LinkedHashMap<String, JaPostRef>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JaPostRef>?): Boolean = size > 256
    }
    private data class CachedPlaylist(val bytes: ByteArray, val at: Long)
    private val playlists = ConcurrentHashMap<String, CachedPlaylist>()

    internal fun postRef(postId: String): JaPostRef? = synchronized(postRefs) { postRefs[postId] }

    internal fun rewrittenPlaylist(url: String): ByteArray? =
        playlists[url]?.takeIf { now() - it.at < CACHE_MS }?.bytes

    internal fun rememberPlaylist(url: String, bytes: ByteArray) {
        val at = now()
        playlists.entries.removeIf { at - it.value.at >= CACHE_MS }
        playlists[url] = CachedPlaylist(bytes, at)
    }

    internal fun expirePlaylists() {
        playlists.clear()
    }
    private val imageHosts = ConcurrentHashMap.newKeySet<String>().apply {
        addAll(listOf("japaneseasmr.com", "www.japaneseasmr.com", "pic.weeabo0.xyz", "img.weeabo0.xyz"))
    }

    internal fun handlesImage(host: String) = host in imageHosts
    internal fun registerImage(url: String?) {
        url?.toHttpUrlOrNull()?.host?.let(imageHosts::add)
    }

    suspend fun search(query: String, next: String? = null): JaPage {
        val url = next ?: BASE.toHttpUrl().newBuilder().addPathSegment("search")
            .addPathSegment(query).addPathSegment("").build().toString()
        val (html, resolved) = listRequest(url)
        return withContext(Dispatchers.IO) { parseJaSearch(html, resolved) }.also { page ->
            for (post in page.posts) {
                registerImage(post.coverUri)
                synchronized(postRefs) { postRefs[post.postId] = post }
            }
        }
    }

    suspend fun postsFor(rj: String): List<JaPostRef> {
        var page = search(rj)
        val visited = mutableSetOf<String>()
        while (true) {
            val matches = page.posts.filter { it.rj.equals(rj, ignoreCase = true) }
            if (matches.isNotEmpty()) return matches
            val next = page.next?.takeIf(visited::add) ?: return emptyList()
            page = search(rj, next)
        }
    }

    suspend fun article(postId: String, expectedRj: String? = null, refresh: Boolean = false): JaArticle {
        require(postId.isNotEmpty() && postId.all { it in '0'..'9' })
        if (refresh) invalidate(postId)
        val article = withTimeoutOrNull(30_000) {
            cachedArticle(postId) ?: pageRequests.withLock {
                cachedArticle(postId) ?: withContext(Dispatchers.IO) {
                    val (html, url) = fetchPage(postUrl(postId))
                    if (!isJaBrowserPage(url) || jaPostId(url) != postId) throw JapaneseAsmrException(JaFailure.IDENTITY)
                    parseJaArticle(html, postId, url)
                }.also { rememberArticle(it, verified = false) }
            }
        } ?: throw JapaneseAsmrException(JaFailure.CONNECTION)
        if (expectedRj != null && !expectedRj.equals(article.post.rj, ignoreCase = true)) {
            throw JapaneseAsmrException(JaFailure.IDENTITY)
        }
        return article
    }

    /** 用户在可见网页上完成验证后，只接收该篇目公开展示的播放器和作品信息。 */
    internal suspend fun rememberVerifiedArticle(
        pageUrl: String,
        html: String,
        expectedRj: String?,
        requireMedia: Boolean = true,
    ): JaArticle = withContext(Dispatchers.IO) {
        val postId = pageUrl.takeIf(::isJaBrowserPage)?.let(::jaPostId)
            ?: throw JapaneseAsmrException(JaFailure.UNAVAILABLE)
        val article = parseJaArticle(html, postId, pageUrl, allowLegacy = !requireMedia)
        if (expectedRj != null && !article.post.rj.equals(expectedRj, ignoreCase = true)) {
            throw JapaneseAsmrException(JaFailure.IDENTITY)
        }
        if (requireMedia && article.media.isEmpty()) throw JapaneseAsmrException(JaFailure.UNAVAILABLE)
        if (article.media.isNotEmpty()) rememberArticle(article, verified = true)
        article
    }

    private fun rememberArticle(article: JaArticle, verified: Boolean) {
        val post = article.post
        synchronized(articles) {
            if (!verified && articles[post.postId]?.verified == true) return
            articles[post.postId] = CachedArticle(article, now(), verified)
        }
        registerImage(post.coverUri)
        synchronized(postRefs) {
            val previous = postRefs[post.postId]
            postRefs[post.postId] = post.copy(
                publishedAt = post.publishedAt ?: previous?.publishedAt,
                coverUri = post.coverUri ?: previous?.coverUri,
            )
        }
    }

    internal fun cachedArticle(postId: String): JaArticle? = synchronized(articles) {
        // 验证后的资料仅驻内存，拖动或切歌继续使用，直到媒体失效或用户主动刷新。
        articles[postId]?.takeIf { it.verified || now() - it.at < CACHE_MS }?.article
    }

    internal fun invalidate(postId: String) {
        val article = synchronized(articles) { articles.remove(postId) }
        article?.article?.media?.flatMap { it.candidates }?.forEach { playlists.remove(it.url) }
    }

    private suspend fun listRequest(url: String): Pair<String, String> {
        while (true) {
            val remaining = pageRequests.withLock {
                val at = now()
                val waitTime = lastListRequestAt?.let { 10_000 - (at - it) } ?: 0L
                if (waitTime <= 0) {
                    lastListRequestAt = at
                    return fetchPage(url)
                }
                waitTime
            }
            wait(remaining)
        }
    }

    private suspend fun fetchPage(url: String): Pair<String, String> = try {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Accept", "*/*").build()
        withContext(Dispatchers.IO) {
            pageClient.newCall(request).withResponse { response ->
                val html = response.body?.string().orEmpty()
                val failure = when {
                    response.isChallenge(html) -> JaFailure.VERIFY
                    response.code in setOf(404, 410) && isJaBrowserPage(response.request.url.toString()) &&
                        jaPostId(response.request.url.toString()) == jaPostId(url) -> JaFailure.MISSING
                    response.code >= 500 -> JaFailure.SERVER
                    !response.isSuccessful -> JaFailure.UNAVAILABLE
                    else -> null
                }
                if (failure != null) {
                    throw JapaneseAsmrException(failure, url.takeIf { failure == JaFailure.VERIFY })
                }
                html to response.request.url.toString()
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: JapaneseAsmrException) {
        throw failure
    } catch (_: IOException) {
        throw JapaneseAsmrException(JaFailure.CONNECTION)
    }

    companion object {
        const val BASE = "https://japaneseasmr.com/"
        const val REFERER = BASE
        const val USER_AGENT = "Mozilla/5.0"
        private const val CACHE_MS = 5 * 60_000L

        fun postUrl(postId: String): String = "$BASE$postId/"

        private fun clientBuilder() = com.nanahoshi.audioplayer.data.HttpRoots.japaneseAsmr.newBuilder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)

        private fun newSiteClient() =
            JaNetworkPolicy.install(clientBuilder(), siteOnly = true, followMediaRedirects = true).build()

        private fun newMediaClient() =
            JaNetworkPolicy.install(clientBuilder(), siteOnly = false, followMediaRedirects = true).build()

        internal fun newWorkClient() =
            JaNetworkPolicy.install(clientBuilder(), siteOnly = false, followMediaRedirects = false).build()

        internal fun mediaRequest(url: String): Request.Builder = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT).header("Referer", REFERER)

        internal fun Response.isChallenge(html: String = ""): Boolean =
            header("cf-mitigated").equals("challenge", ignoreCase = true) ||
                (code == 403 && ("/cdn-cgi/challenge-platform/" in html || "<title>Just a moment" in html))
    }
}
