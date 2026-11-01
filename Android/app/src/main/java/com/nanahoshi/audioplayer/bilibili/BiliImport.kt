package com.nanahoshi.audioplayer.bilibili

import kotlinx.serialization.json.Json

private val importJson = Json { ignoreUnknownKeys = true }
internal fun BiliPart.importMetadata(): String = importJson.encodeToString(BiliPart.serializer(), this)
internal fun biliImportPart(metadata: String, bvid: String, cid: Long): BiliPart {
    val part = try { importJson.decodeFromString(BiliPart.serializer(), metadata) }
    catch (_: Exception) { throw BilibiliException(0, "下载入库信息无效，请从搜索页重新下载") }
    if (part.bvid != bvid || part.cid != cid || cid <= 0 || part.page <= 0 || part.durationMs < 0) {
        throw BilibiliException(0, "下载入库信息不匹配，请从搜索页重新下载")
    }
    return part
}
