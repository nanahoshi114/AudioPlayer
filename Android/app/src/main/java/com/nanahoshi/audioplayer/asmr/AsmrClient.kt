package com.nanahoshi.audioplayer.asmr

import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.data.MAX_SUBTITLE_TEXT_BYTES
import com.nanahoshi.audioplayer.data.RemoteTextPreview
import com.nanahoshi.audioplayer.data.jsonArray
import com.nanahoshi.audioplayer.data.jsonBool
import com.nanahoshi.audioplayer.data.jsonDouble
import com.nanahoshi.audioplayer.data.jsonInt
import com.nanahoshi.audioplayer.data.jsonLong
import com.nanahoshi.audioplayer.data.jsonNames
import com.nanahoshi.audioplayer.data.jsonObject
import com.nanahoshi.audioplayer.data.jsonObjects
import com.nanahoshi.audioplayer.data.jsonString
import com.nanahoshi.audioplayer.data.jsonStringOrEmpty
import com.nanahoshi.audioplayer.data.jsonStrings
import com.nanahoshi.audioplayer.data.parseJsonArrayOrNull
import com.nanahoshi.audioplayer.data.parseJsonObjectOrNull
import com.nanahoshi.audioplayer.data.readFullText
import com.nanahoshi.audioplayer.data.readRemoteTextPreview
import com.nanahoshi.audioplayer.data.withResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.UserFacingError
import java.util.concurrent.TimeUnit


enum class AsmrErrorKind { NOT_FOUND, INVALID_RJ, OTHER }

class AsmrException(
    override val userText: TextRef,
    val kind: AsmrErrorKind = AsmrErrorKind.OTHER,
    cause: Throwable? = null,
) : Exception(userText.debugString(), cause), UserFacingError

