package com.nanahoshi.audioplayer

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.TranslateMark
import com.nanahoshi.audioplayer.ui.asmr.OnlineSearchSource
import com.nanahoshi.audioplayer.ui.asmr.PendingOnlineSearch
import com.nanahoshi.audioplayer.ui.components.LocalActiveTopBarOwner
import com.nanahoshi.audioplayer.ui.components.LocalTopBarOverride
import com.nanahoshi.audioplayer.ui.components.TopBarOverrideController
import com.nanahoshi.audioplayer.ui.components.libraryTopBarOwnerKey
import com.nanahoshi.audioplayer.ui.importing.ClipboardImportViewModel
import com.nanahoshi.audioplayer.ui.player.MiniPlayer
import com.nanahoshi.audioplayer.ui.theme.LocalReduceMotion
import com.nanahoshi.audioplayer.ui.theme.Motion
import com.nanahoshi.audioplayer.ui.theme.enterSpec
import com.nanahoshi.audioplayer.ui.theme.exitSpec
import kotlinx.coroutines.flow.MutableStateFlow

/** 主界面共用的转场规格；减弱动效时顶栏与底栏转场为 None。 */
internal class ShellMotion(
    val fade: FiniteAnimationSpec<Float>,
    val fadeAway: FiniteAnimationSpec<Float>,
    val slide: FiniteAnimationSpec<IntOffset>,
    val slideAway: FiniteAnimationSpec<IntOffset>,
    val barEnter: EnterTransition,
    val barExit: ExitTransition,
    val dockEnter: EnterTransition,
    val dockExit: ExitTransition,
)

