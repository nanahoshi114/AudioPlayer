package com.nanahoshi.audioplayer.bilibili

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class BiliSearchMode { VIDEO, UPLOADER }

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
    if (text.isEmpty()) throw BilibiliException(0, "请输入关键词、BV 号或 UP 主")
    Regex("BV[0-9A-Za-z]{10}").find(text)?.let { return BiliSearchInput.Video(it.value) }
    val url = text.toHttpUrlOrNull()
    val uid = when {
        url?.host == "space.bilibili.com" -> url.pathSegments.firstOrNull()
        Regex("(?i)UID\\s*[:：]?\\s*\\d+").matches(text) -> text.replace(Regex("(?i)UID\\s*[:：]?\\s*"), "")
        mode == BiliSearchMode.UPLOADER && text.all(Char::isDigit) -> text
        else -> null
    }
    if (uid != null) return BiliSearchInput.Uploader(uid.toLongOrNull()?.takeIf { it > 0 }
        ?: throw BilibiliException(0, "请输入正确的 UP 主 UID"))
    return BiliSearchInput.Keyword(text, mode)
}

private fun JsonElement.field(key: String): JsonElement = (this as? JsonObject)?.get(key) ?: JsonNull
private fun JsonElement.text(key: String): String = (field(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonElement.number(key: String): Long? = text(key).toLongOrNull()
internal fun biliPlainText(raw: String): String = raw.replace(Regex("<[^>]*>"), "")
    .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
    .replace("&#39;", "'").replace("&#x27;", "'").replace("&nbsp;", " ").replace("&amp;", "&").trim()
private fun cover(raw: String) = when {
    raw.startsWith("//") -> "https:$raw"
    raw.startsWith("http://") -> "https://" + raw.removePrefix("http://")
    else -> raw
}
internal fun biliDuration(raw: String): Long {
    val parts = raw.trim().split(':').map { it.trim().toLongOrNull() ?: return 0 }
    if (parts.size !in 1..3 || parts.any { it !in 0..99999 }) return 0
    return parts.fold(0L) { sum, part -> sum * 60 + part } * 1000
}

internal fun biliSearchError(code: Int): String = when (code) {
    -101, -111, 401 -> "B站登录信息无效，请在「我的」重新登录"
    -352, -412, 412, 429 -> "B站暂时限制了访问，请稍后重试或更换网络"
    -403 -> "B站请求验证失败，请重试"
    -404, 404 -> "没有找到这个视频或 UP 主"
    else -> "B站读取失败（$code），请稍后重试"
}

/** 只处理可脱敏测试的 JSON；请求和凭证由现有客户端提供。 */
class BiliSearchApi(
    private val signedGet: suspend (path: String, params: Map<String, String>, referer: String, refreshKey: Boolean) -> String,
) {
    private suspend fun data(path: String, params: Map<String, String>, referer: String): JsonElement {
        for (attempt in 0..1) {
            try {
                val raw = signedGet(path, params, referer, attempt == 1)
                val root = try { Json.parseToJsonElement(raw) }
                catch (_: Exception) { throw BilibiliException(0, "B站返回的数据无法读取") }
                val code = root.number("code")?.toInt() ?: throw BilibiliException(0, "B站返回的数据无法读取")
                if (code != 0) throw BilibiliException(code, biliSearchError(code))
                return root.field("data").takeUnless { it == JsonNull }
                    ?: throw BilibiliException(0, "B站没有返回搜索数据")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: BilibiliException) {
                if (error.code != -403 || attempt == 1) throw error
            }
        }
        error("unreachable")
    }

    suspend fun videos(keyword: String, page: Int): BiliPage<BiliVideoHit> {
        val data = search("video", keyword, page)
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
            ?: throw BilibiliException(0, "B站没有返回投稿列表，请重试")
        val items = list.mapNotNull { video(it, mid) }.distinctBy { it.bvid }
        val count = data.field("page").number("count") ?: 0
        return BiliPage(items, page * 30L < count)
    }
    private suspend fun search(type: String, keyword: String, page: Int): JsonElement {
        require(page > 0)
        return data("/x/web-interface/wbi/search/type", mapOf("search_type" to type, "keyword" to keyword,
            "page" to "$page", "page_size" to "20", "order" to if (type == "video") "totalrank" else "0"), "https://search.bilibili.com/")
    }
    private fun results(data: JsonElement): List<JsonElement> = when (val result = data.field("result")) {
        is JsonArray -> result
        else -> if (data.number("numResults") == 0L) emptyList() else throw BilibiliException(0, "B站没有返回搜索列表，请重试")
    }
    private fun video(item: JsonElement, owner: Long = 0): BiliVideoHit? {
        val bvid = item.text("bvid").takeIf { Regex("BV[0-9A-Za-z]{10}").matches(it) } ?: return null
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
