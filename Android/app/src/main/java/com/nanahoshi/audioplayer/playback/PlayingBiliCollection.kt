package com.nanahoshi.audioplayer.playback

import com.nanahoshi.audioplayer.bilibili.BV_PATTERN
import com.nanahoshi.audioplayer.bilibili.BiliCollection
import com.nanahoshi.audioplayer.bilibili.BiliVideoCollectionCache
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

internal fun QueuedTrack?.biliVideoId(): String? {
    if (this?.source != TrackSource.BILIBILI) return null
    return sequenceOf(bvid, workno).filterNotNull().firstOrNull { BV_PATTERN.matches(it) }
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
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), PlayingBiliVideo(null, -1, 0))
    private val linkState = MutableStateFlow<PlayingBiliCollectionLink?>(null)
    val link: StateFlow<PlayingBiliCollectionLink?> = linkState.asStateFlow()

    init {
        scope.launch {
            // 只有播放页订阅 link 时才发布并补查；Activity 级实例无人订阅，不发请求。
            // subscriptionCount 只在 MutableStateFlow 上公开，故内部持有 linkState。
            linkState.subscriptionCount.map { it > 0 }.distinctUntilChanged().collectLatest { active ->
                if (!active) return@collectLatest
                // coroutineScope 把 combine 收集器绑成本次订阅的子协程，随订阅结束一起取消；
                // 直接 launch 会逃逸到外层 scope，每次进出播放页泄漏一个常驻收集器，并让 current 永远保持订阅。
                coroutineScope {
                    launch {
                        combine(current, cache.values) { video, values ->
                            video.bvid?.let { bvid -> values[bvid]?.let { PlayingBiliCollectionLink(bvid, it) } }
                        }.collect { linkState.value = it }
                    }
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
    }
}

internal fun playingBiliCollection(row: QueuedTrack?, link: PlayingBiliCollectionLink?): BiliCollection? =
    link?.takeIf { it.bvid == row.biliVideoId() }?.collection
