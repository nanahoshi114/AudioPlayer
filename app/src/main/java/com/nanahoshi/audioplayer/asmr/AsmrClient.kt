package com.nanahoshi.audioplayer.asmr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.io.IOException
import java.util.concurrent.TimeUnit

class AsmrException(message: String) : Exception(message)

data class AsmrPreview(
    val sourceId: String,
    val title: String,
    val coverUrl: String?,
)

data class AsmrCatalogItem(
    val id: Long,
    val sourceId: String,
    val title: String,
    val circle: String,
    val vas: List<String>,
    val tags: List<String>,
    val release: String,
    val coverUrl: String?,
)

data class AsmrPage(
    val works: List<AsmrCatalogItem>,
    val page: Int,
    val total: Int,
)

data class AsmrWork(
    val id: Long,
    val sourceId: String,
    val title: String,
    val circle: String,
    val vas: List<String>,
    val tags: List<String>,
    val release: String,
    val coverUrl: String?,
    val nodes: List<AsmrNode>,
) {
    fun facts() = WorkFacts(title, circle, vas, tags, release, sourceId)
}

data class WorkFacts(
    val title: String,
    val circle: String,
    val vas: List<String>,
    val tags: List<String>,
    val release: String,
    val sourceId: String,
) {
    fun toJson(): String = JSONObject()
        .put("title", title)
        .put("circle", circle)
        .put("vas", JSONArray(vas))
        .put("tags", JSONArray(tags))
        .put("release", release)
        .put("sourceId", sourceId)
        .toString()

    companion object {
        fun fromJson(raw: String?): WorkFacts? {
            if (raw.isNullOrBlank()) return null
            return try {
                val obj = JSONObject(raw)
                WorkFacts(
                    title = obj.optString("title"),
                    circle = obj.optString("circle"),
                    vas = obj.optJSONArray("vas").toStrings(),
                    tags = obj.optJSONArray("tags").toStrings(),
                    release = obj.optString("release"),
                    sourceId = obj.optString("sourceId"),
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

data class AsmrNode(
    val title: String,
    val type: String,
    val children: List<AsmrNode>,
    val streamUrl: String?,
    val durationMs: Long,
    val hash: String,
)

class AsmrClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    suspend fun preview(input: String): AsmrPreview = withContext(Dispatchers.IO) {
        val info = workInfo(input)
        AsmrPreview(
            sourceId = info.sourceId,
            title = info.title,
            coverUrl = info.coverUrl,
        )
    }

    suspend fun loadWork(input: String): AsmrWork = withContext(Dispatchers.IO) {
        val info = workInfo(input)
        val nodes = parseNodes(JSONArray(get("/api/tracks/${info.id}?v=2")))
        info.toWork(nodes)
    }

    suspend fun facts(input: String): WorkFacts = withContext(Dispatchers.IO) {
        workInfo(input).toWork(emptyList()).facts()
    }

    suspend fun popular(page: Int, subtitleOnly: Boolean): AsmrPage = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("keyword", " ")
            .put("page", page)
            .put("pageSize", PAGE_SIZE)
            .put("subtitle", if (subtitleOnly) 1 else 0)
            .put("localSubtitledWorks", JSONArray())
            .put("withPlaylistStatus", JSONArray())
        parsePage(post("/api/recommender/popular", body.toString()))
    }

    suspend fun search(
        keyword: String,
        order: String,
        sort: String,
        page: Int,
        subtitleOnly: Boolean,
    ): AsmrPage = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(keyword.ifBlank { " " }, Charsets.UTF_8.name()).replace("+", "%20")
        val subtitle = if (subtitleOnly) 1 else 0
        val path = "/api/search/$encoded?order=$order&sort=$sort&page=$page&pageSize=$PAGE_SIZE" +
            "&subtitle=$subtitle&includeTranslationWorks=true"
        parsePage(get(path))
    }

    private fun workInfo(input: String): WorkInfo {
        val code = extractCode(input)
        val info = JSONObject(get("/api/workInfo/$code"))
        val id = info.optLong("id")
        if (id <= 0L) throw AsmrException("没有读到作品编号")
        val sourceId = info.optString("source_id").ifBlank { code }.uppercase()
        val circle = info.optJSONObject("circle")?.optString("name").orEmpty()
            .ifBlank { info.optString("name") }
        val release = info.optString("release").substringBefore('T').substringBefore(' ')
        return WorkInfo(
            id = id,
            sourceId = sourceId,
            title = info.optString("title").ifBlank { sourceId },
            circle = circle,
            vas = info.names("vas"),
            tags = info.names("tags"),
            release = release,
            coverUrl = info.optString("mainCoverUrl").ifBlank { null },
            thumbUrl = info.optString("thumbnailCoverUrl").ifBlank { null },
        )
    }

    private data class WorkInfo(
        val id: Long,
        val sourceId: String,
        val title: String,
        val circle: String,
        val vas: List<String>,
        val tags: List<String>,
        val release: String,
        val coverUrl: String?,
        val thumbUrl: String?,
    ) {
        fun toWork(nodes: List<AsmrNode>) = AsmrWork(
            id = id,
            sourceId = sourceId,
            title = title,
            circle = circle,
            vas = vas,
            tags = tags,
            release = release,
            coverUrl = coverUrl,
            nodes = nodes,
        )

        fun toCatalog() = AsmrCatalogItem(
            id = id,
            sourceId = sourceId,
            title = title,
            circle = circle,
            vas = vas,
            tags = tags,
            release = release,
            coverUrl = thumbUrl ?: coverUrl,
        )
    }

    suspend fun trackTree(workId: Long): List<AsmrNode> = withContext(Dispatchers.IO) {
        parseNodes(JSONArray(get("/api/tracks/$workId?v=2")))
    }

    suspend fun readText(url: String, limit: Int = TEXT_LIMIT): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .header("Origin", ORIGIN)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw AsmrException("无法打开这个文件")
            val input = response.body?.byteStream() ?: return@withContext ""
            val buffer = ByteArray(limit + 1)
            var total = 0
            while (total < buffer.size) {
                val read = input.read(buffer, total, buffer.size - total)
                if (read < 0) break
                total += read
            }
            val clipped = total > limit
            val text = buffer.copyOf(minOf(total, limit)).toString(Charsets.UTF_8)
            if (clipped) text + "\n…（只显示前面一段）" else text
        }
    }

    fun httpClient(): OkHttpClient = client

    private fun get(path: String): String = call(path, null)

    private fun post(path: String, json: String): String = call(path, json)

    private fun call(path: String, json: String?): String {
        var last: Exception? = null
        for (base in MIRRORS) {
            try {
                val builder = Request.Builder()
                    .url(base + path)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", REFERER)
                    .header("Origin", ORIGIN)
                    .header("Accept", "application/json")
                val request = if (json == null) {
                    builder.get().build()
                } else {
                    builder.post(json.toRequestBody(JSON_MEDIA)).build()
                }
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val server = runCatching { JSONObject(body).optString("error") }.getOrNull().orEmpty()
                        val message = when {
                            response.code == 404 -> "未找到该作品"
                            response.code == 400 && "workInfo" in path -> "RJ 号不正确"
                            server.isNotBlank() -> server
                            else -> "暂时无法连接 asmr.one"
                        }
                        if (response.code == 404 || response.code == 400) throw AsmrException(message)
                        last = IOException(message)
                        return@use
                    }
                    return body
                }
            } catch (error: AsmrException) {
                throw error
            } catch (error: Exception) {
                last = error
            }
        }
        throw AsmrException(last?.message ?: "暂时无法连接 asmr.one")
    }

    private fun parsePage(body: String): AsmrPage {
        val root = JSONObject(body)
        val pagination = root.optJSONObject("pagination")
        return AsmrPage(
            works = root.optJSONArray("works").toCatalog(),
            page = pagination?.optInt("currentPage") ?: 1,
            total = pagination?.optInt("totalCount") ?: 0,
        )
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
        const val REFERER = "https://asmr.one/"
        const val ORIGIN = "https://asmr.one"
        private const val TEXT_LIMIT = 512 * 1024
        private const val PAGE_SIZE = 20
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val MIRRORS = listOf(
            "https://api.asmr-200.com",
            "https://api.asmr.one",
            "https://api.asmr-100.com",
            "https://api.asmr-300.com",
        )
        private val CODE = Regex("(?i)(?:RJ|VJ)\\d+")

        fun extractCode(input: String): String {
            val match = CODE.find(input)
            if (match != null) return match.value.uppercase()
            val digits = input.filter { it.isDigit() }
            if (digits.isNotEmpty()) return "RJ$digits"
            throw AsmrException("请输入 RJ 号")
        }

        fun findStream(nodes: List<AsmrNode>, hash: String?, title: String): String? {
            val raw = com.nanahoshi.audioplayer.data.rawFileKey(hash)
            val byHash = if (raw.isNullOrBlank()) {
                null
            } else {
                walk(nodes) { it.hash == raw || it.hash == hash }?.streamUrl
            }
            if (!byHash.isNullOrBlank()) return byHash
            return walk(nodes) { it.title == title }?.streamUrl
        }

        private fun walk(nodes: List<AsmrNode>, match: (AsmrNode) -> Boolean): AsmrNode? {
            for (node in nodes) {
                if (node.children.isNotEmpty()) {
                    walk(node.children, match)?.let { return it }
                }
                if (node.streamUrl != null && match(node)) return node
            }
            return null
        }
    }
}

