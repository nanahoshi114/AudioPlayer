package com.nanahoshi.audioplayer.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.nanahoshi.audioplayer.data.TrackSource

enum class FileKind {
    IMAGE,
    TEXT,
    OTHER,
}

enum class DownloadStatus {
    QUEUED,
    RUNNING,
    DONE,
    FAILED,
}

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
    indices = [
        Index("parentId"),
        Index(value = ["asmrSourceId"], unique = true),
    ],
)
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val parentId: Long? = null,
    val name: String,
    val position: Int,
    @ColumnInfo(defaultValue = "0") val addedAt: Long = 0,
    val asmrWorkId: Long? = null,
    val asmrSourceId: String? = null,
    val coverUri: String? = null,
    val workMeta: String? = null,
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
        Index(value = ["asmrWorkId", "fileHash"], unique = true),
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
    val remoteUrl: String? = null,
    val asmrWorkId: Long? = null,
    val fileHash: String? = null,
)

@Entity(
    tableName = "library_files",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("folderId"),
        Index(value = ["asmrWorkId", "fileHash"], unique = true),
    ],
)
data class LibraryFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val folderId: Long?,
    val name: String,
    val kind: FileKind,
    val remoteUrl: String,
    val addedAt: Long,
    val asmrWorkId: Long,
    val fileHash: String,
)

@Entity(
    tableName = "downloads",
    indices = [Index("trackId")],
)
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val title: String,
    val bytesDone: Long,
    val bytesTotal: Long,
    val status: DownloadStatus,
    val error: String?,
    val remoteUrl: String? = null,
    val referer: String? = null,
    val source: String? = null,
    val workKey: String? = null,
    val localPath: String? = null,
    val fileKey: String? = null,
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
    val trackId: Long? = null,
    val position: Int,
    val title: String? = null,
    val artist: String? = null,
    val coverUri: String? = null,
    val remoteUrl: String? = null,
    val source: String? = null,
    val referer: String? = null,
    val workno: String? = null,
    val fileKey: String? = null,
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
    val trackId: Long?,
    val source: TrackSource,
    val title: String,
    val artist: String?,
    val durationMs: Long,
    val coverUri: String?,
    val localUri: String?,
    val bvid: String?,
    val cid: Long?,
    val page: Int?,
    val remoteUrl: String?,
    val referer: String?,
    val workno: String?,
    val fileKey: String?,
) {
    fun toTrack() = TrackEntity(
        id = trackId ?: 0L,
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
