package com.nanahoshi.audioplayer.ui.asmr

import android.app.Application
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.graph
import com.nanahoshi.audioplayer.japaneseasmr.JaArticle
import com.nanahoshi.audioplayer.japaneseasmr.JaVisibleDownload
import com.nanahoshi.audioplayer.japaneseasmr.isBuzzBrowserPage
import com.nanahoshi.audioplayer.ui.components.AppTopBar
import com.nanahoshi.audioplayer.ui.components.EmptyState
import com.nanahoshi.audioplayer.ui.components.RoundedProgress

@Composable
internal fun JapaneseAsmrVerificationScreen(
    url: String,
    expectedRj: String?,
    onBack: () -> Unit,
    onArticle: (JaArticle) -> Unit = {},
    onDownload: (suspend (JaVisibleDownload) -> Unit)? = null,
    autoRead: Boolean = false,
) {
    val buzzheavier = onDownload != null && isBuzzBrowserPage(url)
    if (!canLoadVerificationAddress(url, buzzheavier)) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(title = "Japanese ASMR", onBack = onBack)
            EmptyState(
                title = stringResource(R.string.ja_verify_address_unavailable_title),
                message = stringResource(R.string.ja_verify_address_unavailable_message),
            )
        }
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = (context.applicationContext as Application).graph().japaneseAsmr
    val controller = remember {
        JapaneseAsmrWebController(url, buzzheavier, context, scope, client)
    }
    controller.onDownload = onDownload
    controller.onArticle = onArticle
    controller.expectedRj = expectedRj
    val initialPostId = controller.initialPostId

    LaunchedEffect(buzzheavier, controller.buzzDirect) { controller.pollBuzzCapture() }
    LaunchedEffect(controller.reading, controller.buzzDirect) { controller.watchCaptureTimeout() }
    LaunchedEffect(controller.buzzLanding, autoRead) { controller.autoStartBuzzRead(autoRead) }
    BackHandler { controller.leave(onBack) }

    Column(Modifier.fillMaxSize().imePadding()) {
        AppTopBar(
            title = if (buzzheavier) stringResource(R.string.ja_verify_hosting_title) else stringResource(R.string.ja_verify_web_title),
            onBack = { controller.leave(onBack) },
            actions = {
                IconButton(onClick = { controller.webView?.let(controller::reloadPage) }, enabled = controller.webView != null) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.a11y_refresh))
                }
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.a11y_close))
                }
                TextButton(enabled = controller.actionEnabled, onClick = controller::onActionClick) {
                    Text(when {
                        controller.hostingUnavailable -> stringResource(R.string.ja_verify_action_retry)
                        onDownload == null -> stringResource(R.string.ja_verify_action_read_tracks)
                        else -> stringResource(R.string.ja_verify_action_read_downloads)
                    })
                }
            },
        )
        if (!controller.hostingUnavailable) Text(
            when {
                buzzheavier && (controller.buzzFile != null || controller.buzzDirect != null) ->
                    stringResource(R.string.ja_verify_buzz_captured)
                buzzheavier ->
                    stringResource(R.string.ja_verify_buzz_help)
                onDownload != null && initialPostId != null -> stringResource(R.string.ja_verify_post_help)
                onDownload != null && (controller.downloadEntryVisible || controller.downloadRedirect != null) ->
                    stringResource(R.string.ja_verify_entry_visible)
                onDownload != null -> stringResource(R.string.ja_verify_entry_help)
                initialPostId == null -> stringResource(R.string.ja_verify_open_post_help)
                else -> stringResource(R.string.ja_verify_wait_player)
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        controller.error?.let {
            Text(it, modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
        }
        if (controller.loading || controller.reading) RoundedProgress(progress = null)
        AndroidView(
            factory = { host -> WebView(host).also(controller::configure) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            onRelease = { view ->
                view.stopLoading()
                view.destroy()
            },
        )
    }
}
