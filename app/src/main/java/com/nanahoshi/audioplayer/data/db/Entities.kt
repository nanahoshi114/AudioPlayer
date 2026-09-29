package com.nanahoshi.audioplayer.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.nanahoshi.audioplayer.data.TrackSource

@Entity(
    tableName = "folders",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("parentId")],
)
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val parentId: Long? = null,
    val name: String,
    val position: Int,
)

@Entity(
    tableName = "tracks",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["localUri"], unique = true),
        Index(value = ["bvid", "cid"], unique = true),
        Index("folderId"),
    ],
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: TrackSource,
    val title: String,
    val artist: String?,
    val durationMs: Long,
    val coverUri: String?,
    val localUri: String?,
    val bvid: String?,
    val cid: Long?,
    val page: Int?,
    val addedAt: Long,
    val folderId: Long? = null,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "playlist_items",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId"), Index("trackId")],
)
data class PlaylistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val trackId: Long,
    val position: Int,
)

@Entity(
    tableName = "queue_items",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("trackId")],
)
data class QueueItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val position: Int,
)

@Entity(tableName = "playback_state")
data class PlaybackStateEntity(
    @PrimaryKey val id: Int = 0,
    val queueItemId: Long = -1,
    val positionMs: Long = 0,
)

data class QueuedTrack(
    val queueItemId: Long,
    val position: Int,
    val trackId: Long,
    val source: TrackSource,
    val title: String,
    val artist: String?,
    val durationMs: Long,
    val coverUri: String?,
    val localUri: String?,
    val bvid: String?,
    val cid: Long?,
    val page: Int?,
) {
    fun toTrack() = TrackEntity(
        id = trackId,
        source = source,
        title = title,
        artist = artist,
        durationMs = durationMs,
        coverUri = coverUri,
        localUri = localUri,
        bvid = bvid,
        cid = cid,
        page = page,
        addedAt = 0,
    )
}

data class PlaylistTrack(
    val itemId: Long,
    val position: Int,
    val trackId: Long,
    val source: TrackSource,
    val title: String,
    val artist: String?,
    val durationMs: Long,
    val coverUri: String?,
    val localUri: String?,
    val bvid: String?,
    val cid: Long?,
    val page: Int?,
) {
    fun toTrack() = TrackEntity(
        id = trackId,
        source = source,
        title = title,
        artist = artist,
        durationMs = durationMs,
        coverUri = coverUri,
        localUri = localUri,
        bvid = bvid,
        cid = cid,
        page = page,
        addedAt = 0,
    )
}
