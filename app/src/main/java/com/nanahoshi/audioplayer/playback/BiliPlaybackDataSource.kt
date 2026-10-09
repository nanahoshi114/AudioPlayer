package com.nanahoshi.audioplayer.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.core.net.toUri
import com.nanahoshi.audioplayer.asmr.AsmrClient
import com.nanahoshi.audioplayer.bilibili.BiliAudio
import com.nanahoshi.audioplayer.bilibili.BilibiliClient
import com.nanahoshi.audioplayer.bilibili.BilibiliException
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.rawFileKey
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.dlsite.DlsiteClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * B 站条目先给出一份只含音轨的播放清单，真正的音频地址再用带浏览器标识的请求去拉。
 * 本地文件仍走系统原有的读取方式。
 */
class BiliPlaybackDataSource(
    private val fallback: DataSource,
    private val asmrHttp: DataSource,
    private val httpClient: OkHttpClient,
    private val api: BilibiliClient,
    private val asmr: AsmrClient,
    private val dlsite: DlsiteClient,
    private val db: AppDatabase,
) : DataSource {
    private var current: DataSource = fallback

    override fun addTransferListener(transferListener: TransferListener) {
        fallback.addTransferListener(transferListener)
        asmrHttp.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        when (dataSpec.uri.scheme) {
            "asmr" -> return openAsmr(dataSpec)
            "dlsite" -> return openDlsite(dataSpec)
            "remote" -> return openRemote(dataSpec)
            "bilibili" -> Unit
            else -> {
                current = fallback
                return fallback.open(dataSpec)
            }
        }
        val manifest = try {
            manifestBytes(dataSpec.uri)
        } catch (error: BilibiliException) {
            throw IOException(error.message ?: "无法获取 B 站音轨", error)
        }
        val bytes = androidx.media3.datasource.ByteArrayDataSource(manifest)
        current = bytes
        return bytes.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current.read(buffer, offset, length)

    override fun getUri(): Uri? = current.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current.responseHeaders

    override fun close() {
        current.close()
        current = fallback
    }

    private fun manifestBytes(uri: Uri): ByteArray {
        val bvid = uri.getQueryParameter("bvid").orEmpty()
        val cid = uri.getQueryParameter("cid")?.toLongOrNull()
            ?: throw IOException("无法识别 B 站音频")
        if (bvid.isBlank()) throw IOException("无法识别 B 站音频")
        return dashManifest(api.resolveAudioBlocking(bvid, cid)).toByteArray()
    }

    private fun openAsmr(dataSpec: DataSpec): Long {
        val trackId = dataSpec.uri.getQueryParameter("trackId")?.toLongOrNull()
            ?: throw IOException("无法识别音频")
        val track = runBlocking(Dispatchers.IO) { db.tracks().getById(trackId) }
            ?: throw IOException("找不到音频")
        val local = track.localUri
        if (!local.isNullOrBlank() && local.startsWith("/") && File(local).isFile) {
            val fileSpec = dataSpec.buildUpon().setUri(Uri.fromFile(File(local))).build()
            current = fallback
            return fallback.open(fileSpec)
        }
        var url = track.remoteUrl
        if (url.isNullOrBlank()) url = refreshAsmrUrl(track)
        if (url.isNullOrBlank()) throw IOException("没有可播放的地址")
        return try {
            openAsmrHttp(dataSpec, url)
        } catch (error: HttpDataSource.InvalidResponseCodeException) {
            if (error.responseCode != 403 && error.responseCode != 404) throw error
            val fresh = refreshAsmrUrl(track) ?: throw IOException("无法刷新音频地址", error)
            openAsmrHttp(dataSpec, fresh)
        }
    }

    private fun openDlsite(dataSpec: DataSpec): Long {
        val trackId = dataSpec.uri.getQueryParameter("trackId")?.toLongOrNull()
            ?: throw IOException("无法识别音频")
        val track = runBlocking(Dispatchers.IO) { db.tracks().getById(trackId) }
            ?: throw IOException("找不到音频")
        val local = track.localUri
        if (!local.isNullOrBlank() && local.startsWith("/") && File(local).isFile) {
            val fileSpec = dataSpec.buildUpon().setUri(Uri.fromFile(File(local))).build()
            current = fallback
            return fallback.open(fileSpec)
        }
        val url = track.remoteUrl
        if (url.isNullOrBlank()) throw IOException("没有可播放的地址")
        val workno = runBlocking(Dispatchers.IO) { worknoOf(track) }
        return openAuthed(dataSpec, url, DlsiteClient.PLAY_REFERER, workno, rawFileKey(track.fileHash))
    }

    private fun openRemote(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        val url = uri.getQueryParameter("url").orEmpty()
        if (url.isBlank()) throw IOException("没有可播放的地址")
        if (url.startsWith("/") && File(url).isFile) {
            val fileSpec = dataSpec.buildUpon().setUri(Uri.fromFile(File(url))).build()
            current = fallback
            return fallback.open(fileSpec)
        }
        val referer = uri.getQueryParameter("referer").orEmpty().ifBlank { AsmrClient.REFERER }
        val source = uri.getQueryParameter("source").orEmpty()
        val workno = uri.getQueryParameter("workno").orEmpty()
        val fileKey = uri.getQueryParameter("file").orEmpty()
        if (source == TrackSource.DLSITE.name && fileKey.isNotBlank()) {
            return openAuthed(dataSpec, url, referer.ifBlank { DlsiteClient.PLAY_REFERER }, workno, fileKey)
        }
        val userAgent = if (source == TrackSource.DLSITE.name) DlsiteClient.USER_AGENT else AsmrClient.USER_AGENT
        return try {
            openHttp(dataSpec, url, referer, cookie = null, userAgent = userAgent)
        } catch (error: HttpDataSource.InvalidResponseCodeException) {
            if (source == TrackSource.DLSITE.name) throw error
            if (error.responseCode != 403 && error.responseCode != 404) throw error
            val workId = workno.toLongOrNull() ?: throw IOException("无法刷新音频地址", error)
            val fresh = runBlocking(Dispatchers.IO) {
                try {
                    AsmrClient.findStream(asmr.trackTree(workId), fileKey, "")
                } catch (_: Exception) {
                    null
                }
            } ?: throw IOException("无法刷新音频地址", error)
            openHttp(dataSpec, fresh, referer, cookie = null, userAgent = AsmrClient.USER_AGENT)
        }
    }

    private fun openAuthed(
        dataSpec: DataSpec,
        url: String,
        referer: String,
        workno: String?,
        fileKey: String?,
    ): Long {
        val signed = if (!workno.isNullOrBlank() && !fileKey.isNullOrBlank()) {
            try {
                dlsite.signedPlayFileBlocking(workno, fileKey)
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        val cookie = signed?.cookie ?: dlsite.playCookie()
        return try {
            openHttp(dataSpec, signed?.url ?: url, referer, cookie, DlsiteClient.USER_AGENT)
        } catch (error: HttpDataSource.InvalidResponseCodeException) {
            if (error.responseCode == 401) throw IOException("登录已失效，请重新登录", error)
            if ((error.responseCode != 403 && error.responseCode != 404) || workno.isNullOrBlank() || fileKey.isNullOrBlank()) {
                throw error
            }
            val fresh = try {
                dlsite.signedPlayFileBlocking(workno, fileKey)
            } catch (failure: Exception) {
                throw IOException(failure.message ?: "无法刷新音频地址", failure)
            }
            openHttp(dataSpec, fresh.url, referer, fresh.cookie, DlsiteClient.USER_AGENT)
        }
    }

    private fun openHttp(
        dataSpec: DataSpec,
        url: String,
        referer: String,
        cookie: String?,
        userAgent: String,
    ): Long {
        val spec = dataSpec.buildUpon().setUri(url.toUri()).build()
        val http = BrowserHttpDataSource(httpClient, referer = referer, userAgent = userAgent, cookie = cookie)
        current = http
        return http.open(spec)
    }

    private fun worknoOf(track: TrackEntity): String? = runBlocking(Dispatchers.IO) {
        var folderId = track.folderId
        while (folderId != null) {
            val folder = db.folders().get(folderId) ?: break
            if (!folder.asmrSourceId.isNullOrBlank()) return@runBlocking folder.asmrSourceId
            folderId = folder.parentId
        }
        null
    }

    private fun openAsmrHttp(dataSpec: DataSpec, url: String): Long {
        val spec = dataSpec.buildUpon().setUri(url.toUri()).build()
        current = asmrHttp
        return asmrHttp.open(spec)
    }

    private fun refreshAsmrUrl(track: TrackEntity): String? = runBlocking(Dispatchers.IO) {
        val workId = track.asmrWorkId ?: return@runBlocking null
        val fresh = try {
            AsmrClient.findStream(asmr.trackTree(workId), rawFileKey(track.fileHash), track.title)
        } catch (_: Exception) {
            null
        }
        if (!fresh.isNullOrBlank()) db.tracks().updateRemoteUrl(track.id, fresh)
        fresh
    }

    class Factory(
        context: Context,
        private val api: BilibiliClient,
        private val asmr: AsmrClient,
        private val dlsite: DlsiteClient,
        private val db: AppDatabase,
    ) : DataSource.Factory {
        private val appContext = context.applicationContext
        private val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()

        override fun createDataSource(): DataSource {
            val http = BrowserHttpDataSource(client)
            val fallback = DefaultDataSource(appContext, http)
            val asmrHttp = BrowserHttpDataSource(
                client,
                referer = AsmrClient.REFERER,
                userAgent = AsmrClient.USER_AGENT,
            )
            return BiliPlaybackDataSource(fallback, asmrHttp, client, api, asmr, dlsite, db)
        }
    }
}

private fun dashManifest(audio: BiliAudio): String {
    val seconds = audio.durationSeconds
    val duration = if (seconds % 1.0 == 0.0) seconds.toLong().toString() else seconds.toString()
    return """
        <?xml version="1.0" encoding="utf-8"?>
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" type="static" mediaPresentationDuration="PT${duration}S" minBufferTime="PT1.5S">
          <Period>
            <AdaptationSet contentType="audio" mimeType="${xml(audio.mimeType)}">
              <Representation id="audio" bandwidth="${audio.bandwidth}" codecs="${xml(audio.codecs)}">
                <BaseURL>${xml(audio.url)}</BaseURL>
                <SegmentBase indexRange="${xml(audio.indexRange)}">
                  <Initialization range="${xml(audio.initializationRange)}"/>
                </SegmentBase>
              </Representation>
            </AdaptationSet>
          </Period>
        </MPD>
    """.trimIndent()
}

private fun xml(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

/** 下载时固定带上网页来源和浏览器标识，并把被改写的逗号还原，否则 B 站会拒绝。 */
private class BrowserHttpDataSource(
    private val client: OkHttpClient,
    private val referer: String = "https://www.bilibili.com",
    private val userAgent: String = BilibiliClient.USER_AGENT,
    private val cookie: String? = null,
) : BaseDataSource(true) {
    private var response: okhttp3.Response? = null
    private var stream: InputStream? = null
    private var openedUri: Uri? = null
    private var bytesRemaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val url = dataSpec.uri.toString().replace("%2C", ",").replace("%2c", ",")
        val builder = Request.Builder()
            .url(url)
            .header("Referer", referer)
            .header("User-Agent", userAgent)
        if (!cookie.isNullOrBlank()) builder.header("Cookie", cookie)
        val position = dataSpec.position
        if (position != 0L || dataSpec.length != C.LENGTH_UNSET.toLong()) {
            val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                ""
            } else {
                (position + dataSpec.length - 1).toString()
            }
            builder.header("Range", "bytes=$position-$end")
        }
        val call = client.newCall(builder.build()).execute()
        response = call
        if (call.code != 200 && call.code != 206) {
            val body = call.body?.bytes() ?: ByteArray(0)
            val headers = call.headers.toMultimap()
            val code = call.code
            val message = call.message
            call.close()
            response = null
            throw HttpDataSource.InvalidResponseCodeException(code, message, null, headers, dataSpec, body)
        }
        val responseBody = call.body ?: throw IOException("音频内容为空")
        stream = responseBody.byteStream()
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            call.code == 206 -> responseBody.contentLength().takeIf { it >= 0 } ?: C.LENGTH_UNSET.toLong()
            else -> {
                val length = responseBody.contentLength()
                if (length >= 0) (length - position).coerceAtLeast(0) else C.LENGTH_UNSET.toLong()
            }
        }
        openedUri = Uri.parse(url)
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val max = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(length.toLong(), bytesRemaining).toInt()
        }
        val read = stream?.read(buffer, offset, max) ?: C.RESULT_END_OF_INPUT
        if (read == -1) return C.RESULT_END_OF_INPUT
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = openedUri

    override fun close() {
        try {
            stream?.close()
        } finally {
            stream = null
            response?.close()
            response = null
            if (openedUri != null) {
                openedUri = null
                transferEnded()
            }
        }
    }
}
