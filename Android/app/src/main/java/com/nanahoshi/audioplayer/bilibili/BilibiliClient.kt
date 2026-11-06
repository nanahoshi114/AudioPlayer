package com.nanahoshi.audioplayer.bilibili

import com.nanahoshi.audioplayer.data.CredentialStore
import com.nanahoshi.audioplayer.data.toHttps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import android.util.Log
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class BiliPart(
    val bvid: String,
    val cid: Long,
    val page: Int,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val coverUrl: String,
)

data class BiliVideoDetails(val video: BiliVideoHit, val parts: List<BiliPart>, val collection: BiliCollection? = null)

class BilibiliException(val code: Int, message: String) : Exception(message)

data class QrLogin(val url: String, val key: String)

sealed interface QrPoll {
    data object Waiting : QrPoll
    data object Scanned : QrPoll
    data object Expired : QrPoll
    data object Confirmed : QrPoll
}

data class FavFolder(
    val id: Long,
    val title: String,
    val mediaCount: Int,
)

data class FavVideo(
    val bvid: String,
    val title: String,
    val coverUrl: String,
    val author: String,
)

data class FavVideoPage(
    val videos: List<FavVideo>,
    val hasMore: Boolean,
)

/** B站字幕的取用结果。空列表不等于出错，要区分没字幕、需要登录和读取失败。 */
sealed interface BiliSubtitle {
    data class Ready(val document: String, val language: String? = null) : BiliSubtitle
    data object NeedsLogin : BiliSubtitle
    data object Missing : BiliSubtitle
    data object Failed : BiliSubtitle
}

/** 字幕列表为空时，判断是不是登录问题：没有 Cookie，或接口没认出登录态（过期/失效时 login_mid 为 0）。 */
internal fun biliNeedsLogin(hasSession: Boolean, loginMid: Long): Boolean =
    !hasSession || loginMid == 0L

data class BiliAudio(
    val url: String,
    val bandwidth: Int,
    val codecs: String,
    val mimeType: String,
    val durationSeconds: Double,
    val initializationRange: String,
    val indexRange: String,
)

