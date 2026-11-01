package com.nanahoshi.audioplayer.youtube

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class MediaRejected(val code: Int) : IOException("YouTube 媒体地址被拒绝")

class YouTubeClient(
    private val cookie: () -> String?,
    private val solver: YouTubeSolver,
    mediaHttp: OkHttpClient? = null,
    private val diagnostics: (YouTubeDiagnostic) -> Unit = {},
    private val transport: (suspend (Request) -> String)? = null,
) {
    companion object {
        const val WEB_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val WEB_VERSION = "2.20261029.11.00"
    }
    private data class Gate(val visitor: String = "", val version: String = WEB_VERSION, val jsUrl: String = "", val timestamp: Int = 0)
    private data class Client(val name: String, val number: String, val version: String, val agent: String)
    private val clients = listOf(
        Client("VISIONOS", "101", "1.02", "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"),
        Client("ANDROID", "3", "21.26.364", "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip"),
        Client("WEB", "1", "", WEB_AGENT),
    )
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).protocols(listOf(Protocol.HTTP_1_1)).build()
    private val media = (mediaHttp ?: http).newBuilder().callTimeout(10, TimeUnit.MINUTES).readTimeout(30, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            if (!mediaHostAllowed(chain.request().url.host)) throw YouTubeException("YouTube 媒体地址无效")
            chain.proceed(chain.request())
        }.build()
    private val gateLock = Mutex()
    @Volatile private var gate: Gate? = null
    private var script: Pair<String, String>? = null

    fun resetSession() { gate = null; script = null }

    /** 只读标题和封面；导入预览不需要媒体地址、登录凭证或签名脚本。 */
    suspend fun videoInfo(id: String): YouTubeVideo {
        if (!videoIdPattern.matches(id)) throw YouTubeException("视频编号不正确")
        val url = "https://www.youtube.com/oembed".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("url", "https://www.youtube.com/watch?v=$id").addQueryParameter("format", "json").build()
        val root = json(request(Request.Builder().url(url).header("User-Agent", WEB_AGENT).build()))
        val title = root.text("title").takeIf { it.isNotBlank() } ?: throw YouTubeException("没有读到 YouTube 视频标题")
        val cover = root.text("thumbnail_url").replace("http://", "https://").toHttpUrlOrNull()
            ?.takeIf { it.isHttps && (it.host == "ytimg.com" || it.host.endsWith(".ytimg.com")) }?.toString()
            ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        return YouTubeVideo(id, title, root.text("author_name"), cover)
    }

    suspend fun lookup(input: String): YouTubeResults {
        val parsed = inputOf(input)
        return when (parsed.kind) {
            InputKind.VIDEO -> YouTubeResults(listOf(resolveAudio(parsed.value).video))
            InputKind.PLAYLIST -> playlist(parsed.value)
            InputKind.SEARCH -> pages("search", parsed.value)
        }
    }
    suspend fun playlist(id: String): YouTubeResults {
        if (!Regex("[A-Za-z0-9_-]{2,80}").matches(id)) throw YouTubeException("播放列表编号不正确")
        if ((id == "LL" || id == "WL") && cookie().isNullOrBlank()) throw YouTubeException("请先在「我的」保存 YouTube Cookie")
        return pages("browse", id)
    }
    suspend fun accountStatus(): String {
        val saved = cookie().orEmpty()
        if (saved.isBlank()) return "还没有保存登录信息"
        val body = request(Request.Builder().url("https://www.youtube.com/").header("User-Agent", WEB_AGENT)
            .header("Cookie", saved).header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8").build())
        return when {
            body.contains("\"LOGGED_IN\":true") || body.contains("loggedIn\":true") -> "已登录"
            body.contains("\"LOGGED_IN\":false") -> "已保存，但 YouTube 没有确认登录"
            else -> "已保存，未能确认登录状态"
        }
    }
    private suspend fun visitor(): Gate = gate ?: gateLock.withLock {
        gate ?: try {
            val body = request(Request.Builder().url("https://www.youtube.com/").header("User-Agent", WEB_AGENT)
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8").build())
            fun field(name: String) = Regex("\"$name\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1).orEmpty()
            Gate(field("VISITOR_DATA"), field("INNERTUBE_CLIENT_VERSION").ifBlank { WEB_VERSION },
                field("jsUrl").replace("\\/", "/"), Regex("\"STS\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0)
                .also { if (it.visitor.isNotBlank()) gate = it }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Gate() }
    }
    private fun context(gate: Gate, spec: Client = clients.last()): JsonObject = buildJsonObject {
        putJsonObject("client") {
            put("clientName", spec.name); put("clientVersion", spec.version.ifBlank { gate.version })
            put("hl", "zh-CN"); put("gl", "US")
            if (gate.visitor.isNotBlank()) put("visitorData", gate.visitor)
            when (spec.name) {
                "VISIONOS" -> { put("deviceMake", "Apple"); put("deviceModel", "RealityDevice17,1"); put("osName", "visionOS"); put("osVersion", "26.5.23O471") }
                "ANDROID" -> { put("osName", "Android"); put("osVersion", "11"); put("androidSdkVersion", 30) }
            }
        }
    }
    private fun apiRequest(path: String, payload: JsonObject, gate: Gate, spec: Client = clients.last(), saved: String = cookie().orEmpty(), referer: String = "https://www.youtube.com/"): Request =
        Request.Builder().url("https://www.youtube.com/youtubei/v1/$path?prettyPrint=false")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("User-Agent", spec.agent).header("X-YouTube-Client-Name", spec.number)
            .header("X-YouTube-Client-Version", spec.version.ifBlank { gate.version })
            .header("Origin", "https://www.youtube.com").header("Referer", referer)
            .apply { authHeaders(saved, System.currentTimeMillis() / 1000).forEach { (key, value) -> header(key, value) } }.build()

    private suspend fun pages(path: String, value: String): YouTubeResults {
        val config = visitor()
        val saved = cookie().orEmpty()
        val videos = mutableListOf<YouTubeVideo>()
        val seen = mutableSetOf<String>()
        val search = path == "search"
        val max = if (search) 40 else 200
        var continuation: String? = null
        repeat(if (search) 3 else 5) {
            currentCoroutineContext().ensureActive()
            try {
                val payload = buildJsonObject {
                    put("context", context(config))
                    if (continuation != null) put("continuation", continuation!!)
                    else put(if (search) "query" else "browseId", if (search) value else "VL$value")
                }
                val ref = if (search) "https://www.youtube.com/results?search_query=${java.net.URLEncoder.encode(value, "UTF-8")}" else "https://www.youtube.com/playlist?list=$value"
                val root = json(request(apiRequest(path, payload, config, saved = saved, referer = ref)))
                val added = collectVideos(root, if (search) "videoRenderer" else "playlistVideoRenderer", max - videos.size, seen)
                videos += added
                continuation = continuationOf(root)
                if (added.isEmpty() || continuation == null || videos.size >= max) return results(videos, search)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (!search && videos.isNotEmpty()) return YouTubeResults(videos, true)
                throw if (error is YouTubeException) error else YouTubeException("YouTube 列表读取失败")
            }
        }
        return results(videos, search)
    }
    private fun results(videos: List<YouTubeVideo>, search: Boolean): YouTubeResults {
        if (videos.isEmpty()) throw YouTubeException(if (search) "没有找到相关视频" else "没有读到这个播放列表，请检查登录信息或列表是否可访问")
        return YouTubeResults(videos)
    }

    suspend fun resolveAudio(id: String): YouTubeAudio {
        if (!videoIdPattern.matches(id)) throw YouTubeException("视频编号不正确")
        var lastStatus = ""
        var message = "这个视频没有可用的独立音轨"
        val saved = cookie().orEmpty()
        for (authenticated in listOf(false, true)) {
            if (authenticated && (saved.isBlank() || lastStatus !in setOf("LOGIN_REQUIRED", "UNPLAYABLE", "AGE_CHECK_REQUIRED"))) break
            for (spec in clients) {
                currentCoroutineContext().ensureActive()
                try {
                    val config = visitor()
                    val payload = buildJsonObject {
                        put("videoId", id); put("context", context(config, spec)); put("contentCheckOk", true); put("racyCheckOk", true)
                        if (config.timestamp > 0) putJsonObject("playbackContext") {
                            putJsonObject("contentPlaybackContext") { put("signatureTimestamp", config.timestamp) }
                        }
                    }
                    val root = json(request(apiRequest("player", payload, config, spec, if (authenticated) saved else "")))
                    lastStatus = root.child("playabilityStatus").text("status")
                    message = when (lastStatus) {
                        "LOGIN_REQUIRED", "AGE_CHECK_REQUIRED" -> "这个视频需要登录或验证，请在「我的」更新 YouTube Cookie"
                        "UNPLAYABLE", "ERROR" -> "这个视频不可播放，可能需要登录或验证"
                        "OK", "" -> "这个视频没有可用的独立音轨"
                        else -> "YouTube 拒绝了播放请求"
                    }
                    if (lastStatus.isNotEmpty() && lastStatus != "OK") continue
                    val streams = audioStreams(root.child("streamingData"))
                    if (streams.isEmpty()) continue
                    val sigs = streams.mapNotNull { form(it.cipher)["s"]?.takeIf(String::isNotBlank) }.distinct()
                    val ns = streams.mapNotNull { stream ->
                        val url = if (stream.cipher.isBlank()) stream.url else form(stream.cipher)["url"].orEmpty()
                        url.toHttpUrlOrNull()?.queryParameter("n")
                    }.distinct()
                    val solved = if (sigs.isEmpty() && ns.isEmpty()) Solved(emptyMap(), emptyMap()) else {
                        val scriptUrl = config.jsUrl.takeIf { it.isNotBlank() } ?: throw YouTubeException("没有找到 YouTube 播放器脚本")
                        val url = (if (scriptUrl.startsWith('/')) "https://www.youtube.com$scriptUrl" else scriptUrl).toHttpUrlOrNull()
                            ?.takeIf { it.isHttps && it.host == "www.youtube.com" } ?: throw YouTubeException("YouTube 播放器脚本地址无效")
                        val player = script?.takeIf { it.first == url.toString() }?.second ?: request(
                            Request.Builder().url(url).header("User-Agent", WEB_AGENT).build(),
                        ).also { if (it.length < 100_000) throw YouTubeException("YouTube 播放器脚本读取失败"); script = url.toString() to it }
                        solvedResults(solver.solve(player, sigs, ns), sigs.isNotEmpty(), ns.isNotEmpty())
                    }
                    val chosen = streams.firstNotNullOfOrNull { stream -> finishUrl(stream, solved)?.let { stream to it } }
                        ?: throw YouTubeException("YouTube 签名解析失败")
                    val details = root.child("videoDetails")
                    val cover = (details.child("thumbnail").child("thumbnails") as? JsonArray)?.lastOrNull()?.text("url")
                    val video = YouTubeVideo(id, details.text("title").ifBlank { id }, details.text("author"),
                        cover?.takeIf { it.isNotBlank() } ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                        details.text("lengthSeconds").toLongOrNull()?.coerceIn(0, 1_000_000_000)?.times(1000) ?: 0)
                    // 媒体 CDN 使用签名地址，不向 googlevideo.com 转发 youtube.com 的登录 Cookie。
                    return YouTubeAudio(video, chosen.second, chosen.first.mime, mapOf(
                        "User-Agent" to spec.agent, "Referer" to video.watchUrl, "Origin" to "https://www.youtube.com",
                    ))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: YouTubeException) { message = error.message ?: "YouTube 读取失败" }
                catch (_: Exception) { lastStatus = ""; message = "连不上 YouTube" }
            }
        }
        throw YouTubeException(message)
    }

    suspend fun downloadAudio(id: String, partial: File, onProgress: (Long, Long) -> Unit): YouTubeAudio {
        for (attempt in 0..1) {
            val audio = resolveAudio(id)
            try {
                copyAudio(audio, partial, onProgress)
                return audio
            } catch (_: MediaRejected) {
                if (attempt == 1) throw YouTubeException("YouTube 媒体地址被拒绝，请检查网络或登录信息")
                resetSession()
            }
        }
        throw YouTubeException("YouTube 音轨下载失败")
    }

    private fun reportFailure(stage: String, error: Throwable, started: Long, responseCode: Int? = null, bytesRead: Long = 0, bytesTotal: Long = -1) {
        // 不使用异常 message 或堆栈，部分网络异常会把签名媒体地址放在里面。
        val types = generateSequence(error) { it.cause }.take(6).map { it.javaClass.simpleName }.joinToString(" > ")
        runCatching { diagnostics(YouTubeDiagnostic(stage, types, responseCode, bytesRead, bytesTotal, (System.nanoTime() - started) / 1_000_000)) }
    }

    /** 每次只请求 1 MiB；断流重试当前段，已完成的段保留。 */
    suspend fun copyAudio(audio: YouTubeAudio, partial: File, onProgress: (Long, Long) -> Unit) = suspendCancellableCoroutine<Unit> { continuation ->
        val started = System.nanoTime()
        val currentCall = java.util.concurrent.atomic.AtomicReference<Call?>()
        var position = 0L
        var total = -1L
        val chunkSize = 1024L * 1024L
        continuation.invokeOnCancellation { currentCall.get()?.cancel(); partial.delete() }

        fun fail(error: Exception) {
            partial.delete()
            if (continuation.isActive) continuation.resumeWithException(
                if (error is MediaRejected || error is YouTubeException) error else YouTubeException("YouTube 音轨下载失败", error),
            )
        }
        fun finish() {
            try {
                if (position < 1024 || partial.length() != position) throw YouTubeException("YouTube 音轨下载不完整")
                val signature = partial.inputStream().use { input ->
                    val header = ByteArray(8)
                    input.read(header)
                    String(header, 4, 4, Charsets.US_ASCII)
                }
                if (signature != "ftyp") throw YouTubeException("YouTube 返回的内容不是 MP4 音轨")
                onProgress(position, total)
                if (continuation.isActive) continuation.resume(Unit)
            } catch (error: Exception) { fail(error) }
        }
        lateinit var enqueueChunk: (Int) -> Unit
        fun failedAttempt(error: Exception, attempt: Int, stage: String, code: Int? = null, bytesRead: Long = position) {
            if (!continuation.isActive) { partial.delete(); return }
            reportFailure(stage, error, started, code, bytesRead, total)
            if (error is IOException && error !is YouTubeException && error !is MediaRejected && attempt < 2) {
                enqueueChunk(attempt + 1)
            } else fail(error)
        }
        enqueueChunk = enqueue@{ attempt ->
            if (!continuation.isActive) { partial.delete(); return@enqueue }
            val start = position
            val end = if (total > 0) minOf(start + chunkSize - 1, total - 1) else start + chunkSize - 1
            val request = Request.Builder().url(audio.url)
                .apply { audio.headers.forEach { (key, value) -> header(key, value) } }
                .header("Range", "bytes=$start-$end").build()
            val call = media.newCall(request)
            currentCall.set(call)
            if (!continuation.isActive) { call.cancel(); partial.delete(); return@enqueue }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    failedAttempt(e, attempt, "media_connect")
                }
                override fun onResponse(call: Call, response: Response) {
                    var read = 0L
                    var complete = false
                    try {
                        response.use {
                            if (!continuation.isActive) throw IOException("cancelled")
                            if (response.code in setOf(401, 403, 404)) throw MediaRejected(response.code)
                            if (response.code != 200 && response.code != 206) throw YouTubeException("YouTube 音轨下载失败")
                            val body = response.body ?: throw YouTubeException("音频内容为空")
                            val length: Long
                            if (response.code == 206) {
                                val range = parseMediaRange(response.header("Content-Range"))
                                    ?: throw YouTubeException("YouTube 返回的音轨分段信息无效")
                                if (range.start != start || range.end > end || (total > 0 && range.total != total)) {
                                    throw YouTubeException("YouTube 返回的音轨分段不匹配，请重试")
                                }
                                total = range.total
                                length = range.end - range.start + 1
                                if (body.contentLength() >= 0 && body.contentLength() != length) throw YouTubeException("YouTube 返回的音轨分段长度不匹配")
                            } else {
                                // 小文件即使忽略 Range 也可一次完成；大文件不能退回被限速的整段读取。
                                if (start != 0L || body.contentLength() > chunkSize) throw YouTubeException("音轨服务器未按分段返回内容，请重试")
                                length = body.contentLength()
                                if (length >= 0) total = length
                                complete = true
                            }
                            body.byteStream().use { input -> java.io.RandomAccessFile(partial, "rw").use { output ->
                                output.setLength(start)
                                output.seek(start)
                                val buffer = ByteArray(64 * 1024)
                                var reported = 0L
                                while (length < 0 || read < length) {
                                    if (!continuation.isActive) throw IOException("cancelled")
                                    val remaining = if (length < 0) chunkSize + 1 - read else length - read
                                    if (remaining <= 0) throw YouTubeException("音轨服务器未按分段返回内容，请重试")
                                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                    if (count < 0) {
                                        if (length >= 0 && read != length) throw java.io.EOFException("incomplete media chunk")
                                        break
                                    }
                                    output.write(buffer, 0, count)
                                    read += count
                                    if (length < 0 && read > chunkSize) throw YouTubeException("音轨服务器未按分段返回内容，请重试")
                                    if (read - reported >= 256 * 1024) {
                                        onProgress(start + read, total.coerceAtLeast(0)); reported = read
                                    }
                                }
                            } }
                        }
                        if (!continuation.isActive) { partial.delete(); return }
                        position = start + read
                        if (total < 0 && complete) total = position
                        onProgress(position, total.coerceAtLeast(0))
                        if (complete || position == total) finish() else enqueueChunk(0)
                    } catch (error: Exception) {
                        failedAttempt(error, attempt, "media_read", response.code, start + read)
                    }
                }
            })
        }
        enqueueChunk(0)
    }
    private fun json(value: String): JsonElement = try { Json.parseToJsonElement(value) } catch (_: Exception) { throw YouTubeException("YouTube 返回的数据无法读取") }
    private suspend fun request(request: Request): String {
        transport?.let { return it(request) }
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(YouTubeException("连不上 YouTube"))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val value = response.use {
                            if (!it.isSuccessful) throw YouTubeException(if (it.code == 401) "YouTube 登录已失效，请重新保存 Cookie" else "YouTube 请求失败，请稍后重试")
                            it.body?.string() ?: throw YouTubeException("YouTube 返回为空")
                        }
                        if (continuation.isActive) continuation.resume(value)
                    } catch (_: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(YouTubeException("YouTube 请求失败，请检查网络和登录信息"))
                    }
                }
            })
        }
    }
}
