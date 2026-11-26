package com.nanahoshi.audioplayer.data

private const val BILI_REFRESH_INTERVAL_MS = 20 * 60 * 60 * 1000L
private const val BILI_REFRESH_RETRY_MS = 10 * 60 * 1000L

/** 续期成功后 20 小时再查；失败后只冷却 10 分钟，避免会话失效却长期不重试。 */
internal fun biliRefreshDueAt(checkedAt: Long, failedAt: Long, now: Long): Boolean {
    if (failedAt > 0L && now - failedAt < BILI_REFRESH_RETRY_MS) return false
    if (now - checkedAt >= BILI_REFRESH_INTERVAL_MS) return true
    return failedAt > 0L
}

/** ticket 至少还要有效 60 秒才随请求发送；过期或临期一律不带，避免触发 -403。 */
internal fun ticketIsFresh(expiresAtEpochSec: Long, nowEpochSec: Long): Boolean =
    expiresAtEpochSec > nowEpochSec + 60

data class TranslateConfig(
    val platform: TranslatePlatform,
    val baseUrl: String,
    val token: String?,
    val model: String,
    val thinking: Boolean,
    val effort: String,
)

data class BiliSessionSnapshot(
    val generation: Long,
    val cookie: String,
    val refreshToken: String,
    val pendingRevokeToken: String?,
)