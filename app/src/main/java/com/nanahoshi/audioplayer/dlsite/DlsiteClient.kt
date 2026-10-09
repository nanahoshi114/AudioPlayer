package com.nanahoshi.audioplayer.dlsite

import com.nanahoshi.audioplayer.asmr.WorkFacts
import com.nanahoshi.audioplayer.data.CredentialStore
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.FileKind
import com.nanahoshi.audioplayer.data.fileExtension
import com.nanahoshi.audioplayer.data.isAsmrAudio
import com.nanahoshi.audioplayer.data.previewKind
import com.nanahoshi.audioplayer.data.toHttps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class DlsiteException(message: String) : Exception(message)

data class DlsiteHit(
    val workno: String,
    val title: String,
    val circle: String,
    val coverUrl: String?,
)

data class DlsitePage(
    val works: List<DlsiteHit>,
    val total: Int,
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

data class DlsitePurchase(
    val workno: String,
    val title: String,
    val circle: String,
    val vas: List<String>,
    val coverUrl: String?,
    val release: String,
)

class DlsiteClient(private val credentials: CredentialStore) {
    private val client = OkHttpClient.Builder()
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

    fun hasSession(): Boolean = credentials.dlsiteCookie() != null

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
        parts += "work_type_category[0]" to "movie_audio"
        parts += "per_page" to PAGE_SIZE.toString()
        parts += "page" to page.toString()
        val path = parts.joinToString("/") { (key, value) -> "$key/${encode(value)}" }
        val body = get("https://www.dlsite.com/maniax/fsr/ajax/=/$path", STORE_REFERER, acceptJson = true)
        val root = JSONObject(body)
        val html = root.optString("search_result")
        val total = root.optJSONObject("page_info")?.optInt("count") ?: 0
        DlsitePage(parseHits(html), total)
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
            throw DlsiteException("DLsite 上没有这个作品")
        }
    }

    suspend fun login(loginId: String, password: String) = withContext(Dispatchers.IO) {
        val id = loginId.trim()
        if (id.isEmpty() || password.isEmpty()) throw DlsiteException("请输入账号和密码")
        val jar = HeaderCookieJar()
        val authed = client.newBuilder().cookieJar(jar).build()
        val page = authed.newCall(
            Request.Builder()
                .url("https://login.dlsite.com/login?user=self")
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://www.dlsite.com/")
                .build(),
        ).execute().use { response ->
            if (!response.isSuccessful) throw DlsiteException("无法打开登录页")
            response.body?.string().orEmpty()
        }
        val token = TOKEN.find(page)?.groupValues?.get(1)
            ?: TOKEN_ALT.find(page)?.groupValues?.get(1)
            ?: throw DlsiteException("登录页没有准备好")
        val form = FormBody.Builder()
            .add("_token", token)
            .add("login_id", id)
            .add("password", password)
            .build()
        val posting = authed.newBuilder().followRedirects(false).followSslRedirects(false).build()
        val loggedIn = posting.newCall(
            Request.Builder()
                .url("https://login.dlsite.com/login")
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://login.dlsite.com/login")
                .post(form)
                .build(),
        ).execute().use { response ->
            val body = response.body?.string().orEmpty()
            "ログイン中です" in body || response.code in 300..399
        }
        if (!loggedIn) throw DlsiteException("账号或密码不正确")
        authorizePlay(authed)
        credentials.saveDlsiteCookie(withAdult(jar.header()))
        owned = null
    }

    suspend fun loginWithCookie(raw: String) = withContext(Dispatchers.IO) {
        val cookie = raw.trim()
        if (cookie.isEmpty() || !cookie.contains("=")) throw DlsiteException("请粘贴 Cookie")
        credentials.saveDlsiteCookie(withAdult(cookie))
        try {
            execute(request("https://play.dlsite.com/login/", PLAY_REFERER, acceptJson = false, authed = true))
            execute(request("https://play.dlsite.com/api/authorize", PLAY_REFERER, acceptJson = true, authed = true))
            owned = null
            salesIds()
        } catch (error: DlsiteException) {
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
        val count = JSONObject(playGet("https://play.dlsite.com/api/v3/content/count?last=0"))
        if (count.optInt("user") < 1) return@withContext emptyList()
        val sales = JSONArray(playGet("https://play.dlsite.com/api/v3/content/sales?last=0"))
        val order = buildList {
            for (index in 0 until sales.length()) {
                val workno = sales.optJSONObject(index)?.optString("workno").orEmpty()
                if (workno.isNotBlank()) add(workno)
            }
        }
        owned = order.map(::canonicalWorkno).toSet()
        buildList {
            order.chunked(100).forEach { batch ->
                val body = JSONArray(batch).toString()
                val root = JSONObject(playPost("https://play.dlsite.com/api/v3/content/works", body))
                val works = root.optJSONArray("works") ?: return@forEach
                for (index in 0 until works.length()) {
                    parsePurchase(works.optJSONObject(index))?.let { add(it) }
                }
            }
        }
    }

    suspend fun isPurchased(input: String): Boolean = withContext(Dispatchers.IO) {
        if (!hasSession()) return@withContext false
        val key = canonicalWorkno(worknoOf(input))
        key in salesIds()
    }

    suspend fun playTree(input: String, artist: String?, coverUrl: String?): List<PlayEntry> =
        withContext(Dispatchers.IO) {
            val workno = worknoOf(input)
            val token = sign(workno)
            val tree = JSONObject(
                get(
                    token.url + "ziptree.json",
                    PLAY_REFERER,
                    acceptJson = false,
                    cookieOverride = token.cookie,
                ),
            )
            val files = tree.optJSONObject("playfile") ?: JSONObject()
            val nodes = tree.optJSONArray("tree") ?: JSONArray()
            buildList {
                for (index in 0 until nodes.length()) {
                    parsePlayNode(nodes.optJSONObject(index), files, token.url, workno, artist, coverUrl)?.let { add(it) }
                }
            }
        }

    fun fileUrlBlocking(workno: String, optimizedName: String): String =
        signedPlayFileBlocking(workno, optimizedName).url

    fun signedPlayFileBlocking(workno: String, optimizedName: String): SignedPlayFile {
        val token = sign(worknoOf(workno))
        val name = optimizedName.removePrefix("dlsite:")
        return SignedPlayFile(token.url.trimEnd('/') + "/optimized/" + name, token.cookie)
    }

    fun playCookie(): String? = playAccessCookie ?: cookieHeader()

    suspend fun readText(url: String, referer: String, authed: Boolean, limit: Int = TEXT_LIMIT): String =
        withContext(Dispatchers.IO) {
            val request = request(
                url,
                referer,
                acceptJson = false,
                authed = authed,
                cookieOverride = if (authed) playCookie() else null,
            )
            client.newCall(request).execute().use { response ->
                if (response.code == 401) throw DlsiteException("登录已失效，请重新登录")
                if (!response.isSuccessful) throw DlsiteException("无法打开这个文件")
                val input = response.body?.byteStream() ?: return@withContext ""
                val buffer = ByteArray(limit + 1)
                var total = 0
                while (total < buffer.size) {
                    val read = input.read(buffer, total, buffer.size - total)
                    if (read < 0) break
                    total += read
                }
                val clipped = total > limit
                val text = buffer.copyOf(minOf(total, limit)).toString(Charsets.UTF_8)
                if (clipped) text + "\n…（只显示前面一段）" else text
            }
        }

    fun cookieHeader(): String? = credentials.dlsiteCookie()?.let(::withAdult)

    private fun salesIds(): Set<String> {
        owned?.let { return it }
        val sales = JSONArray(playGet("https://play.dlsite.com/api/v3/content/sales?last=0"))
        val ids = buildSet {
            for (index in 0 until sales.length()) {
                val workno = sales.optJSONObject(index)?.optString("workno").orEmpty()
                if (workno.isNotBlank()) add(canonicalWorkno(workno))
            }
        }
        owned = ids
        return ids
    }

    private fun sign(workno: String): DownloadToken {
        var last: DlsiteException? = null
        for (id in worknoCandidates(workno)) {
            try {
                return signOnce(id)
            } catch (error: DlsiteException) {
                if (error.message?.contains("登录") == true) throw error
                last = error
            }
        }
        throw last ?: DlsiteException("没有可播放的地址")
    }

    private fun signOnce(workno: String): DownloadToken {
        val call = request(
            "https://play.dl.dlsite.com/api/v3/download/sign/cookie?workno=$workno",
            PLAY_REFERER,
            acceptJson = true,
            authed = true,
        )
        client.newCall(call).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 401) throw DlsiteException("登录已失效，请重新登录")
            if (!response.isSuccessful) throw DlsiteException("暂时无法连接 DLsite")
            val root = JSONObject(body)
            val url = root.optString("url")
            if (url.isBlank()) throw DlsiteException("没有可播放的地址")
            val directory = if (url.endsWith("/")) url else "$url/"
            val extras = buildList {
                add(cookiesOf(root.optJSONObject("cookies")))
                response.headers("Set-Cookie").forEach { add(it.substringBefore(';')) }
            }
            val cookie = playAccessCookie(cookieHeader(), extras)
            playAccessCookie = cookie
            return DownloadToken(directory, cookie)
        }
    }

    private fun cookiesOf(obj: JSONObject?): String {
        if (obj == null) return ""
        return buildList {
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = obj.optString(key)
                if (key.isNotBlank() && value.isNotBlank()) add("$key=$value")
            }
        }.joinToString("; ")
    }

    private fun product(workno: String): DlsiteShowcase? {
        val body = get(
            "https://www.dlsite.com/maniax/api/=/product.json?workno=$workno&locale=zh_CN",
            STORE_REFERER,
            acceptJson = true,
        )
        val array = JSONArray(body)
        val item = array.optJSONObject(0) ?: return null
        val makers = item.optJSONObject("creaters")
        val gallery = sampleImages(item)
        return DlsiteShowcase(
            workno = item.optString("workno").ifBlank { workno },
            title = item.optString("work_name").ifBlank { workno },
            circle = item.optString("maker_name"),
            vas = makers?.names("voice_by").orEmpty(),
            tags = item.names("genres"),
            release = item.optString("regist_date").substringBefore(' '),
            coverUrl = gallery.firstOrNull(),
            siteId = item.optString("site_id").ifBlank { "maniax" },
            gallery = gallery,
            trials = emptyList(),
        )
    }

    private fun workHtml(site: String, workno: String): String {
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

    private fun chobitOf(workno: String, html: String, coverUrl: String?): List<RemotePlayable> {
        val embeds = (chobitEmbeds(workno) + CHOBIT_EMBED.findAll(html).map { it.value.toHttps() })
            .distinct()
            .take(4)
        return embeds.flatMap { embed ->
            val referer = embed.substringBefore('?')
            val page = try {
                get(embed, "https://chobit.cc/", acceptJson = false)
            } catch (_: DlsiteException) {
                return@flatMap emptyList()
            }
            parseChobit(page, referer, coverUrl)
        }.toList()
    }

    private fun chobitEmbeds(workno: String): List<String> {
        for (id in worknoCandidates(workno)) {
            val body = try {
                get("https://chobit.cc/api/v1/dlsite/embed?workno=$id", "https://chobit.cc/", acceptJson = true)
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

    private fun authorizePlay(authed: OkHttpClient) {
        val login = authed.newCall(
            Request.Builder()
                .url("https://play.dlsite.com/login/")
                .header("User-Agent", USER_AGENT)
                .header("Referer", PLAY_REFERER)
                .build(),
        ).execute()
        login.close()
        authed.newCall(
            Request.Builder()
                .url("https://play.dlsite.com/api/authorize")
                .header("User-Agent", USER_AGENT)
                .header("Referer", PLAY_REFERER)
                .header("Accept", "application/json")
                .build(),
        ).execute().use { response ->
            if (response.code == 401) throw DlsiteException("登录已失效，请重新登录")
        }
    }

    private fun playGet(url: String): String = get(url, PLAY_REFERER, acceptJson = true, authed = true)

    private fun playPost(url: String, json: String): String {
        val request = request(url, PLAY_REFERER, acceptJson = true, authed = true)
            .newBuilder()
            .post(json.toRequestBody(JSON_MEDIA))
            .build()
        return execute(request)
    }

    private fun get(
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
            authed -> cookieHeader() ?: throw DlsiteException("请先登录 DLsite")
            else -> withAdult(credentials.dlsiteCookie().orEmpty())
        }
        if (cookie.isNotBlank()) builder.header("Cookie", cookie)
        return builder.build()
    }

    private fun execute(request: Request): String {
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.code == 401) throw DlsiteException("登录已失效，请重新登录")
                if (!response.isSuccessful) throw DlsiteException("暂时无法连接 DLsite")
                return body
            }
        } catch (error: DlsiteException) {
            throw error
        } catch (_: IOException) {
            throw DlsiteException("暂时无法连接 DLsite")
        }
    }

    private data class DownloadToken(val url: String, val cookie: String)

    private class HeaderCookieJar : CookieJar {
        private val all = mutableListOf<Cookie>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (cookie in cookies) {
                all.removeAll { it.name == cookie.name && it.domain == cookie.domain }
                all += cookie
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = all.filter { it.matches(url) }

        fun header(): String = all.distinctBy { it.name }.joinToString("; ") { "${it.name}=${it.value}" }
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
        const val STORE_REFERER = "https://www.dlsite.com/maniax/"
        const val PLAY_REFERER = "https://play.dlsite.com/"
        private const val PAGE_SIZE = 20
        private const val TEXT_LIMIT = 512 * 1024
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val TOKEN = Regex("""name="_token"[^>]*value="([^"]+)"""")
        private val TOKEN_ALT = Regex("""value="([^"]+)"[^>]*name="_token"""")
        private val PRODUCT_ID = Regex("""data-(?:list_item_)?product_id="([^"]+)"""")
        private val WORK_NAME = Regex("""class="work_name"[\s\S]*?<a[^>]*title="([^"]*)"""")
        private val MAKER = Regex("""class="maker_name"[\s\S]*?<a[^>]*>([^<]*)</a>""")
        private val THUMB = Regex("""class="work_thumb[\s\S]*?(?:data-src|src)="([^"]+)"""")
        private val MEDIA = Regex("""(?i)(?:https?:)?//[^"'\\\s>]+\.(?:mp3|m4a|aac|wav|ogg)(?:\?[^"'\\\s]*)?""")
        private val CHOBIT_EMBED = Regex("""(?i)(?:https?:)?//chobit\.cc/embed/[0-9A-Za-z]+/[0-9A-Za-z]+""")
        private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "wav", "ogg")
        private val WORKNO = Regex("(?i)(?:RJ|VJ|BJ)\\d+")

        fun worknoOf(input: String): String {
            val match = WORKNO.find(input)
            if (match != null) return match.value.uppercase()
            val digits = input.filter { it.isDigit() }
            if (digits.isNotEmpty()) return "RJ$digits"
            throw DlsiteException("没有作品编号")
        }

        internal fun worknoCandidates(workno: String): List<String> {
            val match = Regex("(?i)(RJ|VJ|BJ)0*(\\d+)").find(workno) ?: return listOf(workno.trim())
            val prefix = match.groupValues[1].uppercase()
            val digits = match.groupValues[2].trimStart('0').ifEmpty { "0" }
            return listOf(
                workno.trim().uppercase(),
                prefix + digits.padStart(8, '0'),
                prefix + digits.padStart(6, '0'),
                prefix + digits,
            ).distinct()
        }

        internal fun embedUrls(json: String): List<String> {
            val works = JSONObject(json).optJSONArray("works") ?: return emptyList()
            return buildList {
                for (index in 0 until works.length()) {
                    val url = works.optJSONObject(index)?.optString("embed_url").orEmpty().toHttps()
                    if (url.startsWith("https://")) add(url)
                }
            }
        }

        fun canonicalWorkno(raw: String): String {
            val match = Regex("(?i)(RJ|VJ|BJ)0*(\\d+)").find(raw) ?: return raw.trim().uppercase()
            val digits = match.groupValues[2].trimStart('0').ifEmpty { "0" }
            return match.groupValues[1].uppercase() + digits
        }

        private fun encode(value: String): String =
            URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

        private fun withAdult(cookie: String): String = mergeCookies(cookie, "adultchecked=1")

        internal fun playAccessCookie(session: String?, extras: List<String>): String {
            var merged = session.orEmpty()
            extras.forEach { part ->
                if (part.isNotBlank()) merged = mergeCookies(merged, part)
            }
            return merged
        }

        private fun mergeCookies(base: String, extra: String): String {
            val map = linkedMapOf<String, String>()
            fun put(header: String) {
                header.split(';').forEach { part ->
                    val trimmed = part.trim()
                    if (!trimmed.contains('=')) return@forEach
                    val key = trimmed.substringBefore('=').trim()
                    if (key.isNotEmpty()) map[key] = trimmed.substringAfter('=').trim()
                }
            }
            put(base)
            put(extra)
            return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }

        private fun parseHits(html: String): List<DlsiteHit> {
            val marks = PRODUCT_ID.findAll(html).toList()
            return marks.mapIndexed { index, mark ->
                val end = marks.getOrNull(index + 1)?.range?.first ?: html.length
                val chunk = html.substring(mark.range.first, end)
                val workno = mark.groupValues[1].uppercase()
                val title = unescape(WORK_NAME.find(chunk)?.groupValues?.get(1).orEmpty()).ifBlank { workno }
                val circle = unescape(MAKER.find(chunk)?.groupValues?.get(1).orEmpty())
                val cover = THUMB.find(chunk)?.groupValues?.get(1).orEmpty().toHttps().ifBlank { null }
                DlsiteHit(workno, title, circle, cover)
            }
        }

        private fun sampleImages(item: JSONObject): List<String> {
            val urls = mutableListOf<String>()
            fun add(raw: String) {
                val url = raw.toHttps()
                if (url.startsWith("https://")) urls += url
            }
            item.optJSONObject("image_main")?.optString("url")?.let(::add)
            val samples = item.optJSONArray("image_samples")
            if (samples != null) {
                for (index in 0 until samples.length()) {
                    when (val value = samples.opt(index)) {
                        is JSONObject -> add(value.optString("url"))
                        is String -> add(value)
                    }
                }
            }
            return urls.distinct()
        }

        internal fun parseChobit(html: String, referer: String, coverUrl: String?): List<RemotePlayable> {
            return Regex("""<li\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html).mapNotNull { match ->
                val tag = match.value
                val src = attr(tag, "data-src")?.toHttps() ?: return@mapNotNull null
                val extension = src.substringBefore('?').substringAfterLast('.').lowercase()
                if (extension !in AUDIO_EXT) return@mapNotNull null
                val title = unescape(attr(tag, "data-title").orEmpty()).ifBlank { "试听" }
                RemotePlayable(
                    title = title,
                    artist = null,
                    coverUri = coverUrl,
                    remoteUrl = src,
                    source = TrackSource.DLSITE,
                    referer = referer,
                    durationMs = playtimeMs(attr(tag, "data-playtime").orEmpty()),
                )
            }.distinctBy { it.remoteUrl }.toList()
        }

        private fun attr(tag: String, name: String): String? =
            Regex("""\b$name="([^"]*)"""").find(tag)?.groupValues?.get(1)

        private fun playtimeMs(raw: String): Long {
            val parts = raw.trim().split(":").mapNotNull { it.toLongOrNull() }
            return when (parts.size) {
                3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
                2 -> (parts[0] * 60 + parts[1]) * 1000
                1 -> parts[0] * 1000
                else -> 0
            }
        }

        private fun galleryOf(html: String): List<String> {
            val start = html.indexOf("product-slider-data")
            if (start < 0) return emptyList()
            val end = html.indexOf("work_slider", start).takeIf { it > start } ?: minOf(html.length, start + 12000)
            return Regex("""data-src="([^"]+)"""").findAll(html.substring(start, end))
                .map { it.groupValues[1].toHttps() }
                .filter { it.isNotBlank() }
                .distinct()
                .toList()
        }

        private fun trialsOf(html: String, title: String, coverUrl: String?): List<RemotePlayable> {
            val start = html.indexOf("trial_download")
            val slice = if (start >= 0) html.substring(start, minOf(html.length, start + 20000)) else html
            val urls = MEDIA.findAll(slice).map { it.value.toHttps() }.distinct().toList()
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

        private fun titleOf(html: String): String {
            val match = Regex("""id="work_name"[^>]*>([\s\S]*?)</h1>""").find(html) ?: return ""
            return unescape(match.groupValues[1].replace(Regex("<[^>]+>"), "")).trim()
        }

        private fun circleOf(html: String): String {
            val match = Regex("""id="work_maker"[\s\S]*?class="maker_name"[\s\S]*?<a[^>]*>([^<]*)</a>""")
                .find(html) ?: return ""
            return unescape(match.groupValues[1]).trim()
        }

        private fun unescape(raw: String): String = raw
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .trim()

        private fun parsePurchase(item: JSONObject?): DlsitePurchase? {
            if (item == null) return null
            val workno = item.optString("workno")
            if (workno.isBlank()) return null
            val maker = item.optJSONObject("maker")
            val files = item.optJSONObject("work_files")
            val cover = files?.opt("main").asUrl()
            val vas = buildList {
                val tags = item.optJSONArray("tags") ?: return@buildList
                for (index in 0 until tags.length()) {
                    val tag = tags.optJSONObject(index) ?: continue
                    if (tag.optString("class") == "voice_by") {
                        val name = localized(tag.opt("name"))
                        if (name.isNotBlank()) add(name)
                    }
                }
            }
            return DlsitePurchase(
                workno = workno,
                title = localized(item.opt("name")).ifBlank { workno },
                circle = localized(maker?.opt("name")),
                vas = vas,
                coverUrl = cover,
                release = item.optString("regist_date").substringBefore('T').substringBefore(' '),
            )
        }

        private fun localized(value: Any?): String = when (value) {
            is String -> value
            is JSONObject -> value.optString("zh_CN").ifBlank {
                value.optString("ja_JP").ifBlank {
                    value.keys().asSequence().map { value.optString(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
                }
            }
            else -> ""
        }

        private fun Any?.asUrl(): String? = when (this) {
            is String -> toHttps().ifBlank { null }
            is JSONObject -> optString("url").ifBlank { optString("path") }.toHttps().ifBlank { null }
            else -> null
        }

        private fun parsePlayNode(
            node: JSONObject?,
            files: JSONObject,
            directory: String,
            workno: String,
            artist: String?,
            coverUrl: String?,
        ): PlayEntry? {
            if (node == null) return null
            when (node.optString("type")) {
                "hidden" -> return null
                "folder" -> {
                    val children = node.optJSONArray("children") ?: JSONArray()
                    val nested = buildList {
                        for (index in 0 until children.length()) {
                            parsePlayNode(children.optJSONObject(index), files, directory, workno, artist, coverUrl)
                                ?.let { add(it) }
                        }
                    }
                    if (nested.isEmpty()) return null
                    return PlayEntry(node.optString("name").ifBlank { "文件夹" }, nested, null, null)
                }
                "file" -> {
                    val hash = node.optString("hashname")
                    val info = files.optJSONObject(hash) ?: return null
                    val type = info.optString("type")
                    val optimized = info.optJSONObject(type)?.optJSONObject("optimized")
                        ?: info.optJSONObject("optimized")
                        ?: return null
                    if (optimized.optBoolean("crypt")) return null
                    val fileName = optimized.optString("name")
                    if (fileName.isBlank() || fileName.endsWith(".m3u8", ignoreCase = true)) return null
                    val url = directory.trimEnd('/') + "/optimized/" + fileName
                    val title = node.optString("name").ifBlank { fileName }
                    val duration = (optimized.optDouble("duration", 0.0) * 1000).toLong().coerceAtLeast(0)
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
                        if (kind == FileKind.OTHER && fileExtension(fileName) !in setOf("jpg", "jpeg", "png", "webp", "gif", "txt")) {
                            return null
                        }
                        PlayEntry(
                            title = title,
                            children = emptyList(),
                            audio = null,
                            preview = RemotePreview(title, url, kind, PLAY_REFERER, authed = true),
                        )
                    }
                }
                else -> return null
            }
        }
    }
}

private fun JSONObject.names(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val name = array.optJSONObject(index)?.optString("name").orEmpty()
            if (name.isNotBlank()) add(name)
        }
    }
}
