package com.nanahoshi.audioplayer.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.nanahoshi.audioplayer.AudioPlayerApplication
import com.nanahoshi.audioplayer.data.db.PlaybackStateEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var retriedMediaId: String? = null

    override fun onCreate() {
        super.onCreate()
        val graph = (application as AudioPlayerApplication).graph
        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    BiliPlaybackDataSource.Factory(this, graph.bilibili, graph.asmr, graph.dlsite, graph.db),
                ),
            )
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        val restored = runBlocking(Dispatchers.IO) {
            val rows = graph.db.queue().snapshot()
            val state = graph.db.queue().playbackState()
            rows to state
        }
        val (rows, state) = restored
        if (rows.isNotEmpty()) {
            try {
                val items = rows.map { it.toMediaItem() }
                val index = rows.indexOfFirst { it.queueItemId == state?.queueItemId }.coerceAtLeast(0)
                exoPlayer.setMediaItems(items, index, state?.positionMs ?: 0L)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = false
            } catch (error: Exception) {
                graph.messages.value = error.message ?: "无法恢复上次播放"
            }
        }
        exoPlayer.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                retriedMediaId = null
                saveProgress(exoPlayer)
            }

            override fun onPlayerError(error: PlaybackException) {
                val item = exoPlayer.currentMediaItem ?: return
                val uri = item.localConfiguration?.uri
                val scheme = uri?.scheme
                if ((scheme == "bilibili" || scheme == "asmr" || scheme == "dlsite" || scheme == "remote") &&
                    retriedMediaId != item.mediaId
                ) {
                    retriedMediaId = item.mediaId
                    val index = exoPlayer.currentMediaItemIndex
                    val position = exoPlayer.currentPosition.coerceAtLeast(0L)
                    exoPlayer.seekTo(index, position)
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                } else {
                    graph.messages.value = playbackErrorMessage(error)
                }
            }
        })
        player = exoPlayer
        session = MediaSession.Builder(this, exoPlayer).build()
        scope.launch {
            while (isActive) {
                delay(5_000)
                saveProgress(exoPlayer)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        player?.let { exoPlayer ->
            val queueItemId = exoPlayer.currentMediaItem?.mediaId?.toLongOrNull() ?: -1L
            val position = exoPlayer.currentPosition.coerceAtLeast(0L)
            val db = (application as AudioPlayerApplication).graph.db
            runBlocking(Dispatchers.IO) {
                db.queue().savePlayback(PlaybackStateEntity(queueItemId = queueItemId, positionMs = position))
            }
        }
        scope.cancel()
        session?.release()
        session = null
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun playbackErrorMessage(error: PlaybackException): String {
        val detail = generateSequence<Throwable>(error) { it.cause }
            .mapNotNull { it.message?.trim() }
            .lastOrNull { it.isNotBlank() && !it.contains("Source error") && !it.startsWith("androidx.media3") }
        return detail ?: "播放失败，请检查网络或作品是否还能打开"
    }

    private fun saveProgress(exoPlayer: ExoPlayer) {
        val queueItemId = exoPlayer.currentMediaItem?.mediaId?.toLongOrNull() ?: -1L
        val position = exoPlayer.currentPosition.coerceAtLeast(0L)
        val db = (application as AudioPlayerApplication).graph.db
        scope.launch(Dispatchers.IO) {
            db.queue().savePlayback(PlaybackStateEntity(queueItemId = queueItemId, positionMs = position))
        }
    }
}
