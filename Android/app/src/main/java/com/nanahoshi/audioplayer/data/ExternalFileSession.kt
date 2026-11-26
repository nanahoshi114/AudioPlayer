package com.nanahoshi.audioplayer.data

import java.io.InputStream
import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.nanahoshi.audioplayer.asmr.AsmrClient
import com.nanahoshi.audioplayer.dlsite.DlsiteClient
import com.nanahoshi.audioplayer.dlsite.RemotePreview
import com.nanahoshi.audioplayer.dlsite.SignedPlayFile
import com.nanahoshi.audioplayer.dlsite.isTrustedDlsitePlayHost
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.LibraryFileEntity
import com.nanahoshi.audioplayer.playback.nearestSubtitleWorkRoot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.UUID
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.UserFacingError

class ExternalFileException(
    override val userText: TextRef,
    cause: Throwable? = null,
) : IOException(userText.debugString(), cause), UserFacingError {
    constructor(message: String) : this(TextRef.Raw(message))
}

data class ExternalFile(val uri: Uri, val mime: String)

/** One preview owns its temporary directory and never persists media URLs or credentials. */
class ExternalFileSession internal constructor(
    private val context: Context,
    private val cache: CacheStore,
    private val downloader: ExternalFileDownloader,
) {
    constructor(context: Context, cache: CacheStore, dlsite: DlsiteClient) : this(
        context, cache, ExternalFileDownloader(sign = dlsite::signedPlayFile),
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
            if (local == null && request.url.isBlank()) throw ExternalFileException(TextRef.Res(R.string.external_file_not_downloaded))
            cache.protect(directory)
            if (!directory.isDirectory && !directory.mkdirs()) throw ExternalFileException(TextRef.Res(R.string.external_file_staging_dir_failed))
            val target = File(directory, sanitizeEntryName(request.name))
            val partial = File(directory, target.name + ".part")
            try {
                if (local != null) {
                    if (local.length() > directory.usableSpace) throw ExternalFileException(TextRef.Res(R.string.external_file_no_space))
                    currentCoroutineContext().ensureActive()
                    local.inputStream().use { input -> partial.outputStream().use { output ->
                        copyExternalBytes(input, output, local.length(), progress = progress)
                    } }
                } else {
                    downloader.download(request, partial, progress)
                }
                currentCoroutineContext().ensureActive()
                if (!partial.renameTo(target)) throw ExternalFileException(TextRef.Res(R.string.external_file_save_temp_failed))
                ExternalFile(FileProvider.getUriForFile(context, "${context.packageName}.files", target), mime)
                    .also { ready = it; readyPath = target }
            } catch (cancelled: CancellationException) {
                partial.delete()
                throw cancelled
            } catch (failure: Exception) {
                partial.delete()
                if (failure is ExternalFileException) throw failure
                throw ExternalFileException(TextRef.Res(R.string.external_file_prepare_failed))
            }
        }

    fun close() { cache.release(directory) }
}

internal class ExternalFileDownloader(
    client: OkHttpClient = HttpRoots.shared,
    private val usableSpace: (File) -> Long = { it.parentFile!!.usableSpace },
    private val sign: suspend (String, String) -> SignedPlayFile,
) {
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun download(preview: RemotePreview, partial: File, progress: (Long, Long?) -> Unit) =
        download(preview, partial, Long.MAX_VALUE, progress)

    suspend fun downloadFile(preview: RemotePreview, target: File, maxBytes: Long = 25L * 1024 * 1024): Unit =
        withContext(Dispatchers.IO) {
            require(maxBytes > 0L)
            val directory = requireNotNull(target.absoluteFile.parentFile)
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw ExternalFileException(TextRef.Res(R.string.external_file_staging_dir_failed))
            }
            val partial = File.createTempFile("preview-", ".part", directory)
            try {
                download(preview, partial, maxBytes) { _, _ -> }
                currentCoroutineContext().ensureActive()
                if (!partial.renameTo(target)) throw ExternalFileException(TextRef.Res(R.string.external_file_save_temp_failed))
            } finally {
                partial.delete()
            }
        }

    private suspend fun download(preview: RemotePreview, partial: File, maxBytes: Long, progress: (Long, Long?) -> Unit) {
        transfer(preview) { response ->
            val body = response.body ?: throw ExternalFileException(TextRef.Res(R.string.external_file_empty_body))
            val length = body.contentLength().takeIf { it >= 0 }
            if (length != null && length > maxBytes) throw ExternalFileException(TextRef.Res(R.string.library_file_image_failed))
            if (length != null && length > usableSpace(partial)) throw ExternalFileException(TextRef.Res(R.string.external_file_no_space))
            body.byteStream().use { input ->
                partial.outputStream().use { output -> copyExternalBytes(input, output, length, maxBytes, progress) }
            }
        }
    }

    suspend fun readText(preview: RemotePreview, limit: Int): RemoteTextPreview = transfer(preview) { response ->
        response.body?.byteStream()?.readRemoteTextPreview(limit) ?: RemoteTextPreview("")
    }

    suspend fun readFullText(preview: RemotePreview, maxBytes: Int = MAX_SUBTITLE_TEXT_BYTES): String =
        transfer(preview) { response ->
            response.body?.byteStream()?.readFullText(maxBytes).orEmpty()
        }

    private suspend fun <T : Any> transfer(preview: RemotePreview, consume: suspend (Response) -> T): T {
        for (attempt in 0 until if (preview.authed) 2 else 1) {
            currentCoroutineContext().ensureActive()
            val signed = if (preview.authed) {
                val work = preview.workno ?: throw ExternalFileException(TextRef.Res(R.string.external_file_reopen_work))
                val key = preview.fileKey ?: throw ExternalFileException(TextRef.Res(R.string.external_file_reopen_work))
                sign(work, key)
            } else null
            var url = (signed?.url ?: preview.url.toHttps()).toHttpUrlOrNull()
                ?: throw ExternalFileException(TextRef.Res(R.string.external_file_read_failed))
            if (signed != null && (url.port != 443 || !isTrustedDlsitePlayHost(url.host))) {
                throw ExternalFileException(TextRef.Res(R.string.dlsite_error_no_play_url))
            }
            val credentialHost = if (signed != null) url.host else null
            var retryAuthorization = false
            for (hop in 0..8) {
                currentCoroutineContext().ensureActive()
                if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty()) {
                    throw ExternalFileException(TextRef.Res(R.string.external_file_redirect_invalid))
                }
                val builder = Request.Builder().url(url)
                    .header("Referer", preview.referer)
                    .header("Accept-Encoding", "identity")
                    .header("User-Agent", if (preview.authed) DlsiteClient.USER_AGENT else AsmrClient.USER_AGENT)
                if (!preview.authed && preview.referer == AsmrClient.REFERER) builder.header("Origin", AsmrClient.ORIGIN)
                if (signed != null && url.host == credentialHost && url.port == 443 && isTrustedDlsitePlayHost(url.host)) {
                    builder.header("Cookie", signed.cookie)
                }
                val result = client.newCall(builder.build()).withResponse { response ->
                    when {
                        response.code in 300..399 -> {
                        url = HttpRedirect.resolve(url, response)
                            ?: throw ExternalFileException(TextRef.Res(R.string.external_file_redirect_invalid))
                        null
                    }
                        preview.authed && attempt == 0 && response.code in setOf(401, 403) -> {
                            retryAuthorization = true
                            null
                        }
                        else -> {
                            if (!response.isSuccessful) throw ExternalFileException(TextRef.Res(R.string.external_file_read_failed))
                            if (response.body?.contentType()?.subtype?.contains("html") == true &&
                                fileExtension(preview.name) !in setOf("html", "htm")
                            ) throw ExternalFileException(TextRef.Res(R.string.external_file_login_expired))
                            consume(response)
                        }
                    }
                }
                if (result != null) return result
                if (retryAuthorization) break
            }
            if (!retryAuthorization) break
        }
        throw ExternalFileException(TextRef.Res(R.string.external_file_read_failed))
    }
}