class BilibiliClient(
    private val credentials: CredentialStore,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Volatile private var mixinKey: String? = null
    @Volatile private var mixinFetchedAt: Long = 0
    private val sessionRefreshLock = Any()
    private val searchApi = BiliSearchApi { path, params, referer, refresh ->
        val signed = WbiSigner.sign(params, mixin(refresh))
        val url = "https://api.bilibili.com$path".toHttpUrl().newBuilder().apply {
            signed.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        browseGet(url.toString(), referer)
    }

    val videoCollections = BiliVideoCollectionCache()
    val collections = BiliCollectionApi(searchApi::data)

    suspend fun searchVideos(
        keyword: String, page: Int = 1,
        order: BiliVideoOrder = BiliVideoOrder.TOTAL_RANK,
        duration: BiliVideoDuration = BiliVideoDuration.ALL,
    ) = searchApi.videos(keyword, page, order, duration)
    suspend fun searchUploaders(keyword: String, page: Int = 1) = searchApi.uploaders(keyword, page)
    suspend fun uploaderVideos(mid: Long, page: Int = 1) = searchApi.uploads(mid, page)

    /** 浏览请求可以随查询取消；不把服务端正文或带参数的网址放进异常。 */
    private suspend fun browseGet(url: String, referer: String): String = withContext(Dispatchers.IO) {
        prepareIdentity()
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT)
            .header("Referer", referer).header("Origin", originOf(referer))
            .header("Accept", "application/json, text/plain, */*")
            .apply { credentials.cookieHeader().takeIf { it.isNotBlank() }?.let { header("Cookie", it) } }.build()
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    if (continuation.isActive) continuation.resumeWithException(BilibiliException(0, "连不上B站，请检查网络后重试"))
                }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    try {
                        val raw = response.use {
                            if (!it.isSuccessful) throw BilibiliException(it.code, biliSearchError(it.code))
                            val body = it.body?.string().orEmpty()
                            if (body.trimStart().startsWith('<')) throw BilibiliException(-412, biliSearchError(-412))
                            body
                        }
                        if (continuation.isActive) continuation.resume(raw)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            if (error is BilibiliException) error else BilibiliException(0, "B站读取失败，请重试"))
                    }
                }
            })
        }
    }

    suspend fun requestQrLogin(): QrLogin = withContext(Dispatchers.IO) {
        prepareIdentity()
        val body = execute(
            Request.Builder().url("https://passport.bilibili.com/x/passport-login/web/qrcode/generate").get(),
            "https://passport.bilibili.com/login",
            guestOnly = true,
        )
        val response = json.decodeFromString<ApiResponse<QrGenerateData>>(body)
        val url = response.data?.url.orEmpty()
        val key = response.data?.qrcodeKey.orEmpty()
        if (response.code != 0 || url.isBlank() || key.isBlank()) {
            throw BilibiliException(response.code, "没有拿到登录二维码")
        }
        QrLogin(url, key)
    }

    suspend fun pollQrLogin(key: String): QrPoll = withContext(Dispatchers.IO) {
        val url = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll".toHttpUrl()
            .newBuilder()
            .addQueryParameter("qrcode_key", key)
            .build()
        val exchange = exchange(
            Request.Builder().url(url).get(),
            "https://passport.bilibili.com/login",
            guestOnly = true,
        )
        val response = json.decodeFromString<ApiResponse<QrPollData>>(exchange.body)
        val data = response.data ?: throw BilibiliException(response.code, "登录状态没有返回")
        when (data.code) {
            QR_WAITING -> QrPoll.Waiting
            QR_SCANNED -> QrPoll.Scanned
            QR_EXPIRED -> QrPoll.Expired
            0 -> {
                val cookie = combineSessionCookies(
                    sessionCookiesFromSetCookie(exchange.setCookies),
                    sessionCookiesFromUrl(data.url),
                )
                if (cookieField(cookie, "SESSDATA") == null) {
                    throw BilibiliException(0, "登录没有返回有效信息")
                }
                credentials.saveBiliSession(cookie, data.refreshToken)
                QrPoll.Confirmed
            }
            else -> throw BilibiliException(data.code, "扫码登录失败")
        }
    }

    suspend fun favoriteFolders(): List<FavFolder> {
        requireSession()
        val mid = loginMid()
        val body = get(
            "https://api.bilibili.com/x/v3/fav/folder/created/list-all?up_mid=$mid",
            referer = "https://space.bilibili.com/$mid/favlist",
        )
        val response = json.decodeFromString<ApiResponse<FolderListData>>(body)
        val folders = response.data
        if (response.code != 0 || folders == null) {
            Log.w(TAG, "favorite folders code=${response.code} message=${response.detail()}")
            val fallback = if (response.code == 0) {
                "登录有效，但没有读到收藏夹"
            } else {
                "无法读取收藏夹（${response.code}）"
            }
            throw BilibiliException(response.code, response.detail(fallback))
        }
        return folders.list.orEmpty()
            .filter { it.id > 0 }
            .map { FavFolder(id = it.id, title = it.title.ifBlank { "未命名收藏夹" }, mediaCount = it.mediaCount) }
    }

    suspend fun favoriteVideos(folderId: Long, page: Int): FavVideoPage {
        requireSession()
        val body = get(
            "https://api.bilibili.com/x/v3/fav/resource/list?media_id=$folderId&pn=$page&ps=$PAGE_SIZE&platform=web",
            referer = "https://space.bilibili.com",
        )
        val response = json.decodeFromString<ApiResponse<ResourceListData>>(body)
        if (response.code != 0) {
            Log.w(TAG, "favorite videos code=${response.code} message=${response.detail()}")
            throw BilibiliException(response.code, response.detail("无法读取收藏夹（${response.code}）"))
        }
        val medias = response.data?.medias.orEmpty()
        val videos = medias.mapNotNull { media ->
            val bvid = BV_REGEX.find(media.bvid)?.value ?: return@mapNotNull null
            FavVideo(
                bvid = bvid,
                title = media.title.ifBlank { bvid },
                coverUrl = media.cover.toHttps(),
                author = media.upper.name,
            )
        }
        val hasMore = response.data?.hasMore == true || medias.size >= PAGE_SIZE
        return FavVideoPage(videos, hasMore)
    }

    suspend fun subtitleDocument(bvid: String, cid: Long): BiliSubtitle {
        var last: BiliSubtitle = BiliSubtitle.Failed
        repeat(2) { attempt ->
            last = fetchSubtitle(bvid, cid)
            if (last is BiliSubtitle.Ready || last is BiliSubtitle.NeedsLogin) return last
            // 限流时字幕列表或正文可能为空，换一次新的播放信息地址再试。
            if (attempt == 0) delay(SUBTITLE_RETRY_DELAY_MS)
        }
        return last
    }

    private suspend fun fetchSubtitle(bvid: String, cid: Long): BiliSubtitle {
        return try {
            val root = playerInfo(bvid, cid)
            val code = root.optInt("code")
            if (code != 0) {
                Log.w(TAG, "subtitle skipped code=$code bvid=$bvid cid=$cid")
                BiliSubtitle.Failed
            } else {
                val data = root.optJSONObject("data") ?: return BiliSubtitle.Failed
                val returnedBvid = data.optString("bvid").orEmpty()
                val returnedCid = data.optLong("cid")
                if ((returnedBvid.isNotBlank() && returnedBvid != bvid) || (returnedCid != 0L && returnedCid != cid)) {
                    Log.w(TAG, "subtitle skipped mismatched bvid=$returnedBvid cid=$returnedCid")
                    return BiliSubtitle.Failed
                }
                val list = data.optJSONObject("subtitle")?.optJSONArray("subtitles")
                val count = list?.length() ?: 0
                val needLogin = data.optBoolean("need_login_subtitle")
                val loginMid = data.optLong("login_mid")
                if (count == 0) {
                    Log.w(TAG, "subtitle empty bvid=$bvid cid=$cid needLogin=$needLogin loginMid=$loginMid")
                    return if (biliNeedsLogin(!credentials.cookie().isNullOrBlank(), loginMid)) {
                        BiliSubtitle.NeedsLogin
                    } else {
                        BiliSubtitle.Missing
                    }
                }
                val selected = pickSubtitle(list)
                if (selected == null) {
                    Log.w(TAG, "subtitle url missing bvid=$bvid cid=$cid tracks=$count")
                    return BiliSubtitle.Failed
                }
                // 字幕正文在 CDN 上，不需要登录态，不带 SESSDATA。
                val document = get(selected.first, referer = "https://www.bilibili.com/video/$bvid", guestOnly = true)
                val body = try {
                    JSONObject(document).optJSONArray("body")?.length() ?: 0
                } catch (_: Exception) {
                    0
                }
                Log.d(TAG, "subtitle loaded bvid=$bvid cid=$cid tracks=$count body=$body")
                if (body == 0) BiliSubtitle.Failed else BiliSubtitle.Ready(document, selected.second)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "subtitle skipped: ${error.javaClass.simpleName}")
            BiliSubtitle.Failed
        }
    }

    private suspend fun playerInfo(bvid: String, cid: Long): JSONObject {
        var root = requestPlayerInfo(bvid, cid, refreshKey = false)
        val code = root.optInt("code")
        if (code == -403 || code == -400 || code == -412) {
            root = requestPlayerInfo(bvid, cid, refreshKey = true)
        }
        return root
    }

    private suspend fun requestPlayerInfo(bvid: String, cid: Long, refreshKey: Boolean): JSONObject {
        val signed = WbiSigner.sign(
            mapOf(
                "bvid" to bvid,
                "cid" to cid.toString(),
            ),
            mixin(refreshKey),
        )
        val url = "https://api.bilibili.com/x/player/wbi/v2".toHttpUrl().newBuilder().apply {
            signed.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        return JSONObject(get(url.toString(), referer = "https://www.bilibili.com/video/$bvid"))
    }

    private fun pickSubtitle(list: JSONArray?): Pair<String, String?>? {
        if (list == null || list.length() == 0) return null
        var chosen: Pair<String, String?>? = null
        var chosenRank = Int.MAX_VALUE
        for (index in 0 until list.length()) {
            val item = list.optJSONObject(index) ?: continue
            val url = item.optString("subtitle_url").let { raw ->
                when {
                    raw.startsWith("//") -> "https:$raw"
                    raw.startsWith("http://") -> raw.replaceFirst("http://", "https://")
                    raw.startsWith("https://") -> raw
                    else -> ""
                }
            }
            if (url.isBlank()) continue
            val rank = subtitleLanguageRank(item.optString("lan"))
            if (rank < chosenRank) {
                chosenRank = rank
                chosen = url to item.optString("lan").takeIf { it.isNotBlank() }
            }
        }
        return chosen
    }

    private fun subtitleLanguageRank(language: String): Int {
        val value = language.lowercase()
        return when {
            value.startsWith("zh") -> 0
            "zh" in value -> 1
            else -> 2
        }
    }

    private fun requireSession() {
        val raw = credentials.cookie()?.trim().orEmpty()
        if (raw.isEmpty()) {
            throw BilibiliException(-101, "请先在设置里保存登录信息")
        }
        if (raw.contains("=") && !raw.contains("SESSDATA=")) {
            throw BilibiliException(-101, "保存的内容里没有 SESSDATA，请重新粘贴")
        }
    }

    private suspend fun loginMid(): Long {
        val body = get("https://api.bilibili.com/x/web-interface/nav")
        val response = json.decodeFromString<ApiResponse<NavData>>(body)
        val mid = response.data?.mid ?: 0L
        if (response.code != 0 || mid <= 0L) {
            throw BilibiliException(response.code, "登录信息无效或已过期，请重新粘贴 SESSDATA")
        }
        return mid
    }

    suspend fun lookup(input: String): List<BiliPart> = videoDetails(input).parts

    suspend fun videoDetails(input: String): BiliVideoDetails {
        val bvid = BV_REGEX.find(input)?.value
            ?: throw BilibiliException(0, "请输入正确的 BV 号")
        val body = browseGet("https://api.bilibili.com/x/web-interface/view?bvid=$bvid", "https://www.bilibili.com/video/$bvid")
        return parseBiliVideoDetails(body, bvid).also { details ->
            videoCollections.record(details.video.bvid, details.collection)
        }
    }

    fun resolveAudioBlocking(bvid: String, cid: Long): BiliAudio =
        runBlocking { resolveAudio(bvid, cid) }

    suspend fun resolveAudio(bvid: String, cid: Long): BiliAudio {
        return try {
            requestAudio(bvid, cid, refreshKey = false)
        } catch (error: BilibiliException) {
            if (error.code == -403 || error.code == -400 || error.code == -412) {
                credentials.clearTicket()
                requestAudio(bvid, cid, refreshKey = true)
            } else {
                throw error
            }
        }
    }

    private suspend fun requestAudio(bvid: String, cid: Long, refreshKey: Boolean): BiliAudio {
        val signed = WbiSigner.sign(
            mapOf(
                "bvid" to bvid,
                "cid" to cid.toString(),
                "qn" to "127",
                "fnver" to "0",
                "fnval" to "4048",
                "fourk" to "1",
                "platform" to "pc",
            ),
            mixin(refreshKey),
        )
        val url = "https://api.bilibili.com/x/player/wbi/playurl".toHttpUrl().newBuilder().apply {
            signed.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val body = get(url.toString(), referer = "https://www.bilibili.com/video/$bvid")
        val response = json.decodeFromString<ApiResponse<PlayData>>(body)
        val data = response.data
        if (response.code != 0 || data == null) {
            throw BilibiliException(response.code, friendly(response.code, response.message))
        }
        val best = buildList {
            data.dash?.audio?.let(::addAll)
            data.dash?.dolby?.audio?.let(::addAll)
            data.dash?.flac?.audio?.let(::add)
        }.filter { it.playUrl.isNotBlank() }.maxByOrNull { it.bandwidth }
            ?: throw BilibiliException(0, "没有可播放的音轨，可能需要登录或大会员")
        val segment = best.segmentBase ?: best.segmentBaseAlt
        val initRange = segment?.initialization ?: segment?.initializationAlt
        val indexRange = segment?.indexRange ?: segment?.indexRangeAlt
        if (initRange.isNullOrBlank() || indexRange.isNullOrBlank()) {
            throw BilibiliException(0, "B站没有返回可播放的音轨分段")
        }
        return BiliAudio(
            url = best.playUrl.toHttps(),
            bandwidth = best.bandwidth.coerceAtLeast(1),
            codecs = best.codecs.ifBlank { "mp4a.40.2" },
            mimeType = best.mimeType.ifBlank { best.mimeTypeAlt }.ifBlank { "audio/mp4" },
            durationSeconds = (data.dash?.duration ?: 0.0).coerceAtLeast(1.0),
            initializationRange = initRange,
            indexRange = indexRange,
        )
    }

    private fun friendly(code: Int, message: String): String {
        val text = message.ifBlank { "B站返回错误 $code" }
        return if (code == -404) "找不到这个视频" else text
    }

    private suspend fun mixin(force: Boolean): String {
        val cached = mixinKey
        val fresh = cached != null && System.currentTimeMillis() - mixinFetchedAt < KEY_TTL_MS
        if (!force && fresh) return cached
        val body = get("https://api.bilibili.com/x/web-interface/nav")
        val response = json.decodeFromString<ApiResponse<NavData>>(body)
        val img = response.data?.wbiImg?.imgUrl.orEmpty()
        val sub = response.data?.wbiImg?.subUrl.orEmpty()
        if (img.isBlank() || sub.isBlank()) {
            throw BilibiliException(response.code, "暂时无法连接 B 站")
        }
        val key = WbiSigner.mixinKey(keyFromUrl(img), keyFromUrl(sub))
        mixinKey = key
        mixinFetchedAt = System.currentTimeMillis()
        return key
    }

    private suspend fun get(
        url: String,
        referer: String = "https://www.bilibili.com",
        guestOnly: Boolean = false,
    ): String =
        withContext(Dispatchers.IO) {
            prepareIdentity()
            execute(Request.Builder().url(url).get(), referer, guestOnly)
        }

    private fun prepareIdentity() {
        if (credentials.needsSpiBuvid()) {
            try {
                val body = execute(
                    Request.Builder().url("https://api.bilibili.com/x/frontend/finger/spi").get(),
                    "https://www.bilibili.com",
                    guestOnly = true,
                )
                val response = json.decodeFromString<ApiResponse<SpiData>>(body)
                val buvid3 = response.data?.b3.orEmpty()
                val buvid4 = response.data?.b4.orEmpty()
                if (response.code == 0 && buvid3.isNotBlank()) {
                    credentials.saveSpiBuvid(buvid3, buvid4)
                }
            } catch (error: Exception) {
                Log.w(TAG, "buvid skipped: ${error.javaClass.simpleName}")
            }
        }
        if (!credentials.hasFreshTicket()) {
            try {
                fetchTicket()
            } catch (error: Exception) {
                Log.w(TAG, "ticket skipped: ${error.javaClass.simpleName}")
            }
        }
        maybeRefreshSession()
    }

    private fun maybeRefreshSession() {
        if (!credentials.hasBiliRefresh() || !credentials.biliRefreshDue()) return
        synchronized(sessionRefreshLock) {
            val token = credentials.biliRefreshToken() ?: return
            if (!credentials.biliRefreshDue()) return
            val info = try {
                val csrf = cookieField(credentials.cookie().orEmpty(), "bili_jct").orEmpty()
                val url = "https://passport.bilibili.com/x/passport-login/web/cookie/info".toHttpUrl()
                    .newBuilder()
                    .apply { if (csrf.isNotBlank()) addQueryParameter("csrf", csrf) }
                    .build()
                val body = execute(Request.Builder().url(url).get(), "https://www.bilibili.com")
                json.decodeFromString<ApiResponse<CookieInfoData>>(body)
            } catch (error: Exception) {
                Log.w(TAG, "session check skipped: ${error.javaClass.simpleName}")
                credentials.markBiliRefreshFailed()
                return
            }
            val needsRefresh = info.code == -101 || info.data?.refresh == true
            try {
                if (!needsRefresh || refreshSession(token, info.data?.timestamp ?: 0L)) {
                    credentials.markBiliRefreshChecked()
                } else {
                    credentials.markBiliRefreshFailed()
                }
            } catch (error: Exception) {
                Log.w(TAG, "session refresh skipped: ${error.javaClass.simpleName}")
                credentials.markBiliRefreshFailed()
            }
        }
    }

    private fun refreshSession(oldToken: String, timestamp: Long): Boolean {
        val csrf = cookieField(credentials.cookie().orEmpty(), "bili_jct") ?: return false
        val stamp = timestamp.takeIf { it > 0L } ?: System.currentTimeMillis()
        val page = exchange(
            Request.Builder().url("https://www.bilibili.com/correspond/1/${correspondPath(stamp)}").get(),
            "https://www.bilibili.com",
            allowHtml = true,
        )
        val refreshCsrf = refreshCsrf(page.body) ?: return false
        val refreshed = exchange(
            Request.Builder()
                .url("https://passport.bilibili.com/x/passport-login/web/cookie/refresh")
                .post(
                    FormBody.Builder()
                        .add("csrf", csrf)
                        .add("refresh_csrf", refreshCsrf)
                        .add("source", "main_web")
                        .add("refresh_token", oldToken)
                        .build(),
                ),
            "https://www.bilibili.com",
        )
        val response = json.decodeFromString<ApiResponse<RefreshTokenData>>(refreshed.body)
        val newToken = response.data?.refreshToken.orEmpty()
        val newCookie = combineSessionCookies(
            sessionCookiesFromSetCookie(refreshed.setCookies),
            credentials.cookie().orEmpty(),
        )
        if (response.code != 0 || newToken.isBlank() || cookieField(newCookie, "SESSDATA") == null) {
            Log.w(TAG, "session refresh rejected code=${response.code}")
            return false
        }
        credentials.saveBiliSession(newCookie, newToken)
        // 新会话已保存即视为续期成功；作废旧 token 只是尽力而为。
        val newCsrf = cookieField(newCookie, "bili_jct") ?: return true
        val confirmed = execute(
            Request.Builder()
                .url("https://passport.bilibili.com/x/passport-login/web/confirm/refresh")
                .post(
                    FormBody.Builder()
                        .add("csrf", newCsrf)
                        .add("refresh_token", oldToken)
                        .build(),
                ),
            "https://www.bilibili.com",
        )
        val confirmCode = JSONObject(confirmed).optInt("code", -1)
        if (confirmCode != 0) Log.w(TAG, "session confirm code=$confirmCode")
        return true
    }

    private fun fetchTicket() {
        val ts = System.currentTimeMillis() / 1000
        val hexSign = hmacSha256("XgwSnGZ1p", "ts$ts")
        val url = "https://api.bilibili.com/bapis/bilibili.api.ticket.v1.Ticket/GenWebTicket".toHttpUrl()
            .newBuilder()
            .addQueryParameter("key_id", "ec02")
            .addQueryParameter("hexsign", hexSign)
            .addQueryParameter("context[ts]", ts.toString())
            .addQueryParameter("csrf", "")
            .build()
        val body = execute(
            Request.Builder().url(url).post(ByteArray(0).toRequestBody(null)),
            "https://www.bilibili.com",
            guestOnly = true,
        )
        val response = json.decodeFromString<ApiResponse<TicketData>>(body)
        val ticket = response.data?.ticket.orEmpty()
        if (response.code != 0 || ticket.isBlank()) {
            Log.w(TAG, "ticket rejected code=${response.code} message=${response.message}")
            return
        }
        val created = response.data?.createdAt?.takeIf { it > 0 } ?: ts
        val ttl = response.data?.ttl?.takeIf { it > 0 } ?: DEFAULT_TICKET_TTL_SEC
        credentials.saveTicket(ticket, created + ttl)
    }

    private fun hmacSha256(key: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun execute(
        builder: Request.Builder,
        referer: String,
        guestOnly: Boolean = false,
        allowHtml: Boolean = false,
    ): String = exchange(builder, referer, guestOnly, allowHtml).body

    private fun exchange(
        builder: Request.Builder,
        referer: String,
        guestOnly: Boolean = false,
        allowHtml: Boolean = false,
    ): Exchange {
        val cookie = if (guestOnly) credentials.guestCookieHeader() else credentials.cookieHeader()
        if (cookie.isNotBlank()) builder.header("Cookie", cookie)
        val request = builder
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .header("Origin", originOf(referer))
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!allowHtml && body.trimStart().startsWith("<")) {
                throw BilibiliException(-412, "B站没有返回播放数据，请检查登录信息或稍后再试")
            }
            if (response.code in 300..399 || (!response.isSuccessful && body.isBlank())) {
                throw BilibiliException(response.code, "网络请求失败")
            }
            return Exchange(body, response.headers("Set-Cookie"))
        }
    }

    private fun keyFromUrl(url: String): String =
        url.substringAfterLast('/').substringBefore('.')

    private fun originOf(referer: String): String {
        val host = referer.substringAfter("://").substringBefore("/")
        val scheme = referer.substringBefore("://", "https")
        return if (host.isBlank()) "https://www.bilibili.com" else "$scheme://$host"
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val TAG = "BilibiliClient"
        private val BV_REGEX = Regex("BV[0-9A-Za-z]{10}")
        private const val KEY_TTL_MS = 30 * 60 * 1000L
        private const val DEFAULT_TICKET_TTL_SEC = 259200L
        private const val PAGE_SIZE = 20
        private const val SUBTITLE_RETRY_DELAY_MS = 500L
        private const val QR_WAITING = 86101
        private const val QR_SCANNED = 86090
        private const val QR_EXPIRED = 86038
    }
}

