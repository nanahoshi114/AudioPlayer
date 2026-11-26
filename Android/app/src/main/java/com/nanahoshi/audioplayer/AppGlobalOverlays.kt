package com.nanahoshi.audioplayer

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import com.nanahoshi.audioplayer.data.ClipboardImportStage
import com.nanahoshi.audioplayer.ui.components.InfoNoticeDialog
import com.nanahoshi.audioplayer.ui.components.openJapaneseAsmrInBrowser
import com.nanahoshi.audioplayer.ui.importing.ClipboardImportDialog
import com.nanahoshi.audioplayer.ui.importing.ClipboardImportViewModel

/** 全局对话框、提示条队列及通知权限请求；与具体页面无关。 */
@Composable
internal fun AppGlobalOverlays(
    navController: NavController,
    route: String?,
    snackbar: SnackbarHostState,
    clipboardImport: ClipboardImportViewModel,
) {
    val context = LocalContext.current
    val graph = (context.applicationContext as AudioPlayerApplication).graph
    com.nanahoshi.audioplayer.ui.components.DlsiteDownloadDialog(graph.dlsiteDownloadRequests)
    val messages = graph.messages
    val message by messages.collectAsStateWithLifecycle()
    val biliImportPrompt by graph.biliImportRequests.prompt.collectAsStateWithLifecycle()
    com.nanahoshi.audioplayer.ui.bilibili.BiliImportDialog(graph.biliImportRequests, biliImportPrompt)
    val sleepFinished by graph.player.sleepFinished
        .collectAsStateWithLifecycle()
    val clipboardController = clipboardImport.controller
    val pendingLookup = clipboardController.pendingLookup
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val clipboardLookupHint = stringResource(R.string.clipboard_lookup_hint)
    val clipboardLookupAction = stringResource(R.string.clipboard_lookup_action)
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        val long = text.length > 30 || text.count { it == '；' || it == '。' } >= 2
        snackbar.showSnackbar(
            message = text,
            withDismissAction = long,
            duration = if (long) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        messages.value = null
    }
    val jaPlaybackFailure by graph.player.jaPlaybackFailure.collectAsStateWithLifecycle()
    LaunchedEffect(jaPlaybackFailure, route) {
        val failure = jaPlaybackFailure ?: return@LaunchedEffect
        if (route == null || route == Routes.NowPlaying) return@LaunchedEffect
        snackbar.showSnackbar(context.getString(failure.messageRes), withDismissAction = true, duration = SnackbarDuration.Long)
        graph.player.dismissJaPlaybackFailure(failure)
    }
    LaunchedEffect(pendingLookup, sleepFinished, lifecycle) {
        if (pendingLookup == null || sleepFinished) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val result = snackbar.showSnackbar(
                message = clipboardLookupHint,
                actionLabel = clipboardLookupAction,
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
            clipboardController.resolveLookup(pendingLookup, confirmed = result == SnackbarResult.ActionPerformed)
        }
    }
    if (sleepFinished) {
        InfoNoticeDialog(
            message = stringResource(R.string.sleep_timer_finished),
            confirmLabel = stringResource(R.string.player_got_it),
            onDismiss = graph.player::acknowledgeSleepFinished,
            dismissOnOutside = false,
        )
    }
    if (!sleepFinished && biliImportPrompt == null) clipboardController.state?.takeUnless {
        it.stage == ClipboardImportStage.CHOOSING_BILI_PARTS
    }?.let { state ->
        ClipboardImportDialog(state, clipboardController::dismiss, clipboardController::confirm) { workno ->
            val article = state.preview.jaArticle?.post
            val external = state.preview.jaBrowserUrl
            val verification = state.preview.jaVerificationPage
            if (verification != null) {
                clipboardController.beginJaVerification(state.preview)?.let { token ->
                    navController.navigate(Routes.japaneseAsmrVerification(verification, operation = "CLIPBOARD_IMPORT", token = token))
                }
            } else if (external != null) {
                clipboardController.dismiss()
                context.openJapaneseAsmrInBrowser(external)
            } else {
                clipboardController.dismiss()
                navController.navigate(Routes.onlineWork(article?.rj ?: workno, jaPost = article?.postId)) { launchSingleTop = true }
            }
        }
    }
}
