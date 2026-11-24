package com.nanahoshi.audioplayer.playback

import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.text.resolve
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.nanahoshi.audioplayer.data.LibraryRepository
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.dlsite.RemotePlayable
import com.nanahoshi.audioplayer.japaneseasmr.JaArticle
import com.nanahoshi.audioplayer.japaneseasmr.JaFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class PlayerUiState(
    val queueItemId: Long = -1,
    val title: String = "",
    val artist: String = "",
    val coverUri: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val hasMedia: Boolean = false,
    val canSeekBack: Boolean = false,
    val canSeekForward: Boolean = false,
)

internal data class JaPlaybackFailure(val queueId: Long, val reason: JaFailure, val postId: String?, val mediaKey: String?) {
    val messageRes: Int get() = when (reason) {
        JaFailure.MEDIA_VERIFY, JaFailure.WORK_FILE_MISSING, JaFailure.UNSAFE -> reason.messageRes
        else -> R.string.player_ja_playback_failed
    }
}

class PlayerClient(
    context: Context,
    private val library: LibraryRepository,
    private val onRemovalRejected: (String) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ready = CompletableDeferred<MediaController>()
    private val queueMutex = Mutex()
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state
    private val _jaPlaybackFailure = MutableStateFlow<JaPlaybackFailure?>(null)
    internal val jaPlaybackFailure: StateFlow<JaPlaybackFailure?> = _jaPlaybackFailure

    internal fun reportJaPlaybackFailure(failure: JaPlaybackFailure) {
        _jaPlaybackFailure.value = failure
    }

    internal fun dismissJaPlaybackFailure(failure: JaPlaybackFailure) {
        _jaPlaybackFailure.compareAndSet(failure, null)
    }

    internal fun clearJaPlaybackFailure() {
        _jaPlaybackFailure.value = null
    }

    internal suspend fun recoverJaLocalCopy(player: Player, failed: MediaItem): Boolean {
        var recovered = false
        withRevalidatedQueue { items, _ ->
            val current = player.currentMediaItem
            if (current?.mediaId != failed.mediaId || current.localConfiguration?.uri != failed.localConfiguration?.uri) {
                return@withRevalidatedQueue
            }
            val replacement = items.firstOrNull { it.mediaId == failed.mediaId } ?: return@withRevalidatedQueue
            val uri = replacement.localConfiguration?.uri
            if (uri?.scheme != "japaneseasmr" || uri.host != "audio") return@withRevalidatedQueue
            val index = player.currentMediaItemIndex
            val position = player.currentPosition.coerceAtLeast(0L)
            val shouldPlay = player.playWhenReady
            player.replaceMediaItem(index, replacement)
            player.seekTo(index, position)
            player.prepare()
            player.playWhenReady = shouldPlay
            recovered = true
        }
        return recovered
    }

    internal suspend fun refreshJaArticle(article: JaArticle, rootId: Long, cleanup: Boolean): LibraryRepository.JaRefreshResult =
        queueMutex.withLock {
            val result = library.refreshJapaneseAsmrPost(article.post.postId, rootId, cleanup, article) {
                state.value.queueItemId
            }
            if (result.queueIds.isNotEmpty()) withController { removeQueueMedia(result.queueIds) }
            result
        }

    private fun Player.removeQueueMedia(queueIds: List<Long>) {
        for (index in mediaItemCount - 1 downTo 0) {
            if (getMediaItemAt(index).mediaId.toLongOrNull() in queueIds) removeMediaItem(index)
        }
    }

    private var sleepJob: Job? = null
    private val _sleepRemainingMs = MutableStateFlow<Long?>(null)
    val sleepRemainingMs: StateFlow<Long?> = _sleepRemainingMs
    private val _sleepFinished = MutableStateFlow(false)
    val sleepFinished: StateFlow<Boolean> = _sleepFinished

    init {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                val controller = future.get()
                controller.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) {
                        publish(player)
                    }
                })
                ready.complete(controller)
                publish(controller)
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun toggle() = scope.launch { withController { if (isPlaying) pause() else play() } }

    fun next() = scope.launch { withController { seekToNext() } }

    fun previous() = scope.launch { withController { seekToPrevious() } }

    fun seekTo(positionMs: Long) = scope.launch { withController { seekTo(positionMs) } }

    fun seekBack(): Job = seekByStep(forward = false)

    fun seekForward(): Job = seekByStep(forward = true)

    private fun seekByStep(forward: Boolean): Job = scope.launch {
        withController {
            if (seekStepIfAvailable(forward)) publish(this)
        }
    }

    fun startSleepTimer(totalMs: Long) {
        if (totalMs <= 0L) return
        sleepJob?.cancel()
        _sleepFinished.value = false
        val endAt = SystemClock.elapsedRealtime() + totalMs
        sleepJob = scope.launch {
            while (isActive) {
                val left = endAt - SystemClock.elapsedRealtime()
                if (left <= 0L) {
                    _sleepRemainingMs.value = null
                    withController { pause() }
                    _sleepFinished.value = true
                    break
                }
                _sleepRemainingMs.value = left
                delay(minOf(1000L, left))
            }
        }
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        _sleepRemainingMs.value = null
    }

    fun acknowledgeSleepFinished() {
        _sleepFinished.value = false
    }

    fun playTrack(track: TrackEntity) = scope.launch {
        val probed = probeTracks(listOf(track))
        var fallbackId: Long? = null
        queueMutex.withLock {
            val existing = library.firstQueueItemId(track.id)
            if (existing != null) {
                if (!tryPlayFromPlayer(existing)) fallbackId = existing
            } else if (track.id > 0L) {
                appendLocked(probed, startPlaying = true)
            }
        }
        fallbackId?.let { playQueueItemFallback(it) }
    }

    fun playQueueItem(queueItemId: Long) = scope.launch {
        val found = queueMutex.withLock { tryPlayFromPlayer(queueItemId) }
        if (!found) playQueueItemFallback(queueItemId)
    }

    internal fun resumeVerifiedTrack(queueItemId: Long, mediaKey: String?, article: JaArticle) = scope.launch {
        queueMutex.withLock {
            if (state.value.queueItemId != queueItemId || !library.jaQueueMatches(article, queueItemId, mediaKey)) return@withLock
            withController {
                if (currentMediaItem?.mediaId != queueItemId.toString()) return@withController
                if (playbackState == Player.STATE_IDLE) prepare()
                play()
            }
        }
    }

    /** 播放器优先尝试；调用方持有 queueMutex。 */
    private suspend fun tryPlayFromPlayer(queueItemId: Long): Boolean {
        var found = false
        withController {
            val index = indexOfQueueItem(queueItemId)
            if (index >= 0) {
                seekTo(index, 0)
                play()
                found = true
            }
        }
        return found
    }

    fun playTracks(tracks: List<TrackEntity>) = scope.launch {
        if (tracks.isEmpty()) return@launch
        val probed = probeTracks(tracks)
        var fallbackId: Long? = null
        queueMutex.withLock {
            val first = tracks.first()
            val existing = library.firstQueueItemId(first.id)
            if (existing == null) {
                appendLocked(probed.take(1), startPlaying = true)
            } else {
                if (!tryPlayFromPlayer(existing)) fallbackId = existing
            }
            // missing 在 first 处理之后判定，保持原有去重行为。
            val missing = probed.drop(1).filter { library.firstQueueItemId(it.first.id) == null }
            if (missing.isNotEmpty()) appendLocked(missing, startPlaying = false)
        }
        fallbackId?.let { playQueueItemFallback(it) }
    }

    fun append(tracks: List<TrackEntity>) = scope.launch {
        val probed = probeTracks(tracks)
        queueMutex.withLock { appendLocked(probed, startPlaying = false) }
    }

    fun playNext(tracks: List<TrackEntity>) = scope.launch {
        if (tracks.isEmpty()) return@launch
        val probed = probeTracks(tracks)
        queueMutex.withLock {
            val anchor = if (_state.value.hasMedia && _state.value.queueItemId > 0) {
                _state.value.queueItemId
            } else {
                null
            }
            val (index, ids) = library.insertQueueAfter(anchor, tracks.map { it.id })
            val items = assemble(ids, probed)
            withController {
                val wasEmpty = mediaItemCount == 0
                addMediaItems(index.coerceAtMost(mediaItemCount), items)
                if (wasEmpty) {
                    prepare()
                    playWhenReady = false
                }
            }
        }
    }

    fun clearQueue() = replaceAndPlay(emptyList())

    fun replaceAndPlayItems(items: List<QueuePlayable>) = enqueueItems(items, replace = true)
    fun appendItems(items: List<QueuePlayable>) = enqueueItems(items, replace = false)

    private fun enqueueItems(items: List<QueuePlayable>, replace: Boolean) = scope.launch {
        if (items.isEmpty()) return@launch
        val caches = withContext(Dispatchers.IO) { items.map { it.playbackTarget(appContext) } }
        var committed = false
        try {
            queueMutex.withLock {
                val ids = library.writeMixedQueue(items, replace)
                committed = true
                val media = ids.zip(items.zip(caches)).map { (id, itemAndCache) ->
                    itemAndCache.first.mediaItem(id, itemAndCache.second)
                }
                withController {
                    if (replace) {
                        setMediaItems(media, 0, 0)
                        prepare()
                        play()
                    } else {
                        val empty = mediaItemCount == 0
                        addMediaItems(media)
                        if (empty) { prepare(); playWhenReady = false }
                    }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) {
            // 库已提交但播放器没跟上：按库里的队列重建，避免队列页和播放器长期不一致。
            val recovered = if (!committed) false else try {
                if (replace) {
                    withRevalidatedQueue { media, _ ->
                        setMediaItems(media, 0, 0)
                        if (media.isNotEmpty()) { prepare(); play() }
                    }
                } else {
                    rebuildPlayerFromQueue()
                }
                true
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (!recovered) onRemovalRejected(appContext.getString(R.string.player_error_queue_prepare))
        }
    }

    fun replaceAndPlayRemote(items: List<RemotePlayable>, startIndex: Int = 0) = scope.launch {
        queueMutex.withLock {
            val ids = library.replaceRemoteQueue(items)
            val media = withContext(Dispatchers.IO) {
                val jaRows = if (items.any { it.source == com.nanahoshi.audioplayer.data.TrackSource.JAPANESE_ASMR }) {
                    library.queueSnapshot().associateBy { it.queueItemId }
                } else emptyMap()
                ids.zip(items).map { (queueId, item) ->
                    jaRows[queueId]?.takeIf { it.source == com.nanahoshi.audioplayer.data.TrackSource.JAPANESE_ASMR }
                        ?.toMediaItem(appContext) ?: item.toMediaItem(queueId)
                }
            }
            val index = if (media.isEmpty()) 0 else startIndex.coerceIn(0, media.lastIndex)
            withController {
                if (media.isEmpty()) {
                    setMediaItems(emptyList())
                    pause()
                } else {
                    setMediaItems(media, index, 0)
                    prepare()
                    play()
                }
            }
        }
    }

    fun replaceAndPlay(tracks: List<TrackEntity>, startIndex: Int = 0) = scope.launch {
        val probed = probeTracks(tracks)
        queueMutex.withLock {
            val ids = library.replaceQueue(tracks.map { it.id })
            val items = assemble(ids, probed)
            val index = if (items.isEmpty()) 0 else startIndex.coerceIn(0, items.lastIndex)
            withController {
                if (items.isEmpty()) {
                    setMediaItems(emptyList())
                    pause()
                } else {
                    setMediaItems(items, index, 0)
                    prepare()
                    play()
                }
            }
        }
    }

    fun move(from: Int, to: Int) = scope.launch {
        queueMutex.withLock {
            library.moveQueueItem(from, to)
            withController {
                if (from in 0 until mediaItemCount && to in 0 until mediaItemCount) {
                    moveMediaItem(from, to)
                }
            }
        }
    }

    fun remove(index: Int, queueItemId: Long) = scope.launch {
        queueMutex.withLock {
            library.removeQueueItem(queueItemId)
            withController {
                val playerIndex = index.takeIf { it in 0 until mediaItemCount && getMediaItemAt(it).mediaId == queueItemId.toString() }
                    ?: indexOfQueueItem(queueItemId)
                if (playerIndex >= 0) {
                    val removedCurrent = playerIndex == currentMediaItemIndex
                    removeMediaItem(playerIndex)
                    if (removedCurrent && mediaItemCount > 0 && playbackState == Player.STATE_IDLE) prepare()
                }
            }
        }
    }

    fun publishNow() = scope.launch { withController { publish(this) } }

    fun deleteTrack(trackId: Long) = scope.launch {
        try {
            val affected = queueMutex.withLock {
                val affected = library.queueSnapshot().any { it.trackId == trackId }
                library.deleteTrack(trackId)
                affected
            }
            if (affected) rebuildPlayerFromQueue()
        } catch (error: com.nanahoshi.audioplayer.data.LibraryRemovalRejected) {
            onRemovalRejected(appContext.resolve(error.userText))
        }
    }

    fun removeFromLibrary(
        trackIds: List<Long>,
        folderIds: List<Long>,
        fileIds: List<Long> = emptyList(),
    ) = scope.launch {
        if (trackIds.isEmpty() && folderIds.isEmpty() && fileIds.isEmpty()) return@launch
        try {
            val affected = queueMutex.withLock {
                val doomed = library.affectedTrackIds(trackIds, folderIds)
                val affected = library.queueSnapshot().any { it.trackId in doomed }
                library.deleteItems(trackIds, folderIds, fileIds)
                affected
            }
            if (affected) rebuildPlayerFromQueue()
        } catch (error: com.nanahoshi.audioplayer.data.LibraryRemovalRejected) {
            onRemovalRejected(appContext.resolve(error.userText))
        }
    }


    /** 锁外探测（唯一 binder IPC）；pair 保持曲目↔缓存对齐，供子集切片。 */
    private suspend fun probeTracks(tracks: List<TrackEntity>): List<Pair<TrackEntity, PlaybackTarget>> =
        withContext(Dispatchers.IO) { tracks.map { it to it.playbackTarget(appContext) } }

    /** 锁内纯组装，无 IPC。 */
    private fun assemble(ids: List<Long>, probed: List<Pair<TrackEntity, PlaybackTarget>>): List<MediaItem> =
        ids.zip(probed).map { (id, pair) -> pair.first.toPlayableItem(id, pair.second) }

    /** 锁外快照+探测，锁内重读比对（全字段相等，连 localUri 更新与改序也捕获）后应用。 */
    private suspend fun withRevalidatedQueue(apply: MediaController.(List<MediaItem>, List<QueuedTrack>) -> Unit) {
        val rows = library.queueSnapshot()
        val caches = withContext(Dispatchers.IO) { rows.map { it.playbackTarget(appContext) } }
        queueMutex.withLock {
            val fresh = library.queueSnapshot()
            val items = if (fresh == rows) {
                fresh.zip(caches).map { (row, cached) -> row.toMediaItem(cached) }
            } else {
                withContext(Dispatchers.IO) { fresh.map { it.toMediaItem(appContext) } }
            }
            withController { apply(items, fresh) }
        }
    }

    private suspend fun rebuildPlayerFromQueue() = withRevalidatedQueue { items, rows ->
        val currentId = currentMediaItem?.mediaId
        val position = currentPosition
        val index = rows.indexOfFirst { it.queueItemId.toString() == currentId }
        val keep = index >= 0
        setMediaItems(items, if (keep) index else 0, if (keep) position else 0L)
        if (items.isNotEmpty()) prepare() else pause()
    }

    /** 播放器与库不一致时按库快照重建，避免点了队列项没有任何反应。 */
    private suspend fun playQueueItemFallback(queueItemId: Long) = withRevalidatedQueue { items, rows ->
        val playerIndex = indexOfQueueItem(queueItemId)
        if (playerIndex >= 0) {
            seekTo(playerIndex, 0)
            play()
        } else {
            val index = rows.indexOfFirst { it.queueItemId == queueItemId }
            if (index >= 0) {
                setMediaItems(items, index, 0)
                prepare()
                play()
            }
        }
    }

    private suspend fun appendLocked(probed: List<Pair<TrackEntity, PlaybackTarget>>, startPlaying: Boolean) {
        if (probed.isEmpty()) return
        val ids = library.appendQueue(probed.map { it.first.id })
        val items = assemble(ids, probed)
        withController {
            val wasEmpty = mediaItemCount == 0
            val startIndex = mediaItemCount
            addMediaItems(items)
            if (wasEmpty) {
                prepare()
                playWhenReady = startPlaying
            } else if (startPlaying) {
                seekTo(startIndex, 0)
                play()
            }
        }
    }

    private suspend fun withController(block: MediaController.() -> Unit) {
        val controller = ready.await()
        withContext(Dispatchers.Main.immediate) { controller.block() }
    }

    /** 服务恢复与队列操作共用同一把锁：恢复应用不会覆盖抢先发生的用户队列操作。 */
    internal suspend fun <T> withQueueLock(block: suspend () -> T): T = queueMutex.withLock { block() }

    private fun MediaController.indexOfQueueItem(queueItemId: Long): Int {
        val key = queueItemId.toString()
        for (index in 0 until mediaItemCount) {
            if (getMediaItemAt(index).mediaId == key) return index
        }
        return -1
    }

    private fun publish(player: Player) {
        val metadata = player.mediaMetadata
        val duration = player.duration.takeIf { it > 0 } ?: 0L
        _state.value = PlayerUiState(
            queueItemId = player.currentMediaItem?.mediaId?.toLongOrNull() ?: -1L,
            title = metadata.title?.toString().orEmpty(),
            artist = metadata.artist?.toString().orEmpty(),
            coverUri = metadata.artworkUri?.toString(),
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = duration,
            hasMedia = player.mediaItemCount > 0,
            canSeekBack = player.isCommandAvailable(Player.COMMAND_SEEK_BACK),
            canSeekForward = player.isCommandAvailable(Player.COMMAND_SEEK_FORWARD),
        )
    }
}
