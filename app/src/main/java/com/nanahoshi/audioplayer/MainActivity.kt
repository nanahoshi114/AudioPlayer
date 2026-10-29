package com.nanahoshi.audioplayer

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import com.nanahoshi.audioplayer.data.AccentMode
import com.nanahoshi.audioplayer.data.ThemeMode
import com.nanahoshi.audioplayer.ui.settings.AppearanceScreen
import com.nanahoshi.audioplayer.ui.theme.ThemeAccent
import com.nanahoshi.audioplayer.ui.theme.presetAccent
import com.nanahoshi.audioplayer.ui.theme.rememberCoverAccent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.ui.components.LocalTopBarOverride
import com.nanahoshi.audioplayer.ui.theme.LocalReduceMotion
import com.nanahoshi.audioplayer.ui.theme.Motion
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nanahoshi.audioplayer.ui.download.DownloadScreen
import com.nanahoshi.audioplayer.ui.download.StoredFilesScreen
import com.nanahoshi.audioplayer.ui.asmr.MineScreen
import com.nanahoshi.audioplayer.ui.asmr.PurchasesScreen
import com.nanahoshi.audioplayer.ui.asmr.OnlineSearchScreen
import com.nanahoshi.audioplayer.ui.asmr.OnlineWorkScreen
import com.nanahoshi.audioplayer.ui.library.FavoriteScreen
import com.nanahoshi.audioplayer.ui.library.FilePreviewScreen
import com.nanahoshi.audioplayer.ui.library.RemotePreviewScreen
import com.nanahoshi.audioplayer.ui.library.LibraryScreen
import com.nanahoshi.audioplayer.ui.library.LibraryWorkScreen
import com.nanahoshi.audioplayer.ui.player.MiniPlayer
import com.nanahoshi.audioplayer.ui.player.NowPlayingScreen
import com.nanahoshi.audioplayer.ui.playlist.PlaylistDetailScreen
import com.nanahoshi.audioplayer.ui.playlist.PlaylistListScreen
import com.nanahoshi.audioplayer.ui.settings.CacheScreen
import com.nanahoshi.audioplayer.ui.settings.ModelSettingsScreen
import com.nanahoshi.audioplayer.ui.settings.SettingsScreen
import com.nanahoshi.audioplayer.ui.theme.AudioPlayerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                PlayerApp()
            }
        }
    }
}

