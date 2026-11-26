package com.nanahoshi.audioplayer.data

/**
 * Cookie 头的解析、合并与序列化。
 *
 * 所有进入 HTTP 头的值都会先去掉 `\r` / `\n`，防止网页或剪贴板输入
 * 通过换行注入额外请求头。
 */
object CookieHeaders {
    fun sanitize(value: String): String = value.replace("\r", "").replace("\n", "")

    /** 解析 `a=1; b=2`；同名项后者覆盖前者，空名项丢弃。 */
    fun parse(header: String?): LinkedHashMap<String, String> {
        val cookies = linkedMapOf<String, String>()
        if (header.isNullOrBlank()) return cookies
        for (part in sanitize(header).split(';')) {
            val trimmed = part.trim()
            val separator = trimmed.indexOf('=')
            if (separator <= 0) continue
            val name = trimmed.substring(0, separator).trim()
            if (name.isEmpty()) continue
            cookies[name] = trimmed.substring(separator + 1).trim()
        }
        return cookies
    }

    fun serialize(cookies: Map<String, String>): String = cookies.entries
        .joinToString("; ") { (name, value) -> "$name=${sanitize(value)}" }

    /**
     * 合并多段 Cookie 头，按名字去重；后面的值覆盖前面的。
     * [filter] 返回 false 的项直接丢弃，例如 Cloudflare 验证 Cookie。
     */
    fun merge(vararg headers: String?, filter: (String) -> Boolean = { true }): String {
        val cookies = linkedMapOf<String, String>()
        for (header in headers) {
            for ((name, value) in parse(header)) {
                if (filter(name)) cookies[name] = value
            }
        }
        return serialize(cookies)
    }

    fun field(header: String?, name: String): String? = parse(header)[name]?.takeIf { it.isNotEmpty() }
}