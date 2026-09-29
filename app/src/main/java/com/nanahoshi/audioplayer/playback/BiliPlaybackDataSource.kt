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
import com.nanahoshi.audioplayer.bilibili.BiliAudio
import com.nanahoshi.audioplayer.bilibili.BilibiliClient
import com.nanahoshi.audioplayer.bilibili.BilibiliException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * B 站条目先给出一份只含音轨的播放清单，真正的音频地址再用带浏览器标识的请求去拉。
 * 本地文件仍走系统原有的读取方式。
 */
class BiliPlaybackDataSource(
    private val fallback: DataSource,
    private val api: BilibiliClient,
) : DataSource {
    private var current: DataSource = fallback

    override fun addTransferListener(transferListener: TransferListener) {
        fallback.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.uri.scheme != "bilibili") {
            current = fallback
            return fallback.open(dataSpec)
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

    class Factory(
        context: Context,
        private val api: BilibiliClient,
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
            return BiliPlaybackDataSource(fallback, api)
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
            .header("Referer", "https://www.bilibili.com")
            .header("User-Agent", BilibiliClient.USER_AGENT)
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
