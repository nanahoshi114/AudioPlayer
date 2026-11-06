package com.nanahoshi.audioplayer.playback

import com.nanahoshi.audioplayer.data.db.QueueItemEntity
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.dlsite.RemotePlayable

sealed interface QueuePlayable {
    data class Saved(val track: TrackEntity) : QueuePlayable
    data class Remote(val item: RemotePlayable) : QueuePlayable
}

internal fun QueuePlayable.queueRow(position: Int): QueueItemEntity = when (this) {
    is QueuePlayable.Saved -> {
        require(track.id > 0) { "曲目尚未入库" }
        QueueItemEntity(trackId = track.id, position = position)
    }
    is QueuePlayable.Remote -> QueueItemEntity(position = position, title = item.title, artist = item.artist,
        coverUri = item.coverUri, remoteUrl = item.remoteUrl, source = item.source.name,
        referer = item.referer, workno = item.workno, fileKey = item.fileKey)
}

internal fun QueuePlayable.mediaItem(id: Long) = when (this) {
    is QueuePlayable.Saved -> track.toPlayableItem(id)
    is QueuePlayable.Remote -> item.toMediaItem(id)
}
