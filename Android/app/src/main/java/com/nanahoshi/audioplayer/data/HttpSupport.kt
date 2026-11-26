package com.nanahoshi.audioplayer.data

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 进程级 HTTP 根客户端。各来源从 [shared] 派生自己的超时、重定向与拦截器，
 * 共享连接池和调度线程，避免 17+ 个独立根实例各建一套资源。
 */
object HttpRoots {
    private const val MAX_IDLE_CONNECTIONS = 12
    private const val KEEP_ALIVE_MINUTES = 5L
    private const val JA_MAX_IDLE_CONNECTIONS = 4
    private const val JA_KEEP_ALIVE_MINUTES = 2L

    /** 一般来源共用的根客户端。 */
    val shared: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES))
            .dispatcher(Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 8
            })
            .build()
    }

    /**
     * Japanese ASMR 独立连接池。它关闭代理、改写 DNS 并手工跟随重定向，
     * 不能与普通来源共享连接行为。
     */
    val japaneseAsmr: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(JA_MAX_IDLE_CONNECTIONS, JA_KEEP_ALIVE_MINUTES, TimeUnit.MINUTES))
            .dispatcher(Dispatcher().apply {
                maxRequests = 16
                maxRequestsPerHost = 4
            })
            .build()
    }
}

/** `Content-Range: bytes start-end/total` 的集中解析，所有分片与续传共用。 */
data class HttpByteRange(val start: Long, val end: Long, val total: Long) {
    val length: Long get() = end - start + 1

    companion object {
        private val RANGE = Regex("""^\s*bytes\s+(\d+)-(\d+)/(\d+)\s*$""", RegexOption.IGNORE_CASE)
        private val UNSATISFIED = Regex("""^\s*bytes\s+\*/(\d+)\s*$""", RegexOption.IGNORE_CASE)

        fun parse(header: String?): HttpByteRange? {
            val match = RANGE.matchEntire(header.orEmpty()) ?: return null
            val start = match.groupValues[1].toLongOrNull() ?: return null
            val end = match.groupValues[2].toLongOrNull() ?: return null
            val total = match.groupValues[3].toLongOrNull() ?: return null
            if (start < 0 || end < start || total <= end) return null
            return HttpByteRange(start, end, total)
        }

        /** `bytes *\/N`，只用于解释 416 时的资源总长。 */
        fun unsatisfiedTotal(header: String?): Long? {
            val match = UNSATISFIED.matchEntire(header?.trim().orEmpty()) ?: return null
            return match.groupValues[1].toLongOrNull()
        }
    }
}

/** 手工重定向循环共用的判定与解析。 */
object HttpRedirect {
    const val MAX_HOPS = 8
    private val CODES = setOf(301, 302, 303, 307, 308)

    fun isRedirect(code: Int): Boolean = code in CODES

    fun resolve(current: HttpUrl, response: Response): HttpUrl? =
        response.header("Location")?.let(current::resolve)
}

/**
 * 执行 OkHttp [Call] 并把响应体交给 [block]；协程取消时取消底层请求，
 * 无论正常返回还是异常都关闭响应。
 */
internal suspend fun <T> Call.withResponse(block: suspend (Response) -> T): T = coroutineScope {
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { this@withResponse.cancel() }
    }
    try { awaitHeaders().use { block(it) } } finally { watcher.cancel() }
}

private suspend fun Call.awaitHeaders(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}

internal class StreamCopyLimitExceededException : IOException("copy limit exceeded")
internal class StreamCopyIncompleteException(val done: Long, val total: Long?) :
    IOException("incomplete copy: $done/$total")

/**
 * 带取消和进度节流的缓冲拷贝。分片、直链和外部预览都通过这里落盘，
 * 不再各自维护一份 64 KiB 循环。
 *
 * @throws StreamCopyLimitExceededException 超过 [maxBytes]
 * @throws StreamCopyIncompleteException 空文件或未读满已知长度
 */
internal suspend fun copyStreamToFile(
    input: InputStream,
    output: OutputStream,
    total: Long?,
    maxBytes: Long = Long.MAX_VALUE,
    bufferSize: Int = COPY_BUFFER_BYTES,
    progress: (done: Long, total: Long?) -> Unit = { _, _ -> },
): Long {
    val context = currentCoroutineContext()
    val buffer = ByteArray(bufferSize)
    var done = 0L
    var reported = 0L
    while (true) {
        context.ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        if (count > maxBytes - done) throw StreamCopyLimitExceededException()
        output.write(buffer, 0, count)
        done += count
        if (done - reported >= COPY_PROGRESS_BYTES || done == total) {
            progress(done, total)
            reported = done
        }
    }
    if (done == 0L) throw StreamCopyIncompleteException(done, total)
    if (total != null && done != total) throw StreamCopyIncompleteException(done, total)
    progress(done, total)
    return done
}

private const val COPY_BUFFER_BYTES = 64 * 1024
private const val COPY_PROGRESS_BYTES = 256 * 1024L