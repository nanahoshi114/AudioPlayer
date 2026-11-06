package com.nanahoshi.audioplayer.bilibili

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** 只接受视频详情明确给出的合集；不根据标题或本地系列成员推断归属。 */
internal fun biliVideoCollection(element: JsonElement?, ownerMid: Long): BiliCollection? {
    val season = element as? JsonObject ?: return null
    fun text(name: String) = (season[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val id = text("id").toLongOrNull()?.takeIf { it > 0 } ?: return null
    val mid = if (text("mid").isBlank()) ownerMid else text("mid").toLongOrNull() ?: return null
    if (mid <= 0) return null
    val title = biliPlainText(text("title")).takeIf { it.isNotBlank() } ?: return null
    val firstEpisode = (season["sections"] as? JsonArray).orEmpty().asSequence().flatMap { section ->
        ((section as? JsonObject)?.get("episodes") as? JsonArray).orEmpty().asSequence()
    }.firstOrNull() as? JsonObject
    val firstCover = ((firstEpisode?.get("arc") as? JsonObject)?.get("pic") as? JsonPrimitive)?.contentOrNull
    return BiliCollection(BiliCollectionKind.SEASON, id, mid, title,
        description = biliPlainText(text("intro")), total = text("ep_count").toIntOrNull()?.coerceAtLeast(0) ?: 0)
        .withFirstVideoCover(firstCover)
}

/** 会话内复用已读取的归属；null 表示详情读取成功且没有有效合集信息。 */
class BiliVideoCollectionCache {
    private val mutable = MutableStateFlow<Map<String, BiliCollection?>>(emptyMap())
    private val gate = Mutex()
    val values = mutable.asStateFlow()

    suspend fun resolve(bvid: String, load: suspend (String) -> BiliCollection?): BiliCollection? = gate.withLock {
        if (mutable.value.containsKey(bvid)) mutable.value[bvid]
        else load(bvid).also { record(bvid, it) }
    }

    fun record(bvid: String, collection: BiliCollection?) {
        mutable.update { previous ->
            (previous - bvid + (bvid to collection)).entries.toList().takeLast(64).associate { it.key to it.value }
        }
    }
}