private data class Exchange(val body: String, val setCookies: List<String>)

@Serializable
private data class ApiResponse<T>(
    val code: Int = -1,
    val message: String = "",
    val msg: String = "",
    val data: T? = null,
) {
    fun detail(fallback: String = ""): String {
        val text = message.ifBlank { msg }
        return if (text.isBlank() || text == "0") fallback else text
    }
}

@Serializable
private data class NavData(
    @SerialName("isLogin") val isLogin: Boolean = false,
    val mid: Long = 0,
    @SerialName("wbi_img") val wbiImg: WbiImg? = null,
)

@Serializable
private data class FolderListData(
    val list: List<FolderInfo>? = null,
)

@Serializable
private data class FolderInfo(
    val id: Long = 0,
    val title: String = "",
    @SerialName("media_count") val mediaCount: Int = 0,
)

@Serializable
private data class ResourceListData(
    @SerialName("has_more") val hasMore: Boolean = false,
    val medias: List<FavMedia>? = null,
)

@Serializable
private data class FavMedia(
    val bvid: String = "",
    val title: String = "",
    val cover: String = "",
    val upper: Owner = Owner(),
)

@Serializable
private data class WbiImg(
    @SerialName("img_url") val imgUrl: String = "",
    @SerialName("sub_url") val subUrl: String = "",
)

