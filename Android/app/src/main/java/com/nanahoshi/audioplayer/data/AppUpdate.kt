package com.nanahoshi.audioplayer.data

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.math.BigInteger
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val APP_RELEASES_URL = "https://github.com/nanahoshi114/AudioPlayer/releases"
internal const val APP_LATEST_RELEASE_API = "https://api.github.com/repos/nanahoshi114/AudioPlayer/releases/latest"

internal data class AppRelease(val tag: String, val versionName: String?) {
    // 发布页由固定仓库和 tag 生成，不使用附件下载地址。
    val pageUrl: String get() = APP_RELEASES_URL.toHttpUrl().newBuilder().addPathSegment("tag").addPathSegment(tag).build().toString()
}
internal class AppUpdateException(message: String) : IOException(message)
internal enum class UpdateRelation { AVAILABLE, CURRENT, LOCAL_NEWER, UNKNOWN }

/** 按数字段比较，省略的段按 0，支持 v 前缀、预发布和构建标识。 */
internal data class AppVersionNumber(val parts: List<BigInteger>, val preview: List<String>) : Comparable<AppVersionNumber> {
    override fun compareTo(other: AppVersionNumber): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val result = (parts.getOrNull(i) ?: BigInteger.ZERO).compareTo(other.parts.getOrNull(i) ?: BigInteger.ZERO)
            if (result != 0) return result
        }
        if (preview.isEmpty() && other.preview.isEmpty()) return 0
        if (preview.isEmpty()) return 1
        if (other.preview.isEmpty()) return -1
        for (i in 0 until minOf(preview.size, other.preview.size)) {
            val left = preview[i]; val right = other.preview[i]
            val a = left.toBigIntegerOrNull(); val b = right.toBigIntegerOrNull()
            val result = when {
                a != null && b != null -> a.compareTo(b)
                a != null -> -1
                b != null -> 1
                else -> left.compareTo(right)
            }
            if (result != 0) return result
        }
        return preview.size.compareTo(other.preview.size)
    }
    companion object {
        fun parse(raw: String): AppVersionNumber? {
            val match = Regex("[vV]?([0-9]+(?:\\.[0-9]+)*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?")
                .matchEntire(raw.trim()) ?: return null
            return AppVersionNumber(match.groupValues[1].split('.').map { it.toBigInteger() },
                match.groupValues[2].takeIf { it.isNotEmpty() }?.split('.').orEmpty())
        }
    }
}

internal fun updateRelation(currentVersion: String, release: AppRelease): UpdateRelation {
    val current = AppVersionNumber.parse(currentVersion) ?: return UpdateRelation.UNKNOWN
    val latest = release.versionName?.let(AppVersionNumber::parse) ?: return UpdateRelation.UNKNOWN
    val comparison = latest.compareTo(current)
    return when {
        comparison > 0 -> UpdateRelation.AVAILABLE
        comparison == 0 -> UpdateRelation.CURRENT
        else -> UpdateRelation.LOCAL_NEWER
    }
}

internal fun parseAppRelease(raw: String): AppRelease {
    val root = try { Json.parseToJsonElement(raw) as? JsonObject } catch (_: Exception) { null }
        ?: throw AppUpdateException("发布信息无法读取，请稍后重试或打开发布页")
    fun value(key: String) = (root[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    if (value("draft") == "true" || value("prerelease") == "true") throw AppUpdateException("尚未读到可用的正式版本，请打开发布页查看")
    val apk = (root["assets"] as? JsonArray).orEmpty().any { item ->
        val asset = item as? JsonObject ?: return@any false
        (asset["name"] as? JsonPrimitive)?.contentOrNull?.endsWith(".apk", true) == true &&
            (asset["state"] as? JsonPrimitive)?.contentOrNull == "uploaded"
    }
    if (!apk) throw AppUpdateException("最新发布尚未提供 APK，请打开发布页查看")
    val tag = value("tag_name").trim().takeIf { it.isNotEmpty() }
        ?: throw AppUpdateException("发布信息缺少版本标签，请打开发布页查看")
    val version = listOf(tag, value("name")).firstOrNull { AppVersionNumber.parse(it) != null }
    return AppRelease(tag, version)
}

/** 独立匿名客户端，只取 GitHub 版本 JSON，不带应用账号信息，不请求 APK。 */
internal class AppUpdateClient(http: OkHttpClient = OkHttpClient()) {
    private val http = http.newBuilder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    suspend fun latest(): AppRelease = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(APP_LATEST_RELEASE_API)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2026-03-10")
            .header("User-Agent", "AudioPlayer-Android-UpdateCheck").build()
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(AppUpdateException("检查更新失败，请检查网络后重试"))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val release = response.use {
                        if (!it.isSuccessful) throw AppUpdateException(when {
                            it.code == 429 || (it.code == 403 && it.header("X-RateLimit-Remaining") == "0") -> "检查更新次数过多，请稍后重试"
                            it.code == 404 -> "未找到发布信息，请打开发布页查看"
                            else -> "检查更新失败，请稍后重试或打开发布页"
                        })
                        parseAppRelease(it.body?.string().orEmpty())
                    }
                    if (continuation.isActive) continuation.resume(release)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(
                        if (error is AppUpdateException) error else AppUpdateException("发布信息无法读取，请稍后重试或打开发布页"))
                }
            }
        })
    }
}
