package com.nanahoshi.audioplayer.japaneseasmr

import androidx.annotation.StringRes
import com.nanahoshi.audioplayer.text.PersistedErrors
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.data.toHex
import com.nanahoshi.audioplayer.data.withResponse
import com.nanahoshi.audioplayer.japaneseasmr.JapaneseAsmrClient.Companion.isChallenge
import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.UserFacingError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

internal const val JA_WORK_DOWNLOAD = "JAPANESE_ASMR_WORK"
internal const val JA_VERIFY_PHASE = "VERIFY"
internal const val JA_WORK_FILE_MISSING = PersistedErrors.JA_WORK_FILE_MISSING

internal open class JaWorkException(
    override val userText: TextRef,
    val persistedError: String? = null,
) : IOException(persistedError ?: userText.debugString()), UserFacingError
internal class JaWorkAddressExpired(text: TextRef, val sessionRequired: Boolean = false) : JaWorkException(text)
internal class JaDownloadVerification(val entry: String) : IOException(PersistedErrors.JA_VERIFY), UserFacingError {
    override val userText = TextRef.Res(R.string.download_status_waiting_verification)
}

internal data class JaWorkEntry(val post: JaPostRef, val address: String)
internal data class JaVisibleDownload(
    val url: String,
    val html: String,
    val direct: String? = null,
    val userAgent: String? = null,
    val copyLink: String? = null,
    /** 浏览器实际请求成功并返回音频/文件的地址；可以是带 t 令牌的下载端点。 */
    val fileUrl: String? = null,
)

internal data class JaBuzzPage(val fileLink: String, val address: String) {
    fun accepts(candidate: String): Boolean {
        val expected = address.toHttpUrlOrNull()?.takeIf { it.buzzTransferHost() } ?: return false
        val actual = candidate.toHttpUrlOrNull()?.takeIf { it.buzzTransferHost() } ?: return false
        return isBuzzBrowserPage(candidate) &&
            (expected.encodedPath.trimEnd('/') == actual.encodedPath.trimEnd('/') || expected.host == "ts.buzzheavier.com")
    }
}

internal class JaBuzzVerification(
    val entry: JaWorkEntry,
    val sourcePage: JaVisibleDownload?,
    val pages: List<JaBuzzPage>,
    val fileLink: String? = null,
) : JaWorkException(TextRef.Res(R.string.ja_work_buzz_challenge))

internal class JaTransferVerification(val page: JaBuzzPage) :
    JaWorkException(TextRef.Res(R.string.ja_work_buzz_challenge))

private class JaWorkResponseIsPage : JaWorkException(TextRef.Res(R.string.ja_error_buzz_response_page))

internal sealed interface JaFileTransfer {
    data class Buzz(
        val address: HttpUrl,
        val etag: String?,
        val lastModified: String? = null,
        val buttonPage: HttpUrl? = null,
        val userAgent: String? = null,
        val verificationPage: JaBuzzPage? = null,
    ) : JaFileTransfer
    data class Mega(
        val source: JaMegaFileSource,
        val crypto: JaMegaCrypto,
        val initialAddress: HttpUrl? = null,
    ) : JaFileTransfer {
        override fun toString(): String = "Mega(source=$source, hasInitialAddress=${initialAddress != null})"
    }
}

internal data class JaRemoteFile(
    val relativePath: String,
    val size: Long?,
    val sha256: String?,
    val transfer: JaFileTransfer,
)

internal data class JaWorkManifest(
    val entry: JaWorkEntry,
    val files: List<JaRemoteFile>,
    val skipped: Int,
    val resolvedAtMs: Long = System.nanoTime() / 1_000_000,
    val fromVerifiedPage: Boolean = false,
    val sourcePage: JaVisibleDownload? = null,
    val verifiedBuzzPages: Map<String, JaVisibleDownload> = emptyMap(),
) {
    val bytes: Long? get() = if (files.all { it.size != null }) files.sumOf { it.size ?: 0L } else null
    val pureMega: Boolean get() = files.isNotEmpty() && files.all { it.transfer is JaFileTransfer.Mega }

    fun forMegaRetention(keepInitialAddress: Boolean): JaWorkManifest = copy(
        files = if (keepInitialAddress) files else files.map { file ->
            val transfer = file.transfer as JaFileTransfer.Mega
            if (transfer.initialAddress == null) file else file.copy(transfer = transfer.copy(initialAddress = null))
        },
        sourcePage = null,
        verifiedBuzzPages = emptyMap(),
    )
}

