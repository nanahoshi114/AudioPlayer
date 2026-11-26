package com.nanahoshi.audioplayer.bilibili

import java.net.URLEncoder

object WbiSigner {
    private val mixinTab = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
        61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
        36, 20, 34, 44, 52,
    )

    fun mixinKey(imgKey: String, subKey: String): String {
        val raw = imgKey + subKey
        return buildString {
            for (index in mixinTab) {
                if (index < raw.length) append(raw[index])
            }
        }.take(32)
    }

    fun sign(params: Map<String, String>, mixinKey: String): Map<String, String> =
        signAt(params, mixinKey, System.currentTimeMillis() / 1000)

    /** 固定时间戳的签名入口，便于用已知向量验证；生产请求始终走 [sign]。 */
    internal fun signAt(params: Map<String, String>, mixinKey: String, timestampSeconds: Long): Map<String, String> {
        val withTime = params + ("wts" to timestampSeconds.toString())
        val filtered = signedParams(withTime)
        val query = canonicalQuery(filtered)
        return filtered + ("w_rid" to md5(query + mixinKey))
    }

    /** 过滤后按字典序编码的规范化查询串；Wbi 签名要求昵称等字段先去掉 `!'()*`。 */
    internal fun canonicalQuery(params: Map<String, String>): String =
        signedParams(params).entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }

    private fun signedParams(params: Map<String, String>): Map<String, String> =
        params.mapValues { (_, value) -> value.filterNot { it in "!'()*" } }.toSortedMap()

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun md5(value: String): String = com.nanahoshi.audioplayer.data.md5Hex(value)
}
