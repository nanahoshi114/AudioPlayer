package com.nanahoshi.audioplayer.data

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.nanahoshi.audioplayer.asmr.AsmrClient
import com.nanahoshi.audioplayer.dlsite.DlsiteClient
import com.nanahoshi.audioplayer.dlsite.RemotePreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ExternalFileException(message: String) : IOException(message)

data class ExternalFile(val uri: Uri, val mime: String)

/** One preview owns its temporary directory and never persists media URLs or credentials. */
class ExternalFileSession internal constructor(
    private val context: Context,
    private val cache: CacheStore,
    private val downloader: ExternalFileDownloader,
) {
    constructor(context: Context, cache: CacheStore, dlsite: DlsiteClient) : this(
        context, cache, ExternalFileDownloader { work, key -> dlsite.signedPlayFileBlocking(work, key) },
    )
    private val directory = File(context.cacheDir, "external-open/${UUID.randomUUID()}")
    private var ready: ExternalFile? = null
    private var readyPath: File? = null

    suspend fun prepare(request: RemotePreview, localUri: String?, progress: (Long, Long?) -> Unit): ExternalFile =
        withContext(Dispatchers.IO) {
            ready?.takeIf { readyPath?.isFile == true }?.let { return@withContext it }
            val local = localUri?.let { if (it.startsWith("file:")) File(Uri.parse(it).path.orEmpty()) else File(it) }
                ?.takeIf { it.isFile }
            val mime = externalFileMime(request.name)
            if (localUri?.startsWith("content:") == true) return@withContext ExternalFile(Uri.parse(localUri), mime)
            if (local != null && local.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator)) {
                return@withContext ExternalFile(FileProvider.getUriForFile(context, "${context.packageName}.files", local), mime)
            }
            if (local == null && request.url.isBlank()) throw ExternalFileException("这个文件还没有下载到本机，请重新下载完整原始包")
            cache.protect(directory)
            if (!directory.isDirectory && !directory.mkdirs()) throw ExternalFileException("无法创建文件暂存目录")
            val target = File(directory, sanitizeEntryName(request.name))
            val partial = File(directory, target.name + ".part")
            try {
                if (local != null) {
                    if (local.length() > directory.usableSpace) throw ExternalFileException("剩余空间不足")
                    currentCoroutineContext().ensureActive()
                    local.inputStream().use { input -> partial.outputStream().use { output ->
                        copyExternalBytes(input, output, local.length(), progress)
                    } }
                } else {
                    downloader.download(request, partial, progress)
                }
                currentCoroutineContext().ensureActive()
                if (!partial.renameTo(target)) throw ExternalFileException("无法保存暂存文件")
                ExternalFile(FileProvider.getUriForFile(context, "${context.packageName}.files", target), mime)
                    .also { ready = it; readyPath = target }
            } catch (cancelled: CancellationException) {
                partial.delete()
                throw cancelled
            } catch (failure: Exception) {
                partial.delete()
                if (failure is ExternalFileException) throw failure
                throw ExternalFileException("无法准备这个文件，请检查网络、登录状态或剩余空间后重试")
            }
        }

    fun close() { cache.release(directory) }
}

internal class ExternalFileDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build(),
    private val usableSpace: (File) -> Long = { it.parentFile!!.usableSpace },
    private val sign: (String, String) -> com.nanahoshi.audioplayer.dlsite.SignedPlayFile,
) {
    suspend fun download(preview: RemotePreview, partial: File, progress: (Long, Long?) -> Unit) {
        for (attempt in 0 until if (preview.authed) 2 else 1) {
            currentCoroutineContext().ensureActive()
            val signed = if (preview.authed) {
                val work = preview.workno ?: error("请重新打开作品以获取文件授权")
                val key = preview.fileKey ?: error("请重新打开作品以获取文件授权")
                sign(work, key)
            } else null
            var url = (signed?.url ?: preview.url.toHttps()).toHttpUrl()
            val credentialHost = if (signed != null) url.host else null
            for (hop in 0..8) {
                currentCoroutineContext().ensureActive()
                require(url.scheme == "https" && url.username.isEmpty() && url.password.isEmpty())
                val builder = Request.Builder().url(url)
                    .header("Referer", preview.referer)
                    .header("Accept-Encoding", "identity")
                    .header("User-Agent", if (preview.authed) DlsiteClient.USER_AGENT else AsmrClient.USER_AGENT)
                if (!preview.authed && preview.referer == AsmrClient.REFERER) builder.header("Origin", AsmrClient.ORIGIN)
                if (signed != null && url.host == credentialHost && url.port == 443) builder.header("Cookie", signed.cookie)
                val call = client.newCall(builder.build())
                var transferred = false
                val retryAuthorization = call.withResponse {
                    if (it.code in 300..399) {
                        url = it.header("Location")?.let(url::resolve) ?: error("下载跳转无效")
                        false
                    } else if (preview.authed && attempt == 0 && it.code in setOf(401, 403)) {
                        true
                    } else {
                        check(it.isSuccessful)
                        val body = it.body ?: error("文件为空")
                        if (body.contentType()?.subtype?.contains("html") == true && fileExtension(preview.name) !in setOf("html", "htm")) {
                            throw ExternalFileException("服务器返回了网页，请检查登录状态后重试")
                        }
                        val length = body.contentLength().takeIf { n -> n >= 0 }
                        if (length != null && length > usableSpace(partial)) throw ExternalFileException("剩余空间不足")
                        body.byteStream().use { input -> partial.outputStream().use { output -> copyExternalBytes(input, output, length, progress) } }
                        transferred = true
                        false
                    }
                }
                if (transferred) return
                if (retryAuthorization) break
            }
        }
        throw ExternalFileException("无法读取文件或跳转次数过多")
    }

}

internal suspend fun copyExternalBytes(input: java.io.InputStream, output: java.io.OutputStream, total: Long?, progress: (Long, Long?) -> Unit) {
    val context = currentCoroutineContext()
    val buffer = ByteArray(64 * 1024)
    var done = 0L
    var reported = 0L
    while (true) {
        context.ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        output.write(buffer, 0, count)
        done += count
        if (done - reported >= 256 * 1024 || done == total) { progress(done, total); reported = done }
    }
    check(done > 0 && (total == null || total == done))
    progress(done, total)
}

internal fun externalFileMime(name: String): String = MimeTypeMap.getSingleton()
    .getMimeTypeFromExtension(fileExtension(name)) ?: "*/*"

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

private suspend fun <T> Call.withResponse(block: suspend (Response) -> T): T = coroutineScope {
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { this@withResponse.cancel() }
    }
    try { awaitHeaders().use { block(it) } } finally { watcher.cancel() }
}
