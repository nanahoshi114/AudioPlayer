package com.nanahoshi.audioplayer.bilibili

import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.coroutines.coroutineContext

@Serializable enum class BiliCollectionKind { SEASON, SERIES }

@Serializable
data class BiliCollection(
    val kind: BiliCollectionKind,
    val remoteId: Long,
    val mid: Long,
    val title: String,
    val coverUrl: String = "",
    val description: String = "",
    val total: Int = 0,
    /** 兼容已有元数据：true 表示封面来自首视频，false 的旧合集封面不再使用。 */
    val coverIsFallback: Boolean = false,
) {
    val key: String get() = "${kind.name}:$remoteId"
    val label: String get() = if (kind == BiliCollectionKind.SEASON) "B站合集" else "B站系列"
}

data class BiliCollectionPage(
    val collection: BiliCollection,
    val videos: List<BiliVideoHit>,
    val hasMore: Boolean,
    /** 聚合快照是否读完且条数与总数一致；单页结果不使用该字段。 */
    val complete: Boolean = false,
)

private fun JsonElement.obj() = this as? JsonObject ?: throw BilibiliException(0, "B站没有返回合集数据")
private fun JsonElement.value(key: String) = obj()[key] ?: JsonNull
private fun JsonElement.string(key: String) = (value(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonElement.long(key: String) = string(key).toLongOrNull()
private fun JsonElement.array(key: String) = value(key) as? JsonArray
    ?: throw BilibiliException(0, "B站没有返回合集列表，请重试")
private fun https(value: String) = when {
    value.startsWith("//") -> "https:$value"
    value.startsWith("http://") -> value.replaceFirst("http://", "https://")
    else -> value
}

/** 统一使用首视频封面；未知首视频时只保留已确认来自首视频的缓存。 */
internal fun BiliCollection.withFirstVideoCover(cover: String?): BiliCollection =
    if (cover == null && coverIsFallback) this
    else copy(coverUrl = https(cover.orEmpty()), coverIsFallback = true)

/** 与请求身份分离，解析可在 JVM 上测试。 */
class BiliCollectionApi(private val data: suspend (String, Map<String, String>, String) -> JsonElement) {
    suspend fun catalogue(mid: Long, page: Int): BiliPage<BiliCollection> {
        require(mid > 0 && page > 0)
        val lists = data("/x/polymer/web-space/seasons_series_list",
            mapOf("mid" to "$mid", "page_num" to "$page", "page_size" to "20"), "https://space.bilibili.com/$mid").value("items_lists")
        val items = listOf("seasons_list" to BiliCollectionKind.SEASON, "series_list" to BiliCollectionKind.SERIES).flatMap { (name, kind) ->
            lists.array(name).map { entry ->
                val meta = entry.value("meta")
                val id = meta.long(if (kind == BiliCollectionKind.SEASON) "season_id" else "series_id")
                    ?.takeIf { it > 0 } ?: throw BilibiliException(0, "合集编号无效")
                val first = (entry.value("archives") as? JsonArray)?.firstOrNull() as? JsonObject
                metadata(meta, BiliCollection(kind, id, mid, "")).withFirstVideoCover(first?.string("pic"))
            }
        }.distinctBy { it.key }
        val total = lists.value("page").long("total") ?: throw BilibiliException(0, "合集分页信息缺失")
        return BiliPage(items, page * 20L < total)
    }

    suspend fun videos(collection: BiliCollection, page: Int): BiliCollectionPage {
        require(collection.mid > 0 && collection.remoteId > 0 && page > 0)
        val season = collection.kind == BiliCollectionKind.SEASON
        val path = if (season) "/x/polymer/web-space/seasons_archives_list" else "/x/series/archives"
        val params = if (season) mapOf("mid" to "${collection.mid}", "season_id" to "${collection.remoteId}",
            "page_num" to "$page", "page_size" to "30", "sort_reverse" to "false")
        else mapOf("mid" to "${collection.mid}", "series_id" to "${collection.remoteId}", "pn" to "$page", "ps" to "30", "sort" to "asc")
        val body = data(path, params, "https://space.bilibili.com/${collection.mid}")
        val total = body.value("page").long("total") ?: throw BilibiliException(0, "合集分页信息缺失")
        val items = body.array("archives").map { item ->
            val bv = item.string("bvid").takeIf { Regex("BV[0-9A-Za-z]{10}").matches(it) }
                ?: throw BilibiliException(0, "合集视频身份缺失，请重试")
            BiliVideoHit(bv, biliPlainText(item.string("title")), "", https(item.string("pic")),
                (item.long("duration") ?: 0).coerceAtLeast(0) * 1000, collection.mid)
        }.distinctBy { it.bvid }
        val updated = if (season) metadata(body.value("meta"), collection) else if (page == 1) {
            val info = data("/x/series/series", mapOf("series_id" to "${collection.remoteId}"), "https://space.bilibili.com/${collection.mid}")
            metadata(info.value("meta"), collection)
        } else collection
        val covered = if (page == 1) updated.withFirstVideoCover(items.firstOrNull()?.coverUrl.orEmpty()) else updated
        return BiliCollectionPage(covered.copy(total = total.toInt()), items, page * 30L < total)
    }

    private fun metadata(meta: JsonElement, fallback: BiliCollection) = fallback.withFirstVideoCover(null).copy(
        title = biliPlainText(meta.string("name")).ifBlank { fallback.title.ifBlank { fallback.label } },
        description = biliPlainText(meta.string("description")),
        total = (meta.long("total") ?: fallback.total.toLong()).toInt(),
    )
}

/** 全部分页完成才返回完整快照；条数不符时重读一次，仍不符则返回不完整结果而不丢弃已读内容。 */
internal suspend fun loadBiliCollection(
    collection: BiliCollection,
    load: suspend (BiliCollection, Int) -> BiliCollectionPage,
    progress: (List<BiliVideoHit>) -> Unit = {},
): BiliCollectionPage {
    val first = loadAllPages(collection, load, progress)
    if (first.complete) return first
    return loadAllPages(collection, load, progress)
}

private suspend fun loadAllPages(
    collection: BiliCollection,
    load: suspend (BiliCollection, Int) -> BiliCollectionPage,
    progress: (List<BiliVideoHit>) -> Unit,
): BiliCollectionPage {
    val videos = linkedMapOf<String, BiliVideoHit>()
    var current = collection
    var page = 1
    while (true) {
        coroutineContext.ensureActive()
        val result = load(current, page)
        current = result.collection
        val previous = videos.size
        result.videos.forEach { videos.putIfAbsent(it.bvid, it) }
        progress(videos.values.toList())
        if (!result.hasMore) {
            // 接口不提供失效条目清单，只能通过条数判断目录是否完整。
            val complete = videos.size == current.total
            return snapshot(current, videos, complete)
        }
        if (previous == videos.size) return snapshot(current, videos, complete = false)
        page++
    }
}

private fun snapshot(
    collection: BiliCollection,
    videos: Map<String, BiliVideoHit>,
    complete: Boolean,
): BiliCollectionPage = BiliCollectionPage(
    collection.withFirstVideoCover(videos.values.firstOrNull()?.coverUrl.orEmpty()),
    videos.values.toList(),
    false,
    complete,
)
