package com.nanahoshi.audioplayer.bilibili

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable enum class BiliImportMode { SPLIT, GROUPED }

@Serializable
data class BiliImportRequest(
    val video: BiliVideoHit,
    val parts: List<BiliPart>,
    val mode: BiliImportMode,
    val conversionConfirmed: Boolean = false,
)

@Serializable
internal data class BiliDownloadImport(val part: BiliPart, val batchId: String? = null)

internal val biliLibraryJson = Json { ignoreUnknownKeys = true }

internal fun decodeBiliDownloadImport(raw: String, bvid: String, cid: Long): BiliDownloadImport {
    val envelope = runCatching { biliLibraryJson.decodeFromString(BiliDownloadImport.serializer(), raw) }.getOrNull()
        ?: BiliDownloadImport(biliImportPart(raw, bvid, cid))
    check(envelope.part.bvid == bvid && envelope.part.cid == cid && cid > 0 && envelope.part.page > 0 && envelope.part.durationMs >= 0) {
        "下载入库信息不匹配，请重新导入"
    }
    return envelope
}

internal fun BiliImportRequest.checked(): BiliImportRequest {
    require(Regex("BV[0-9A-Za-z]{10}").matches(video.bvid) && parts.isNotEmpty()) { "没有可导入的分 P" }
    require(parts.all { it.bvid == video.bvid && it.cid > 0 && it.page > 0 && it.durationMs >= 0 }) { "分 P 信息无效" }
    return copy(parts = parts.distinctBy { it.cid }.sortedBy { it.page })
}
