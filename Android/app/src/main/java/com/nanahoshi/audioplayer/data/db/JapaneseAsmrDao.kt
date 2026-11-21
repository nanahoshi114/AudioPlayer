package com.nanahoshi.audioplayer.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.nanahoshi.audioplayer.japaneseasmr.JaPostRef

@Entity(tableName = "japaneseasmr_posts", indices = [Index("rootFolderId")])
data class JapaneseAsmrPostEntity(
    @PrimaryKey val postId: String,
    val rootFolderId: Long?,
    val articleFolderId: Long?,
    val rj: String,
    val title: String,
    val coverUri: String?,
    val publishedAt: String?,
    val url: String,
    val refreshedAt: Long?,
) {
    fun ref() = JaPostRef(postId, rj, title, publishedAt, url, coverUri)
}

@Entity(tableName = "japaneseasmr_media", primaryKeys = ["postId", "mediaKey"], indices = [Index("trackId"), Index("postId", "pathHash")])
data class JapaneseAsmrMediaEntity(
    val postId: String,
    val mediaKey: String,
    val trackId: Long?,
    val kind: String,
    val title: String,
    val durationMs: Long,
    val chapters: String?,
    val pathHash: String? = null,
)

@Dao
interface JapaneseAsmrDao {
    @Query("SELECT * FROM japaneseasmr_posts WHERE postId = :postId")
    suspend fun post(postId: String): JapaneseAsmrPostEntity?

    @Query("SELECT * FROM japaneseasmr_posts WHERE rootFolderId = :rootId ORDER BY rowid")
    suspend fun postsForRoot(rootId: Long): List<JapaneseAsmrPostEntity>

    @Query("SELECT coverUri FROM japaneseasmr_posts WHERE coverUri IS NOT NULL UNION SELECT coverUri FROM tracks WHERE source = 'JAPANESE_ASMR' AND coverUri IS NOT NULL")
    suspend fun covers(): List<String>

    @Query("SELECT * FROM japaneseasmr_media WHERE postId = :postId AND mediaKey = :key")
    suspend fun media(postId: String, key: String): JapaneseAsmrMediaEntity?

    @Query("SELECT * FROM japaneseasmr_media WHERE postId = :postId")
    suspend fun mediaForPost(postId: String): List<JapaneseAsmrMediaEntity>

    @Query("SELECT * FROM japaneseasmr_media WHERE trackId = :trackId LIMIT 1")
    suspend fun mediaForTrack(trackId: Long): JapaneseAsmrMediaEntity?

    @Query("SELECT tracks.* FROM japaneseasmr_media JOIN tracks ON tracks.id = japaneseasmr_media.trackId WHERE postId = :postId AND mediaKey = :key AND source = 'JAPANESE_ASMR'")
    suspend fun track(postId: String, key: String): TrackEntity?

    @Query("UPDATE japaneseasmr_media SET durationMs = :duration WHERE trackId = :trackId AND durationMs = 0")
    suspend fun fillDuration(trackId: Long, duration: Long)

    @Upsert
    suspend fun upsertPost(post: JapaneseAsmrPostEntity)

    @Query("DELETE FROM japaneseasmr_media WHERE postId = :postId AND mediaKey = :key")
    suspend fun deleteMedia(postId: String, key: String)

    @Upsert
    suspend fun upsertMedia(media: JapaneseAsmrMediaEntity)
}