private const val MAX_PAGE = 512_000L
private const val MAX_MEGA_FOLDER_JSON = 8L * 1024 * 1024
private val refreshTarget = Regex("""(?i)(?:^|[;,\s])url\s*=\s*(.+)$""")
private val htmlStart = Regex("""^(?:<!--|<!doctype\s+html|<\?xml|<(?:html|head|body|title|script|meta|div)(?:[\s>/]))""", RegexOption.IGNORE_CASE)
private val buzzHosts = setOf("buzzheavier.com", "www.buzzheavier.com", "ts.buzzheavier.com", "dd.buzzheavier.com")
private val megaHosts = setOf("mega.nz", "www.mega.nz", "mega.co.nz", "www.mega.co.nz")
private val redirectCodes = setOf(301, 302, 303, 307, 308)
private val hashPattern = Regex("(?i)\\b[a-f0-9]{64}\\b")
private val buzzDownloadPath = Regex("/(?:[A-Za-z0-9_-]+/download|d/[A-Za-z0-9_-]+)")
private val reservedNames = Regex("(?i)(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")
private val quotedDownloadUrl = Regex("""["'](https://(?:[a-zA-Z0-9.-]+\.)?(?:buzzheavier\.com|mega\.nz|mega\.co\.nz)/[^"'\s<>]+)["']""")
private val downloadTokenPath = Regex("""/[A-Za-z0-9_-]+/download\?t=[^"'\s<>]+""")
private val sha256Label = Regex("(?i)SHA[- ]?256\\s*:?\\s*([a-f0-9]{64})")
private val quotedRelativeDownload = Regex("""["'](/[A-Za-z0-9_-]+/download\?t=[^"']+)["']""")

internal fun isJaDownloadRedirect(address: String): Boolean =
    address.toHttpUrlOrNull()?.let { it.publicHttps() && (it.host in buzzHosts || it.host in megaHosts) } == true

internal fun isBuzzBrowserPage(address: String): Boolean =
    address.toHttpUrlOrNull()?.let { it.buzzTransferHost() && !buzzDownloadPath.matches(it.encodedPath) } == true

internal fun isBuzzHost(address: String): Boolean =
    address.toHttpUrlOrNull()?.let { it.buzzTransferHost() } == true

internal fun isBuzzDownloadEndpoint(address: String): Boolean =
    address.toHttpUrlOrNull()?.let { it.buzzTransferHost() && buzzDownloadPath.matches(it.encodedPath) } == true

private fun buzzLandingUrl(address: HttpUrl): String {
    val path = address.encodedPath
    return if (buzzDownloadPath.matches(path)) {
        val landingPath = if (path.startsWith("/d/")) "/" + path.substringAfterLast('/') else path.removeSuffix("/download")
        val landingHost = if (address.host == "ts.buzzheavier.com") "buzzheavier.com" else address.host
        address.newBuilder().host(landingHost).encodedPath(landingPath).query(null).fragment(null).build().toString()
    } else address.toString()
}

private fun HttpUrl.publicHttps() = isHttps && port == 443 && username.isEmpty() && password.isEmpty()

private fun HttpUrl.buzzTransferHost(): Boolean =
    publicHttps() && (host in buzzHosts || host.endsWith(".buzzheavier.com"))

internal fun normalizeJaWorkPath(raw: String): String? {
    val path = Normalizer.normalize(raw.replace('\\', '/'), Normalizer.Form.NFC)
    if (path.startsWith('/') || path.length > 2048) return null
    return path.takeIf {
        it.split('/').all { part ->
            part.isNotBlank() && part.length <= 255 && part != "." && part != ".." &&
                !part.endsWith('.') && !part.endsWith(' ') && !reservedNames.matches(part) &&
                part.none { char -> char.isISOControl() || char in ":*?\"<>|" }
        }
    }
}

internal fun jaWorkPathKey(path: String): String = path.lowercase(Locale.ROOT)

internal fun jaDownloadEntry(post: JaPostRef, article: JaArticle?): List<JaWorkEntry> =
    article?.downloadLinks.orEmpty().mapNotNull { address ->
        val url = address.toHttpUrlOrNull()?.takeIf { isJaBrowserPage(address) } ?: return@mapNotNull null
        val work = url.queryParameter("f")
        if (work != null && !work.equals(post.rj, ignoreCase = true)) return@mapNotNull null
        val normalized = url.newBuilder().host("japaneseasmr.com").fragment(null).query(null)
        for (name in url.queryParameterNames.sorted()) {
            for (value in url.queryParameterValues(name)) {
                normalized.addQueryParameter(name, if (name == "f") value?.uppercase(Locale.ROOT) else value)
            }
        }
        JaWorkEntry(post, normalized.build().toString())
    }.distinctBy { it.address }.ifEmpty {
        listOf(JaWorkEntry(post, "https://japaneseasmr.com/dlc.php?f=${post.rj}"))
    }