private object Routes {
    const val Library = "library"
    const val LibraryWork = "library/work/{folderId}"
    fun libraryWork(folderId: Long) = "library/work/$folderId"
    const val Playlists = "playlists"
    const val Online = "online"
    const val OnlineWork = "online/work/{sourceId}"
    fun onlineWork(sourceId: String) = "online/work/$sourceId"
    const val Mine = "mine"
    const val Settings = "settings"
    const val ModelSettings = "settings/models"
    const val CacheSettings = "settings/cache"
    const val Appearance = "settings/appearance"
    const val Purchases = "purchases"
    const val NowPlaying = "now"
    const val Downloads = "downloads"
    const val Stored = "stored"
    const val Preview = "preview/{fileId}"
    fun preview(fileId: Long) = "preview/$fileId"
    const val RemotePreview = "preview/remote"
    const val Favorites = "favorites?folderId={folderId}"
    fun favorites(folderId: Long?) = "favorites?folderId=${folderId ?: -1L}"
    const val Playlist = "playlist/{id}"
    fun playlist(id: Long) = "playlist/$id"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerApp() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    var lastTab by remember { mutableStateOf(Routes.Library) }
    SideEffect {
        if (isTab(route)) lastTab = route!!
    }
    val highlight = if (isTab(route)) route else lastTab
    val showTopBar = isTab(route)
    val showBottomDock = showTopBar || route == Routes.OnlineWork || route == Routes.LibraryWork
    val context = LocalContext.current
    val graph = (context.applicationContext as AudioPlayerApplication).graph
    val messages = graph.messages
    val message by messages.collectAsStateWithLifecycle()
    val sleepFinished by graph.player.sleepFinished
        .collectAsStateWithLifecycle()
    val sizeConfirm by graph.whisper.sizeConfirmJob.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val topBarOverride = remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    val reduceMotion = LocalReduceMotion.current
    val downloads by graph.downloads.observe().collectAsStateWithLifecycle(emptyList())
    val whisperJobs by graph.whisper.observe().collectAsStateWithLifecycle(emptyList())
    val translations by graph.db.translations().observe().collectAsStateWithLifecycle(emptyList())
    val activeTasks = downloads.count { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING } +
        whisperJobs.count { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING } +
        translations.count { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING }
    val fade = if (reduceMotion) snap<Float>() else tween<Float>(220, easing = Motion.enter)
    val fadeAway = if (reduceMotion) snap<Float>() else tween<Float>(Motion.short, easing = Motion.exit)
    val slide = if (reduceMotion) snap<IntOffset>() else tween<IntOffset>(Motion.medium, easing = Motion.enter)
    val slideAway = if (reduceMotion) snap<IntOffset>() else tween<IntOffset>(Motion.medium, easing = Motion.exit)
    val barEnter = if (reduceMotion) EnterTransition.None else slideInVertically(slide) { -it } + fadeIn(fade)
    val barExit = if (reduceMotion) ExitTransition.None else slideOutVertically(slideAway) { -it } + fadeOut(fadeAway)
    val dockEnter = if (reduceMotion) EnterTransition.None else slideInVertically(slide) { it } + fadeIn(fade)
    val dockExit = if (reduceMotion) ExitTransition.None else slideOutVertically(slideAway) { it } + fadeOut(fadeAway)
    val title = when (route) {
        Routes.Playlists -> "播放列表"
        Routes.Online -> "在线搜索"
        Routes.Mine -> "我的"
        Routes.Settings -> "设置"
        else -> "音频库"
    }
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
        snackbar.showSnackbar(text)
        messages.value = null
    }
    if (sleepFinished) {
        AlertDialog(
            onDismissRequest = {},
            text = { Text("倒计时已结束") },
            confirmButton = {
                TextButton(onClick = {
                    graph.player.acknowledgeSleepFinished()
                }) { Text("确定") }
            },
        )
    }
    if (sizeConfirm != null) {
        AlertDialog(
            onDismissRequest = { graph.whisper.declineSizeConfirm() },
            text = { Text("检测到填入的可能是在线模型，当前文件大小超过25MB，可能会被拒绝，是否仍发送？") },
            confirmButton = {
                TextButton(onClick = { graph.whisper.acceptSizeConfirm() }) { Text("仍要发送") }
            },
            dismissButton = {
                TextButton(onClick = { graph.whisper.declineSizeConfirm() }) { Text("取消") }
            },
        )
    }
    SharedTransitionLayout {
    val shared = this
    CompositionLocalProvider(LocalTopBarOverride provides topBarOverride) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            AnimatedVisibility(visible = showTopBar, enter = barEnter, exit = barExit) {
                val selection = topBarOverride.value
                if (route == Routes.Library && selection != null) {
                    selection()
                } else {
                    TopAppBar(
                        title = {
                            AnimatedContent(
                                targetState = title,
                                transitionSpec = { fadeIn(fade) togetherWith fadeOut(fadeAway) },
                                label = "tabTitle",
                            ) { value ->
                                Text(value)
                            }
                        },
                        actions = {
                            IconButton(onClick = { navController.navigate(Routes.Downloads) }) {
                                BadgedBox(
                                    badge = {
                                        if (activeTasks > 0) {
                                            Badge {
                                                Text(if (activeTasks > 99) "99+" else activeTasks.toString())
                                            }
                                        }
                                    },
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = "下载")
                                }
                            }
                        },
                    )
                }
            }
        },
        bottomBar = {
            AnimatedVisibility(visible = showBottomDock, enter = dockEnter, exit = dockExit) {
                val dockScope = this
                Column {
                    MiniPlayer(
                        onOpen = { navController.navigate(Routes.NowPlaying) },
                        coverModifier = with(shared) {
                            Modifier.sharedElement(
                                rememberSharedContentState(key = "player-cover"),
                                animatedVisibilityScope = dockScope,
                            )
                        },
                    )
                    NavigationBar {
                        NavigationBarItem(
                            selected = highlight == Routes.Library,
                            onClick = {
                                navController.navigate(Routes.Library) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(Icons.Default.LibraryMusic, contentDescription = null) },
                            label = { Text("音频库") },
                        )
                        NavigationBarItem(
                            selected = highlight == Routes.Playlists,
                            onClick = {
                                navController.navigate(Routes.Playlists) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) },
                            label = { Text("播放列表") },
                        )
                        NavigationBarItem(
                            selected = highlight == Routes.Online,
                            onClick = {
                                navController.navigate(Routes.Online) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(Icons.Default.Search, contentDescription = null) },
                            label = { Text("在线搜索") },
                        )
                        NavigationBarItem(
                            selected = highlight == Routes.Mine,
                            onClick = {
                                navController.navigate(Routes.Mine) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(Icons.Default.Person, contentDescription = null) },
                            label = { Text("我的") },
                        )
                        NavigationBarItem(
                            selected = highlight == Routes.Settings,
                            onClick = {
                                navController.navigate(Routes.Settings) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                            label = { Text("设置") },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.Library,
            // 子页面自带顶栏，也会再垫一次状态栏高度；这里先声明已经垫过，避免顶部出现两份空白。
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            enterTransition = {
                if (reduceMotion) {
                    EnterTransition.None
                } else {
                    val from = initialState.destination.route
                    val to = targetState.destination.route
                    when {
                        to == Routes.NowPlaying -> slideInVertically(slide) { it } + fadeIn(fade)
                        isTab(from) && isTab(to) -> fadeIn(fade) + scaleIn(fade, initialScale = 0.96f)
                        else -> slideInHorizontally(slide) { it / 4 } + fadeIn(fade)
                    }
                }
            },
            exitTransition = {
                if (reduceMotion) {
                    ExitTransition.None
                } else {
                    val from = initialState.destination.route
                    val to = targetState.destination.route
                    when {
                        to == Routes.NowPlaying -> fadeOut(fadeAway)
                        isTab(from) && isTab(to) -> fadeOut(fadeAway) + scaleOut(fadeAway, targetScale = 0.98f)
                        else -> slideOutHorizontally(slideAway) { -it / 8 } + fadeOut(fadeAway)
                    }
                }
            },
            popEnterTransition = {
                if (reduceMotion) {
                    EnterTransition.None
                } else if (initialState.destination.route == Routes.NowPlaying) {
                    fadeIn(fade)
                } else {
                    slideInHorizontally(slide) { -it / 4 } + fadeIn(fade)
                }
            },
            popExitTransition = {
                if (reduceMotion) {
                    ExitTransition.None
                } else if (initialState.destination.route == Routes.NowPlaying) {
                    slideOutVertically(slideAway) { it } + fadeOut(fadeAway)
                } else {
                    slideOutHorizontally(slideAway) { it / 4 } + fadeOut(fadeAway)
                }
            },
        ) {
            composable(Routes.Library) {
                LibraryScreen(
                    onOpenPreview = { fileId -> navController.navigate(Routes.preview(fileId)) },
                    onOpenLibraryWork = { id -> navController.navigate(Routes.libraryWork(id)) },
                )
            }
            composable(
                route = Routes.LibraryWork,
                arguments = listOf(navArgument("folderId") { type = NavType.LongType }),
            ) { entry ->
                val id = entry.arguments?.getLong("folderId") ?: return@composable
                LibraryWorkScreen(
                    folderId = id,
                    onBack = { navController.popBackStack() },
                    onOpenPreview = { fileId -> navController.navigate(Routes.preview(fileId)) },
                    onOpenOnline = { sourceId -> navController.navigate(Routes.onlineWork(sourceId)) },
                )
            }
            composable(Routes.Playlists) {
                PlaylistListScreen(onOpen = { id -> navController.navigate(Routes.playlist(id)) })
            }
            composable(Routes.Online) {
                OnlineSearchScreen(onOpenWork = { sourceId -> navController.navigate(Routes.onlineWork(sourceId)) })
            }
            composable(
                route = Routes.OnlineWork,
                arguments = listOf(navArgument("sourceId") { type = NavType.StringType }),
            ) { entry ->
                val sourceId = entry.arguments?.getString("sourceId").orEmpty()
                OnlineWorkScreen(
                    sourceId,
                    onBack = { navController.popBackStack() },
                    onOpenPreview = { fileId -> navController.navigate(Routes.preview(fileId)) },
                    onOpenRemotePreview = { navController.navigate(Routes.RemotePreview) },
                )
            }
            composable(Routes.Mine) {
                MineScreen(
                    onOpenPurchases = { navController.navigate(Routes.Purchases) },
                    onOpenFavorites = { navController.navigate(Routes.favorites(null)) },
                )
            }
            composable(Routes.Settings) {
                SettingsScreen(
                    onOpenStored = { navController.navigate(Routes.Stored) },
                    onOpenModels = { navController.navigate(Routes.ModelSettings) },
                    onOpenCache = { navController.navigate(Routes.CacheSettings) },
                    onOpenAppearance = { navController.navigate(Routes.Appearance) },
                )
            }
            composable(Routes.Playlist) { entry ->
                val id = entry.arguments?.getString("id")?.toLongOrNull() ?: return@composable
                PlaylistDetailScreen(
                    id,
                    onBack = { navController.popBackStack() },
                    onDeleted = { navController.popBackStack() },
                )
            }
            composable(Routes.NowPlaying) {
                val playingScope = this
                NowPlayingScreen(
                    onBack = { navController.popBackStack() },
                    coverModifier = with(shared) {
                        Modifier.sharedElement(
                            rememberSharedContentState(key = "player-cover"),
                            animatedVisibilityScope = playingScope,
                        )
                    },
                )
            }
            composable(Routes.Downloads) { DownloadScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.Stored) {
                StoredFilesScreen(
                    onBack = { navController.popBackStack() },
                    onOpenWork = { sourceId -> navController.navigate(Routes.onlineWork(sourceId)) },
                )
            }
            composable(Routes.ModelSettings) {
                ModelSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.Appearance) {
                AppearanceScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.CacheSettings) {
                CacheScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.Purchases) {
                PurchasesScreen(
                    onBack = { navController.popBackStack() },
                    onOpenWork = { sourceId -> navController.navigate(Routes.onlineWork(sourceId)) },
                )
            }
            composable(Routes.RemotePreview) {
                RemotePreviewScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Routes.Preview,
                arguments = listOf(navArgument("fileId") { type = NavType.LongType }),
            ) { entry ->
                val fileId = entry.arguments?.getLong("fileId") ?: return@composable
                FilePreviewScreen(fileId, onBack = { navController.popBackStack() })
            }
            composable(
                route = Routes.Favorites,
                arguments = listOf(navArgument("folderId") { type = NavType.LongType; defaultValue = -1L }),
            ) { entry ->
                val folderId = entry.arguments?.getLong("folderId")?.takeIf { it >= 0 }
                FavoriteScreen(onBack = { navController.popBackStack() }, folderId = folderId)
            }
        }
    }
    }
    }
}

private fun isTab(route: String?) = route == Routes.Library || route == Routes.Playlists ||
    route == Routes.Online || route == Routes.Mine || route == Routes.Settings