@Serializable
private data class ViewData(
    val bvid: String = "",
    val title: String = "",
    val pic: String = "",
    val duration: Long = 0,
    val cid: Long = 0,
    val owner: Owner = Owner(),
    val pages: List<PageInfo> = emptyList(),
    @SerialName("ugc_season") val ugcSeason: kotlinx.serialization.json.JsonElement? = null,
)

@Serializable
private data class Owner(val name: String = "", val mid: Long = 0)

@Serializable
private data class PageInfo(
    val cid: Long = 0,
    val page: Int = 1,
    val part: String = "",
    val duration: Long = 0,
)

@Serializable
private data class PlayData(val dash: Dash? = null)

@Serializable
private data class Dash(
    val duration: Double = 0.0,
    val audio: List<AudioStream>? = null,
    val dolby: DolbyAudio? = null,
    val flac: FlacAudio? = null,
)

@Serializable
private data class DolbyAudio(val audio: List<AudioStream>? = null)

@Serializable
private data class FlacAudio(val audio: AudioStream? = null)

@Serializable
private data class AudioStream(
    val bandwidth: Int = 0,
    val codecs: String = "",
    val mimeType: String = "",
    @SerialName("mime_type") val mimeTypeAlt: String = "",
    val baseUrl: String = "",
    @SerialName("base_url") val baseUrlAlt: String = "",
    val backupUrl: List<String>? = null,
    @SerialName("SegmentBase") val segmentBase: SegmentBase? = null,
    @SerialName("segment_base") val segmentBaseAlt: SegmentBase? = null,
) {
    val playUrl: String
        get() = baseUrl.ifBlank { baseUrlAlt }.ifBlank { backupUrl?.firstOrNull().orEmpty() }
}