sealed class CatalogLookup {
    data class Found(val item: AsmrCatalogItem) : CatalogLookup()
    data object Missing : CatalogLookup()
    data object Unknown : CatalogLookup()
}

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
    val asmrAvailable: Boolean = false,
) {
    fun toJson(): String = buildJsonObject {
        put("title", title)
        put("circle", circle)
        putJsonArray("vas") { vas.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("tags") { tags.forEach { add(JsonPrimitive(it)) } }
        put("release", release)
        put("sourceId", sourceId)
        if (asmrAvailable) put("asmrAvailable", true)
    }.toString()

    companion object {
        fun fromJson(raw: String?): WorkFacts? {
            val obj = parseJsonObjectOrNull(raw) ?: return null
            return WorkFacts(
                title = obj.jsonStringOrEmpty("title"),
                circle = obj.jsonStringOrEmpty("circle"),
                vas = obj.jsonArray("vas").jsonStrings(),
                tags = obj.jsonArray("tags").jsonStrings(),
                release = obj.jsonStringOrEmpty("release"),
                sourceId = obj.jsonStringOrEmpty("sourceId"),
                asmrAvailable = obj.jsonBool("asmrAvailable") ?: false,
            )
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

class AsmrClient(root: OkHttpClient = com.nanahoshi.audioplayer.data.HttpRoots.shared) {
    private val client = root.newBuilder()
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
        val nodes = parseNodes(trackArray("/api/tracks/${info.id}?v=2"))
        info.toWork(nodes)
    }

    suspend fun facts(input: String): WorkFacts = withContext(Dispatchers.IO) {
        workInfo(input).toWork(emptyList()).facts()
    }

    suspend fun popular(page: Int, subtitleOnly: Boolean): AsmrPage = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("keyword", " ")
            put("page", page)
            put("pageSize", PAGE_SIZE)
            put("subtitle", if (subtitleOnly) 1 else 0)
            putJsonArray("localSubtitledWorks") { }
            putJsonArray("withPlaylistStatus") { }
        }
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

    suspend fun lookupCatalog(input: String): CatalogLookup = withContext(Dispatchers.IO) {
        try {
            CatalogLookup.Found(workInfo(input).toCatalog())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: AsmrException) {
            when (error.kind) {
                AsmrErrorKind.NOT_FOUND, AsmrErrorKind.INVALID_RJ -> CatalogLookup.Missing
                else -> CatalogLookup.Unknown
            }
        } catch (_: Exception) {
            CatalogLookup.Unknown
        }
    }

    private suspend fun workInfo(input: String): WorkInfo {
        val code = extractCode(input)
        val info = parseJsonObjectOrNull(get("/api/workInfo/$code"))
            ?: throw AsmrException(TextRef.Res(R.string.asmr_error_connection))
        val id = info.jsonLong("id") ?: 0L
        if (id <= 0L) throw AsmrException(TextRef.Res(R.string.asmr_error_work_id_missing))
        val sourceId = info.jsonStringOrEmpty("source_id").ifBlank { code }.uppercase()
        val circle = info.jsonObject("circle")?.jsonString("name").orEmpty()
            .ifBlank { info.jsonStringOrEmpty("name") }
        val release = info.jsonStringOrEmpty("release").substringBefore('T').substringBefore(' ')
        return WorkInfo(
            id = id,
            sourceId = sourceId,
            title = info.jsonStringOrEmpty("title").ifBlank { sourceId },
            circle = circle,
            vas = info.jsonNames("vas"),
            tags = info.jsonNames("tags"),
            release = release,
            coverUrl = info.jsonStringOrEmpty("mainCoverUrl").ifBlank { null },
            thumbUrl = info.jsonStringOrEmpty("thumbnailCoverUrl").ifBlank { null },
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
        parseNodes(trackArray("/api/tracks/$workId?v=2"))
    }

    private suspend fun trackArray(path: String): JsonArray =
        parseJsonArrayOrNull(get(path)) ?: throw AsmrException(TextRef.Res(R.string.asmr_error_connection))

    internal suspend fun readText(url: String, limit: Int = TEXT_LIMIT): RemoteTextPreview = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .header("Origin", ORIGIN)
            .build()
        client.newCall(request).withResponse { response ->
            if (!response.isSuccessful) throw AsmrException(TextRef.Res(R.string.dlsite_error_file_open))
            response.body?.byteStream()?.readRemoteTextPreview(limit) ?: RemoteTextPreview("")
        }
    }

    internal suspend fun readFullText(
        url: String,
        maxBytes: Int = MAX_SUBTITLE_TEXT_BYTES,
    ): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .header("Origin", ORIGIN)
            .build()
        client.newCall(request).withResponse { response ->
            if (!response.isSuccessful) throw AsmrException(TextRef.Res(R.string.dlsite_error_file_open))
            response.body?.byteStream()?.readFullText(maxBytes).orEmpty()
        }
    }

    fun httpClient(): OkHttpClient = client

    private suspend fun get(path: String): String = call(path, null)

    private suspend fun post(path: String, json: String): String = call(path, json)

    private suspend fun call(path: String, json: String?): String {
        var last: TextRef? = null
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
                val result = client.newCall(request).withResponse { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val server = parseJsonObjectOrNull(body)?.jsonString("error").orEmpty()
                        val fallback = TextRef.Res(R.string.asmr_error_connection)
                        val serverError = server.takeIf(String::isNotBlank)?.let(TextRef::Raw) ?: fallback
                        when {
                            response.code == 404 -> throw AsmrException(
                                TextRef.Res(R.string.asmr_error_work_missing), AsmrErrorKind.NOT_FOUND,
                            )
                            response.code == 400 && "workInfo" in path -> throw AsmrException(
                                TextRef.Res(R.string.asmr_error_invalid_rj), AsmrErrorKind.INVALID_RJ,
                            )
                            response.code == 400 -> throw AsmrException(serverError)
                            else -> last = serverError
                        }
                        return@withResponse null
                    }
                    body
                }
                if (result != null) return result
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: AsmrException) {
                throw error
            } catch (_: Exception) {
                last = TextRef.Res(R.string.asmr_error_connection)
            }
        }
        throw AsmrException(last ?: TextRef.Res(R.string.asmr_error_connection))
    }

    private fun parsePage(body: String): AsmrPage {
        val root = parseJsonObjectOrNull(body) ?: throw AsmrException(TextRef.Res(R.string.asmr_error_connection))
        val pagination = root.jsonObject("pagination")
        return AsmrPage(
            works = root.jsonArray("works").toCatalog(),
            page = pagination?.jsonInt("currentPage") ?: 1,
            total = pagination?.jsonInt("totalCount") ?: 0,
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

        fun extractCode(input: String): String =
            com.nanahoshi.audioplayer.data.WorkIds.fromInput(input)
                ?: throw AsmrException(TextRef.Res(R.string.asmr_error_enter_rj))

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

private fun parseNodes(array: JsonArray): List<AsmrNode> = array.jsonObjects().map(::parseNode)

private fun parseNode(obj: JsonObject): AsmrNode {
    val children = obj.jsonArray("children")?.let(::parseNodes).orEmpty()
    val stream = obj.jsonStringOrEmpty("mediaStreamUrl").ifBlank { obj.jsonStringOrEmpty("mediaDownloadUrl") }.ifBlank { null }
    return AsmrNode(
        title = obj.jsonStringOrEmpty("title").ifBlank { "未命名" },
        type = obj.jsonStringOrEmpty("type"),
        children = children,
        streamUrl = stream,
        durationMs = ((obj.jsonDouble("duration") ?: 0.0) * 1000).toLong().coerceAtLeast(0),
        hash = obj.jsonStringOrEmpty("hash"),
    )
}

private fun JsonArray?.toCatalog(): List<AsmrCatalogItem> = jsonObjects().mapNotNull { item ->
    val id = item.jsonLong("id") ?: 0L
    if (id <= 0L) return@mapNotNull null
    val sourceId = item.jsonStringOrEmpty("source_id").uppercase()
    val circle = item.jsonObject("circle")?.jsonString("name").orEmpty()
        .ifBlank { item.jsonStringOrEmpty("name") }
    val cover = item.jsonStringOrEmpty("thumbnailCoverUrl")
        .ifBlank { item.jsonStringOrEmpty("mainCoverUrl") }
        .ifBlank { null }
    AsmrCatalogItem(
        id = id,
        sourceId = sourceId,
        title = item.jsonStringOrEmpty("title").ifBlank { sourceId },
        circle = circle,
        vas = item.jsonNames("vas"),
        tags = item.jsonNames("tags"),
        release = item.jsonStringOrEmpty("release").substringBefore('T').substringBefore(' '),
        coverUrl = cover,
    )
}