@Composable
internal fun rememberShellMotion(reduceMotion: Boolean): ShellMotion {
    val fade = enterSpec<Float>(Motion.short)
    val fadeAway = exitSpec<Float>(Motion.short)
    val slide = enterSpec<IntOffset>(Motion.medium)
    val slideAway = exitSpec<IntOffset>(Motion.medium)
    val barEnter = if (reduceMotion) EnterTransition.None else slideInVertically(slide) { -it } + fadeIn(fade)
    val barExit = if (reduceMotion) ExitTransition.None else slideOutVertically(slideAway) { -it } + fadeOut(fadeAway)
    val dockEnter = if (reduceMotion) EnterTransition.None else slideInVertically(slide) { it } + fadeIn(fade)
    val dockExit = if (reduceMotion) ExitTransition.None else slideOutVertically(slideAway) { it } + fadeOut(fadeAway)
    return ShellMotion(fade, fadeAway, slide, slideAway, barEnter, barExit, dockEnter, dockExit)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerApp(
    clipboardImport: ClipboardImportViewModel,
    openTaskPage: MutableStateFlow<String?>,
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStack.collectAsStateWithLifecycle()
    val currentEntry = backStack.lastOrNull()
    val route = currentEntry?.destination?.route
    LaunchedEffect(route, navController, openTaskPage) {
        if (route == null) return@LaunchedEffect
        openTaskPage.collect { destination ->
            if (destination != null) {
                openTaskPage.value = null
                navController.navigate(destination) { launchSingleTop = true }
            }
        }
    }
    val tabInBackStack = activeTabGraph(backStack.map { it.destination.route })
    var lastTabGraph by rememberSaveable { mutableStateOf(TabGraphs.Library) }
    SideEffect {
        if (tabInBackStack != null) lastTabGraph = tabInBackStack
    }
    val activeGraph = tabInBackStack ?: lastTabGraph
    val showTopBar = isTab(route)
    val showBottomDock = showBottomDock(route)
    BackHandler(enabled = returnsToLibraryOnBack(route)) {
        if (returnsToLibraryOnBack(navController.currentDestination?.route)) {
            navController.navigateTab(TabGraphs.Library, Routes.Library)
            if (navController.currentDestination?.route != Routes.Library) {
                navController.popBackStack(Routes.Library, inclusive = false)
            }
        }
    }
    val snackbar = remember { SnackbarHostState() }
    AppGlobalOverlays(navController, route, snackbar, clipboardImport)
    val clipboardController = clipboardImport.controller
    var onlineSource by rememberSaveable { mutableStateOf(OnlineSearchSource.WORKS) }
    var pendingOnlineSearch by remember { mutableStateOf<PendingOnlineSearch?>(null) }
    var nextSearchId by remember { mutableLongStateOf(0L) }
    fun openOnlineSearch(create: (Long) -> PendingOnlineSearch) {
        val request = create(++nextSearchId)
        pendingOnlineSearch = request
        onlineSource = when (request) {
            is PendingOnlineSearch.WorkPiece -> OnlineSearchSource.WORKS
            is PendingOnlineSearch.BiliUploader -> OnlineSearchSource.BILIBILI
        }
        navController.navigateTab(TabGraphs.Online, Routes.Online)
        if (navController.currentDestination?.route != Routes.Online) {
            navController.popBackStack(Routes.Online, inclusive = false)
        }
    }
    val topBarOverride = remember { TopBarOverrideController() }
    val activeTopBarOwner = currentEntry?.let { entry ->
        when (route) {
            Routes.Library -> libraryTopBarOwnerKey(entry.id)
            Routes.Online -> onlineSource.topBarOwnerKey(entry.id)
            else -> null
        }
    }
    val reduceMotion = LocalReduceMotion.current
    val motion = rememberShellMotion(reduceMotion)
    val title = stringResource(
        when (route) {
            Routes.Playlists -> R.string.nav_playlists
            Routes.Online -> R.string.nav_online
            Routes.Mine -> R.string.nav_mine
            Routes.Settings -> R.string.nav_settings
            else -> R.string.nav_library
        },
    )
    SharedTransitionLayout {
    CompositionLocalProvider(
        LocalTopBarOverride provides topBarOverride,
        LocalActiveTopBarOwner provides activeTopBarOwner,
    ) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            MainTopBar(
                visible = showTopBar,
                motion = motion,
                title = title,
                topBarOverride = topBarOverride,
                activeTopBarOwner = activeTopBarOwner,
                navController = navController,
            )
        },
        bottomBar = {
            MainBottomDock(
                visible = showBottomDock,
                motion = motion,
                navController = navController,
                activeGraph = activeGraph,
            )
        },
    ) { padding ->
        val hostPadding = if (isEdgeToEdgeTopRoute(route)) {
            val direction = LocalLayoutDirection.current
            PaddingValues(
                start = padding.calculateStartPadding(direction),
                top = 0.dp,
                end = padding.calculateEndPadding(direction),
                bottom = padding.calculateBottomPadding(),
            )
        } else {
            padding
        }
        AppNavHost(
            navController = navController,
            hostPadding = hostPadding,
            motion = motion,
            onlineSource = onlineSource,
            onOnlineSourceChange = { onlineSource = it },
            pendingOnlineSearch = pendingOnlineSearch,
            onConsumeOnlineSearch = { id -> if (pendingOnlineSearch?.id == id) pendingOnlineSearch = null },
            openOnlineSearch = { create -> openOnlineSearch(create) },
            clipboardController = clipboardController,
        )
    }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTopBar(
    visible: Boolean,
    motion: ShellMotion,
    title: String,
    topBarOverride: TopBarOverrideController,
    activeTopBarOwner: String?,
    navController: NavController,
) {
    val graph = (LocalContext.current.applicationContext as AudioPlayerApplication).graph
    val downloads by graph.downloads.observe().collectAsStateWithLifecycle(emptyList())
    val whisperJobs by graph.whisper.observe().collectAsStateWithLifecycle(emptyList())
    val translations by graph.db.translations().observe().collectAsStateWithLifecycle(emptyList())
    val activeTasks = downloads.count {
        it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING || it.pendingBiliSubtitle == true
    } +
        whisperJobs.count {
            it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING ||
                TranslateMark.isActive(it.translateStatus)
        } +
        translations.count { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING }
    val fade = motion.fade
    val fadeAway = motion.fadeAway
    AnimatedVisibility(visible = visible, enter = motion.barEnter, exit = motion.barExit) {
        val selection = topBarOverride.entry?.takeIf { it.ownerKey == activeTopBarOwner }
        if (selection != null) {
            key(selection.ownerKey) { selection.content() }
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
                            Icon(
                                Icons.Default.Download,
                                contentDescription = if (activeTasks > 0) {
                                    stringResource(R.string.common_download_tasks_active, activeTasks)
                                } else {
                                    stringResource(R.string.common_download_tasks)
                                },
                            )
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun SharedTransitionScope.MainBottomDock(
    visible: Boolean,
    motion: ShellMotion,
    navController: NavController,
    activeGraph: String,
) {
    val shared = this
    AnimatedVisibility(visible = visible, enter = motion.dockEnter, exit = motion.dockExit) {
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
                    selected = activeGraph == TabGraphs.Library,
                    onClick = { navController.navigateTab(TabGraphs.Library, Routes.Library) },
                    icon = { Icon(Icons.Default.LibraryMusic, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_library)) },
                )
                NavigationBarItem(
                    selected = activeGraph == TabGraphs.Playlists,
                    onClick = { navController.navigateTab(TabGraphs.Playlists, Routes.Playlists) },
                    icon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_playlists)) },
                )
                NavigationBarItem(
                    selected = activeGraph == TabGraphs.Online,
                    onClick = { navController.navigateTab(TabGraphs.Online, Routes.Online) },
                    icon = { Icon(Icons.Default.Search, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_online)) },
                )
                NavigationBarItem(
                    selected = activeGraph == TabGraphs.Mine,
                    onClick = { navController.navigateTab(TabGraphs.Mine, Routes.Mine) },
                    icon = { Icon(Icons.Default.Person, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_mine)) },
                )
                NavigationBarItem(
                    selected = activeGraph == TabGraphs.Settings,
                    onClick = { navController.navigateTab(TabGraphs.Settings, Routes.Settings) },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_settings)) },
                )
            }
        }
    }
}
