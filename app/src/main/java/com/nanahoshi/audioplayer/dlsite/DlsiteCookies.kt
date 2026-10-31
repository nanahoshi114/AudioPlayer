package com.nanahoshi.audioplayer.dlsite

/** Cloudflare 的验证 Cookie，只对登录页有效，不能当成 DLsite 登录凭证。 */
private val CLOUDFLARE_PREFIXES = listOf("cf_", "__cf", "_cfuvid")

internal fun isCloudflareCookie(name: String): Boolean =
    CLOUDFLARE_PREFIXES.any { name.startsWith(it, ignoreCase = true) }

/** 合并几段 Cookie 头，按名字去重；后面的值覆盖前面的，Cloudflare 项丢掉。 */
internal fun mergeDlsiteCookies(vararg headers: String?): String {
    val cookies = linkedMapOf<String, String>()
    for (header in headers) {
        if (header.isNullOrBlank()) continue
        for (part in header.split(';')) {
            val trimmed = part.trim()
            if (!trimmed.contains('=')) continue
            val name = trimmed.substringBefore('=').trim()
            if (name.isEmpty() || isCloudflareCookie(name)) continue
            cookies[name] = trimmed.substringAfter('=').trim()
        }
    }
    return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
}
