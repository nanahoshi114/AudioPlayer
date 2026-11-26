package com.nanahoshi.audioplayer.playback

import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.UserFacingIOException
import com.nanahoshi.audioplayer.text.userTextOr
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.youtube.YouTubeClient
import com.nanahoshi.audioplayer.youtube.readyYouTubeAudio

import com.nanahoshi.audioplayer.japaneseasmr.JaFailure
import com.nanahoshi.audioplayer.japaneseasmr.JaMatchResult
import com.nanahoshi.audioplayer.japaneseasmr.JaMediaKindChanged
import com.nanahoshi.audioplayer.japaneseasmr.resolveQueuedJaMatch
import com.nanahoshi.audioplayer.japaneseasmr.resolveStoredJaMatches
import com.nanahoshi.audioplayer.japaneseasmr.JaMediaKind
import com.nanahoshi.audioplayer.japaneseasmr.JapaneseAsmrClient
import com.nanahoshi.audioplayer.japaneseasmr.JapaneseAsmrException
import com.nanahoshi.audioplayer.japaneseasmr.JapaneseAsmrClient.Companion.isChallenge
import com.nanahoshi.audioplayer.japaneseasmr.restoreJaMediaUrl
import com.nanahoshi.audioplayer.japaneseasmr.rewriteJaPlaylist

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
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
import com.nanahoshi.audioplayer.data.withResponse
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
internal class JaLocalCopyLost(cause: Exception) : IOException(cause)