@Serializable
private data class SpiData(
    @SerialName("b_3") val b3: String = "",
    @SerialName("b_4") val b4: String = "",
)

@Serializable
private data class QrGenerateData(
    val url: String = "",
    @SerialName("qrcode_key") val qrcodeKey: String = "",
)

@Serializable
private data class QrPollData(
    val url: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    val code: Int = -1,
)

@Serializable
private data class CookieInfoData(
    val refresh: Boolean = false,
    val timestamp: Long = 0,
)

@Serializable
private data class RefreshTokenData(
    @SerialName("refresh_token") val refreshToken: String = "",
)

@Serializable
private data class TicketData(
    val ticket: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
    val ttl: Long = 0,
)

@Serializable
private data class SegmentBase(
    @SerialName("Initialization") val initialization: String? = null,
    @SerialName("initialization") val initializationAlt: String? = null,
    val indexRange: String? = null,
    @SerialName("index_range") val indexRangeAlt: String? = null,
)

private val biliVideoJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

/** 视频详情解析与 Android 请求环境分离。 */
internal fun parseBiliVideoDetails(body: String, bvid: String): BiliVideoDetails {
    val response = try { biliVideoJson.decodeFromString<ApiResponse<ViewData>>(body) }
    catch (_: kotlinx.serialization.SerializationException) { throw BilibiliException(0, "B站视频详情无法读取，请重试") }
    val data = response.data
    if (response.code != 0 || data == null) {
        throw BilibiliException(response.code, if (response.code != 0) biliSearchError(response.code) else "没有读到这个视频的信息")
    }
    if (data.bvid.isNotBlank() && data.bvid != bvid) throw BilibiliException(0, "B站返回的视频身份不匹配")
    val pages = data.pages.ifEmpty {
        listOf(PageInfo(cid = data.cid, page = 1, part = "", duration = data.duration))
    }
    val parts = pages.map { page ->
        val title = if (pages.size > 1 && page.part.isNotBlank()) {
            "${data.title} - ${page.part}"
        } else {
            data.title
        }
        BiliPart(
            bvid = data.bvid.ifBlank { bvid },
            cid = page.cid,
            page = page.page,
            title = title,
            artist = data.owner.name,
            durationMs = page.duration * 1000,
            coverUrl = data.pic.toHttps(),
        )
    }
    return BiliVideoDetails(BiliVideoHit(data.bvid.ifBlank { bvid }, data.title, data.owner.name,
        data.pic.toHttps(), data.duration * 1000, data.owner.mid), parts,
        biliVideoCollection(data.ugcSeason, data.owner.mid))
}
