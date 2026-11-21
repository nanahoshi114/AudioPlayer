package com.nanahoshi.audioplayer.dlsite

import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.data.TaskReporter
import com.nanahoshi.audioplayer.data.ProgressPhase
import com.nanahoshi.audioplayer.data.ProgressNetworkEvents
import com.nanahoshi.audioplayer.data.taskProgress
import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.UserFacingError

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Messages deliberately omit request URLs, cookies and server error bodies. */
enum class DlsiteArchiveFailureKind { INTERRUPTED, INTEGRITY, UNAVAILABLE, OTHER }

open class DlsiteArchiveException(
    override val userText: TextRef,
    internal val archiveKind: DlsiteArchiveFailureKind = DlsiteArchiveFailureKind.OTHER,
    cause: Throwable? = null,
) : IOException(userText.debugString(), cause), UserFacingError {
    internal val unavailable get() = archiveKind == DlsiteArchiveFailureKind.UNAVAILABLE

    constructor(message: String, unavailable: Boolean = false) : this(
        TextRef.Raw(message),
        if (unavailable) DlsiteArchiveFailureKind.UNAVAILABLE else DlsiteArchiveFailureKind.OTHER,
    )
}

/** Only integrity failures justify discarding the downloaded package before an explicit retry. */
class DlsiteArchiveIntegrityException(text: TextRef) :
    DlsiteArchiveException(text, DlsiteArchiveFailureKind.INTEGRITY) {
    constructor(message: String) : this(TextRef.Raw(message))
}

