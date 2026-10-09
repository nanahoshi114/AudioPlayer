package com.nanahoshi.audioplayer.bilibili

import com.nanahoshi.audioplayer.data.CredentialStore
import com.nanahoshi.audioplayer.data.toHttps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import android.util.Log
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

data class BiliPart(
    val bvid: String,
    val cid: Long,
    val page: Int,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val coverUrl: String,
)

class BilibiliException(val code: Int, message: String) : Exception(message)

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

    suspend fun subtitleDocument(bvid: String, cid: Long): String? = try {
        val root = playerInfo(bvid, cid)
        if (root.optInt("code") != 0) {
            Log.w(TAG, "subtitle skipped code=${root.optInt("code")}")
            null
        } else {
            val data = root.optJSONObject("data")
            val returnedBvid = data?.optString("bvid").orEmpty()
            val returnedCid = data?.optLong("cid") ?: 0L
            if ((returnedBvid.isNotBlank() && returnedBvid != bvid) || (returnedCid != 0L && returnedCid != cid)) {
                Log.w(TAG, "subtitle skipped mismatched bvid=$returnedBvid cid=$returnedCid")
                null
            } else {
                val list = data?.optJSONObject("subtitle")?.optJSONArray("subtitles")
                val url = pickSubtitleUrl(list)
                if (url == null) {
                    null
                } else {
                    get(url, referer = "https://www.bilibili.com/video/$bvid")
                }
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.w(TAG, "subtitle skipped: ${error.javaClass.simpleName}")
        null
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

    private fun pickSubtitleUrl(list: JSONArray?): String? {
        if (list == null || list.length() == 0) return null
        var chosenUrl: String? = null
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
                chosenUrl = url
            }
        }
        return chosenUrl
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

    suspend fun lookup(input: String): List<BiliPart> {
        val bvid = BV_REGEX.find(input)?.value
            ?: throw BilibiliException(0, "请输入正确的 BV 号")
        val body = get("https://api.bilibili.com/x/web-interface/view?bvid=$bvid")
        val response = json.decodeFromString<ApiResponse<ViewData>>(body)
        val data = response.data
        if (response.code != 0 || data == null) {
            throw BilibiliException(response.code, response.message.ifBlank { "找不到这个视频" })
        }
        val pages = data.pages.ifEmpty {
            listOf(PageInfo(cid = data.cid, page = 1, part = "", duration = data.duration))
        }
        return pages.map { page ->
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

    private suspend fun get(url: String, referer: String = "https://www.bilibili.com"): String =
        withContext(Dispatchers.IO) {
            prepareIdentity()
            execute(Request.Builder().url(url).get(), referer)
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

    private fun execute(builder: Request.Builder, referer: String, guestOnly: Boolean = false): String {
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
            if (body.trimStart().startsWith("<")) {
                throw BilibiliException(-412, "B站没有返回播放数据，请检查登录信息或稍后再试")
            }
            if (response.code in 300..399 || (!response.isSuccessful && body.isBlank())) {
                throw BilibiliException(response.code, "网络请求失败")
            }
            return body
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
    }
}

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
)

@Serializable
private data class Owner(val name: String = "")

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
