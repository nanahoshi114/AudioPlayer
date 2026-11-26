package com.nanahoshi.audioplayer.ui.asmr

import com.nanahoshi.audioplayer.text.resolveUserError
import com.nanahoshi.audioplayer.text.UserFacingException
import com.nanahoshi.audioplayer.asmr.AsmrException
import com.nanahoshi.audioplayer.asmr.AsmrErrorKind
import com.nanahoshi.audioplayer.R
import androidx.annotation.StringRes
import com.nanahoshi.audioplayer.text.TextRef
import androidx.compose.ui.res.stringResource
import com.nanahoshi.audioplayer.japaneseasmr.JaArticle
import com.nanahoshi.audioplayer.japaneseasmr.JaMediaRef
import com.nanahoshi.audioplayer.japaneseasmr.JaPostRef
import com.nanahoshi.audioplayer.japaneseasmr.JapaneseAsmrClient
import com.nanahoshi.audioplayer.japaneseasmr.JapaneseAsmrException
import com.nanahoshi.audioplayer.ui.components.JapaneseAsmrVerificationState
import com.nanahoshi.audioplayer.ui.components.openJapaneseAsmrInBrowser
import com.nanahoshi.audioplayer.data.storageKey
import com.nanahoshi.audioplayer.data.sanitizeEntryName
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.activity.compose.BackHandler
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nanahoshi.audioplayer.bilibili.BilibiliException
import com.nanahoshi.audioplayer.bilibili.QrPoll
import com.nanahoshi.audioplayer.bilibili.qrBitmap
import com.nanahoshi.audioplayer.data.toHttps
import com.nanahoshi.audioplayer.dlsite.DlsiteClient
import com.nanahoshi.audioplayer.asmr.AsmrCatalogItem
import com.nanahoshi.audioplayer.asmr.AsmrNode
import com.nanahoshi.audioplayer.asmr.AsmrWork
import com.nanahoshi.audioplayer.asmr.CatalogLookup
import com.nanahoshi.audioplayer.asmr.WorkFacts
import com.nanahoshi.audioplayer.data.LibrarySort
import com.nanahoshi.audioplayer.data.LocalAudio
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.FileKind
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.LibraryFileEntity
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.data.previewKind
import com.nanahoshi.audioplayer.data.rawFileKey
import com.nanahoshi.audioplayer.data.isAsmrAudio
import com.nanahoshi.audioplayer.data.isContainer
import com.nanahoshi.audioplayer.data.isSubtitleFile
import com.nanahoshi.audioplayer.data.sortedForLibrary
import com.nanahoshi.audioplayer.graph
import com.nanahoshi.audioplayer.playback.pickSubtitleName
import com.nanahoshi.audioplayer.ui.CollapsingTop
import com.nanahoshi.audioplayer.ui.canCollapseHeader
import com.nanahoshi.audioplayer.ui.components.AppTopBar
import com.nanahoshi.audioplayer.ui.components.AccountCard
import com.nanahoshi.audioplayer.ui.components.BadgeItem
import com.nanahoshi.audioplayer.ui.components.ConfirmActionDialog
import com.nanahoshi.audioplayer.ui.components.CoverImage
import com.nanahoshi.audioplayer.ui.components.DropdownFilterButton
import com.nanahoshi.audioplayer.ui.components.ErrorState
import com.nanahoshi.audioplayer.ui.components.EmptyState
import com.nanahoshi.audioplayer.ui.components.ImmersiveTopBar
import com.nanahoshi.audioplayer.ui.components.ImmersiveWorkScaffold
import com.nanahoshi.audioplayer.ui.components.InfoNoticeDialog
import com.nanahoshi.audioplayer.ui.components.LoadMoreOnScrollEnd
import com.nanahoshi.audioplayer.ui.components.LoadingState
import com.nanahoshi.audioplayer.ui.components.SecretTextField
import com.nanahoshi.audioplayer.ui.components.SectionHeader
import com.nanahoshi.audioplayer.ui.components.SegmentedTabBar
import com.nanahoshi.audioplayer.ui.components.WorkHeader
import com.nanahoshi.audioplayer.ui.components.WorkCard
import com.nanahoshi.audioplayer.ui.components.SkeletonWorkList
import com.nanahoshi.audioplayer.ui.components.toWorkFacts
import com.nanahoshi.audioplayer.ui.theme.LocalReduceMotion
import com.nanahoshi.audioplayer.ui.theme.Motion
import com.nanahoshi.audioplayer.ui.theme.enterSpec
import com.nanahoshi.audioplayer.ui.theme.exitSpec
import com.nanahoshi.audioplayer.ui.library.LibraryEntry
import com.nanahoshi.audioplayer.ui.library.LibraryCrumb
import com.nanahoshi.audioplayer.ui.library.LoadedWorkFolder
import com.nanahoshi.audioplayer.ui.library.TrackMetaRow
import com.nanahoshi.audioplayer.ui.library.WorkFolderRequest
import com.nanahoshi.audioplayer.ui.library.observeFolderListing
import com.nanahoshi.audioplayer.ui.library.observeWorkPath
import com.nanahoshi.audioplayer.ui.library.observeWorkSubtitleTrackIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class MineViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
    var cookie by mutableStateOf("")
    var loggedIn by mutableStateOf(graph.dlsite.hasSession())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var webLoginVisible by mutableStateOf(false)
        private set
    var webLoginLoading by mutableStateOf(false)
        private set
    var webLoginError by mutableStateOf<String?>(null)
        private set
    private var webLoginJob: Job? = null
    var biliReady by mutableStateOf(graph.credentials.hasCookie())
        private set
    var biliCanRefresh by mutableStateOf(graph.credentials.hasBiliRefresh())
        private set
    val biliRefreshFailure = graph.credentials.biliRefreshFailure
    var qrVisible by mutableStateOf(false)
        private set
    var qrImage by mutableStateOf<Bitmap?>(null)
        private set
    var qrMessage by mutableStateOf("")
        private set
    var qrExpired by mutableStateOf(false)
        private set
    var qrFailed by mutableStateOf(false)
        private set
    private var qrJob: Job? = null
    private var qrGeneration = 0

    fun openWebLogin() {
        webLoginError = null
        webLoginVisible = true
    }

    fun dismissWebLogin() {
        webLoginJob?.cancel()
        webLoginJob = null
        webLoginVisible = false
        webLoginLoading = false
        webLoginError = null
    }

    fun completeWebLogin(cookie: String) {
        if (webLoginLoading) return
        webLoginJob?.cancel()
        webLoginJob = viewModelScope.launch {
            webLoginLoading = true
            webLoginError = null
            try {
                graph.dlsite.loginWithCookie(cookie, loadPurchases = false)
                loggedIn = true
                error = null
                webLoginVisible = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                webLoginError = getApplication<Application>().resolveUserError(failure, R.string.online_login_failed)
            } finally {
                webLoginLoading = false
                webLoginJob = null
            }
        }
    }

    fun loginCookie() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                graph.dlsite.loginWithCookie(cookie)
                cookie = ""
                loggedIn = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = getApplication<Application>().resolveUserError(failure, R.string.online_login_failed)
            } finally {
                loading = false
            }
        }
    }

    fun logout() {
        graph.dlsite.logout()
        clearDlsiteWebCookies()
        loggedIn = false
    }

    fun savedBiliCookie(): String = graph.credentials.cookie().orEmpty()

    fun saveBili(value: String) {
        graph.credentials.saveCookie(value)
        biliReady = graph.credentials.hasCookie()
        biliCanRefresh = graph.credentials.hasBiliRefresh()
    }

    fun clearBili() {
        graph.credentials.clearCookie()
        biliReady = false
        biliCanRefresh = false
    }

    fun startBiliQr() {
        qrJob?.cancel()
        val generation = ++qrGeneration
        qrVisible = true
        qrImage = null
        qrExpired = false
        qrFailed = false
        qrMessage = getApplication<Application>().getString(R.string.mine_qr_getting)
        qrJob = viewModelScope.launch {
            try {
                val login = graph.bilibili.requestQrLogin()
                if (generation != qrGeneration) return@launch
                qrImage = qrBitmap(login.url)
                qrMessage = getApplication<Application>().getString(R.string.mine_qr_scan_hint)
                while (isActive) {
                    delay(2_000)
                    val state = graph.bilibili.pollQrLogin(login.key)
                    if (state == QrPoll.Confirmed) {
                        biliReady = true
                        biliCanRefresh = graph.credentials.hasBiliRefresh()
                        if (generation == qrGeneration) {
                            qrVisible = false
                            qrImage = null
                        }
                        return@launch
                    }
                    if (generation != qrGeneration) return@launch
                    when (state) {
                        QrPoll.Waiting -> Unit
                        QrPoll.Scanned -> qrMessage = getApplication<Application>().getString(R.string.mine_qr_scanned)
                        QrPoll.Expired -> {
                            qrExpired = true
                            qrMessage = getApplication<Application>().getString(R.string.mine_qr_expired)
                            return@launch
                        }
                        QrPoll.Confirmed -> Unit
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation != qrGeneration) return@launch
                qrFailed = true
                qrMessage = if (failure is BilibiliException) {
                    getApplication<Application>().resolveUserError(failure, R.string.online_qr_login_failed)
                } else {
                    getApplication<Application>().getString(R.string.online_bilibili_connection_failed)
                }
            }
        }
    }

    fun stopBiliQr() {
        qrGeneration++
        qrJob?.cancel()
        qrJob = null
        qrVisible = false
        qrImage = null
        qrExpired = false
        qrFailed = false
    }
}

