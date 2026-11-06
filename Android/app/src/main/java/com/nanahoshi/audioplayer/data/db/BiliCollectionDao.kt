package com.nanahoshi.audioplayer.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "bili_collections", foreignKeys = [ForeignKey(entity = PlaylistEntity::class,
    parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["kind", "remoteId"], unique = true)])
data class BiliCollectionEntity(
    @PrimaryKey val playlistId: Long,
    val kind: String,
    val remoteId: Long,
    val metadata: String,
    val revision: Long = 0,
    val refreshedAt: Long? = null,
)

@Entity(tableName = "bili_collection_videos", primaryKeys = ["playlistId", "bvid"],
    foreignKeys = [ForeignKey(entity = BiliCollectionEntity::class, parentColumns = ["playlistId"],
        childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE)])
data class BiliCollectionVideoEntity(
    val playlistId: Long,
    val bvid: String,
    val position: Int,
    val video: String,
    val parts: String? = null,
)

@Dao
interface BiliCollectionDao {
    @Query("SELECT * FROM bili_collections") fun observeAll(): Flow<List<BiliCollectionEntity>>
    @Query("SELECT * FROM bili_collection_videos WHERE position = 0")
    fun observeFirstVideos(): Flow<List<BiliCollectionVideoEntity>>
    @Query("SELECT * FROM bili_collections WHERE playlistId = :id") fun observe(id: Long): Flow<BiliCollectionEntity?>
    @Query("SELECT * FROM bili_collections WHERE playlistId = :id") suspend fun get(id: Long): BiliCollectionEntity?
    @Query("SELECT * FROM bili_collections WHERE kind = :kind AND remoteId = :remoteId")
    suspend fun find(kind: String, remoteId: Long): BiliCollectionEntity?
    @Insert suspend fun insert(row: BiliCollectionEntity)
    @Query("UPDATE bili_collections SET revision = revision + 1 WHERE playlistId = :id") suspend fun invalidate(id: Long)
    @Query("UPDATE bili_collections SET metadata = :metadata, refreshedAt = :time WHERE playlistId = :id")
    suspend fun published(id: Long, metadata: String, time: Long)
    @Query("UPDATE playlists SET name = :title WHERE id = :id") suspend fun updateTitle(id: Long, title: String)
    @Query("SELECT * FROM bili_collection_videos WHERE playlistId = :id ORDER BY position")
    suspend fun videos(id: Long): List<BiliCollectionVideoEntity>
    @Query("SELECT * FROM bili_collection_videos WHERE playlistId = :id AND bvid = :bvid")
    suspend fun video(id: Long, bvid: String): BiliCollectionVideoEntity?
    @Query("DELETE FROM bili_collection_videos WHERE playlistId = :id") suspend fun clearVideos(id: Long)
    @Insert suspend fun insertVideos(rows: List<BiliCollectionVideoEntity>)
    @Query("UPDATE bili_collection_videos SET parts = :parts WHERE playlistId = :id AND bvid = :bvid")
    suspend fun cacheParts(id: Long, bvid: String, parts: String)
}
