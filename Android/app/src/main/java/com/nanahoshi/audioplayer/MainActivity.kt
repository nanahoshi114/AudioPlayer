package com.nanahoshi.audioplayer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nanahoshi.audioplayer.data.AccentMode
import com.nanahoshi.audioplayer.data.AudioDownloadService
import com.nanahoshi.audioplayer.data.SubtitleTaskService
import com.nanahoshi.audioplayer.data.ThemeMode
import com.nanahoshi.audioplayer.ui.importing.ClipboardImportViewModel
import com.nanahoshi.audioplayer.ui.theme.AudioPlayerTheme
import com.nanahoshi.audioplayer.ui.theme.ThemeAccent
import com.nanahoshi.audioplayer.ui.theme.presetAccent
import com.nanahoshi.audioplayer.ui.theme.rememberCoverAccent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val clipboardImport by lazy { ViewModelProvider(this)[ClipboardImportViewModel::class.java] }
    private var clipboardCheckedThisEntry = false
    private val openTaskPage = MutableStateFlow<String?>(null)

    override fun onStart() {
        super.onStart()
        application.graph().subtitleDisplay.appVisible.value = true
        clipboardCheckedThisEntry = false
    }

    override fun onStop() {
        application.graph().subtitleDisplay.appVisible.value = false
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        application.graph().downloads.resumePendingSubtitles()
        application.graph().whisper.resumePending()
        window.decorView.post { inspectClipboardWhenReady() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) inspectClipboardWhenReady()
    }

    private fun inspectClipboardWhenReady() {
        if (clipboardCheckedThisEntry || !hasWindowFocus() || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        clipboardCheckedThisEntry = true
        clipboardImport.inspectClipboard()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 进程死亡后从最近任务恢复会重投原启动 Intent；只在全新启动时处理通知动作，避免重放导航。
        if (savedInstanceState == null) handleDownloadNotification(intent)
        val client = application.graph().player
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                client.state.map { it.isPlaying }.distinctUntilChanged().collectLatest { playing ->
                    client.publishNow()
                    if (playing) {
                        while (true) {
                            delay(400)
                            client.publishNow()
                        }
                    }
                }
            }
        }
        enableEdgeToEdge()
        setContent {
            val graph = (application as AudioPlayerApplication).graph
            val appearance by graph.appearance.state.collectAsStateWithLifecycle()
            val player by graph.player.state.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val dark = when (appearance.themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            val coverAccent = rememberCoverAccent(
                player.coverUri.takeIf { appearance.accentMode == AccentMode.COVER },
            )
            val accent = when (appearance.accentMode) {
                AccentMode.SYSTEM -> ThemeAccent.System
                AccentMode.COVER -> coverAccent?.let { ThemeAccent.Seed(it) } ?: ThemeAccent.Default
                AccentMode.PRESET -> presetAccent(appearance.presetId)
            }
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT,
                    ) { dark },
                    navigationBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.argb(0xe6, 0xFF, 0xFF, 0xFF),
                        android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b),
                    ) { dark },
                )
                onDispose { }
            }
            AudioPlayerTheme(darkTheme = dark, accent = accent) {
                PlayerApp(clipboardImport, openTaskPage)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDownloadNotification(intent)
    }

    private fun handleDownloadNotification(intent: Intent) {
        val destination = when (intent.action) {
            AudioDownloadService.OPEN_DOWNLOADS -> Routes.Downloads
            SubtitleTaskService.OPEN_TASKS -> Routes.SubtitleTasks
            else -> return
        }
        intent.action = null
        openTaskPage.value = destination
    }
}
