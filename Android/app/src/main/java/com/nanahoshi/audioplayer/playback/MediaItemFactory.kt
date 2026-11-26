package com.nanahoshi.audioplayer.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.nanahoshi.audioplayer.graph
import com.nanahoshi.audioplayer.japaneseasmr.JaMediaKind
import com.nanahoshi.audioplayer.japaneseasmr.jaIdentity
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.nanahoshi.audioplayer.data.LocalAudio
import com.nanahoshi.audioplayer.data.jaWorkFileIdentity
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import com.nanahoshi.audioplayer.data.toHttps
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.dlsite.RemotePlayable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 阶段一：唯一的 binder 探测（content: 可读性），须在 queueMutex 外执行；/ 与 file: 为磁盘 stat。 */
suspend fun TrackEntity.biliLocalCache(context: Context): Uri? = withContext(Dispatchers.IO) {
    if (source != TrackSource.BILIBILI && source != TrackSource.JAPANESE_ASMR) return@withContext null
    val local = localUri ?: return@withContext null
    when {
        local.startsWith("/") -> File(local).takeIf { it.isFile && it.length() > 0 }?.let(Uri::fromFile)
        // 失效的 content: 关联不能直接交给播放器，否则失去在线回退；校验与 hasLocalPart 一致。
        local.startsWith("content:") -> local.toUri().takeIf { uri -> LocalAudio.contentUriReadable(context, uri) }
        local.startsWith("file:") -> local.toUri().takeIf { uri ->
            File(uri.path.orEmpty()).let { it.isFile && it.length() > 0 }
        }
        else -> null
    }
}

internal data class PlaybackTarget(val local: Uri?, val jaUri: Uri? = null, val jaKind: String? = null)

internal suspend fun TrackEntity.playbackTarget(context: Context): PlaybackTarget {
    val local = biliLocalCache(context)
    if (source != TrackSource.JAPANESE_ASMR || local != null) return PlaybackTarget(local)
    val graph = (context.applicationContext as android.app.Application).graph()
    val media = graph.db.japaneseAsmr().mediaForTrack(id)
    if (media != null) {
        return PlaybackTarget(null, jaPlaybackUri(media.postId, media.mediaKey, media.pathHash, media.kind), media.kind)
    }
    val identity = remoteUrl?.takeIf { it.startsWith("japaneseasmr://file?") }
        ?: graph.db.jaDownloads().entryForTrack(id)?.let { jaWorkFileIdentity(it.rj, it.relativePath) }
    return PlaybackTarget(null, identity?.toUri())
}

internal suspend fun QueuedTrack.playbackTarget(context: Context): PlaybackTarget {
    if (trackId != null && (remoteUrl.isNullOrBlank() || remoteUrl.startsWith("japaneseasmr://file?"))) {
        return toTrack().copy(remoteUrl = remoteUrl).playbackTarget(context)
    }
    if (source != TrackSource.JAPANESE_ASMR) return PlaybackTarget(null)
    val graph = (context.applicationContext as android.app.Application).graph()
    if (remoteUrl?.startsWith("japaneseasmr://file?") == true) {
        val rj = workno ?: return PlaybackTarget(null)
        val relativePath = fileKey ?: return PlaybackTarget(null)
        val path = graph.db.jaDownloads().entry(rj, relativePath)?.localPath
        return PlaybackTarget(path?.let(::File)?.takeIf { it.isFile }?.let(Uri::fromFile))
    }
    val post = workno ?: return PlaybackTarget(null)
    val key = fileKey ?: return PlaybackTarget(null)
    val path = graph.downloads.savedRemotePath(source, post, key)
    return PlaybackTarget(path?.let { Uri.fromFile(File(it)) })
}

internal fun TrackEntity.toPlayableItem(queueItemId: Long, target: PlaybackTarget): MediaItem {
    val item = toPlayableItem(queueItemId, target.local)
    return if (source == TrackSource.JAPANESE_ASMR && target.local == null) {
        item.buildUpon().setUri(target.jaUri ?: Uri.EMPTY)
            .setMimeType(MimeTypes.APPLICATION_M3U8.takeIf { target.jaKind == JaMediaKind.HLS.name }).build()
    } else if (source == TrackSource.JAPANESE_ASMR) item.withJaLocalCopy() else item
}

/** 组装播放项时不读取数据库或发起 IPC。 */
fun TrackEntity.toPlayableItem(queueItemId: Long, cached: Uri?): MediaItem {
    val uri = when (source) {
        // 始终保留视频身份，数据源打开时决定读本地还是在线；删缓存后队列也能恢复在线。
        TrackSource.YOUTUBE -> Uri.Builder().scheme("youtube").authority("audio").appendQueryParameter("videoId", youtubeVideoId).build()
        TrackSource.LOCAL -> localUri?.toUri() ?: Uri.EMPTY
        TrackSource.JAPANESE_ASMR -> cached ?: Uri.EMPTY
        TrackSource.BILIBILI -> cached ?: "bilibili://audio?bvid=$bvid&cid=$cid".toUri()
        TrackSource.ASMR -> "asmr://audio?trackId=$id".toUri()
        TrackSource.DLSITE -> "dlsite://audio?trackId=$id".toUri()
    }
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist.orEmpty())
        .setArtworkUri(coverUri?.toHttps()?.let(Uri::parse))
        .setExtras(Bundle().apply { putLong(EXTRA_TRACK_ID, id) })
        .build()
    return MediaItem.Builder()
        .setMediaId(queueItemId.toString())
        .setUri(uri)
        .apply {
            if (source == TrackSource.BILIBILI && cached == null) setMimeType(MimeTypes.APPLICATION_MPD)
        }
        .setMediaMetadata(metadata)
        .build()
        .let { if (source == TrackSource.BILIBILI && cached != null) it.withBiliLocalCopy() else it }
}

