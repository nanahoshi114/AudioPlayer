package com.nanahoshi.audioplayer.playback

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.nanahoshi.audioplayer.data.LibraryRepository
import com.nanahoshi.audioplayer.data.db.TrackEntity
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
)

class PlayerClient(
    context: Context,
    private val library: LibraryRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ready = CompletableDeferred<MediaController>()
    private val queueMutex = Mutex()
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state
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
        queueMutex.withLock {
            val existing = library.firstQueueItemId(track.id)
            if (existing != null) {
                withController {
                    val index = indexOfQueueItem(existing)
                    if (index >= 0) {
                        seekTo(index, 0)
                        play()
                    }
                }
            } else {
                appendLocked(listOf(track), startPlaying = true)
            }
        }
    }

    fun playTracks(tracks: List<TrackEntity>) = scope.launch {
        if (tracks.isEmpty()) return@launch
        queueMutex.withLock {
            val first = tracks.first()
            val existing = library.firstQueueItemId(first.id)
            if (existing == null) {
                appendLocked(listOf(first), startPlaying = true)
            } else {
                withController {
                    val index = indexOfQueueItem(existing)
                    if (index >= 0) {
                        seekTo(index, 0)
                        play()
                    }
                }
            }
            val missing = tracks.drop(1).filter { library.firstQueueItemId(it.id) == null }
            if (missing.isNotEmpty()) appendLocked(missing, startPlaying = false)
        }
    }

    fun append(tracks: List<TrackEntity>) = scope.launch {
        queueMutex.withLock { appendLocked(tracks, startPlaying = false) }
    }

    fun playNext(tracks: List<TrackEntity>) = scope.launch {
        if (tracks.isEmpty()) return@launch
        queueMutex.withLock {
            val anchor = if (_state.value.hasMedia && _state.value.queueItemId > 0) {
                _state.value.queueItemId
            } else {
                null
            }
            val (index, ids) = library.insertQueueAfter(anchor, tracks.map { it.id })
            val items = ids.zip(tracks).map { (queueId, track) -> track.toPlayableItem(queueId) }
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

    fun replaceAndPlay(tracks: List<TrackEntity>) = scope.launch {
        queueMutex.withLock {
            val ids = library.replaceQueue(tracks.map { it.id })
            val items = ids.zip(tracks).map { (queueId, track) -> track.toPlayableItem(queueId) }
            withController {
                setMediaItems(items, 0, 0)
                if (items.isNotEmpty()) {
                    prepare()
                    play()
                } else {
                    pause()
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
                if (index in 0 until mediaItemCount && getMediaItemAt(index).mediaId == queueItemId.toString()) {
                    removeMediaItem(index)
                }
            }
        }
    }

    fun publishNow() = scope.launch { withController { publish(this) } }

    fun deleteTrack(trackId: Long) = scope.launch {
        queueMutex.withLock {
            val affected = library.queueSnapshot().any { it.trackId == trackId }
            library.deleteTrack(trackId)
            if (affected) rebuildPlayerFromQueue()
        }
    }

    fun removeFromLibrary(trackIds: List<Long>, folderIds: List<Long>) = scope.launch {
        if (trackIds.isEmpty() && folderIds.isEmpty()) return@launch
        queueMutex.withLock {
            val doomed = library.affectedTrackIds(trackIds, folderIds)
            val affected = library.queueSnapshot().any { it.trackId in doomed }
            library.deleteItems(trackIds, folderIds)
            if (affected) rebuildPlayerFromQueue()
        }
    }

    private suspend fun rebuildPlayerFromQueue() {
        val rows = library.queueSnapshot()
        val items = rows.map { it.toTrack().toPlayableItem(it.queueItemId) }
        withController {
            val currentId = currentMediaItem?.mediaId
            val position = currentPosition
            val index = rows.indexOfFirst { it.queueItemId.toString() == currentId }
            val keep = index >= 0
            setMediaItems(items, if (keep) index else 0, if (keep) position else 0L)
            if (items.isNotEmpty()) prepare() else pause()
        }
    }

    private suspend fun appendLocked(tracks: List<TrackEntity>, startPlaying: Boolean) {
        if (tracks.isEmpty()) return
        val ids = library.appendQueue(tracks.map { it.id })
        val items = ids.zip(tracks).map { (queueId, track) -> track.toPlayableItem(queueId) }
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
        )
    }
}
