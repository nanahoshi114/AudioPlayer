package com.nanahoshi.audioplayer.bilibili

import androidx.annotation.StringRes
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.data.toHttps
import com.nanahoshi.audioplayer.text.TextRef
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

internal val BV_PATTERN = Regex("BV[0-9A-Za-z]{10}")
private val UID_PATTERN = Regex("(?i)UID\\s*[:：]?\\s*\\d+")
private val UID_PREFIX = Regex("(?i)UID\\s*[:：]?\\s*")

enum class BiliSearchMode { VIDEO, UPLOADER }

enum class BiliVideoOrder(@param:StringRes val labelRes: Int, val value: String) {
    TOTAL_RANK(R.string.bilibili_order_totalrank, "totalrank"),
    PLAY_COUNT(R.string.bilibili_order_play_count, "click"),
    PUBLISHED(R.string.bilibili_order_published, "pubdate"),
    DANMAKU(R.string.bilibili_order_danmaku, "dm"),
    FAVORITES(R.string.bilibili_order_favorites, "stow"),
    COMMENTS(R.string.bilibili_order_comments, "scores"),
}

enum class BiliVideoDuration(@param:StringRes val labelRes: Int, val value: String) {
    ALL(R.string.bilibili_duration_all, "0"),
    UNDER_TEN(R.string.bilibili_duration_under_ten, "1"),
    TEN_TO_THIRTY(R.string.bilibili_duration_ten_thirty, "2"),
    THIRTY_TO_SIXTY(R.string.bilibili_duration_thirty_sixty, "3"),
    OVER_SIXTY(R.string.bilibili_duration_over_sixty, "4"),
}

sealed interface BiliSearchInput {
    data class Video(val bvid: String) : BiliSearchInput
    data class Uploader(val mid: Long) : BiliSearchInput
    data class Keyword(val text: String, val mode: BiliSearchMode) : BiliSearchInput
}

@kotlinx.serialization.Serializable
data class BiliVideoHit(
    val bvid: String, val title: String, val author: String, val coverUrl: String,
    val durationMs: Long, val mid: Long = 0,
)
data class BiliUploader(
    val mid: Long, val name: String, val avatar: String = "", val signature: String = "",
    val fans: Long? = null, val videos: Long? = null,
)
data class BiliPage<T>(val items: List<T>, val hasMore: Boolean)

internal fun biliInput(raw: String, mode: BiliSearchMode): BiliSearchInput {
    val text = raw.trim()
    if (text.isEmpty()) throw BilibiliException(0, TextRef.Res(
        if (mode == BiliSearchMode.UPLOADER) R.string.bilibili_error_uploader_hint
        else R.string.bilibili_error_video_hint,
    ))
    BV_PATTERN.find(text)?.let { return BiliSearchInput.Video(it.value) }
    val url = text.toHttpUrlOrNull()
    val uid = when {
        url?.host == "space.bilibili.com" -> url.pathSegments.firstOrNull()
        UID_PATTERN.matches(text) -> UID_PREFIX.replace(text, "")
        mode == BiliSearchMode.UPLOADER && text.all(Char::isDigit) -> text
        else -> null
    }
    if (uid != null) return BiliSearchInput.Uploader(uid.toLongOrNull()?.takeIf { it > 0 }
        ?: throw BilibiliException(0, TextRef.Res(R.string.bilibili_error_invalid_uid)))
    return BiliSearchInput.Keyword(text, mode)
}