@Composable
fun MineScreen(
    onOpenPurchases: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenYouTubeList: (String) -> Unit,
    viewModel: MineViewModel = viewModel(),
) {
    var biliHelp by remember { mutableStateOf(false) }
    var sessdataLogin by remember { mutableStateOf(false) }
    var confirmDlsiteLogout by remember { mutableStateOf(false) }
    var confirmBiliClear by remember { mutableStateOf(false) }
    val biliRefreshFailed by viewModel.biliRefreshFailure.collectAsStateWithLifecycle()
    if (viewModel.qrVisible) {
        BiliQrDialog(
            image = viewModel.qrImage,
            message = viewModel.qrMessage,
            expired = viewModel.qrExpired,
            failed = viewModel.qrFailed,
            onRefresh = viewModel::startBiliQr,
            onDismiss = viewModel::stopBiliQr,
        )
    }
    if (biliHelp) {
        BiliLoginHelpDialog(onDismiss = { biliHelp = false })
    }
    if (sessdataLogin) {
        BiliSessdataDialog(
            saved = viewModel.savedBiliCookie(),
            scanLogin = viewModel.biliCanRefresh,
            onSave = { value ->
                viewModel.saveBili(value)
                sessdataLogin = false
            },
            onDismiss = { sessdataLogin = false },
        )
    }
    if (viewModel.webLoginVisible) {
        DlsiteWebLoginDialog(
            loading = viewModel.webLoginLoading,
            error = viewModel.webLoginError,
            onComplete = viewModel::completeWebLogin,
            onDismiss = viewModel::dismissWebLogin,
        )
    }
    var cookieOpen by remember { mutableStateOf(false) }
    var showCookie by rememberSaveable { mutableStateOf(false) }
    val reduceCookieMotion = LocalReduceMotion.current
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(key = "dlsite") {
            AccountCard(title = "DLsite", loggedIn = viewModel.loggedIn) {
                if (!viewModel.loggedIn) {
                    Button(
                        onClick = viewModel::openWebLogin,
                        enabled = !viewModel.loading,
                        modifier = Modifier.padding(top = 12.dp),
                    ) { Text(stringResource(R.string.mine_dlsite_web_login)) }
                    Text(
                        stringResource(R.string.mine_dlsite_web_login_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    TextButton(onClick = { cookieOpen = !cookieOpen }) {
                        Text(stringResource(if (cookieOpen) R.string.mine_cookie_collapse else R.string.mine_cookie_use))
                    }
                    AnimatedVisibility(
                        visible = cookieOpen,
                        enter = fadeIn(enterSpec()) + expandVertically(enterSpec()),
                        exit = fadeOut(exitSpec()) + shrinkVertically(exitSpec()),
                    ) {
                        Column {
                            SecretTextField(
                                value = viewModel.cookie,
                                onValueChange = { viewModel.cookie = it },
                                label = stringResource(R.string.mine_dlsite_cookie_label),
                                visible = showCookie,
                                onToggleVisible = { showCookie = !showCookie },
                                singleLine = false,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            TextButton(
                                onClick = viewModel::loginCookie,
                                enabled = !viewModel.loading,
                            ) { Text(stringResource(R.string.mine_cookie_login)) }
                        }
                    }
                } else {
                    Button(
                        onClick = onOpenPurchases,
                        enabled = !viewModel.loading,
                        modifier = Modifier.padding(top = 12.dp),
                    ) { Text(stringResource(R.string.mine_view_purchases)) }
                    TextButton(onClick = viewModel::openWebLogin) { Text(stringResource(R.string.mine_relogin)) }
                    TextButton(onClick = { confirmDlsiteLogout = true }) { Text(stringResource(R.string.mine_logout)) }
                }
            }
        }
        viewModel.error?.let { message ->
            item(key = "dlsite-error") {
                Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
        }
        item(key = "youtube") {
            com.nanahoshi.audioplayer.ui.youtube.YouTubeAccountCard(onOpenYouTubeList)
        }
        item(key = "bilibili") {
            val biliBadge = when {
                !viewModel.biliReady -> stringResource(R.string.mine_bili_not_logged_in)
                viewModel.biliCanRefresh -> stringResource(R.string.mine_bili_badge_logged_in)
                else -> stringResource(R.string.mine_bili_badge_saved)
            }
            AccountCard(title = stringResource(R.string.library_badge_bilibili), loggedIn = viewModel.biliReady, badgeText = biliBadge) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            !viewModel.biliReady -> stringResource(R.string.mine_bili_not_logged_in_message)
                            viewModel.biliCanRefresh && biliRefreshFailed -> stringResource(R.string.mine_bili_refresh_failed)
                            viewModel.biliCanRefresh -> stringResource(R.string.mine_bili_qr_logged_in)
                            else -> stringResource(R.string.mine_bili_saved)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { biliHelp = true }) {
                        Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = stringResource(R.string.a11y_bili_login_help))
                    }
                }
                if (viewModel.biliReady) {
                    Button(onClick = onOpenFavorites, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.mine_view_favorites)) }
                    TextButton(onClick = viewModel::startBiliQr) { Text(stringResource(R.string.mine_qr_login)) }
                } else {
                    Button(onClick = viewModel::startBiliQr, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.mine_qr_login)) }
                    TextButton(onClick = onOpenFavorites, enabled = false) { Text(stringResource(R.string.mine_login_before_favorites)) }
                }
                TextButton(onClick = { sessdataLogin = true }) { Text(stringResource(R.string.mine_paste_login)) }
                TextButton(onClick = { confirmBiliClear = true }) { Text(stringResource(R.string.mine_logout)) }
            }
        }
    }
    if (confirmDlsiteLogout) {
        ConfirmActionDialog(
            title = stringResource(R.string.mine_dlsite_logout_title),
            message = stringResource(R.string.mine_dlsite_logout_message),
            confirmLabel = stringResource(R.string.mine_logout),
            onConfirm = { confirmDlsiteLogout = false; viewModel.logout() },
            onDismiss = { confirmDlsiteLogout = false },
        )
    }
    if (confirmBiliClear) {
        ConfirmActionDialog(
            title = stringResource(R.string.mine_bili_logout_title),
            message = stringResource(
                if (viewModel.biliCanRefresh) R.string.mine_bili_logout_message_qr
                else R.string.mine_bili_logout_message_cookie,
            ),
            confirmLabel = stringResource(R.string.mine_logout),
            onConfirm = { confirmBiliClear = false; viewModel.clearBili() },
            onDismiss = { confirmBiliClear = false },
        )
    }
}