internal fun hasJaDownloadLinks(url: String, html: String): Boolean =
    url.toHttpUrlOrNull()?.let { jaDownloadLinks(it, html).isNotEmpty() } == true

private fun jaDownloadLinks(address: HttpUrl, html: String): List<Pair<String, String>> {
    val page = Jsoup.parse(html, address.toString())
    val anchors = page.select("a[href], [data-url]").map { element ->
        val link = element.absUrl(if (element.hasAttr("href")) "href" else "data-url")
        link to element.text()
    }
    val refresh = page.select("meta[http-equiv=refresh]").mapNotNull {
        val target = refreshTarget.find(it.attr("content"))?.groupValues?.get(1)
            ?.trim()?.trim('"', '\'') ?: return@mapNotNull null
        address.resolve(target)?.toString()?.let { link -> link to "" }
    }
    val quoted = quotedDownloadUrl
        .findAll(html).map { it.groupValues[1].replace("&amp;", "&") to "" }.toList()
    return (anchors + refresh + quoted).filter { isJaDownloadRedirect(it.first) }.distinctBy { it.first }
}

private data class BuzzFilePage(
    val name: String,
    val download: HttpUrl,
    val sha256: String?,
    val size: Long?,
    val buttonPage: HttpUrl?,
)

private fun parseBuzzFilePage(url: HttpUrl, html: String, label: String, requireDownload: Boolean = true): BuzzFilePage? {
    val page = Jsoup.parse(html, url.toString())
    fun isDownload(candidate: HttpUrl) = candidate.buzzTransferHost() && buzzDownloadPath.matches(candidate.encodedPath)
    val button = page.select("[hx-get]").asSequence()
        .mapNotNull { url.resolve(it.attr("hx-get")) }
        .firstOrNull { it.host == url.host && isDownload(it) }
    val candidates = page.select("[href], [data-clipboard-text]").flatMap { element ->
        listOf("href", "data-clipboard-text").mapNotNull { attribute ->
            element.attr(attribute).takeIf(String::isNotBlank)?.let(url::resolve)
        }
    } + downloadTokenPath.findAll(html)
        .mapNotNull { url.resolve(it.value.replace("&amp;", "&")) }.toList()
    val download = button ?: candidates.filter(::isDownload)
        .maxByOrNull { if (it.queryParameter("t") != null) 1 else 0 } ?: if (requireDownload) return null else url
    val title = page.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.takeIf(String::isNotEmpty)
        ?: page.select("h1, h2").firstOrNull { it.text().contains('.') }?.text()
        ?: label.trim().takeIf { it.contains('.') }
        ?: page.title().substringBefore(" - Buzzheavier").trim().takeIf {
            it.isNotBlank() && !it.equals("Buzzheavier", ignoreCase = true)
        }
        ?: page.select("h1, h2").firstOrNull {
            it.text().isNotBlank() && !it.text().equals("Buzzheavier", ignoreCase = true)
        }?.text()
        ?: return null
    val hash = sha256Label.find(page.text())?.groupValues?.get(1)?.lowercase()
        ?: page.select("code").mapNotNull { hashPattern.matchEntire(it.text().trim())?.value }.singleOrNull()?.lowercase()
    return BuzzFilePage(title, download, hash, page.selectFirst("[data-size]")?.attr("data-size")?.toLongOrNull(),
        buttonPage = url.takeIf { button != null })
}

internal fun hasBuzzDownloadFile(url: String, html: String): Boolean =
    url.toHttpUrlOrNull()?.takeIf { isBuzzBrowserPage(url) }?.let { parseBuzzFilePage(it, html, "") != null } == true

/** 托管页里 Download File 按钮请求的地址；XHR 失败时改为主框架加载。 */
internal fun buzzDownloadEndpoint(url: String, html: String): String? =
    url.toHttpUrlOrNull()?.takeIf { isBuzzBrowserPage(url) }
        ?.let { parseBuzzFilePage(it, html, "")?.download?.toString() }

