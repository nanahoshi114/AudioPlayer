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

internal val biliLibraryJson = Json { ignoreUnknownKeys = true }

internal fun BiliImportRequest.checked(): BiliImportRequest {
    require(BV_PATTERN.matches(video.bvid) && parts.isNotEmpty()) { "没有可导入的分 P" }
    require(parts.all { it.bvid == video.bvid && it.cid > 0 && it.page > 0 && it.durationMs >= 0 }) { "分 P 信息无效" }
    return copy(parts = parts.distinctBy { it.cid }.sortedBy { it.page })
}