@Composable
private fun BiliLoginHelpDialog(onDismiss: () -> Unit) {
    InfoNoticeDialog(
        title = stringResource(R.string.mine_bili_login_title),
        message = "${stringResource(R.string.mine_bili_login_help)}\n\n${stringResource(R.string.mine_bili_login_help_qr)}",
        onDismiss = onDismiss,
        scrollable = true,
    )
}

@Composable
private fun BiliSessdataDialog(
    saved: String,
    scanLogin: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(saved) }
    var confirming by remember { mutableStateOf(false) }
    var showSecret by rememberSaveable { mutableStateOf(false) }
    fun submit() {
        val value = text.trim()
        if (value.isEmpty()) return
        if (scanLogin && value != saved.trim()) {
            confirming = true
        } else {
            onSave(value)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mine_paste_login_title)) },
        text = {
            SecretTextField(
                value = text,
                onValueChange = { text = it },
                label = stringResource(R.string.mine_cookie_label),
                visible = showSecret,
                onToggleVisible = { showSecret = !showSecret },
                singleLine = false,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = ::submit, enabled = text.isNotBlank()) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
    if (confirming) {
        ConfirmActionDialog(
            title = stringResource(R.string.mine_replace_login_title),
            message = stringResource(R.string.mine_replace_login_message),
            confirmLabel = stringResource(R.string.common_save),
            destructive = false,
            onConfirm = { onSave(text.trim()) },
            onDismiss = { confirming = false },
        )
    }
}

@Composable
private fun BiliQrDialog(
    image: Bitmap?,
    message: String,
    expired: Boolean,
    failed: Boolean,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val canRetry = expired || failed
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mine_qr_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (image != null) {
                    // 白色底与静区保证二维码在深色主题下仍可被 B 站 App 识别。
                    Surface(
                        color = Color.White,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Box(Modifier.padding(12.dp)) {
                            Image(
                                bitmap = image.asImageBitmap(),
                                contentDescription = stringResource(R.string.a11y_bili_qr),
                                modifier = Modifier.size(220.dp),
                            )
                        }
                    }
                } else if (!canRetry) {
                    Box(Modifier.size(244.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(48.dp))
                    }
                }
                if (message.isNotBlank()) {
                    Text(message, modifier = Modifier.padding(top = 12.dp))
                }
            }
        },
        confirmButton = {
            if (canRetry) {
                TextButton(onClick = onRefresh) { Text(stringResource(R.string.mine_qr_refresh)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
