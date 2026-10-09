package com.nanahoshi.audioplayer.playback

import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import com.nanahoshi.audioplayer.data.toHttps
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.dlsite.RemotePlayable
import java.io.File

fun TrackEntity.toPlayableItem(queueItemId: Long): MediaItem {
    val uri = when (source) {
        TrackSource.LOCAL -> localUri?.toUri() ?: Uri.EMPTY
        TrackSource.BILIBILI -> "bilibili://audio?bvid=$bvid&cid=$cid".toUri()
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
            if (source == TrackSource.BILIBILI) setMimeType(MimeTypes.APPLICATION_MPD)
        }
        .setMediaMetadata(metadata)
        .build()
}

const val EXTRA_TRACK_ID = "trackId"

fun QueuedTrack.toMediaItem(): MediaItem {
    if (trackId != null && remoteUrl.isNullOrBlank()) return toTrack().toPlayableItem(queueItemId)
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
    ).toMediaItem(queueItemId)
}

fun RemotePlayable.toMediaItem(queueItemId: Long): MediaItem {
    if (remoteUrl.startsWith("/") && File(remoteUrl).isFile) {
        return localFileItem(queueItemId, title, artist, coverUri, remoteUrl)
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
