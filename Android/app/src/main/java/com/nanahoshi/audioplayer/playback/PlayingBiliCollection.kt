package com.nanahoshi.audioplayer.playback

import com.nanahoshi.audioplayer.bilibili.BiliCollection
import com.nanahoshi.audioplayer.bilibili.BiliVideoCollectionCache
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

internal fun QueuedTrack?.biliVideoId(): String? {
    if (this?.source != TrackSource.BILIBILI) return null
    return sequenceOf(bvid, workno).filterNotNull().firstOrNull { Regex("BV[0-9A-Za-z]{10}").matches(it) }
}

internal data class PlayingBiliVideo(val bvid: String?, val queueItemId: Long, val session: Long)
internal data class PlayingBiliCollectionLink(val bvid: String, val collection: BiliCollection)

internal class PlayingBiliCollection(
    scope: CoroutineScope,
    selection: Flow<PlayingBiliVideo>,
    cache: BiliVideoCollectionCache,
    load: suspend (String) -> BiliCollection?,
) {
    private val current = selection.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, PlayingBiliVideo(null, -1, 0))
    val link: StateFlow<PlayingBiliCollectionLink?> = combine(current, cache.values) { video, values ->
        video.bvid?.let { bvid -> values[bvid]?.let { PlayingBiliCollectionLink(bvid, it) } }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch {
            current.collectLatest { video ->
                val bvid = video.bvid ?: return@collectLatest
                if (!cache.values.value.containsKey(bvid)) {
                    try { cache.resolve(bvid, load) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* 附加信息不可用时不影响音频、队列或字幕。 */ }
                }
            }
        }
    }
}

internal fun playingBiliCollection(row: QueuedTrack?, link: PlayingBiliCollectionLink?): BiliCollection? =
    link?.takeIf { it.bvid == row.biliVideoId() }?.collection
