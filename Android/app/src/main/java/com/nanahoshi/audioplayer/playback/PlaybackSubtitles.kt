package com.nanahoshi.audioplayer.playback

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal class PlaybackSubtitles(
    private val context: Context,
    private val scope: CoroutineScope,
    private val player: Player,
    private val subtitles: CurrentSubtitles,
    private val preferences: SubtitleDisplayPreferences,
    private val onDesktopUnavailable: () -> Unit,
) : Player.Listener {
    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val appOps = context.getSystemService(AppOpsManager::class.java)
    private var screenInteractive = power.isInteractive
    private var screenAvailable = screenInteractive && !keyguard.isKeyguardLocked
    private var overlayAllowed = Settings.canDrawOverlays(context)
    private var appVisible = preferences.appVisible.value
    private var desktop: DesktopSubtitleWindow? = null
    private var readJob: Job? = null
    private var tickJob: Job? = null
    private var pauseJob: Job? = null
    private var closed = false

    private val permissionListener = AppOpsManager.OnOpChangedListener { _, _ ->
        scope.launch { refreshEnvironment() }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshEnvironment()
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // These protected broadcasts can be sent by a separate privileged system process.
        ContextCompat.registerReceiver(context, screenReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, context.packageName, permissionListener)
        player.addListener(this)
        subtitles.select(queueItemId())
        scope.launch { preferences.desktop.collect { refreshEnvironment() } }
        scope.launch {
            preferences.appVisible.collectLatest { visible ->
                // A replacement activity can start immediately after the old one stops on rotation.
                if (!visible && appVisible) delay(200)
                appVisible = visible
                refreshEnvironment()
            }
        }
        scope.launch { subtitles.selection.collect { refresh() } }
    }

    override fun onEvents(player: Player, events: Player.Events) {
        updateScreenState()
        subtitles.select(queueItemId())
        refresh()
    }

    fun relayout() {
        refreshEnvironment()
        desktop?.relayout()
    }

    fun close() {
        closed = true
        player.removeListener(this)
        appOps.stopWatchingMode(permissionListener)
        context.unregisterReceiver(screenReceiver)
        readJob?.cancel()
        tickJob?.cancel()
        pauseJob?.cancel()
        desktop?.show(null)
        subtitles.select(-1)
    }

    private fun updateScreenState() {
        screenInteractive = power.isInteractive
        screenAvailable = screenInteractive && !keyguard.isKeyguardLocked
    }

    private fun refreshEnvironment() {
        if (closed) return
        updateScreenState()
        overlayAllowed = Settings.canDrawOverlays(context)
        if (!overlayAllowed && preferences.desktop.value.enabled) preferences.enableDesktop(false)
        refresh()
    }

    private fun refresh() {
        if (closed) return
        val id = queueItemId()
        val playable = id > 0 && player.playerError == null &&
            (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
        val desktopSettings = preferences.desktop.value
        val desktopRequested = playable && overlayAllowed && desktopSettings.enabled && !appVisible
        val desktopAllowed = desktopRequested && screenAvailable
        if (!desktopAllowed || player.playWhenReady || desktopSettings.locked) {
            pauseJob?.cancel()
            pauseJob = null
        }
        val desktopActive = desktopAllowed && (player.playWhenReady || pauseJob != null)
        observeSubtitles(desktopActive)
        val cues = if (desktopActive) subtitles.state.value.forItem(id)?.cues.orEmpty() else emptyList()
        if (cues.isEmpty()) {
            desktop?.show(null)
            // Wake and unlock may be delivered separately. Recheck only while the screen is awake.
            poll(desktopRequested && player.playWhenReady && screenInteractive && !screenAvailable)
            return
        }
        val selection = subtitles.selection.value
        val position = player.currentPosition.coerceAtLeast(0L)
        val cue = cues.getOrNull(cues.indexAt(position))
        val line = cue?.line(chinese = selection.queueItemId != id || selection.chinese).orEmpty()
        if (desktop == null && line.isNotBlank()) desktop = createDesktop()
        desktop?.show(
            DesktopSubtitleContent(
                line, desktopSettings, player.playWhenReady,
                player.hasPreviousMediaItem(), player.hasNextMediaItem(),
            ),
            scrollProgress = cue?.let { desktopSubtitleScrollProgress(it, position, player.duration, line.length) } ?: 0f,
        )
        poll(player.isPlaying)
    }

    private fun observeSubtitles(needed: Boolean) {
        if (needed && readJob == null) {
            // Assign the job before StateFlow delivers its current value on the player thread.
            readJob = scope.launch(start = CoroutineStart.LAZY) { subtitles.state.collect { refresh() } }
            readJob?.start()
        } else if (!needed) {
            readJob?.cancel()
            readJob = null
        }
    }

    private fun poll(needed: Boolean) {
        if (needed && tickJob == null) {
            tickJob = scope.launch {
                while (true) {
                    delay(if (screenAvailable) 200 else 500)
                    if (!screenAvailable) updateScreenState()
                    refresh()
                }
            }
        } else if (!needed) {
            tickJob?.cancel()
            tickJob = null
        }
    }

    private fun createDesktop() = DesktopSubtitleWindow(
        context,
        preferences,
        onToggle = {
            if (player.playWhenReady) {
                pauseJob?.cancel()
                pauseJob = scope.launch {
                    delay(30_000)
                    pauseJob = null
                    refresh()
                }
                player.pause()
            } else {
                player.play()
            }
            refresh()
        },
        onPrevious = { if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem() },
        onNext = { if (player.hasNextMediaItem()) player.seekToNextMediaItem() },
        onUnavailable = {
            if (!Settings.canDrawOverlays(context)) {
                refreshEnvironment()
                onDesktopUnavailable()
            }
        },
    )

    private fun queueItemId(): Long = player.currentMediaItem?.mediaId?.toLongOrNull() ?: -1
}

internal fun desktopSubtitleScrollProgress(cue: SubtitleCue, positionMs: Long, trackDurationMs: Long, textLength: Int): Float {
    val endMs = if (trackDurationMs > cue.startMs) minOf(cue.endMs, trackDurationMs) else cue.endMs
    val durationMs = if (endMs == Long.MAX_VALUE) {
        // The last LRC line can be open-ended, and a stream may not report a track duration.
        (textLength * 100L).coerceAtLeast(4_000L)
    } else {
        (endMs - cue.startMs).coerceAtLeast(1L)
    }
    val elapsed = (positionMs - cue.startMs).coerceAtLeast(0L)
    // Leave time to read both the beginning and the end of the caption.
    return ((elapsed.toDouble() / durationMs - .1) / .7).coerceIn(0.0, 1.0).toFloat()
}
