package com.nanahoshi.audioplayer.dlsite

import com.nanahoshi.audioplayer.data.RemoteTextPreview
import com.nanahoshi.audioplayer.data.ExternalFileDownloader
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.data.jsonArray
import com.nanahoshi.audioplayer.data.jsonBool
import com.nanahoshi.audioplayer.data.jsonDouble
import com.nanahoshi.audioplayer.data.jsonInt
import com.nanahoshi.audioplayer.data.jsonObject
import com.nanahoshi.audioplayer.data.jsonObjects
import com.nanahoshi.audioplayer.data.jsonString
import com.nanahoshi.audioplayer.data.jsonStringOrEmpty
import com.nanahoshi.audioplayer.data.jsonTextOrNull
import com.nanahoshi.audioplayer.data.parseJsonArrayOrNull
import com.nanahoshi.audioplayer.data.parseJsonObjectOrNull
import com.nanahoshi.audioplayer.data.withResponse

import com.nanahoshi.audioplayer.asmr.WorkFacts
import com.nanahoshi.audioplayer.data.CredentialStore
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.FileKind
import com.nanahoshi.audioplayer.data.fileExtension
import com.nanahoshi.audioplayer.data.isAsmrAudio
import com.nanahoshi.audioplayer.data.previewKind
import com.nanahoshi.audioplayer.data.toHttps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder
import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.UserFacingError
import java.util.concurrent.TimeUnit

enum class DlsiteFailureKind { AUTH, OTHER }

class DlsiteException(
    override val userText: TextRef,
    internal val statusCode: Int? = null,
    internal val kind: DlsiteFailureKind = if (statusCode == 401) DlsiteFailureKind.AUTH else DlsiteFailureKind.OTHER,
    cause: Throwable? = null,
) : Exception(userText.debugString(), cause), UserFacingError

data class DlsiteHit(
    val workno: String,
    val title: String,
    val circle: String,
    val coverUrl: String?,
    val vas: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val release: String = "",
    val workType: String = "",
)

data class DlsitePage(
    val works: List<DlsiteHit>,
    val total: Int,
    val fetched: Int = works.size,
)

data class DlsiteShowcase(
    val workno: String,
    val title: String,
    val circle: String,
    val vas: List<String>,
    val tags: List<String>,
    val release: String,
    val coverUrl: String?,
    val siteId: String,
    val gallery: List<String>,
    val trials: List<RemotePlayable>,
) {
    fun facts() = WorkFacts(title, circle, vas, tags, release, workno)
}

data class SignedPlayFile(
    val url: String,
    val cookie: String,
)

internal fun isTrustedDlsitePlayHost(host: String): Boolean {
    val h = host.lowercase()
    return h == "dlsite.com" || h.endsWith(".dlsite.com")
}

data class DlsitePurchase(
    val workno: String,
    val title: String,
    val circle: String,
    val vas: List<String>,
    val coverUrl: String?,
    val release: String,
)

