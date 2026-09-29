package com.nanahoshi.audioplayer.playback

import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.toHttps
import com.nanahoshi.audioplayer.data.db.TrackEntity

fun TrackEntity.toPlayableItem(queueItemId: Long): MediaItem {
    val uri = if (source == TrackSource.LOCAL) {
        localUri?.toUri() ?: Uri.EMPTY
    } else {
        "bilibili://audio?bvid=$bvid&cid=$cid".toUri()
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