suspend fun TrackEntity.toPlayableItem(queueItemId: Long, context: Context): MediaItem =
    toPlayableItem(queueItemId, playbackTarget(context))

const val EXTRA_TRACK_ID = "trackId"
internal const val EXTRA_JA_LOCAL_COPY = "jaLocalCopy"
internal const val EXTRA_BILI_LOCAL_COPY = "biliLocalCopy"

private fun MediaItem.withJaLocalCopy(): MediaItem = withExtraFlag(EXTRA_JA_LOCAL_COPY)

private fun MediaItem.withBiliLocalCopy(): MediaItem = withExtraFlag(EXTRA_BILI_LOCAL_COPY)

private fun MediaItem.withExtraFlag(key: String): MediaItem {
    val extras = Bundle(mediaMetadata.extras ?: Bundle()).apply { putBoolean(key, true) }
    return buildUpon().setMediaMetadata(mediaMetadata.buildUpon().setExtras(extras).build()).build()
}

internal fun QueuedTrack.toMediaItem(target: PlaybackTarget): MediaItem {
    if (trackId != null && (remoteUrl.isNullOrBlank() || remoteUrl.startsWith("japaneseasmr://file?"))) {
        return toTrack().toPlayableItem(queueItemId, target)
    }
    if (source == TrackSource.JAPANESE_ASMR && target.local != null) {
        return mediaItem(queueItemId, target.local, title, artist, coverUri).withJaLocalCopy()
    }
    val url = remoteUrl.orEmpty()
    if (url.startsWith("/") && File(url).isFile) {
        return localFileItem(queueItemId, title, artist, coverUri, url)
    }
    return RemotePlayable(
        title = title,
        artist = artist,
        coverUri = coverUri,
        remoteUrl = url,
        source = source,
        referer = referer.orEmpty(),
        durationMs = durationMs,
        workno = workno,
        fileKey = fileKey,
        mediaKind = mediaKind,
        mediaPathHash = mediaPathHash,
    ).toMediaItem(queueItemId)
}

fun QueuedTrack.toMediaItem(cached: Uri?): MediaItem = toMediaItem(PlaybackTarget(cached))

suspend fun QueuedTrack.toMediaItem(context: Context): MediaItem = toMediaItem(playbackTarget(context))

fun RemotePlayable.toMediaItem(queueItemId: Long): MediaItem {
    if (remoteUrl.startsWith("/") && File(remoteUrl).isFile) {
        return localFileItem(queueItemId, title, artist, coverUri, remoteUrl)
    }
    if (source == TrackSource.JAPANESE_ASMR) {
        val uri = if (remoteUrl.startsWith("japaneseasmr://file?")) remoteUrl.toUri()
            else jaPlaybackUri(workno.orEmpty(), fileKey.orEmpty(), mediaPathHash, mediaKind)
        return mediaItem(queueItemId, uri, title, artist, coverUri).buildUpon()
            .setMimeType(MimeTypes.APPLICATION_M3U8.takeIf { mediaKind == JaMediaKind.HLS.name }).build()
    }
    if (source == TrackSource.BILIBILI) {
        val uri = Uri.Builder().scheme("bilibili").authority("audio")
            .appendQueryParameter("bvid", workno).appendQueryParameter("cid", fileKey).build()
        return mediaItem(queueItemId, uri, title, artist, coverUri).buildUpon()
            .setMimeType(MimeTypes.APPLICATION_MPD).build()
    }
    if (source == TrackSource.YOUTUBE) {
        val uri = Uri.Builder().scheme("youtube").authority("audio").appendQueryParameter("videoId", workno).build()
        return mediaItem(queueItemId, uri, title, artist, coverUri)
    }
    val uri = Uri.Builder()
        .scheme("remote")
        .authority("audio")
        .appendQueryParameter("url", remoteUrl)
        .appendQueryParameter("referer", referer)
        .appendQueryParameter("source", source.name)
        .appendQueryParameter("workno", workno.orEmpty())
        .appendQueryParameter("file", fileKey.orEmpty())
        .build()
    return mediaItem(queueItemId, uri, title, artist, coverUri)
}

private fun localFileItem(
    queueItemId: Long,
    title: String,
    artist: String?,
    coverUri: String?,
    path: String,
): MediaItem = mediaItem(queueItemId, Uri.fromFile(File(path)), title, artist, coverUri)

private fun mediaItem(
    queueItemId: Long,
    uri: Uri,
    title: String,
    artist: String?,
    coverUri: String?,
): MediaItem {
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist.orEmpty())
        .setArtworkUri(coverUri?.toHttps()?.let(Uri::parse))
        .build()
    return MediaItem.Builder()
        .setMediaId(queueItemId.toString())
        .setUri(uri)
        .setMediaMetadata(metadata)
        .build()
}

private fun jaPlaybackUri(postId: String, mediaKey: String, pathHash: String?, kind: String?): Uri =
    jaIdentity(postId, mediaKey).toUri().buildUpon().apply {
        if (pathHash != null) appendQueryParameter("path", pathHash)
        appendQueryParameter("kind", kind ?: JaMediaKind.DIRECT.name)
    }.build()
