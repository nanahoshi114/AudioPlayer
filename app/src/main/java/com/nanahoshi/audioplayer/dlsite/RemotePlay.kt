package com.nanahoshi.audioplayer.dlsite

import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.FileKind

data class RemotePlayable(
    val title: String,
    val artist: String?,
    val coverUri: String?,
    val remoteUrl: String,
    val source: TrackSource,
    val referer: String,
    val durationMs: Long = 0,
    val workno: String? = null,
    val fileKey: String? = null,
)

data class RemotePreview(
    val name: String,
    val url: String,
    val kind: FileKind,
    val referer: String,
    val authed: Boolean,
)

data class PlayEntry(
    val title: String,
    val children: List<PlayEntry>,
    val audio: RemotePlayable?,
    val preview: RemotePreview?,
)