class BiliPlaybackDataSource(
    private val fallback: DataSource,
    private val asmrHttp: DataSource,
    private val httpClient: OkHttpClient,
    private val api: BilibiliClient,
    private val asmr: AsmrClient,
    private val dlsite: DlsiteClient,
    private val db: AppDatabase,
    private val youtube: YouTubeClient,
    private val filesDir: File,
    private val japaneseAsmr: JapaneseAsmrClient = JapaneseAsmrClient(),
) : DataSource {
    private var current: DataSource = fallback

    override fun addTransferListener(transferListener: TransferListener) {
        fallback.addTransferListener(transferListener)
        asmrHttp.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        when (dataSpec.uri.scheme) {
            "youtube" -> return openYouTube(dataSpec)
            "japaneseasmr" -> return openJapaneseAsmr(dataSpec)
            "jamedia" -> {
                val address = restoreJaMediaUrl(dataSpec.uri.encodedQuery) ?: throw JapaneseAsmrException(JaFailure.MEDIA)
                try {
                    return openJaMedia(dataSpec, address, rewritePlaylist = true)
                } catch (failure: HttpDataSource.InvalidResponseCodeException) {
                    japaneseAsmr.expirePlaylists()
                    throw JapaneseAsmrException(if (failure.responseCode >= 500) JaFailure.SERVER else JaFailure.MEDIA)
                } catch (failure: IOException) {
                    if (failure is JapaneseAsmrException) throw failure
                    throw JapaneseAsmrException(JaFailure.CONNECTION)
                }
            }
            "asmr" -> return openAsmr(dataSpec)
            "dlsite" -> return openDlsite(dataSpec)
            "remote" -> return openRemote(dataSpec)
            "bilibili" -> Unit
            else -> {
                current = fallback
                return try {
                    fallback.open(dataSpec)
                } catch (failure: Exception) {
                    if (dataSpec.uri.scheme in setOf("file", "content") &&
                        (failure is IOException || failure is SecurityException)
                    ) throw JaLocalCopyLost(failure)
                    throw failure
                }
            }
        }
        val manifest = try {
            manifestBytes(dataSpec.uri)
        } catch (error: BilibiliException) {
            throw UserFacingIOException(error.userText, error)
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

    private fun openJapaneseAsmr(spec: DataSpec): Long {
        if (spec.uri.host == "file") {
            val rj = spec.uri.getQueryParameter("rj") ?: throw JapaneseAsmrException(JaFailure.WORK_FILE_MISSING)
            val relative = spec.uri.getQueryParameter("path") ?: throw JapaneseAsmrException(JaFailure.WORK_FILE_MISSING)
            val path = runBlocking { db.jaDownloads().entry(rj, relative)?.localPath }
            val managed = File(filesDir, "japaneseasmr-downloads").canonicalFile
            val file = path?.let { File(it).canonicalFile }
                ?.takeIf { it.startsWith(managed) && it.isFile && it.length() > 0 }
                ?: throw JapaneseAsmrException(JaFailure.WORK_FILE_MISSING)
            current = fallback
            return fallback.open(spec.withUri(android.net.Uri.fromFile(file)))
        }
        val post = spec.uri.getQueryParameter("post") ?: throw JapaneseAsmrException(JaFailure.MEDIA)
        val key = spec.uri.getQueryParameter("media") ?: throw JapaneseAsmrException(JaFailure.MEDIA)
        val path = spec.uri.getQueryParameter("path")
        val kind = spec.uri.getQueryParameter("kind")
        var lastFailure = JaFailure.MEDIA
        for (attempt in 0..1) {
            val (article, match, expected) = runBlocking {
                val expectedRj = db.japaneseAsmr().post(post)?.rj ?: japaneseAsmr.postRef(post)?.rj
                val article = japaneseAsmr.article(post, expectedRj, refresh = attempt == 1)
                val stored = resolveStoredJaMatches(article, post, db.japaneseAsmr())
                Triple(article, resolveQueuedJaMatch(article, key, path, stored, db.queue().snapshot()), expectedRj)
            }
            val media = match.media
            if (media == null) {
                lastFailure = if (expected != null && article.complete && match == JaMatchResult.Gone) JaFailure.GONE
                    else JaFailure.REOPEN
                continue
            }
            val preferred = JaMediaKind.entries.firstOrNull { it.name == kind } ?: media.kind
            val candidates = media.candidates.filter { it.kind == preferred }
            if (candidates.isEmpty()) throw JaMediaKindChanged(media.kind)
            var missing = 0
            lastFailure = JaFailure.MEDIA
            for (candidate in candidates) {
                try {
                    return openJaMedia(spec, candidate.url, rewritePlaylist = candidate.kind == JaMediaKind.HLS)
                } catch (error: HttpDataSource.InvalidResponseCodeException) {
                    current.close()
                    when (error.responseCode) {
                        404, 410 -> missing++
                        in 500..599 -> lastFailure = JaFailure.SERVER
                        403 -> lastFailure = JaFailure.MEDIA_VERIFY
                    }
                } catch (error: IOException) {
                    current.close()
                    if (error is JapaneseAsmrException) throw error
                    lastFailure = JaFailure.CONNECTION
                }
            }
            if (missing == candidates.size) {
                val alternatives = media.candidates.filter { it.kind != preferred }
                var allMissing = true
                for (candidate in alternatives) {
                    val code = runBlocking {
                        try {
                            japaneseAsmr.mediaClient.newCall(JapaneseAsmrClient.mediaRequest(candidate.url)
                                .header("Range", "bytes=0-0").build()).withResponse { it.code }
                        } catch (failure: JapaneseAsmrException) {
                            throw failure
                        } catch (_: IOException) {
                            null
                        }
                    }
                    if (code != null && code in 200..299) throw JaMediaKindChanged(candidate.kind)
                    if (code != 404 && code != 410) allMissing = false
                }
                if (allMissing) lastFailure = JaFailure.MEDIA_GONE
            }
        }
        throw JapaneseAsmrException(lastFailure)
    }

    private fun openJaMedia(spec: DataSpec, url: String, rewritePlaylist: Boolean): Long {
        if (rewritePlaylist) japaneseAsmr.rewrittenPlaylist(url)?.let { bytes ->
            val source = androidx.media3.datasource.ByteArrayDataSource(bytes)
            current = source
            return source.open(spec)
        }
        val source = BrowserHttpDataSource(
            japaneseAsmr.mediaClient, JapaneseAsmrClient.REFERER, JapaneseAsmrClient.USER_AGENT,
            preserveUrl = true,
            playlist = if (rewritePlaylist) { bytes -> japaneseAsmr.rememberPlaylist(url, bytes) } else null,
        )
        current = source
        return source.open(spec.buildUpon().setUri(url.toUri()).build())
    }

    private fun openYouTube(dataSpec: DataSpec): Long {
        val id = dataSpec.uri.getQueryParameter("videoId") ?: throw UserFacingIOException(TextRef.Res(R.string.player_error_youtube_audio))
        readyYouTubeAudio(filesDir, id)?.let {
            current = fallback
            return fallback.open(dataSpec.buildUpon().setUri(Uri.fromFile(it)).build())
        }
        for (attempt in 0..1) {
            val audio = runBlocking(Dispatchers.IO) { youtube.resolveAudio(id) }
            val source = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(20_000).setReadTimeoutMs(30_000)
                .setDefaultRequestProperties(audio.headers).createDataSource()
            current = source
            try {
                // DefaultHttpDataSource 正确处理 Range、206 和服务器忽略 Range 时的跳过。
                return source.open(dataSpec.buildUpon().setUri(audio.url.toUri()).build())
            } catch (error: HttpDataSource.InvalidResponseCodeException) {
                source.close()
                if (error.responseCode !in setOf(401, 403, 404) || attempt == 1) {
                    throw UserFacingIOException(TextRef.Res(R.string.player_error_youtube_cookie))
                }
                youtube.resetSession()
            }
        }
        throw UserFacingIOException(TextRef.Res(R.string.player_error_youtube_cookie))
    }

    private fun manifestBytes(uri: Uri): ByteArray {
        val bvid = uri.getQueryParameter("bvid").orEmpty()
        val cid = uri.getQueryParameter("cid")?.toLongOrNull()
            ?: throw UserFacingIOException(TextRef.Res(R.string.player_error_missing_audio_address))
        if (bvid.isBlank()) throw UserFacingIOException(TextRef.Res(R.string.player_error_missing_audio_address))
        return dashManifest(api.resolveAudioBlocking(bvid, cid)).toByteArray()
    }

    private fun openAsmr(dataSpec: DataSpec): Long {
        val trackId = dataSpec.uri.getQueryParameter("trackId")?.toLongOrNull()
            ?: throw UserFacingIOException(TextRef.Res(R.string.player_error_missing_audio_address))
        val track = runBlocking(Dispatchers.IO) { db.tracks().getById(trackId) }
            ?: throw UserFacingIOException(TextRef.Res(R.string.player_error_missing_audio_address))
        openStored(dataSpec, track.localUri)?.let { return it }
        var url = track.remoteUrl
        if (url.isNullOrBlank()) url = refreshAsmrUrl(track)
        if (url.isNullOrBlank()) throw UserFacingIOException(TextRef.Res(R.string.player_error_missing_audio_address))
        return try {
            openAsmrHttp(dataSpec, url)
        } catch (error: HttpDataSource.InvalidResponseCodeException) {
            if (error.responseCode != 403 && error.responseCode != 404) throw error
            val fresh = refreshAsmrUrl(track) ?: throw UserFacingIOException(TextRef.Res(R.string.player_error_refresh_audio), error)
            openAsmrHttp(dataSpec, fresh)
        }
    }

    private fun openStored(dataSpec: DataSpec, local: String?): Long? {
        if (local.isNullOrBlank()) return null
        val uri = when {
            local.startsWith("/") && File(local).isFile -> Uri.fromFile(File(local))
            local.startsWith("content:") || local.startsWith("file:") -> local.toUri()
            else -> return null
        }
        return try {
            val opened = fallback.open(dataSpec.buildUpon().setUri(uri).build())
            current = fallback
            opened
        } catch (error: Exception) {
            runCatching { fallback.close() }
            if (local.startsWith("/")) throw error
            null
        }
    }

    private fun openDlsite(dataSpec: DataSpec): Long {
        val trackId = dataSpec.uri.getQueryParameter("trackId")?.toLongOrNull()
            ?: throw UserFacingIOException(TextRef.Res(R.string.player_error_missing_audio_address))
        val track = runBlocking(Dispatchers.IO) { db.tracks().getById(trackId) }
            ?: throw UserFacingIOException(TextRef.Res(R.string.player_error_missing_audio_address))
        // 识别挂上的 content:/file: 和下载到 App 目录的文件都在这里读本地。
        openStored(dataSpec, track.localUri)?.let { return it }
        val url = track.remoteUrl
        if (url.isNullOrBlank()) throw UserFacingIOException(TextRef.Res(R.string.player_error_dlsite_original_missing))
        val workno = runBlocking(Dispatchers.IO) { worknoOf(track) }
        return openAuthed(dataSpec, url, DlsiteClient.PLAY_REFERER, workno, rawFileKey(track.fileHash))
    }

    private fun openRemote(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        val url = uri.getQueryParameter("url").orEmpty()
        if (url.isBlank()) throw UserFacingIOException(TextRef.Res(R.string.player_error_missing_audio_address))
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
            val stored = runBlocking(Dispatchers.IO) {
                val canonical = DlsiteClient.canonicalWorkno(workno)
                db.dlsiteOriginals().entryForPlay(canonical, rawFileKey(fileKey) ?: fileKey)?.localPath
                    ?.takeIf { File(it).isFile }
                    ?: db.downloads().finishedForWork(source, canonical).firstOrNull {
                        rawFileKey(it.fileKey) == (rawFileKey(fileKey) ?: fileKey) && it.localPath?.let { path -> File(path).isFile } == true
                    }?.localPath
            }
            openStored(dataSpec, stored)?.let { return it }
            return openAuthed(dataSpec, url, referer.ifBlank { DlsiteClient.PLAY_REFERER }, workno, fileKey)
        }
        val userAgent = if (source == TrackSource.DLSITE.name) DlsiteClient.USER_AGENT else AsmrClient.USER_AGENT
        return try {
            openHttp(dataSpec, url, referer, cookie = null, userAgent = userAgent)
        } catch (error: HttpDataSource.InvalidResponseCodeException) {
            if (source == TrackSource.DLSITE.name) throw error
            if (error.responseCode != 403 && error.responseCode != 404) throw error
            val workId = workno.toLongOrNull() ?: throw UserFacingIOException(TextRef.Res(R.string.player_error_refresh_audio), error)
            val fresh = runBlocking(Dispatchers.IO) {
                try {
                    AsmrClient.findStream(asmr.trackTree(workId), fileKey, "")
                } catch (_: Exception) {
                    null
                }
            } ?: throw UserFacingIOException(TextRef.Res(R.string.player_error_refresh_audio), error)
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
            if (error.responseCode == 401) throw UserFacingIOException(TextRef.Res(R.string.dlsite_error_login_expired), error)
            if ((error.responseCode != 403 && error.responseCode != 404) || workno.isNullOrBlank() || fileKey.isNullOrBlank()) {
                throw error
            }
            val fresh = try {
                dlsite.signedPlayFileBlocking(workno, fileKey)
            } catch (failure: Exception) {
                throw UserFacingIOException(failure.userTextOr(R.string.player_error_refresh_audio), failure)
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
        private val youtube: YouTubeClient,
        private val japaneseAsmr: JapaneseAsmrClient = JapaneseAsmrClient(),
        httpRoot: OkHttpClient = com.nanahoshi.audioplayer.data.HttpRoots.shared,
    ) : DataSource.Factory {
        private val appContext = context.applicationContext
        private val client = httpRoot.newBuilder()
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
            return BiliPlaybackDataSource(fallback, asmrHttp, client, api, asmr, dlsite, db, youtube, appContext.filesDir, japaneseAsmr)
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
    private val preserveUrl: Boolean = false,
    private val playlist: ((ByteArray) -> Unit)? = null,
) : BaseDataSource(true) {
    private var response: okhttp3.Response? = null
    private var stream: InputStream? = null
    private var openedUri: Uri? = null
    private var bytesRemaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val url = dataSpec.uri.toString().let {
            if (preserveUrl) it else it.replace("%2C", ",").replace("%2c", ",")
        }
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
        if (call.code == 416) {
            val totalSize = parseUnsatisfiedContentRangeTotal(call.header("Content-Range"))
            if (totalSize != null && position == totalSize) {
                call.close()
                response = null
                stream = ByteArray(0).inputStream()
                bytesRemaining = 0L
                openedUri = Uri.parse(url)
                transferStarted(dataSpec)
                return 0L
            }
            if (totalSize != null && position > totalSize) {
                call.close()
                response = null
                throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            }
        }
        if (call.code != 200 && call.code != 206) {
            val body = call.peekBody(4096).bytes()
            if (preserveUrl && call.isChallenge(body.toString(Charsets.UTF_8))) {
                call.close()
                response = null
                throw JapaneseAsmrException(JaFailure.MEDIA_VERIFY)
            }
            val headers = call.headers.toMultimap()
            val code = call.code
            val message = call.message
            call.close()
            response = null
            throw HttpDataSource.InvalidResponseCodeException(code, message, null, headers, dataSpec, body)
        }
        val responseBody = call.body ?: throw UserFacingIOException(TextRef.Res(R.string.player_error_empty_audio))
        if (playlist != null && position == 0L && call.peekBody(7).string() == "#EXTM3U") {
            val bytes = rewriteJaPlaylist(responseBody.string(), call.request.url.toString())
            playlist(bytes)
            stream = bytes.inputStream()
            bytesRemaining = bytes.size.toLong()
            openedUri = dataSpec.uri
            transferStarted(dataSpec)
            return bytesRemaining
        }
        val input = responseBody.byteStream()
        // 服务器忽略 Range 直接回 200 时，必须自己跳过前 position 字节，否则读到的是错位数据。
        if (position > 0 && call.code == 200) {
            var remaining = position
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped > 0) {
                    remaining -= skipped
                } else if (input.read() < 0) {
                    break
                } else {
                    remaining--
                }
            }
            if (remaining > 0) throw UserFacingIOException(TextRef.Res(R.string.player_error_seek))
        }
        stream = input
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

internal fun parseUnsatisfiedContentRangeTotal(header: String?): Long? =
    com.nanahoshi.audioplayer.data.HttpByteRange.unsatisfiedTotal(header)