internal suspend fun copyExternalBytes(
    input: InputStream, output: java.io.OutputStream, total: Long?, maxBytes: Long = Long.MAX_VALUE, progress: (Long, Long?) -> Unit,
) {
    try {
        copyStreamToFile(input, output, total, maxBytes, progress = progress)
    } catch (_: StreamCopyLimitExceededException) {
        throw ExternalFileException(TextRef.Res(R.string.library_file_image_failed))
    } catch (error: StreamCopyIncompleteException) {
        throw ExternalFileException(TextRef.Res(R.string.external_file_read_failed), error)
    }
}

internal fun externalFileMime(name: String): String = MimeTypeMap.getSingleton()
    .getMimeTypeFromExtension(fileExtension(name)) ?: "*/*"

internal data class RemoteTextPreview(val text: String, val clipped: Boolean = false)

internal fun InputStream.readRemoteTextPreview(limit: Int = 512 * 1024): RemoteTextPreview {
    val (bytes, clipped) = readBoundedBytes(this, limit)
    return RemoteTextPreview(decodeTextBytes(bytes, clipped), clipped)
}

internal fun readLocalTextPreview(context: Context, location: String): RemoteTextPreview? {
    val stream = openLocalTextStream(context, location)
    return stream?.use { it.readRemoteTextPreview() }
}

internal fun readLocalTextFull(
    context: Context,
    location: String,
    maxBytes: Int = MAX_SUBTITLE_TEXT_BYTES,
): String? = openLocalTextStream(context, location)?.use { it.readFullText(maxBytes) }

private fun openLocalTextStream(context: Context, location: String): InputStream? = when {
    location.startsWith("content:") -> context.contentResolver.openInputStream(Uri.parse(location))
    location.startsWith("file:") -> Uri.parse(location).path?.let { File(it).inputStream() }
    location.startsWith("/") -> File(location).inputStream()
    else -> null
}

internal fun libraryFilePreview(
    file: LibraryFileEntity,
    folders: Map<Long, FolderEntity>,
    source: TrackSource? = resolveLibraryFileSource(file),
): RemotePreview {
    val authed = source == TrackSource.DLSITE
    val url = file.remoteUrl.takeUnless { source == TrackSource.JAPANESE_ASMR }.orEmpty()
    val workno = if (authed) {
        nearestSubtitleWorkRoot(file.folderId, folders)?.asmrSourceId?.takeIf { it.isNotBlank() }
            ?: folders.values.firstOrNull { it.asmrWorkId == file.asmrWorkId && !it.asmrSourceId.isNullOrBlank() }?.asmrSourceId
    } else null
    // Older Play attachments stored their library path as the hash and the Play key in the URL.
    val key = if (authed) {
        url.substringAfter("/optimized/", "").substringBefore('?').substringBefore('#').takeIf { it.isNotBlank() }
            ?: rawFileKey(file.fileHash)?.takeUnless { it.startsWith("original:") }
    } else null
    return RemotePreview(
        file.name, url, file.kind, if (authed) DlsiteClient.PLAY_REFERER else AsmrClient.REFERER,
        authed, workno = workno, fileKey = key,
    )
}