class DlsiteClient(
    private val credentials: CredentialStore,
    root: OkHttpClient = com.nanahoshi.audioplayer.data.HttpRoots.shared,
) {
    private val client = root.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    @Volatile
    private var owned: Set<String>? = null

    @Volatile
    private var playAccessCookie: String? = null

    fun hasSession(): Boolean =
        credentials.dlsiteCookie()?.let { mergeDlsiteCookies(it).isNotBlank() } == true

    suspend fun search(
        keyword: String,
        creator: String,
        genreIds: List<String>,
        allAges: Boolean,
        order: String,
        page: Int,
    ): DlsitePage = withContext(Dispatchers.IO) {
        val parts = mutableListOf("language" to "jp")
        if (keyword.isNotBlank()) parts += "keyword" to keyword.trim()
        if (creator.isNotBlank()) parts += "keyword_creator" to creator.trim()
        if (allAges) parts += "age_category[0]" to "general"
        genreIds.forEachIndexed { index, id -> parts += "genre[$index]" to id }
        parts += "order" to order
        parts += "work_type" to "SOU"
        parts += "per_page" to PAGE_SIZE.toString()
        parts += "page" to page.toString()
        val path = parts.joinToString("/") { (key, value) -> "$key/${encode(value)}" }
        val body = get("https://www.dlsite.com/maniax/fsr/ajax/=/$path", STORE_REFERER, acceptJson = true)
        val root = parseJsonObjectOrNull(body)
            ?: throw DlsiteException(TextRef.Res(R.string.dlsite_error_connection))
        val html = root.jsonStringOrEmpty("search_result")
        val total = root.jsonObject("page_info")?.jsonInt("count") ?: 0
        val parsed = parseHits(html)
        val works = fillFromProducts(parsed.works)
        DlsitePage(works, total, parsed.fetched)
    }

    private suspend fun fillFromProducts(hits: List<DlsiteHit>): List<DlsiteHit> {
        if (hits.isEmpty()) return hits
        val details = try {
            productCards(hits.map { it.workno })
        } catch (_: DlsiteException) {
            emptyList()
        }
        if (details.isEmpty()) return hits
        val byId = details.associateBy { canonicalWorkno(it.workno) }
        return hits.mapNotNull { hit ->
            val detail = byId[canonicalWorkno(hit.workno)] ?: return@mapNotNull hit
            if (detail.workType.isNotBlank() && detail.workType != "SOU") return@mapNotNull null
            detail.copy(
                title = detail.title.ifBlank { hit.title },
                circle = detail.circle.ifBlank { hit.circle },
                coverUrl = detail.coverUrl ?: hit.coverUrl,
                vas = detail.vas.ifEmpty { hit.vas },
                tags = detail.tags.ifEmpty { hit.tags },
                release = detail.release.ifBlank { hit.release },
            )
        }
    }

    /**
     * 先用一次 `product.json` 批量请求补齐整页作品，只对批次里缺失的编号
     * 逐个回退到候选编号与作品页，避免把可批量的查询拆成整页阻塞扇出。
     */
    private suspend fun productCards(worknos: List<String>): List<DlsiteHit> = withContext(Dispatchers.IO) {
        resolveProductCards(
            worknos = worknos,
            fetchBatch = { ids -> loadProductCards(ids) },
            fetchSingle = { id -> resolveCard(id) },
            // 批量结果只有明确标注类型或字段可读时才采用；其余仍走候选编号与作品页，
            // 与单 ID 解析的判定保持一致。
            acceptBatchHit = { hit ->
                (hit.workType.isNotBlank() && hit.workType != "SOU") || readable(hit)
            },
        )
    }

    private suspend fun resolveCard(workno: String): DlsiteHit? {
        var blank: DlsiteHit? = null
        for (id in worknoCandidates(workno)) {
            val hit = try {
                loadProductCards(listOf(id)).firstOrNull()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: continue
            if (hit.workType.isNotBlank() && hit.workType != "SOU") return hit
            if (readable(hit)) return hit
            if (blank == null) blank = hit
        }
        return pageCard(workno) ?: blank
    }

    private fun readable(hit: DlsiteHit): Boolean {
        val titleIsId = hit.title.isBlank() || canonicalWorkno(hit.title) == canonicalWorkno(hit.workno)
        return !titleIsId || hit.circle.isNotBlank()
    }

    private suspend fun pageCard(workno: String): DlsiteHit? {
        for (id in worknoCandidates(workno)) {
            val html = workHtml("maniax", id)
            if (html.isBlank()) continue
            val title = titleOf(html)
            val circle = circleOf(html)
            val cover = galleryOf(html).firstOrNull()
            if (title.isBlank() && circle.isBlank() && cover == null) continue
            return DlsiteHit(
                workno = workno.uppercase(),
                title = title.ifBlank { workno.uppercase() },
                circle = circle,
                coverUrl = cover,
            )
        }
        return null
    }

    private suspend fun loadProductCards(worknos: List<String>): List<DlsiteHit> {
        val joined = worknos.joinToString(",") { encode(it) }
        val body = get(
            "https://www.dlsite.com/maniax/api/=/product.json?workno=$joined&locale=zh_CN",
            STORE_REFERER,
            acceptJson = true,
        )
        return parseProductCards(body)
    }

    internal suspend fun lookupProduct(input: String): DlsiteShowcase? = withContext(Dispatchers.IO) {
        try {
            product(worknoOf(input))
        } catch (error: DlsiteException) {
            if (error.statusCode == 404) null else throw error
        }
    }

    suspend fun showcase(input: String): DlsiteShowcase = withContext(Dispatchers.IO) {
        val workno = worknoOf(input)
        val product = product(workno)
        val site = product?.siteId?.ifBlank { null } ?: "maniax"
        val html = workHtml(site, workno)
        val gallery = galleryOf(html).ifEmpty { product?.gallery.orEmpty() }
        val lookup = product?.workno?.ifBlank { null } ?: workno
        val trials = (
            chobitOf(lookup, html, product?.coverUrl ?: gallery.firstOrNull()) +
                trialsOf(html, product?.title.orEmpty(), product?.coverUrl)
            ).distinctBy { it.remoteUrl }
        if (product != null) {
            product.copy(gallery = gallery, trials = trials)
        } else if (html.isNotBlank()) {
            val title = titleOf(html).ifBlank { workno }
            DlsiteShowcase(
                workno = workno,
                title = title,
                circle = circleOf(html),
                vas = emptyList(),
                tags = emptyList(),
                release = "",
                coverUrl = gallery.firstOrNull(),
                siteId = site,
                gallery = gallery,
                trials = trials,
            )
        } else {
            throw DlsiteException(TextRef.Res(R.string.dlsite_error_work_missing))
        }
    }

    suspend fun loginWithCookie(raw: String, loadPurchases: Boolean = true) = withContext(Dispatchers.IO) {
        val cookie = mergeDlsiteCookies(raw)
        if (cookie.isBlank() || !cookie.contains("=")) throw DlsiteException(TextRef.Res(R.string.dlsite_error_cookie_required))
        credentials.saveDlsiteCookie(withAdult(cookie))
        try {
            execute(request("https://play.dlsite.com/login/", PLAY_REFERER, acceptJson = false, authed = true))
            execute(request("https://play.dlsite.com/api/authorize", PLAY_REFERER, acceptJson = true, authed = true))
            owned = null
            if (loadPurchases) salesIds()
        } catch (cancelled: CancellationException) {
            credentials.clearDlsiteCookie()
            owned = null
            throw cancelled
        } catch (error: Exception) {
            credentials.clearDlsiteCookie()
            owned = null
            throw error
        }
    }

    fun logout() {
        credentials.clearDlsiteCookie()
        owned = null
        playAccessCookie = null
    }

    suspend fun purchases(): List<DlsitePurchase> = withContext(Dispatchers.IO) {
        if (!hasSession()) return@withContext emptyList()
        val count = parseJsonObjectOrNull(playGet("https://play.dlsite.com/api/v3/content/count?last=0"))
            ?: throw DlsiteException(TextRef.Res(R.string.dlsite_error_connection))
        if ((count.jsonInt("user") ?: 0) < 1) return@withContext emptyList()
        val sales = parseJsonArrayOrNull(playGet("https://play.dlsite.com/api/v3/content/sales?last=0"))
            ?: throw DlsiteException(TextRef.Res(R.string.dlsite_error_connection))
        val order = sales.jsonObjects().mapNotNull { it.jsonString("workno")?.takeIf(String::isNotBlank) }
        owned = order.map(::canonicalWorkno).toSet()
        buildList {
            order.chunked(100).forEach { batch ->
                val body = JsonArray(batch.map { JsonPrimitive(it) }).toString()
                val root = parseJsonObjectOrNull(playPost("https://play.dlsite.com/api/v3/content/works", body))
                    ?: return@forEach
                root.jsonObjects("works").forEach { parsePurchase(it)?.let { purchase -> add(purchase) } }
            }
        }
    }

    suspend fun isPurchased(input: String): Boolean = withContext(Dispatchers.IO) {
        if (!hasSession()) return@withContext false
        val key = canonicalWorkno(worknoOf(input))
        key in salesIds()
    }

    /** 已购列表还没读过时返回 null，不发起请求。 */
    fun cachedPurchased(input: String): Boolean? {
        if (!hasSession()) return false
        val known = owned ?: return null
        val key = try {
            canonicalWorkno(worknoOf(input))
        } catch (_: DlsiteException) {
            return false
        }
        return key in known
    }

    suspend fun playTree(input: String, artist: String?, coverUrl: String?): List<PlayEntry> =
        withContext(Dispatchers.IO) {
            val workno = worknoOf(input)
            val token = sign(workno)
            val tree = parseJsonObjectOrNull(
                get(
                    token.url + "ziptree.json",
                    PLAY_REFERER,
                    acceptJson = false,
                    cookieOverride = token.cookie,
                ),
            ) ?: throw DlsiteException(TextRef.Res(R.string.dlsite_error_connection))
            val files = tree.jsonObject("playfile") ?: JsonObject(emptyMap())
            val nodes = tree.jsonArray("tree") ?: JsonArray(emptyList())
            nodes.jsonObjects().mapNotNull { parsePlayNode(it, files, token.url, workno, artist, coverUrl) }
        }

    fun signedPlayFileBlocking(workno: String, optimizedName: String): SignedPlayFile {
        return kotlinx.coroutines.runBlocking { signedPlayFile(workno, optimizedName) }
    }

    internal suspend fun signedPlayFile(workno: String, optimizedName: String): SignedPlayFile {
        val token = sign(worknoOf(workno))
        val name = optimizedName.removePrefix("dlsite:")
        return SignedPlayFile(token.url.trimEnd('/') + "/optimized/" + name, token.cookie)
    }

    fun playCookie(): String? = playAccessCookie ?: cookieHeader()

    private val externalFiles by lazy { ExternalFileDownloader(sign = ::signedPlayFile) }

    internal suspend fun readText(preview: RemotePreview, limit: Int = TEXT_LIMIT): RemoteTextPreview =
        withContext(Dispatchers.IO) { externalFiles.readText(preview, limit) }

    internal suspend fun readFullText(
        preview: RemotePreview,
        maxBytes: Int = com.nanahoshi.audioplayer.data.MAX_SUBTITLE_TEXT_BYTES,
    ): String = withContext(Dispatchers.IO) { externalFiles.readFullText(preview, maxBytes) }

    fun cookieHeader(): String? {
        val cookie = credentials.dlsiteCookie() ?: return null
        val merged = mergeDlsiteCookies(cookie)
        return if (merged.isBlank()) null else withAdult(merged)
    }

    private suspend fun salesIds(): Set<String> {
        owned?.let { return it }
        val sales = parseJsonArrayOrNull(playGet("https://play.dlsite.com/api/v3/content/sales?last=0"))
            ?: throw DlsiteException(TextRef.Res(R.string.dlsite_error_connection))
        val ids = sales.jsonObjects()
            .mapNotNull { it.jsonString("workno")?.takeIf(String::isNotBlank) }
            .mapTo(mutableSetOf(), ::canonicalWorkno)
        owned = ids
        return ids
    }

    private suspend fun sign(workno: String): DownloadToken {
        var last: DlsiteException? = null
        for (id in worknoCandidates(workno)) {
            try {
                return signOnce(id)
            } catch (error: DlsiteException) {
                if (error.kind == DlsiteFailureKind.AUTH) throw error
                last = error
            }
        }
        throw last ?: DlsiteException(TextRef.Res(R.string.dlsite_error_no_play_url))
    }

    private suspend fun signOnce(workno: String): DownloadToken {
        val call = request(
            "https://play.dl.dlsite.com/api/v3/download/sign/cookie?workno=$workno",
            PLAY_REFERER,
            acceptJson = true,
            authed = true,
        )
        return client.newCall(call).withResponse { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 401) throw DlsiteException(TextRef.Res(R.string.dlsite_error_login_expired), statusCode = 401)
            if (!response.isSuccessful) throw DlsiteException(TextRef.Res(R.string.dlsite_error_connection))
            val root = parseJsonObjectOrNull(body)
                ?: throw DlsiteException(TextRef.Res(R.string.dlsite_error_connection))
            val signedUrl = root.jsonStringOrEmpty("url").toHttpUrlOrNull()
            if (signedUrl == null || signedUrl.scheme != "https" || signedUrl.port != 443 ||
                signedUrl.username.isNotEmpty() || signedUrl.password.isNotEmpty() || !isTrustedDlsitePlayHost(signedUrl.host)
            ) throw DlsiteException(TextRef.Res(R.string.dlsite_error_no_play_url))
            val url = signedUrl.toString()
            val directory = if (url.endsWith("/")) url else "$url/"
            val extras = buildList {
                add(cookiesOf(root.jsonObject("cookies")))
                response.headers("Set-Cookie").forEach { add(it.substringBefore(';')) }
            }
            val cookie = playAccessCookie(cookieHeader(), extras)
            playAccessCookie = cookie
            DownloadToken(directory, cookie)
        }
    }

    private fun cookiesOf(obj: JsonObject?): String {
        if (obj == null) return ""
        return obj.entries.mapNotNull { (key, element) ->
            val value = element.jsonTextOrNull().orEmpty()
            if (key.isNotBlank() && value.isNotBlank()) "$key=$value" else null
        }.joinToString("; ")
    }

    private suspend fun product(workno: String): DlsiteShowcase? {
        val body = get(
            "https://www.dlsite.com/maniax/api/=/product.json?workno=$workno&locale=zh_CN",
            STORE_REFERER,
            acceptJson = true,
        )
        val array = parseJsonArrayOrNull(body)
            ?: throw DlsiteException(TextRef.Res(R.string.dlsite_error_connection))
        val item = array.firstOrNull() as? JsonObject ?: return null
        val fields = productFields(item)
        return DlsiteShowcase(
            workno = fields.workno.ifBlank { workno },
            title = fields.title.ifBlank { workno },
            circle = fields.circle,
            vas = fields.vas,
            tags = fields.tags,
            release = fields.release,
            coverUrl = fields.gallery.firstOrNull(),
            siteId = fields.siteId.ifBlank { "maniax" },
            gallery = fields.gallery,
            trials = emptyList(),
        )
    }

    private suspend fun workHtml(site: String, workno: String): String {
        val sites = listOf(site, "maniax", "home").distinct()
        for (name in sites) {
            val url = "https://www.dlsite.com/$name/work/=/product_id/$workno.html/?locale=zh_CN"
            try {
                return get(url, "https://www.dlsite.com/$name/", acceptJson = false)
            } catch (_: DlsiteException) {
                continue
            }
        }
        return ""
    }

    private suspend fun chobitOf(workno: String, html: String, coverUrl: String?): List<RemotePlayable> {
        val embeds = (chobitEmbeds(workno) + CHOBIT_EMBED.findAll(html).map { it.value.toHttps() })
            .distinct()
            .take(4)
        return embeds.flatMap { embed ->
            val referer = embed.substringBefore('?')
            val page = try {
                get(embed, "https://chobit.cc/", acceptJson = false, cookieOverride = ADULT_ONLY_COOKIE)
            } catch (_: DlsiteException) {
                return@flatMap emptyList()
            }
            parseChobit(page, referer, coverUrl)
        }.toList()
    }

    private suspend fun chobitEmbeds(workno: String): List<String> {
        for (id in worknoCandidates(workno)) {
            val body = try {
                get("https://chobit.cc/api/v1/dlsite/embed?workno=$id", "https://chobit.cc/",
                    acceptJson = true, cookieOverride = ADULT_ONLY_COOKIE)
            } catch (_: DlsiteException) {
                continue
            }
            val urls = try {
                embedUrls(body)
            } catch (_: Exception) {
                emptyList()
            }
            if (urls.isNotEmpty()) return urls
        }
        return emptyList()
    }

    private suspend fun playGet(url: String): String = get(url, PLAY_REFERER, acceptJson = true, authed = true)

    private suspend fun playPost(url: String, json: String): String {
        val request = request(url, PLAY_REFERER, acceptJson = true, authed = true)
            .newBuilder()
            .post(json.toRequestBody(JSON_MEDIA))
            .build()
        return execute(request)
    }

    private suspend fun get(
        url: String,
        referer: String,
        acceptJson: Boolean,
        authed: Boolean = false,
        cookieOverride: String? = null,
    ): String = execute(request(url, referer, acceptJson, authed, cookieOverride))

    private fun request(
        url: String,
        referer: String,
        acceptJson: Boolean,
        authed: Boolean,
        cookieOverride: String? = null,
    ): Request {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
        if (acceptJson) builder.header("Accept", "application/json")
        val cookie = when {
            !cookieOverride.isNullOrBlank() -> cookieOverride
            authed -> cookieHeader() ?: throw DlsiteException(TextRef.Res(R.string.dlsite_error_login_required), statusCode = 401)
            else -> withAdult(credentials.dlsiteCookie().orEmpty())
        }
        if (cookie.isNotBlank()) builder.header("Cookie", cookie)
        return builder.build()
    }

    /**
     * 商店与 Play 接口共用的正文读取：走 [withResponse] 以便协程取消时终止请求，
     * 并保证响应体在成功、异常和取消路径上都关闭。
     */
    private suspend fun execute(request: Request): String {
        try {
            return client.newCall(request).withResponse { response ->
                val body = response.body?.string().orEmpty()
                if (response.code == 401) throw DlsiteException(TextRef.Res(R.string.dlsite_error_login_expired), response.code)
                if (!response.isSuccessful) throw DlsiteException(TextRef.Res(R.string.dlsite_error_connection), response.code)
                body
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: DlsiteException) {
            throw error
        } catch (_: IOException) {
            throw DlsiteException(TextRef.Res(R.string.dlsite_error_connection))
        }
    }

    private data class DownloadToken(val url: String, val cookie: String)

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
        const val STORE_REFERER = "https://www.dlsite.com/maniax/"
        const val PLAY_REFERER = "https://play.dlsite.com/"
        private const val PAGE_SIZE = 20
        private const val TEXT_LIMIT = 512 * 1024
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val WORK_TYPE = Regex("""\btype_([A-Z]{2,4})\b""")
        private val MEDIA = Regex("""(?i)(?:https?:)?//[^"'\\\s>]+\.(?:mp3|m4a|aac|wav|ogg)(?:\?[^"'\\\s]*)?""")
        private val CHOBIT_EMBED = Regex("""(?i)(?:https?:)?//chobit\.cc/embed/[0-9A-Za-z]+/[0-9A-Za-z]+""")
        private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "wav", "ogg")

        fun worknoOf(input: String): String =
            com.nanahoshi.audioplayer.data.WorkIds.fromInput(input)
                ?: throw DlsiteException(TextRef.Res(R.string.dlsite_error_work_id_missing))

        internal fun worknoCandidates(workno: String): List<String> =
            com.nanahoshi.audioplayer.data.WorkIds.candidates(workno)

        internal fun embedUrls(json: String): List<String> {
            val works = parseJsonObjectOrNull(json)?.jsonArray("works") ?: return emptyList()
            return works.jsonObjects()
                .map { it.jsonStringOrEmpty("embed_url").toHttps() }
                .filter { it.startsWith("https://") }
        }

        fun canonicalWorkno(raw: String): String = com.nanahoshi.audioplayer.data.WorkIds.canonical(raw)

        private fun encode(value: String): String =
            URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

        private fun withAdult(cookie: String): String = mergeDlsiteCookies(cookie, "adultchecked=1")

        /** 试听走第三方主机，只带成年确认，不带 DLsite 登录会话。 */
        private const val ADULT_ONLY_COOKIE = "adultchecked=1"

        internal fun playAccessCookie(session: String?, extras: List<String>): String {
            var merged = session.orEmpty()
            extras.forEach { part ->
                if (part.isNotBlank()) merged = mergeDlsiteCookies(merged, part)
            }
            return merged
        }

        internal fun parseSearchCards(html: String): List<DlsiteHit> = parseHits(html).works

        private data class ProductFields(
            val workno: String,
            val title: String,
            val circle: String,
            val gallery: List<String>,
            val vas: List<String>,
            val tags: List<String>,
            val release: String,
            val workType: String,
            val siteId: String,
        )

        /** `product()` 与批量 `product.json` 共用同一套字段提取，避免两套解析漂移。 */
        private fun productFields(item: JsonObject): ProductFields {
            val makers = item.jsonObject("creaters")
            return ProductFields(
                workno = item.jsonStringOrEmpty("workno").ifBlank { item.jsonStringOrEmpty("product_id") }.uppercase(),
                title = localized(item["work_name"]),
                circle = localized(item["maker_name"]),
                gallery = sampleImages(item),
                vas = makers?.jsonObjects("voice_by").orEmpty().mapNotNull { localized(it["name"]).ifBlank { null } },
                tags = item.jsonObjects("genres").mapNotNull { localized(it["name"]).ifBlank { null } },
                release = item.jsonStringOrEmpty("regist_date").substringBefore('T').substringBefore(' '),
                workType = item.jsonStringOrEmpty("work_type"),
                siteId = item.jsonStringOrEmpty("site_id"),
            )
        }

        internal fun parseProductCards(body: String): List<DlsiteHit> {
            val trimmed = body.trim()
            if (trimmed.isEmpty() || trimmed == "[]") return emptyList()
            val array = parseJsonArrayOrNull(trimmed)
                ?: throw DlsiteException(TextRef.Res(R.string.dlsite_error_connection))
            return array.jsonObjects().mapNotNull { item ->
                val fields = productFields(item)
                if (fields.workno.isBlank()) return@mapNotNull null
                DlsiteHit(
                    workno = fields.workno,
                    title = fields.title.ifBlank { fields.workno },
                    circle = fields.circle,
                    coverUrl = fields.gallery.firstOrNull(),
                    vas = fields.vas,
                    tags = fields.tags,
                    release = fields.release,
                    workType = fields.workType,
                )
            }
        }

        private data class ParsedSearch(val works: List<DlsiteHit>, val fetched: Int)

        private fun parseHits(html: String): ParsedSearch {
            val cards = Jsoup.parse(html).select("[data-list_item_product_id], [data-product_id]")
            val works = cards.mapNotNull { card ->
                val workno = card.attr("data-list_item_product_id")
                    .ifBlank { card.attr("data-product_id") }
                    .trim()
                    .uppercase()
                if (workno.isEmpty() || !isVoiceCard(card)) return@mapNotNull null
                DlsiteHit(
                    workno = workno,
                    title = cardTitle(card).ifBlank { workno },
                    circle = cardText(card, "maker_name"),
                    coverUrl = cardCover(card),
                    vas = cardTexts(card, "author"),
                )
            }
            return ParsedSearch(works, cards.size)
        }

        private fun isVoiceCard(card: Element): Boolean {
            val types = card.allElements.flatMap { element ->
                element.classNames().mapNotNull { name -> WORK_TYPE.find(name)?.groupValues?.get(1) }
            }.toSet()
            return types.isEmpty() || "SOU" in types
        }

        private fun cardTitle(card: Element): String {
            card.selectFirst(".work_name")?.let { name ->
                anchorTitle(name).takeIf { it.isNotBlank() }?.let { return it }
            }
            return card.selectFirst("img[alt]")?.attr("alt").orEmpty().replace(' ', ' ').trim()
        }

        private fun cardText(card: Element, className: String): String =
            card.selectFirst(".$className")?.let(::anchorTitle).orEmpty()

        private fun cardTexts(card: Element, className: String): List<String> =
            card.selectFirst(".$className")?.let(::anchors).orEmpty()

        private fun anchorTitle(scope: Element): String = anchors(scope).maxByOrNull(String::length).orEmpty()

        private fun anchors(scope: Element): List<String> = scope.select("a").map { anchor ->
            anchor.attr("title").ifBlank { anchor.text() }.replace(' ', ' ').trim()
        }.filter(String::isNotBlank).distinct()

        private fun cardCover(card: Element): String? {
            val image = card.selectFirst("[class*=work_thumb] img")
                ?: card.selectFirst("img[data-src], img[src]")
                ?: return null
            val raw = image.attr("data-src").ifBlank { image.attr("src") }.trim()
            return raw.toHttps().ifBlank { null }
        }

        private fun sampleImages(item: JsonObject): List<String> {
            val urls = mutableListOf<String>()
            fun add(raw: String) {
                val url = raw.toHttps()
                if (url.startsWith("https://")) urls += url
            }
            item.jsonObject("image_main")?.jsonString("url")?.let(::add)
            item.jsonArray("image_samples")?.forEach { value ->
                when (value) {
                    is JsonPrimitive -> if (value.isString) add(value.content)
                    is JsonObject -> add(value.jsonStringOrEmpty("url"))
                    else -> Unit
                }
            }
            return urls.distinct()
        }

        internal fun parseChobit(html: String, referer: String, coverUrl: String?): List<RemotePlayable> {
            return Jsoup.parse(html).select("li[data-src]").mapNotNull { item ->
                val src = item.attr("data-src").trim().toHttps()
                if (src.isBlank()) return@mapNotNull null
                val extension = src.substringBefore('?').substringAfterLast('.').lowercase()
                if (extension !in AUDIO_EXT) return@mapNotNull null
                RemotePlayable(
                    title = item.attr("data-title").trim().ifBlank { "试听" },
                    artist = null,
                    coverUri = coverUrl,
                    remoteUrl = src,
                    source = TrackSource.DLSITE,
                    referer = referer,
                    durationMs = playtimeMs(item.attr("data-playtime")),
                )
            }.distinctBy { it.remoteUrl }.toList()
        }

        private fun playtimeMs(raw: String): Long {
            val parts = raw.trim().split(":").mapNotNull { it.toLongOrNull() }
            return when (parts.size) {
                3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
                2 -> (parts[0] * 60 + parts[1]) * 1000
                1 -> parts[0] * 1000
                else -> 0
            }
        }

        internal fun galleryOf(html: String): List<String> {
            val document = Jsoup.parse(html)
            val scope = document.selectFirst(".product-slider-data, [class*=product-slider-data], [id*=product-slider-data]")
                ?: document.selectFirst("[class*=work_slider]")
                ?: return emptyList()
            return scope.allElements.mapNotNull { element ->
                element.attr("data-src").trim().takeIf(String::isNotBlank)?.toHttps()
            }.distinct()
        }

        private fun trialsOf(html: String, title: String, coverUrl: String?): List<RemotePlayable> {
            val document = Jsoup.parse(html)
            val scope = document.selectFirst("[class*=trial_download], [id*=trial_download]") ?: document
            val urls = linkedSetOf<String>()
            fun absorb(raw: String) {
                if (raw.isBlank()) return
                MEDIA.findAll(raw).forEach { urls += it.value.toHttps() }
            }
            scope.allElements.forEach { element ->
                element.attributes().forEach { absorb(it.value) }
                if (element.tagName() == "script" || element.tagName() == "style") absorb(element.data())
            }
            return urls.mapIndexed { index, url ->
                val name = url.substringBefore('?').substringAfterLast('/').substringBefore('.')
                RemotePlayable(
                    title = name.ifBlank { if (urls.size == 1) title.ifBlank { "试听" } else "试听 ${index + 1}" },
                    artist = null,
                    coverUri = coverUrl,
                    remoteUrl = url,
                    source = TrackSource.DLSITE,
                    referer = STORE_REFERER,
                )
            }
        }

        internal fun titleOf(html: String): String =
            Jsoup.parse(html).getElementById("work_name")?.text()?.replace(' ', ' ')?.trim().orEmpty()

        internal fun circleOf(html: String): String =
            Jsoup.parse(html).getElementById("work_maker")?.selectFirst(".maker_name a")?.text()
                ?.replace(' ', ' ')?.trim().orEmpty()

        private fun parsePurchase(item: JsonObject?): DlsitePurchase? {
            if (item == null) return null
            val workno = item.jsonStringOrEmpty("workno")
            if (workno.isBlank()) return null
            val maker = item.jsonObject("maker")
            val files = item.jsonObject("work_files")
            val cover = files?.get("main").asUrl()
            val vas = item.jsonObjects("tags").mapNotNull { tag ->
                if (tag.jsonString("class") == "voice_by") localized(tag["name"]).ifBlank { null } else null
            }
            return DlsitePurchase(
                workno = workno,
                title = localized(item["name"]).ifBlank { workno },
                circle = localized(maker?.get("name")),
                vas = vas,
                coverUrl = cover,
                release = item.jsonStringOrEmpty("regist_date").substringBefore('T').substringBefore(' '),
            )
        }

        private fun localized(value: JsonElement?): String = when (value) {
            is JsonPrimitive -> value.takeIf { it.isString }?.content.orEmpty()
            is JsonObject -> value.jsonString("zh_CN").orEmpty().ifBlank {
                value.jsonString("ja_JP").orEmpty().ifBlank {
                    value.entries.mapNotNull { it.value.jsonTextOrNull()?.takeIf(String::isNotBlank) }.firstOrNull().orEmpty()
                }
            }
            else -> ""
        }

        private fun JsonElement?.asUrl(): String? = when (this) {
            is JsonPrimitive -> takeIf { it.isString }?.content?.toHttps()?.ifBlank { null }
            is JsonObject -> jsonString("url").orEmpty().ifBlank { jsonString("path").orEmpty() }.toHttps().ifBlank { null }
            else -> null
        }

        private fun parsePlayNode(
            node: JsonObject?,
            files: JsonObject,
            directory: String,
            workno: String,
            artist: String?,
            coverUrl: String?,
        ): PlayEntry? {
            if (node == null) return null
            when (node.jsonStringOrEmpty("type")) {
                "hidden" -> return null
                "folder" -> {
                    val children = node.jsonArray("children") ?: JsonArray(emptyList())
                    val nested = children.jsonObjects()
                        .mapNotNull { parsePlayNode(it, files, directory, workno, artist, coverUrl) }
                    if (nested.isEmpty()) return null
                    return PlayEntry(node.jsonStringOrEmpty("name").ifBlank { "文件夹" }, nested, null, null)
                }
                "file" -> {
                    val hash = node.jsonStringOrEmpty("hashname")
                    val info = files.jsonObject(hash) ?: return null
                    val type = info.jsonStringOrEmpty("type")
                    val optimized = info.jsonObject(type)?.jsonObject("optimized")
                        ?: info.jsonObject("optimized")
                        ?: return null
                    if (optimized.jsonBool("crypt") == true) return null
                    val fileName = optimized.jsonStringOrEmpty("name")
                    if (fileName.isBlank() || fileName.endsWith(".m3u8", ignoreCase = true)) return null
                    val url = directory.trimEnd('/') + "/optimized/" + fileName
                    val title = node.jsonStringOrEmpty("name").ifBlank { fileName }
                    val duration = ((optimized.jsonDouble("duration") ?: 0.0) * 1000).toLong().coerceAtLeast(0)
                    val audio = isAsmrAudio(type, title) || isAsmrAudio(type, fileName)
                    return if (audio) {
                        PlayEntry(
                            title = title,
                            children = emptyList(),
                            audio = RemotePlayable(
                                title = title,
                                artist = artist,
                                coverUri = coverUrl,
                                remoteUrl = url,
                                source = TrackSource.DLSITE,
                                referer = PLAY_REFERER,
                                durationMs = duration,
                                workno = workno,
                                fileKey = fileName,
                            ),
                            preview = null,
                        )
                    } else {
                        val kind = when {
                            type.equals("image", ignoreCase = true) -> FileKind.IMAGE
                            type.equals("text", ignoreCase = true) -> FileKind.TEXT
                            else -> previewKind(title.ifBlank { fileName })
                        }
                        PlayEntry(
                            title = title,
                            children = emptyList(),
                            audio = null,
                            preview = RemotePreview(title, url, kind, PLAY_REFERER, authed = true, workno = workno, fileKey = fileName),
                        )
                    }
                }
                else -> return null
            }
        }
    }
}