/** Original packages use the store session, independently of Play's signed optimized files. */
class DlsiteArchiveClient internal constructor(
    private val session: () -> String?,
    private val transport: OkHttpClient,
) {
    constructor(session: () -> String?) : this(session, OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build())

    suspend fun download(
        workno: String,
        stagingDir: File,
        taskProgress: TaskReporter? = null,
        onProgress: (Long, Long?) -> Unit = { _, _ -> },
        checkCancelled: () -> Unit = {},
    ): List<File> = coroutineScope {
        val calls = ConcurrentHashMap.newKeySet<Call>()
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { calls.forEach { it.cancel() } }
        }
        try {
            withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                val originalSession = mergeDlsiteCookies(session())
                if (originalSession.isBlank()) throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_error_login_required))
                val checked = {
                    context.ensureActive()
                    checkCancelled()
                    if (mergeDlsiteCookies(session()) != originalSession) {
                        throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_account_changed))
                    }
                }
                val id = workno.trim().uppercase()
                if (!Regex("(?:RJ|BJ|VJ)[0-9]+").matches(id)) {
                    throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_invalid_work_id))
                }
                if ((!stagingDir.isDirectory && !stagingDir.mkdirs()) || stagingDir.isFile) {
                    throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_create_directory))
                }
                val jar = ArchiveCookies(originalSession)
                val client = transport.newBuilder().eventListenerFactory(ProgressNetworkEvents.factory).followRedirects(false)
                    .followSslRedirects(false).cookieJar(jar).build()
                val access = Access(client, calls, checked, taskProgress)
                val files = mutableListOf<File>()
                val names = mutableSetOf<String>()
                var complete = 0L
                var resolved = false
                var foundPackage = false
                for (candidate in archiveWorknoCandidates(id)) {
                    checked()
                    val root = "https://play.dlsite.com/api/v3/download".toHttpUrl().newBuilder()
                        .addQueryParameter("workno", candidate).build()
                    try {
                        access.resolve(root, candidate, consume = { part ->
                            foundPackage = true
                            if (!names.add(part.name)) {
                                throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_duplicate_files))
                            }
                            taskProgress?.volume(files.size + 1)
                            val file = access.downloadPart(part, stagingDir) { bytes ->
                                taskProgress?.cumulative(complete + bytes)
                                onProgress(complete + bytes, null)
                            }
                            complete += file.length()
                            files += file
                            onProgress(complete, null)
                        })
                        resolved = true
                        break
                    } catch (error: DlsiteArchiveException) {
                        // Only an explicit absence permits trying another spelling. Authentication,
                        // challenge and transport failures must not be disguised as missing works.
                        if (!error.unavailable || foundPackage) throw error
                    }
                }
                if (!resolved || files.isEmpty()) {
                    throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_unavailable))
                }
                onProgress(complete, complete)
                files
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: DlsiteArchiveException) {
            throw error
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_network))
        } finally {
            cancellation.cancel()
        }
    }

    private data class Package(val request: HttpUrl, val name: String, val size: Long?)
    private data class Resolution(val part: Package? = null, val links: List<HttpUrl> = emptyList())

    private class Access(
        private val client: OkHttpClient,
        private val calls: MutableSet<Call>,
        private val checked: () -> Unit,
        private val progress: TaskReporter?,
    ) {
        private fun <T> request(start: HttpUrl, range: String? = null, action: (Response) -> T): T {
            var url = start
            repeat(MAX_REDIRECTS + 1) {
                checked()
                validateUrl(url)
                val builder = Request.Builder().url(url).taskProgress(progress.takeIf { range != null })
                    .header("User-Agent", ARCHIVE_USER_AGENT)
                    .header("Referer", DlsiteClient.PLAY_REFERER)
                    .header("Accept-Encoding", "identity")
                if (range != null) builder.header("Range", range)
                val call = client.newCall(builder.build())
                calls.add(call)
                try {
                    call.execute().use { response ->
                        checked()
                        if (response.code in 300..399) {
                            val target = response.header("Location")?.let(url::resolve)
                                ?: throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_redirect))
                            validateUrl(target)
                            url = target
                        } else {
                            when (response.code) {
                                401 -> throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_error_login_expired))
                                403 -> throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_forbidden))
                                404 -> throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_unavailable), DlsiteArchiveFailureKind.UNAVAILABLE)
                            }
                            if (!response.isSuccessful) {
                                throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_server_denied))
                            }
                            return action(response)
                        }
                    }
                } finally { calls.remove(call) }
            }
            throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_redirect_limit))
        }

        /** Control pages require an ordinary GET. Only resolved file transfers use Range. */
        fun resolve(
            root: HttpUrl,
            workno: String,
            depth: Int = 0,
            visited: MutableSet<HttpUrl> = mutableSetOf(),
            consume: (Package) -> Unit,
        ) {
            if (depth > MAX_REDIRECTS || visited.size >= 1024 || !visited.add(root)) {
                throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_page_loop))
            }
            progress?.phase(ProgressPhase.RESOLVING)
            val resolution = request(root) { response ->
                val name = archiveName(response)
                if (!isHtml(response) && name != null) {
                    safePackageName(name)
                    val size = if (response.code == 206) {
                        parseContentRange(response.header("Content-Range"))?.third
                            ?: throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_invalid_size))
                    } else response.body?.contentLength()?.takeIf { it >= 0 }
                    // Keep the resolved temporary URL only in memory; don't send Range through
                    // the Play/store redirect chain again for probing file metadata.
                    return@request Resolution(part = Package(response.request.url, name, size))
                }
                val body = response.body ?: throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_empty_page))
                val bytes = body.byteStream().use { it.readBounded(PAGE_LIMIT, checked) }
                val html = bytes.toString(Charsets.UTF_8)
                val links = parseDownloadLinks(response.request.url, html, workno)
                if (links.isEmpty()) throw DlsiteArchiveException(
                    if ("filelist" in response.request.url.pathSegments) TextRef.Res(R.string.dlsite_archive_error_missing_link)
                    else downloadPageError(html),
                )
                Resolution(links = links)
            }
            // A numbered store request can replace the current file authorization. Consume each
            // resolved volume before visiting the next link, rather than prefetching every URL.
            // The metadata response is closed before starting the actual file transfer.
            val part = resolution.part
            if (part != null) consume(part)
            else resolution.links.forEach { resolve(it, workno, depth + 1, visited, consume) }
        }

        fun downloadPart(part: Package, staging: File, progress: (Long) -> Unit): File {
            val target = File(staging, part.name)
            val partial = File(staging, part.name + ".part")
            val completeMarker = File(staging, part.name + ".complete")
            val recordedSize = completeMarker.takeIf { it.isFile }?.let {
                runCatching { if (it.length() < 32) it.readText().trim().toLongOrNull() else null }.getOrNull()
            }
            if (target.isFile && target.length() > 0 &&
                ((part.size != null && target.length() == part.size) ||
                    (part.size == null && recordedSize == target.length()))) {
                progress(target.length())
                return target
            }
            // Only this job's download staging files are touched. Unmarked unknown-length targets
            // cannot be trusted as complete (e.g. a process death between rename and marker write).
            if (target.exists() && !target.delete()) throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_resume))
            if (completeMarker.exists() && !completeMarker.delete()) throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_resume))
            var failure: IOException? = null
            repeat(3) { attempt ->
                if (attempt > 0) this.progress?.retry()
                this.progress?.phase(ProgressPhase.REQUESTING)
                checked()
                try {
                    var offset = partial.takeIf { it.isFile }?.length() ?: 0L
                    if (part.size != null && offset > part.size) {
                        if (!partial.delete()) throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_resume))
                        offset = 0
                    }
                    if (part.size == null || offset != part.size) {
                        request(part.request, "bytes=$offset-") { response ->
                            if (isHtml(response)) throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_html_login))
                            val responseName = archiveName(response)
                            if (responseName != null && responseName != part.name) {
                                throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_incomplete_response))
                            }
                            val length: Long?
                            val append: Boolean
                            if (response.code == 206) {
                                val range = parseContentRange(response.header("Content-Range"))
                                    ?: throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_invalid_range))
                                if (range.first != offset) throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_wrong_range))
                                if (part.size != null && range.third != part.size) {
                                    throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_changed))
                                }
                                length = range.third
                                append = offset > 0
                            } else {
                                // A server ignoring Range requires restarting, never appending a full response.
                                offset = 0
                                length = response.body?.contentLength()?.takeIf { it >= 0 }
                                append = false
                            }
                            val body = response.body ?: throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_empty_body))
                            val transfer = this.progress?.phase(ProgressPhase.DOWNLOADING, force = true)
                            transfer?.bytes(0, offset, length)
                            body.byteStream().use { input ->
                                partial.outputStream(append).use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    var first = true
                                    while (true) {
                                        checked()
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        if (first && offset == 0L && looksLikeHtml(buffer, count)) {
                                            throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_html_response))
                                        }
                                        first = false
                                        output.write(buffer, 0, count)
                                        offset += count
                                        transfer?.bytes(count.toLong(), offset, length)
                                        if (length != null && offset > length) {
                                            throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_integrity))
                                        }
                                        progress(offset)
                                    }
                                }
                            }
                            if ((length != null && offset != length) || (part.size != null && offset != part.size)) {
                                throw IOException("Incomplete archive")
                            }
                        }
                    }
                    if (!partial.isFile || partial.length() == 0L) throw IOException("Empty archive")
                    if (!partial.renameTo(target)) throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_save))
                    completeMarker.writeText(target.length().toString())
                    return target
                } catch (error: DlsiteArchiveException) {
                    throw error
                } catch (error: IOException) {
                    checked()
                    failure = error
                }
            }
            // Keep the partial file for an explicit retry; never expose the transport's URL-bearing message.
            if (failure != null) throw DlsiteArchiveException(
                TextRef.Res(R.string.dlsite_archive_error_interrupted), DlsiteArchiveFailureKind.INTERRUPTED,
            )
            throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_failed))
        }
    }

    private class ArchiveCookies(session: String) : CookieJar {
        private val cookies = mutableListOf<Cookie>()
        init {
            // The stored header has no domain information. Seed only the two first-party endpoints.
            for (host in listOf("play.dlsite.com", "www.dlsite.com")) {
                for (part in session.split(';')) {
                    val name = part.substringBefore('=').trim()
                    val value = part.substringAfter('=', "").trim()
                    if (name.isBlank() || !part.contains('=')) continue
                    runCatching { Cookie.Builder().name(name).value(value).hostOnlyDomain(host).secure().build() }
                        .getOrNull()?.let(cookies::add)
                }
            }
        }
        @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.filterNot { isCloudflareCookie(it.name) }.forEach { cookie ->
                this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                this.cookies.add(cookie)
            }
        }
        @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
            cookies.removeAll { it.expiresAt < System.currentTimeMillis() }
            return cookies.filter { it.matches(url) }
        }
    }

    companion object {
        // The mobile agent sends both single and split downloads to /download/filelist/.
        // Original packages require the desktop flow; keep Play/login's agent independent.
        internal const val ARCHIVE_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        private const val MAX_REDIRECTS = 8
        private const val PAGE_LIMIT = 512 * 1024

        internal fun validateUrl(url: HttpUrl) {
            val domain = url.host == "dlsite.com" || url.host.endsWith(".dlsite.com")
            if (url.host == "login.dlsite.com" || url.encodedPath.contains("/login")) {
                throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_error_login_expired))
            }
            if (url.scheme != "https" || url.port != 443 || !domain || url.username.isNotBlank() || url.password.isNotBlank()) {
                throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_invalid_url))
            }
        }

        /** Restore the store's six/eight-digit spelling after library identity normalization. */
        internal fun archiveWorknoCandidates(workno: String): List<String> {
            val id = workno.trim().uppercase()
            val match = Regex("(RJ|BJ|VJ)([0-9]+)").matchEntire(id)
            val preferred = match?.let {
                val digits = it.groupValues[2]
                val width = if (digits.length <= 6) 6 else 8
                it.groupValues[1] + digits.padStart(width, '0')
            } ?: id
            return (listOf(preferred) + DlsiteClient.worknoCandidates(id)).distinct()
                .filter { it.length - 2 == 6 || it.length - 2 >= 8 }
        }

        internal fun parseDownloadLinks(page: HttpUrl, html: String, workno: String): List<HttpUrl> {
            // Official pages may put a URL in href, data attributes or an inline onclick.
            // Read quoted URL values without evaluating any of the page's JavaScript.
            val values = listOf('"', '\'').asSequence().flatMap { quote ->
                Regex("$quote([^$quote<>\\r\\n]+)$quote").findAll(html).map { it.groupValues[1] }
            }
            val links = values.mapNotNull { value ->
                val raw = decodeLink(value).replace("\\/", "/")
                val url = page.resolve(raw) ?: return@mapNotNull null
                val segments = url.pathSegments
                val id = segments.indexOf("product_id").takeIf { it >= 0 }?.let { segments.getOrNull(it + 1) }
                    ?.substringBefore('.')
                val site = segments.firstOrNull()
                val downloadPage = site in setOf("home", "maniax", "girls", "books", "soft", "pro", "appx") &&
                    segments.getOrNull(1) == "download" && segments.getOrNull(2) in setOf("=", "split", "serial")
                if (url.scheme != "https" || url.port != 443 || url.host != "www.dlsite.com" ||
                    !downloadPage || url.username.isNotBlank() || url.password.isNotBlank() || sameDownloadPage(url, page) ||
                    id == null || DlsiteClient.canonicalWorkno(id) != DlsiteClient.canonicalWorkno(workno)) null else url
            }.distinct().toList()
            val parts = links.filter { isDirectDownload(it) && volumeNumber(it) != null }
            val segments = page.pathSegments
            val splitPage = segments.getOrNull(1) == "split" ||
                (segments.getOrNull(1) == "download" && segments.getOrNull(2) == "split")
            if (splitPage || parts.isNotEmpty()) {
                // Locale menus and page aliases aren't volumes. Repeated references to one
                // volume (e.g. an onclick plus a locale variant) must not download it twice.
                return parts.groupBy { volumeNumber(it)!! }.toSortedMap().values.map { variants ->
                    variants.minBy { it.querySize }
                }
            }
            // A serial-number page can link to a direct download, never to its language menu.
            return links.filter { isDirectDownload(it) && "number" !in it.pathSegments }
        }

        private fun isDirectDownload(url: HttpUrl): Boolean =
            url.pathSegments.getOrNull(1) == "download" && url.pathSegments.getOrNull(2) == "="

        private fun volumeNumber(url: HttpUrl): Int? {
            val segments = url.pathSegments
            val index = segments.indexOf("number")
            return if (index < 0) null else segments.getOrNull(index + 1)?.toIntOrNull()?.takeIf { it > 0 }
        }

        private fun sameDownloadPage(first: HttpUrl, second: HttpUrl): Boolean {
            fun identity(url: HttpUrl) = url.newBuilder().fragment(null)
                .encodedPath(url.encodedPath.trimEnd('/').ifEmpty { "/" })
                .removeAllQueryParameters("locale").build()
            return identity(first) == identity(second)
        }

        private fun decodeLink(value: String): String {
            val named = value.replace("&amp;", "&").replace("&quot;", "\"").replace("&apos;", "'")
            return Regex("&#(x[0-9a-fA-F]+|[0-9]+);").replace(named) { match ->
                val number = match.groupValues[1]
                val code = if (number.startsWith("x")) number.drop(1).toIntOrNull(16) else number.toIntOrNull()
                code?.takeIf { it in 1..0x10FFFF && it !in 0xD800..0xDFFF }
                    ?.let { String(Character.toChars(it)) } ?: match.value
            }
        }

        private fun downloadPageError(html: String): TextRef {
            val lower = html.lowercase()
            return when {
                lower.contains("cf-chl") || lower.contains("challenge-platform") ||
                    lower.contains("<title>just a moment") || lower.contains("<title>attention required") ->
                    TextRef.Res(R.string.dlsite_archive_error_verify)
                Regex("<input[^>]+(?:type|name)\\s*=\\s*[\"'](?:password|login_id)[\"']", RegexOption.IGNORE_CASE).containsMatchIn(html) ->
                    TextRef.Res(R.string.dlsite_archive_error_store_login)
                else -> TextRef.Res(R.string.dlsite_archive_error_missing_address)
            }
        }

        internal fun parseContentRange(raw: String?): Triple<Long, Long, Long>? {
            val match = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(raw.orEmpty()) ?: return null
            val (start, end, total) = match.destructured
            val first = start.toLongOrNull() ?: return null
            val last = end.toLongOrNull() ?: return null
            val size = total.toLongOrNull() ?: return null
            return Triple(first, last, size).takeIf { first <= last && last < size }
        }

        private fun archiveName(response: Response): String? {
            val disposition = response.header("Content-Disposition").orEmpty()
            val encoded = Regex("filename\\*\\s*=\\s*UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(disposition)?.groupValues?.get(1)
            val regular = Regex("filename\\s*=\\s*(?:\"([^\"]+)\"|([^;]+))", RegexOption.IGNORE_CASE)
                .find(disposition)?.let { it.groupValues[1].ifBlank { it.groupValues[2].trim() } }
            val segments = response.request.url.pathSegments
            val urlName = segments.indexOf("file").takeIf { it >= 0 }?.let { segments.getOrNull(it + 1) }
            return (encoded?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
                ?: regular ?: urlName)?.takeIf { it.isNotBlank() }
        }

        internal fun safePackageName(name: String) {
            if (name.length > 240 || name.any { it == '/' || it == '\\' || it == '\u0000' || it.isISOControl() } ||
                name == "." || name == ".." || name.contains(':') ||
                !Regex(".*\\.(zip|rar|exe|[rR][0-9]{2,3}|[0-9]{3})", RegexOption.IGNORE_CASE).matches(name)) {
                throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_format))
            }
        }

        private fun isHtml(response: Response): Boolean = response.header("Content-Type")
            ?.substringBefore(';')?.lowercase()?.let { it.contains("html") || it.startsWith("text/") || it.contains("json") } == true

        private fun looksLikeHtml(bytes: ByteArray, count: Int): Boolean {
            val text = bytes.copyOf(count.coerceAtMost(256)).toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t').lowercase()
            return text.startsWith("<!doctype html") || text.startsWith("<html")
        }
    }
}

private fun File.outputStream(append: Boolean) = java.io.FileOutputStream(this, append)

private fun java.io.InputStream.readBounded(limit: Int, checkCancelled: () -> Unit): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        checkCancelled()
        val count = read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
        if (count < 0) return output.toByteArray()
        output.write(buffer, 0, count)
        if (output.size() > limit) throw DlsiteArchiveException(TextRef.Res(R.string.dlsite_archive_error_large_page))
    }
}
