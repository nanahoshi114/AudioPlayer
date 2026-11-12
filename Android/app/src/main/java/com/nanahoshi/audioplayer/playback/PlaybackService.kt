package com.nanahoshi.audioplayer.playback

import android.content.res.Configuration
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var subtitles: PlaybackSubtitles? = null
    private var retriedMediaId: String? = null
    private var lastSaved: PlaybackStateEntity? = null
    private var restorePending = true
    private var restoreJob: Job? = null
    // 单线程串行写：去重后不再周期性重写，乱序的并发写会把旧进度永久留在库里。
    private val saveDispatcher = Dispatchers.IO.limitedParallelism(1)

    override fun onCreate() {
        super.onCreate()
        val graph = (application as AudioPlayerApplication).graph
        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    BiliPlaybackDataSource.Factory(this, graph.bilibili, graph.asmr, graph.dlsite, graph.db, graph.youtube),
                ),
            )
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
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
        // Session 必须在 onCreate 返回前建好：onBind 经 onGetSession 取它。
        session = MediaSession.Builder(this, exoPlayer).build()
        subtitles = PlaybackSubtitles(
            this, scope, exoPlayer, graph.currentSubtitles, graph.subtitleDisplay,
            onDesktopUnavailable = { graph.messages.value = "无法显示桌面字幕，请检查悬浮窗权限" },
        )
        // 恢复异步化：DB 读取与逐条 content: 探测不再阻塞主线程。
        // 应用与 PlayerClient 共用 queueMutex，并在锁内重读快照，避免覆盖抢先的队列操作。
        restoreJob = scope.launch {
            val (rows, state) = withContext(Dispatchers.IO) {
                graph.db.queue().snapshot() to graph.db.queue().playbackState()
            }
            if (rows.isEmpty()) {
                restorePending = false
                return@launch
            }
            val items = try {
                withContext(Dispatchers.IO) { rows.map { it.toMediaItem(this@PlaybackService) } }
            } catch (error: Exception) {
                restorePending = false
                graph.messages.value = error.message ?: "无法恢复上次播放"
                return@launch
            }
            try {
                graph.player.withQueueLock {
                    if (!restorePending) return@withQueueLock
                    if (exoPlayer.mediaItemCount > 0) {
                        // 客户端已抢先填充播放器，用户操作优先。
                        restorePending = false
                        return@withQueueLock
                    }
                    val fresh = graph.db.queue().snapshot()
                    val unchanged = fresh == rows
                    val applyRows = if (unchanged) rows else fresh
                    val applyItems = if (unchanged) items else withContext(Dispatchers.IO) {
                        fresh.map { it.toMediaItem(this@PlaybackService) }
                    }
                    // 先解除进度保存抑制再应用：setMediaItems 同步触发的过渡回调要能记录恢复态。
                    restorePending = false
                    val index = applyRows.indexOfFirst { it.queueItemId == state?.queueItemId }.coerceAtLeast(0)
                    exoPlayer.setMediaItems(applyItems, index, state?.positionMs ?: 0L)
                    if (applyItems.isNotEmpty()) exoPlayer.prepare()
                    exoPlayer.playWhenReady = false
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                restorePending = false
                graph.messages.value = error.message ?: "无法恢复上次播放"
            }
        }
        scope.launch {
            while (isActive) {
                delay(5_000)
                saveProgress(exoPlayer)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        subtitles?.relayout()
    }

    override fun onDestroy() {
        val pending = restorePending
        restorePending = false
        restoreJob?.cancel()
        restoreJob = null
        subtitles?.close()
        subtitles = null
        player?.let { exoPlayer ->
            // 恢复未应用且播放器为空时，当前状态不代表库里的队列，跳过存盘避免 (-1, 0) 覆盖。
            if (!pending || exoPlayer.mediaItemCount > 0) {
                val queueItemId = exoPlayer.currentMediaItem?.mediaId?.toLongOrNull() ?: -1L
                val position = exoPlayer.currentPosition.coerceAtLeast(0L)
                val db = (application as AudioPlayerApplication).graph.db
                runBlocking(Dispatchers.IO) {
                    db.queue().savePlayback(PlaybackStateEntity(queueItemId = queueItemId, positionMs = position))
                }
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
        // 恢复未应用前播放器为空，(-1, 0) 复位信号不能覆盖库里的上次播放状态。
        if (restorePending) return
        val queueItemId = exoPlayer.currentMediaItem?.mediaId?.toLongOrNull() ?: -1L
        val position = exoPlayer.currentPosition.coerceAtLeast(0L)
        val state = PlaybackStateEntity(queueItemId = queueItemId, positionMs = position)
        // 相等去重：暂停、静止或无媒体时不再每 5 秒重复写。(-1, 0) 是清空队列的复位信号，仍要写一次。
        if (state == lastSaved) return
        lastSaved = state
        val db = (application as AudioPlayerApplication).graph.db
        scope.launch(saveDispatcher) { db.queue().savePlayback(state) }
    }
}
