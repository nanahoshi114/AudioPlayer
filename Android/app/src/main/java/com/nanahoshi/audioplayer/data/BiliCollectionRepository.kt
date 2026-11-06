package com.nanahoshi.audioplayer.data

import android.content.Context
import androidx.room.withTransaction
import com.nanahoshi.audioplayer.bilibili.*
import com.nanahoshi.audioplayer.data.db.*
import com.nanahoshi.audioplayer.dlsite.RemotePlayable
import com.nanahoshi.audioplayer.playback.QueuePlayable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.builtins.ListSerializer
import kotlin.coroutines.coroutineContext

class BiliCollectionRepository(
    private val context: Context,
    private val db: AppDatabase,
    client: BilibiliClient,
    private val loadPage: suspend (BiliCollection, Int) -> BiliCollectionPage = client.collections::videos,
    private val loadDetails: suspend (String) -> BiliVideoDetails = client::videoDetails,
) {
    private val json get() = biliLibraryJson
    val sources = combine(db.collections().observeAll(), db.collections().observeFirstVideos()) { rows, firstVideos ->
        val firstById = firstVideos.associateBy { it.playlistId }
        rows.map { row ->
            val firstCover = firstById[row.playlistId]?.let { json.decodeFromString<BiliVideoHit>(it.video).coverUrl }
            val covered = decode(row).withFirstVideoCover(firstCover)
            row.copy(metadata = json.encodeToString(BiliCollection.serializer(), covered))
        }
    }
    fun decode(row: BiliCollectionEntity): BiliCollection =
        json.decodeFromString<BiliCollection>(row.metadata).withFirstVideoCover(null)

    suspend fun bookmark(collection: BiliCollection): Long = db.withTransaction {
        db.collections().find(collection.kind.name, collection.remoteId)?.let { return@withTransaction it.playlistId }
        val now = System.currentTimeMillis()
        val id = db.playlists().insert(PlaylistEntity(name = collection.title, createdAt = now, updatedAt = now))
        db.collections().insert(BiliCollectionEntity(id, collection.kind.name, collection.remoteId,
            json.encodeToString(BiliCollection.serializer(), collection.withFirstVideoCover(null))))
        id
    }

    suspend fun unbookmark(id: Long) = db.withTransaction {
        if (db.collections().get(id) != null) db.playlists().delete(id)
    }

    suspend fun cached(id: Long): BiliCollectionPage? = db.withTransaction {
        val row = db.collections().get(id)?.takeIf { it.refreshedAt != null } ?: return@withTransaction null
        val videos = db.collections().videos(id).map { json.decodeFromString<BiliVideoHit>(it.video) }
        BiliCollectionPage(decode(row).withFirstVideoCover(videos.firstOrNull()?.coverUrl), videos, false, true)
    }

    suspend fun refresh(collection: BiliCollection, id: Long?, valid: () -> Boolean,
                        progress: (List<BiliVideoHit>) -> Unit): BiliCollectionPage {
        val revision = if (id == null) null else db.withTransaction {
            check(db.collections().get(id) != null) { "合集已取消收藏" }
            db.collections().invalidate(id)
            db.collections().get(id)!!.revision
        }
        val snapshot = loadBiliCollection(collection, loadPage, progress)
        coroutineContext.ensureActive()
        if (id != null && snapshot.complete) db.withTransaction {
            val current = db.collections().get(id)
            check(valid() && current?.revision == revision) { "合集读取已过时，请重试" }
            val old = db.collections().videos(id).associateBy { it.bvid }
            db.collections().clearVideos(id)
            db.collections().insertVideos(snapshot.videos.mapIndexed { position, video ->
                BiliCollectionVideoEntity(id, video.bvid, position, json.encodeToString(BiliVideoHit.serializer(), video), old[video.bvid]?.parts)
            })
            db.collections().published(id, json.encodeToString(BiliCollection.serializer(), snapshot.collection), System.currentTimeMillis())
            // 更新时间用于用户编辑排序；后台刷新只同步名称。
            db.collections().updateTitle(id, snapshot.collection.title)
        }
        return snapshot
    }

    suspend fun details(video: BiliVideoHit, id: Long?, allowPartialLocal: Boolean = false): BiliVideoDetails {
        try {
            val result = loadDetails(video.bvid)
            if (id != null) db.collections().cacheParts(id, video.bvid,
                json.encodeToString(ListSerializer(BiliPart.serializer()), result.parts))
            return result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val cached = id?.let { db.collections().video(it, video.bvid)?.parts }
            val parts = cached?.let { runCatching { json.decodeFromString(ListSerializer(BiliPart.serializer()), it) }.getOrNull() }
            if (!parts.isNullOrEmpty() && parts.all { it.bvid == video.bvid && it.cid > 0 }) {
                val available = parts.filter { stored(it) != null }.sortedBy { it.page }
                if (available.size == parts.size || (allowPartialLocal && available.isNotEmpty())) {
                    return BiliVideoDetails(video, available)
                }
            }
            throw BilibiliException((error as? BilibiliException)?.code ?: 0,
                (error as? BilibiliException)?.message ?: "无法读取分 P；离线播放需要已缓存的分 P 信息和本地音轨")
        }
    }

    private suspend fun stored(part: BiliPart): String? {
        val track = db.tracks().findByBvid(part.bvid, part.cid)
        val uri = track?.localUri
        if (!uri.isNullOrBlank()) {
            val readable = runCatching {
                if (uri.startsWith("content:")) context.contentResolver.openAssetFileDescriptor(android.net.Uri.parse(uri), "r")?.use { true } == true
                else java.io.File(if (uri.startsWith("file:")) android.net.Uri.parse(uri).path.orEmpty() else uri).let { it.isFile && it.length() > 0 }
            }.getOrDefault(false)
            if (readable) return uri
        }
        return readyBiliAudio(context.filesDir, part.bvid, part.cid)?.absolutePath
    }

    suspend fun playable(part: BiliPart): QueuePlayable {
        val track = db.tracks().findByBvid(part.bvid, part.cid)
        val local = stored(part)
        if (track != null) {
            if (local != null && local != track.localUri) db.tracks().updateLocalUri(track.id, local)
            return QueuePlayable.Saved(track.copy(localUri = local))
        }
        val page = "https://www.bilibili.com/video/${part.bvid}"
        return QueuePlayable.Remote(RemotePlayable(part.title, part.artist, part.coverUrl, local ?: page,
            TrackSource.BILIBILI, page, part.durationMs, part.bvid, part.cid.toString()))
    }
}