/** 页面「Copy download link」提供的带当前令牌的下载地址。 */
internal fun buzzCopyDownloadLink(url: String, html: String): String? {
    val base = url.toHttpUrlOrNull()?.takeIf { isBuzzBrowserPage(url) } ?: return null
    val page = Jsoup.parse(html, url)
    fun value(raw: String): HttpUrl? = base.resolve(raw)?.takeIf {
        it.buzzTransferHost() && buzzDownloadPath.matches(it.encodedPath)
    }
    val clipboard = page.select("[data-clipboard-text], [data-url]").mapNotNull { element ->
        value(element.attr("data-clipboard-text").ifBlank { element.attr("data-url") })
    }
    val anchors = page.select("a[href]").filter { it.text().contains("copy", ignoreCase = true) }
        .mapNotNull { value(it.attr("href")) }
    val quoted = quotedRelativeDownload
        .find(html)?.groupValues?.get(1)?.let(::value)
    return (clipboard + anchors + listOfNotNull(quoted)).sortedByDescending { it.queryParameter("t") != null }
        .firstOrNull()?.toString()
}

internal class JaWorkClient(
    http: OkHttpClient = JapaneseAsmrClient.newWorkClient(),
    private val sitePages: Mutex = Mutex(),
    private val cookieProvider: ((HttpUrl) -> String?)? = null,
) {
    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .cookieJar(okhttp3.CookieJar.NO_COOKIES).build()
    private val megaSeq = AtomicLong(1)

    suspend fun manifest(
        entry: JaWorkEntry,
        visible: JaVisibleDownload? = null,
        verifiedBuzzPages: Map<String, JaVisibleDownload> = emptyMap(),
        onVerifyBuzz: (suspend (JaBuzzVerification) -> Map<String, JaVisibleDownload>)? = null,
    ): JaWorkManifest {
        val first = visible ?: sitePages.withLock { entryPage(entry) }
        val address = first.url.toHttpUrlOrNull() ?: unavailable()
        if (!isJaBrowserPage(first.url) && !isJaDownloadRedirect(first.url)) unavailable()
        if (isJaBrowserPage(first.url) && address.queryParameter("f")?.let { !it.equals(entry.post.rj, ignoreCase = true) } == true) {
            throw JaWorkException(TextRef.Res(R.string.ja_error_entry_wrong_work))
        }
        if (isJaBrowserPage(first.url) && ("/cdn-cgi/challenge-platform/" in first.html || "<title>Just a moment" in first.html)) {
            throw JaDownloadVerification(entry.address)
        }
        val links = if (address.host in buzzHosts || address.host in megaHosts) {
            listOf(first.url to "")
        } else {
            jaDownloadLinks(address, first.html)
        }
        if (links.isEmpty()) unavailable()
        val accepted = linkedMapOf<String, JaRemoteFile>()
        val directories = mutableSetOf<String>()
        fun parentPaths(key: String) = generateSequence(key.substringBeforeLast('/', "")) { it.substringBeforeLast('/', "") }
            .takeWhile { it.isNotEmpty() }
        val confirmed = verifiedBuzzPages.toMutableMap()
        var skipped = 0
        for ((index, item) in links.withIndex()) {
            val (link, label) = item
            val url = link.toHttpUrlOrNull() ?: continue
            fun verifyBuzz(landing: HttpUrl): Nothing {
                val pages = listOf(JaBuzzPage(link, buzzLandingUrl(landing))) +
                    links.drop(index + 1).mapNotNull { (next, _) ->
                        next.toHttpUrlOrNull()?.takeIf { it.buzzTransferHost() && next !in confirmed }
                            ?.let { JaBuzzPage(next, buzzLandingUrl(it)) }
                    }
                throw JaBuzzVerification(entry, first, pages, fileLink = link)
            }
            val files = if (url.host in megaHosts) {
                val resolved = mega(url)
                skipped += resolved.skipped
                resolved.files
            } else {
                var attempt = 0
                var result: JaRemoteFile? = null
                while (result == null) {
                    try {
                        result = buzz(url, link, label, confirmed[link], ::verifyBuzz)
                    } catch (verify: JaBuzzVerification) {
                        if (++attempt > 3) {
                            throw JaWorkException(TextRef.Res(R.string.ja_error_verified_address_missing))
                        }
                        val pages = onVerifyBuzz?.invoke(verify) ?: throw verify
                        confirmed.putAll(pages)
                    }
                }
                listOf(result)
            }
            for (file in files) {
                val path = normalizeJaWorkPath(file.relativePath)
                val key = path?.let(::jaWorkPathKey)
                if (key == null || key in accepted || key in directories || parentPaths(key).any { it in accepted }) {
                    skipped++
                    continue
                }
                accepted[key] = file.copy(relativePath = requireNotNull(path))
                directories.addAll(parentPaths(key))
            }
        }
        if (accepted.isEmpty()) unavailable()
        return JaWorkManifest(
            entry, accepted.values.toList(), skipped,
            fromVerifiedPage = visible != null || confirmed.isNotEmpty(),
            sourcePage = first, verifiedBuzzPages = confirmed.toMap(),
        )
    }

    private suspend fun entryPage(entry: JaWorkEntry): JaVisibleDownload {
        var url = entry.address.toHttpUrlOrNull() ?: unavailable()
        repeat(6) {
            if (isJaDownloadRedirect(url.toString())) return JaVisibleDownload(url.toString(), "")
            if (!isJaBrowserPage(url.toString())) unavailable()
            val page = http.newCall(request(url)).withResponse { response ->
                val redirected = response.header("Location")?.let(url::resolve)
                if (response.code in redirectCodes && redirected != null) return@withResponse redirected to null
                val html = response.peekBody(MAX_PAGE + 1).string()
                if (response.isChallenge(html) || response.code == 403) {
                    throw JaDownloadVerification(entry.address)
                }
                if (!response.isSuccessful || html.length > MAX_PAGE) unavailable()
                null to JaVisibleDownload(url.toString(), html)
            }
            page.second?.let { return it }
            url = page.first ?: unavailable()
        }
        unavailable()
    }

    private suspend fun buzz(
        start: HttpUrl,
        sourceLink: String,
        label: String,
        visible: JaVisibleDownload? = null,
        onVerify: ((HttpUrl) -> Nothing)? = null,
    ): JaRemoteFile {
        val (url, html) = if (visible != null) {
            val address = visible.url.toHttpUrlOrNull()?.takeIf { isBuzzBrowserPage(visible.url) } ?: unavailable()
            address to visible.html
        } else {
            buzzRequest(start, headers = emptyMap()) { response ->
                checkBuzzResponse(response, R.string.ja_stage_hosting_page, onVerify?.let { verify -> { verify(response.request.url) } })
                val html = response.peekBody(MAX_PAGE + 1).string()
                if (html.length > MAX_PAGE) unavailable()
                response.request.url to html
            }
        }
        val userAgent = visible?.userAgent
        // 浏览器已确认返回音频/文件的地址，优先使用，即使它是带 t 的下载端点。
        val fileUrl = visible?.fileUrl?.toHttpUrlOrNull()?.takeIf { it.buzzTransferHost() }
        // 网页里点过「Download File」后拿到的最终地址。直接用它可绕开需要网页会话的中间端点。
        val direct = visible?.direct?.toHttpUrlOrNull()
            ?.takeIf { it.buzzTransferHost() && !buzzDownloadPath.matches(it.encodedPath) }
        // 「Copy download link」通常带当前令牌，比不带 t 的按钮地址更容易直接通过。
        val copy = visible?.copyLink?.toHttpUrlOrNull()?.takeIf { it.buzzTransferHost() }
        val file = parseBuzzFilePage(url, html, label,
            requireDownload = fileUrl == null && direct == null && copy == null)
            ?: throw JaWorkException(TextRef.Res(R.string.ja_error_buzz_link_missing))
        val download = fileUrl ?: direct ?: copy ?: file.download
        val buttonPage = when {
            fileUrl != null -> null
            direct != null -> null
            copy != null -> url
            else -> file.buttonPage
        }
        return buzzRequest(download, if (fileUrl != null) emptyMap() else mapOf("Range" to "bytes=0-4095"),
            buttonPage, userAgent) { response ->
            checkBuzzFileResponse(response, onVerify?.let { verify -> { verify(url) } })
            val size = when (response.code) {
                206 -> contentRange(response)?.third
                200 -> response.body?.contentLength()?.takeIf { it >= 0 }
                else -> null
            } ?: file.size
            // 复制链接/按钮地址可能只能使用一次；传输改用探测后的最终地址，避免再次消耗令牌。
            val finalUrl = response.request.url
            JaRemoteFile(file.name, size, file.sha256, JaFileTransfer.Buzz(finalUrl,
                response.header("ETag")?.takeUnless { it.startsWith("W/") }, response.header("Last-Modified"),
                buttonPage?.takeIf { it.host == finalUrl.host }, userAgent,
                verificationPage = JaBuzzPage(sourceLink, buzzLandingUrl(url))))
        }
    }

    private suspend fun mega(url: HttpUrl): JaMegaResolved = when (val share = JaMegaShare.parse(url)) {
        is JaMegaShare.SingleFile -> JaMegaResolved(listOf(share.resolve(megaApi(share.source::downloadRequest))))
        is JaMegaShare.Folder -> share.resolveTree(megaApi(share::request, MAX_MEGA_FOLDER_JSON))
    }

    private suspend fun megaApi(request: (Long) -> Request, limit: Long = MAX_PAGE): JsonObject {
        val context = currentCoroutineContext()
        for (attempt in 0..2) {
            try {
                return http.newCall(request(megaSeq.getAndIncrement())).withResponse { response ->
                    checkMegaResponse(response)
                    val bytes = response.peekBody(limit + 1).bytes()
                    if (bytes.size > limit) unavailable()
                    parseMegaResponse(bytes.toString(Charsets.UTF_8))
                }
            } catch (failure: IOException) {
                context.ensureActive()
                if (attempt == 2 || !retryableMega(failure)) throw failure
                delay(megaRetryDelay(attempt))
            }
        }
        error("MEGA API retry loop exited without a result")
    }

    suspend fun download(file: JaRemoteFile, destination: File, report: (Long) -> Unit) {
        val context = currentCoroutineContext()
        val transfer = file.transfer
        val version = when (transfer) {
            is JaFileTransfer.Buzz -> file.sha256 ?: transfer.etag?.let(::jaHash)
            is JaFileTransfer.Mega -> transfer.crypto.checksum
        }
        val reuseToken = version?.let { "${file.size}:$it" }
        val token = File(destination.parentFile, ".version")
        if (reuseToken == null || !token.isFile || token.readText() != reuseToken) destination.delete()
        if (reuseToken != null) token.writeText(reuseToken)
        if (transfer is JaFileTransfer.Mega) {
            context.ensureActive()
            if (destination.isFile && destination.length() == file.size && verify(file, destination)) {
                report(destination.length())
                return
            }
            if (destination.length() == requireNotNull(file.size)) destination.delete()
            transferMega(file, transfer, destination, report)
            if (verify(file, destination)) return
            destination.delete()
            throw JaWorkException(TextRef.Res(R.string.ja_error_file_integrity))
        }
        var verified = false
        try {
            for (attempt in 0..2) {
                context.ensureActive()
                if (destination.isFile && destination.length() == file.size && verify(file, destination)) {
                    verified = true
                    report(destination.length())
                    return
                }
                if (reuseToken == null || file.size == null || destination.length() >= file.size) destination.delete()
                try {
                    transferBuzz(file, transfer as JaFileTransfer.Buzz, destination, report)
                    if (verify(file, destination)) {
                        verified = true
                        return
                    }
                    destination.delete()
                    throw JaWorkException(TextRef.Res(R.string.ja_error_file_integrity))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: JaWorkException) {
                    if (error is JaTransferVerification || error is JaWorkAddressExpired || error is JaWorkResponseIsPage || attempt == 2) {
                        throw error
                    }
                } catch (error: JapaneseAsmrException) {
                    throw error
                } catch (error: IOException) {
                    context.ensureActive()
                    if (attempt == 2) throw error
                }
            }
        } finally {
            if (!verified && reuseToken == null && file.size != destination.length()) destination.delete()
        }
    }

    private suspend fun transferBuzz(file: JaRemoteFile, transfer: JaFileTransfer.Buzz, out: File, report: (Long) -> Unit) {
        val offset = out.length()
        val headers = if (offset > 0) buildMap {
            put("Range", "bytes=$offset-")
            (transfer.etag ?: transfer.lastModified)?.let { put("If-Range", it) }
        } else emptyMap()
        buzzRequest(transfer.address, headers, transfer.buttonPage, transfer.userAgent) { response ->
            if (response.code == 416) {
                out.delete()
                throw JaWorkException(TextRef.Res(R.string.ja_error_buzz_length_changed))
            }
            checkBuzzFileResponse(response, transfer.verificationPage?.let { page ->
                { throw JaTransferVerification(page) }
            })
            val range = contentRange(response)
            val validRange = range != null && range.first == offset && range.second == range.third - 1 &&
                (file.size == null || file.size == range.third)
            val validatorChanged = (transfer.etag != null && transfer.etag != response.header("ETag")) ||
                (transfer.etag == null && transfer.lastModified != null && transfer.lastModified != response.header("Last-Modified"))
            if (response.code == 206 && (!validRange || validatorChanged)) {
                out.delete()
                throw JaWorkException(TextRef.Res(R.string.ja_error_buzz_file_changed))
            }
            val append = offset > 0 && response.code == 206
            if (response.code != 200 && response.code != 206) unavailable()
            copy(response, out, if (append) offset else 0, file.size, report)
        }
    }

    internal suspend fun transferMega(file: JaRemoteFile, transfer: JaFileTransfer.Mega, out: File, report: (Long) -> Unit) {
        val size = requireNotNull(file.size)
        val context = currentCoroutineContext()
        var activeAddress = transfer.initialAddress
        var refreshed = false
        var failures = 0
        while (true) {
            context.ensureActive()
            val offset = out.length()
            if (offset == size) {
                if (size == 0L) out.outputStream().close()
                return
            }
            if (offset > size) {
                out.delete()
                throw JaWorkException(TextRef.Res(R.string.ja_error_file_length_check))
            }
            // Resolve failures leave this loop with their original error and retry budget.
            val address = activeAddress ?: resolveMegaAddress(megaApi(transfer.source::downloadRequest), size)
            activeAddress = address
            val endpoint = address.newBuilder().addPathSegment("$offset-${size - 1}").build()
            try {
                val expired = http.newCall(request(endpoint)).withResponse { response ->
                    if (!response.isChallenge() && response.code in setOf(403, 404, 410)) return@withResponse true
                    checkMegaResponse(response)
                    if (response.code != 200) unavailable()
                    copy(response, out, offset, size, report, transfer.crypto.cipherAt(offset))
                    false
                }
                if (!expired) return
            } catch (failure: IOException) {
                context.ensureActive()
                val incomplete = failure is JaWorkException &&
                    failure.userText == TextRef.Res(R.string.ja_error_file_incomplete)
                if (failures == 2 || (!incomplete && !retryableMega(failure))) throw failure
                delay(megaRetryDelay(failures++))
                continue
            }
            if (refreshed) {
                throw JaMegaManifestInvalidException(TextRef.Res(R.string.ja_error_download_address_expired))
            }
            activeAddress = resolveMegaAddress(megaApi(transfer.source::downloadRequest), size)
            refreshed = true
        }
    }

    private fun retryableMega(failure: IOException): Boolean = when (failure) {
        is JaMegaTransientException -> true
        is JaWorkException, is JapaneseAsmrException -> false
        else -> true
    }

    private fun megaRetryDelay(attempt: Int): Long = 1_000L shl attempt

    internal suspend fun copy(response: Response, out: File, offset: Long, size: Long?, report: (Long) -> Unit,
        cipher: javax.crypto.Cipher? = null) {
        fun lengthError(message: Int): JaWorkException {
            val text = TextRef.Res(message)
            return if (cipher != null) JaMegaManifestInvalidException(text) else JaWorkException(text)
        }
        val context = currentCoroutineContext()
        val body = response.body ?: unavailable()
        val expected = size?.minus(offset)
        if (expected != null && body.contentLength() >= 0 && expected != body.contentLength()) {
            out.delete()
            throw lengthError(R.string.ja_error_file_length_changed)
        }
        var bytes = offset
        java.io.FileOutputStream(out, offset != 0L).buffered().use { output ->
            body.byteStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    context.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    bytes += count
                    if (size != null && bytes > size) throw lengthError(R.string.ja_error_file_length_check)
                    if (cipher == null) output.write(buffer, 0, count)
                    else cipher.update(buffer, 0, count)?.let(output::write)
                    report(bytes)
                }
                cipher?.doFinal()?.let(output::write)
            }
        }
        if (size != null && bytes != size) throw JaWorkException(TextRef.Res(R.string.ja_error_file_incomplete))
    }

    private suspend fun verify(file: JaRemoteFile, path: File): Boolean {
        if (!path.isFile || (file.size != null && path.length() != file.size)) return false
        val mega = file.transfer as? JaFileTransfer.Mega
        if (mega != null && !mega.crypto.verify(path)) return false
        return file.sha256 == null || jaFileSha256(path) == file.sha256
    }

    private suspend fun <T : Any> buzzRequest(
        initial: HttpUrl,
        headers: Map<String, String>,
        buttonPage: HttpUrl? = null,
        userAgent: String? = null,
        use: suspend (Response) -> T,
    ): T {
        var url = initial
        repeat(6) { hop ->
            if (!url.buzzTransferHost()) unavailable()
            val request = request(url, userAgent).newBuilder().apply {
                if (hop == 0 && buttonPage != null && buttonPage.host == url.host) {
                    val page = buttonPage.newBuilder().fragment(null).build().toString()
                    header("HX-Request", "true")
                    header("HX-Current-URL", page)
                    header("Referer", page)
                    header("Origin", "https://${url.host}")
                    header("Sec-Fetch-Dest", "empty")
                    header("Sec-Fetch-Mode", "cors")
                    header("Sec-Fetch-Site", "same-origin")
                } else {
                    headers.forEach { (name, value) -> header(name, value) }
                }
            }.build()
            val (next, result) = http.newCall(request).withResponse { response ->
                val target = when {
                    response.code in redirectCodes -> response.header("Location")
                    response.isSuccessful -> response.header("HX-Redirect")
                    else -> null
                }
                if (target != null) {
                    (url.resolve(target) ?: unavailable()) to null
                } else null to use(response)
            }
            if (next == null) return requireNotNull(result)
            url = next
        }
        unavailable()
    }

    private fun request(url: HttpUrl, userAgent: String? = null) = Request.Builder().url(url)
        .header("User-Agent", userAgent ?: JapaneseAsmrClient.USER_AGENT)
        .apply {
            if (url.buzzTransferHost()) {
                cookieProvider?.invoke(url)?.takeIf(String::isNotBlank)?.let { header("Cookie", it) }
            }
        }
        .header("Accept-Encoding", "identity").build()

    private fun checkBuzzFileResponse(response: Response, onVerify: (() -> Nothing)?) {
        val prefix = response.body?.source()?.let { source ->
            try {
                source.request(4096)
            } catch (_: IOException) {
                // Keep any buffered bytes available to the transfer, which handles interrupted bodies.
            }
            source.buffer.copy().readUtf8(minOf(source.buffer.size, 4096))
        }.orEmpty().trimStart { it.isWhitespace() || it == '\uFEFF' }
        val type = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
        val html = type == "text/html" || type == "application/xhtml+xml" || htmlStart.containsMatchIn(prefix)
        if (response.isChallenge(prefix) ||
            (html && ("/cdn-cgi/challenge-platform/" in prefix || "Just a moment" in prefix && "cf-chl" in prefix))
        ) {
            onVerify?.invoke()
            throw JaWorkAddressExpired(TextRef.Res(R.string.ja_work_buzz_verify,
                listOf(TextRef.Res(R.string.ja_stage_file_download))), sessionRequired = true)
        }
        checkBuzzResponse(response, R.string.ja_stage_file_download, onVerify)
        if (html) throw JaWorkResponseIsPage()
    }

    private fun checkBuzzResponse(response: Response, @StringRes stageRes: Int, onVerify: (() -> Nothing)? = null) {
        if (response.isChallenge() || (response.code == 403 && response.isChallenge(response.peekBody(32_768).string()))) {
            onVerify?.invoke()
            throw JaWorkAddressExpired(TextRef.Res(R.string.ja_work_buzz_verify, listOf(TextRef.Res(stageRes))), sessionRequired = true)
        }
        if (response.code == 401 || response.code == 403) {
            throw JaWorkException(TextRef.Res(R.string.ja_work_buzz_refused, listOf(TextRef.Res(stageRes))))
        }
        if (response.code == 503) {
            throw JaWorkException(TextRef.Res(R.string.ja_work_buzz_unavailable, listOf(TextRef.Res(stageRes))))
        }
        checkDownloadResponse(response)
    }

    private fun checkMegaResponse(response: Response) {
        if (response.code == 401 || response.isChallenge()) {
            throw JaMegaFatalException(TextRef.Res(R.string.ja_error_mega_use_browser))
        }
        when (response.code) {
            403 -> throw JaMegaFatalException(TextRef.Res(R.string.ja_error_mega_use_browser))
            404, 410 -> throw JaMegaManifestInvalidException(TextRef.Res(R.string.ja_error_mega_share_expired))
            429, 503 -> throw JaMegaTransientException(TextRef.Res(R.string.ja_error_mega_busy))
            509 -> throw JaMegaFatalException(TextRef.Res(R.string.ja_error_mega_quota))
        }
        if (!response.isSuccessful) unavailable()
    }

    private fun checkDownloadResponse(response: Response) {
        if (response.code in setOf(404, 410)) throw JaWorkAddressExpired(TextRef.Res(R.string.ja_error_download_address_expired))
        if (!response.isSuccessful) unavailable()
    }

    private fun contentRange(response: Response): Triple<Long, Long, Long>? {
        val range = com.nanahoshi.audioplayer.data.HttpByteRange.parse(response.header("Content-Range"))
            ?: return null
        return Triple(range.start, range.end, range.total)
    }

    private fun unavailable(): Nothing = throw JaWorkException(TextRef.Res(R.string.ja_error_download_unavailable))
}

internal suspend fun jaFileSha256(file: File): String {
    val context = currentCoroutineContext()
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            context.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().toHex()
}