private fun parseNodes(array: JSONArray): List<AsmrNode> = buildList {
    for (index in 0 until array.length()) {
        add(parseNode(array.optJSONObject(index) ?: continue))
    }
}

private fun parseNode(obj: JSONObject): AsmrNode {
    val children = obj.optJSONArray("children")?.let(::parseNodes).orEmpty()
    val stream = obj.optString("mediaStreamUrl").ifBlank { obj.optString("mediaDownloadUrl") }.ifBlank { null }
    return AsmrNode(
        title = obj.optString("title").ifBlank { "未命名" },
        type = obj.optString("type"),
        children = children,
        streamUrl = stream,
        durationMs = (obj.optDouble("duration", 0.0) * 1000).toLong().coerceAtLeast(0),
        hash = obj.optString("hash"),
    )
}

private fun JSONObject.names(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val name = array.optJSONObject(index)?.optString("name").orEmpty()
            if (name.isNotBlank()) add(name)
        }
    }
}

private fun JSONArray?.toCatalog(): List<AsmrCatalogItem> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val id = item.optLong("id")
            if (id <= 0L) continue
            val sourceId = item.optString("source_id").uppercase()
            val circle = item.optJSONObject("circle")?.optString("name").orEmpty()
                .ifBlank { item.optString("name") }
            val cover = item.optString("thumbnailCoverUrl").ifBlank { item.optString("mainCoverUrl") }.ifBlank { null }
            add(
                AsmrCatalogItem(
                    id = id,
                    sourceId = sourceId,
                    title = item.optString("title").ifBlank { sourceId },
                    circle = circle,
                    vas = item.names("vas"),
                    tags = item.names("tags"),
                    release = item.optString("release").substringBefore('T').substringBefore(' '),
                    coverUrl = cover,
                ),
            )
        }
    }
}

private fun JSONArray?.toStrings(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val value = optString(index)
            if (value.isNotBlank()) add(value)
        }
    }
}