/**
 * 批量解析作品卡片：先按 [batchSize] 分组请求 [fetchBatch]，只对批次未返回的编号
 * 回退到 [fetchSingle]（候选编号与作品页），保持输入顺序并去重。
 *
 * 批次失败不会让整页结果失败；逐条回退本身也吞掉非取消异常，与旧的单 ID 扇出行为一致。
 */
internal suspend fun resolveProductCards(
    worknos: List<String>,
    fetchBatch: suspend (List<String>) -> List<DlsiteHit>,
    fetchSingle: suspend (String) -> DlsiteHit?,
    batchSize: Int = 20,
    acceptBatchHit: (DlsiteHit) -> Boolean = { true },
): List<DlsiteHit> = coroutineScope {
    val ids = worknos.map(String::trim).filter(String::isNotEmpty).distinct()
    if (ids.isEmpty()) return@coroutineScope emptyList()
    val requested = ids.mapTo(HashSet()) { DlsiteClient.canonicalWorkno(it) }
    val resolved = linkedMapOf<String, DlsiteHit>()
    for (chunk in ids.chunked(batchSize)) {
        val hits = try {
            fetchBatch(chunk)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        for (hit in hits) {
            val key = DlsiteClient.canonicalWorkno(hit.workno)
            if (key in requested && acceptBatchHit(hit)) resolved.putIfAbsent(key, hit)
        }
    }
    val missing = ids.filter { DlsiteClient.canonicalWorkno(it) !in resolved }
    if (missing.isNotEmpty()) {
        missing.map { id ->
            async {
                try {
                    fetchSingle(id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
        }.awaitAll().forEach { hit ->
            if (hit != null) resolved.putIfAbsent(DlsiteClient.canonicalWorkno(hit.workno), hit)
        }
    }
    ids.mapNotNull { resolved[DlsiteClient.canonicalWorkno(it)] }
}