private fun JsonElement.field(key: String): JsonElement = (this as? JsonObject)?.get(key) ?: JsonNull
private fun JsonElement.text(key: String): String = (field(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonElement.number(key: String): Long? = text(key).toLongOrNull()
/** 搜索结果里的标题、昵称和简介可能带高亮标签与 HTML 实体。 */
internal fun biliPlainText(raw: String): String = Jsoup.parse(raw).text().replace(' ', ' ').trim()
private fun cover(raw: String) = raw.toHttps()
internal fun biliDuration(raw: String): Long {
    val parts = raw.trim().split(':').map { it.trim().toLongOrNull() ?: return 0 }
    if (parts.size !in 1..3 || parts.any { it !in 0..99999 }) return 0
    return parts.fold(0L) { sum, part -> sum * 60 + part } * 1000
}

internal fun biliSearchError(code: Int): TextRef = TextRef.Res(when (code) {
    -101, -111, 401 -> R.string.bilibili_error_login_invalid
    -352, -412, 412, 429 -> R.string.bilibili_error_rate_limited
    -403 -> R.string.bilibili_error_verify
    -404, 404 -> R.string.bilibili_error_not_found
    else -> R.string.bilibili_error_read_failed
})

/** 只处理可脱敏测试的 JSON；请求和凭证由现有客户端提供。 */
class BiliSearchApi(
    private val signedGet: suspend (path: String, params: Map<String, String>, referer: String, refreshKey: Boolean) -> String,
) {
    internal suspend fun data(path: String, params: Map<String, String>, referer: String): JsonElement {
        for (attempt in 0..1) {
            try {
                val raw = signedGet(path, params, referer, attempt == 1)
                val root = try { Json.parseToJsonElement(raw) }
                catch (_: Exception) { throw BilibiliException(0, TextRef.Res(R.string.bilibili_error_search_failed)) }
                val code = root.number("code")?.toInt()
                    ?: throw BilibiliException(0, TextRef.Res(R.string.bilibili_error_search_failed))
                if (code != 0) throw BilibiliException(code, biliSearchError(code))
                return root.field("data").takeUnless { it == JsonNull }
                    ?: throw BilibiliException(0, TextRef.Res(R.string.bilibili_error_no_search_data))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: BilibiliException) {
                if (error.code != -403 || attempt == 1) throw error
            }
        }
        error("unreachable")
    }

    suspend fun videos(
        keyword: String, page: Int,
        order: BiliVideoOrder = BiliVideoOrder.TOTAL_RANK,
        duration: BiliVideoDuration = BiliVideoDuration.ALL,
    ): BiliPage<BiliVideoHit> {
        val data = search("video", keyword, page, order, duration)
        val items = results(data).mapNotNull { video(it) }.distinctBy { it.bvid }
        return BiliPage(items, page < (data.number("numPages") ?: 1))
    }
    suspend fun uploaders(keyword: String, page: Int): BiliPage<BiliUploader> {
        val data = search("bili_user", keyword, page)
        val items = results(data).mapNotNull { item ->
            val mid = item.number("mid")?.takeIf { it > 0 } ?: return@mapNotNull null
            BiliUploader(mid, biliPlainText(item.text("uname")), cover(item.text("upic")),
                biliPlainText(item.text("usign")), item.number("fans"), item.number("videos"))
        }.distinctBy { it.mid }
        return BiliPage(items, page < (data.number("numPages") ?: 1))
    }
    suspend fun uploads(mid: Long, page: Int): BiliPage<BiliVideoHit> {
        require(mid > 0 && page > 0)
        val data = data("/x/space/wbi/arc/search", mapOf("mid" to "$mid", "pn" to "$page", "ps" to "30", "order" to "pubdate"), "https://space.bilibili.com/$mid/upload/video")
        val list = data.field("list").field("vlist") as? JsonArray
            ?: throw BilibiliException(0, TextRef.Res(R.string.bilibili_error_no_upload_list))
        val items = list.mapNotNull { video(it, mid) }.distinctBy { it.bvid }
        val count = data.field("page").number("count") ?: 0
        return BiliPage(items, page * 30L < count)
    }
    private suspend fun search(
        type: String, keyword: String, page: Int,
        order: BiliVideoOrder = BiliVideoOrder.TOTAL_RANK,
        duration: BiliVideoDuration = BiliVideoDuration.ALL,
    ): JsonElement {
        require(page > 0)
        val params = mutableMapOf("search_type" to type, "keyword" to keyword,
            "page" to "$page", "page_size" to "20", "order" to if (type == "video") order.value else "0")
        if (type == "video") params["duration"] = duration.value
        return data("/x/web-interface/wbi/search/type", params, "https://search.bilibili.com/")
    }
    private fun results(data: JsonElement): List<JsonElement> = when (val result = data.field("result")) {
        is JsonArray -> result
        else -> if (data.number("numResults") == 0L) emptyList() else throw BilibiliException(0, TextRef.Res(R.string.bilibili_error_no_search_list))
    }
    private fun video(item: JsonElement, owner: Long = 0): BiliVideoHit? {
        val bvid = item.text("bvid").takeIf { BV_PATTERN.matches(it) } ?: return null
        return BiliVideoHit(bvid, biliPlainText(item.text("title")), biliPlainText(item.text("author")),
            cover(item.text("pic")), biliDuration(item.text("duration").ifBlank { item.text("length") }), item.number("mid") ?: owner)
    }
}

/** 页内和页间去重；重复页不继续触发加载，失败时由调用方保留旧页码。 */
internal fun <T, K> mergeBiliPage(old: List<T>, page: BiliPage<T>, key: (T) -> K): BiliPage<T> {
    val seen = old.map(key).toMutableSet()
    val added = page.items.filter { seen.add(key(it)) }
    return BiliPage(old + added, page.hasMore && added.isNotEmpty())
}
