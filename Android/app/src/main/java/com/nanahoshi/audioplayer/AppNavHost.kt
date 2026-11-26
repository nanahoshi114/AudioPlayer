package com.nanahoshi.audioplayer

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.navigation
import com.nanahoshi.audioplayer.data.ClipboardImportController
import com.nanahoshi.audioplayer.ui.asmr.JapaneseAsmrVerificationScreen
import com.nanahoshi.audioplayer.ui.asmr.MineScreen
import com.nanahoshi.audioplayer.ui.asmr.OnlineSearchScreen
import com.nanahoshi.audioplayer.ui.asmr.OnlineSearchSource
import com.nanahoshi.audioplayer.ui.asmr.OnlineWorkScreen
import com.nanahoshi.audioplayer.ui.asmr.PendingOnlineSearch
import com.nanahoshi.audioplayer.ui.asmr.PurchasesScreen
import com.nanahoshi.audioplayer.ui.components.libraryTopBarOwnerKey
import com.nanahoshi.audioplayer.ui.download.DownloadScreen
import com.nanahoshi.audioplayer.ui.download.StoredFilesScreen
import com.nanahoshi.audioplayer.ui.library.FavoriteScreen
import com.nanahoshi.audioplayer.ui.library.FilePreviewScreen
import com.nanahoshi.audioplayer.ui.library.LibraryScreen
import com.nanahoshi.audioplayer.ui.library.LibraryWorkScreen
import com.nanahoshi.audioplayer.ui.library.RemotePreviewScreen
import com.nanahoshi.audioplayer.ui.player.NowPlayingScreen
import com.nanahoshi.audioplayer.ui.playlist.PlaylistDetailScreen
import com.nanahoshi.audioplayer.ui.playlist.PlaylistListScreen
import com.nanahoshi.audioplayer.ui.settings.AboutScreen
import com.nanahoshi.audioplayer.ui.settings.AppearanceScreen
import com.nanahoshi.audioplayer.ui.settings.CacheScreen
import com.nanahoshi.audioplayer.ui.settings.ModelSettingsScreen
import com.nanahoshi.audioplayer.ui.settings.SettingsScreen
import com.nanahoshi.audioplayer.ui.theme.LocalReduceMotion
import kotlinx.coroutines.launch

/** 主界面路由表及页面转场；状态由 [PlayerApp] 持有并传入。 */
@Composable
internal fun SharedTransitionScope.AppNavHost(
    navController: NavHostController,
    hostPadding: PaddingValues,
    motion: ShellMotion,
    onlineSource: OnlineSearchSource,
    onOnlineSourceChange: (OnlineSearchSource) -> Unit,
    pendingOnlineSearch: PendingOnlineSearch?,
    onConsumeOnlineSearch: (Long) -> Unit,
    openOnlineSearch: ((Long) -> PendingOnlineSearch) -> Unit,
    clipboardController: ClipboardImportController,
) {
    val shared = this
    val reduceMotion = LocalReduceMotion.current
    val fade = motion.fade
    val fadeAway = motion.fadeAway
    val slide = motion.slide
    val slideAway = motion.slideAway
    val context = LocalContext.current
    val graph = (context.applicationContext as AudioPlayerApplication).graph
    NavHost(
        navController = navController,
        startDestination = TabGraphs.Library,
        modifier = Modifier.padding(hostPadding).consumeWindowInsets(hostPadding),
        enterTransition = {
            if (reduceMotion) {
                EnterTransition.None
            } else {
                val from = initialState.destination.route
                val to = targetState.destination.route
                when {
                    to == Routes.NowPlaying -> slideInVertically(slide) { it } + fadeIn(fade)
                    isTab(from) && isTab(to) -> fadeIn(fade)
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
                    isTab(from) && isTab(to) -> fadeOut(fadeAway)
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
        navigation(route = TabGraphs.Library, startDestination = Routes.Library) {
            composable(Routes.Library) { entry ->
                LibraryScreen(
                    topBarOwnerKey = libraryTopBarOwnerKey(entry.id),
                    onOpenPreview = { fileId -> navController.navigate(Routes.preview(fileId)) },
                    onOpenLibraryWork = { id -> navController.navigate(Routes.libraryWork(id)) },
                    onOpenBiliVideo = { id -> navController.navigate(Routes.biliVideo(id)) },
                    onVerifyJaDownload = { token -> navController.navigate(Routes.jaDownloadVerification(token)) },
                    onImportOnline = {
                        navController.navigateTab(TabGraphs.Online, Routes.Online)
                        if (navController.currentDestination?.route != Routes.Online) {
                            navController.popBackStack(Routes.Online, inclusive = false)
                        }
                    },
                )
            }
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
                onVerifyJaDownload = { token -> navController.navigate(Routes.jaDownloadVerification(token)) },
                onVerifyJaRefresh = { token -> navController.navigate(Routes.japaneseAsmrVerification(operation = "REFRESH", token = token)) },
                onSearchWorkPiece = { piece -> openOnlineSearch { PendingOnlineSearch.WorkPiece(it, piece) } },
            )
        }
        composable(Routes.BiliVideo, arguments = listOf(navArgument("folderId") { type = NavType.LongType })) { entry ->
            val id = entry.arguments?.getLong("folderId") ?: return@composable
            com.nanahoshi.audioplayer.ui.bilibili.BiliVideoScreen(
                id,
                onBack = { if (navController.currentBackStackEntry?.id == entry.id) navController.popBackStack() },
                onSearchUploader = { name -> openOnlineSearch { PendingOnlineSearch.BiliUploader(it, name) } },
            )
        }
        composable(Routes.BiliCollection, arguments = listOf(navArgument("ref") { type = NavType.StringType })) { entry ->
            val ref = runCatching {
                com.nanahoshi.audioplayer.bilibili.biliLibraryJson.decodeFromString(
                    com.nanahoshi.audioplayer.bilibili.BiliCollection.serializer(), entry.arguments?.getString("ref").orEmpty())
            }.getOrNull()
            if (ref != null) com.nanahoshi.audioplayer.ui.bilibili.BiliCollectionScreen(
                initial = ref,
                onBack = { if (navController.currentBackStackEntry?.id == entry.id) navController.popBackStack() },
            )
            else com.nanahoshi.audioplayer.ui.bilibili.BiliCollectionRouteError(onBack = { navController.popBackStack() })
        }
        navigation(route = TabGraphs.Playlists, startDestination = Routes.Playlists) {
            composable(Routes.Playlists) {
                PlaylistListScreen(onOpen = { id -> navController.navigate(Routes.playlist(id)) })
            }
        }
        navigation(route = TabGraphs.Online, startDestination = Routes.Online) {
            composable(Routes.Online) { entry ->
                OnlineSearchScreen(
                    entryId = entry.id,
                    source = onlineSource,
                    onSourceChange = onOnlineSourceChange,
                    onOpenWork = { sourceId -> navController.navigate(Routes.onlineWork(sourceId)) },
                    onOpenBiliCollection = { ref -> navController.navigate(Routes.biliCollection(ref)) },
                    pendingSearch = pendingOnlineSearch,
                    onConsumeSearch = onConsumeOnlineSearch,
                )
            }
        }
        composable(
            route = Routes.OnlineWork,
            arguments = listOf(
                navArgument("sourceId") { type = NavType.StringType },
                navArgument("jaPost") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            val sourceId = entry.arguments?.getString("sourceId").orEmpty()
            OnlineWorkScreen(
                sourceId = sourceId,
                onBack = { navController.popBackStack() },
                onOpenLibraryWork = { folderId ->
                    if (navController.currentBackStackEntry?.id == entry.id) {
                        val previous = navController.previousBackStackEntry
                        val backToSameWork = isSameLibraryWork(
                            previous?.destination?.route, previous?.arguments?.getLong("folderId"), folderId,
                        )
                        if (backToSameWork) {
                            navController.popBackStack()
                        } else {
                            navController.navigate(Routes.libraryWork(folderId)) { launchSingleTop = true }
                        }
                    }
                },
                onOpenPreview = { fileId -> navController.navigate(Routes.preview(fileId)) },
                onOpenRemotePreview = { navController.navigate(Routes.RemotePreview) },
                onVerifyJapaneseAsmr = { page, rj, cleanup ->
                    navController.navigate(Routes.japaneseAsmrVerification(page, rj, operation = if (cleanup) "REFRESH" else "SYNC"))
                },
                verifiedJaResult = entry.savedStateHandle.getStateFlow("jaVerifiedArticle", null),
                onConsumeJaResult = { entry.savedStateHandle["jaVerifiedArticle"] = null },
                jaPost = entry.arguments?.getString("jaPost"),
                onSearchWorkPiece = { piece -> openOnlineSearch { PendingOnlineSearch.WorkPiece(it, piece) } },
            )
        }
        composable(
            route = Routes.JapaneseAsmrVerification,
            arguments = listOf(
                navArgument("url") { type = NavType.StringType },
                navArgument("rj") { type = NavType.StringType; defaultValue = "" },
                navArgument("queueId") { type = NavType.LongType; defaultValue = -1L },
                navArgument("mediaKey") { type = NavType.StringType; defaultValue = "" },
                navArgument("operation") { type = NavType.StringType; defaultValue = "SYNC" },
                navArgument("token") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val args = entry.arguments ?: return@composable
            val queueId = args.getLong("queueId")
            val mediaKey = args.getString("mediaKey")
            val operation = args.getString("operation") ?: "SYNC"
            val token = args.getString("token").orEmpty()
            val request = token.takeIf { operation == "REFRESH" && it.isNotEmpty() }?.let(graph.jaRefreshRequests::get)
            val url = request?.post?.url ?: args.getString("url").orEmpty()
            val expected = request?.post?.rj ?: args.getString("rj")?.takeIf { it.isNotEmpty() }
            JapaneseAsmrVerificationScreen(
                url = url,
                expectedRj = expected,
                onBack = {
                    if (navController.currentBackStackEntry?.id != entry.id) return@JapaneseAsmrVerificationScreen
                    if (request != null) graph.jaRefreshRequests.finish(token, null)
                    if (operation == "CLIPBOARD_IMPORT") clipboardController.finishJaVerification(token, null)
                    navController.popBackStack()
                },
                onArticle = { article ->
                    if (navController.currentBackStackEntry?.id != entry.id) return@JapaneseAsmrVerificationScreen
                    when {
                        request != null -> graph.jaRefreshRequests.finish(token, article)
                        operation == "PLAYBACK" -> graph.player.resumeVerifiedTrack(queueId, mediaKey, article)
                        operation == "CLIPBOARD_IMPORT" -> clipboardController.finishJaVerification(token, article)
                        else -> navController.previousBackStackEntry?.savedStateHandle
                            ?.set("jaVerifiedArticle", "${article.post.postId}:$operation")
                    }
                    navController.popBackStack()
                },
            )
        }
        composable(Routes.YouTubeList) { entry ->
            val id = entry.arguments?.getString("listId")?.takeIf { it == "LL" || it == "WL" } ?: return@composable
            com.nanahoshi.audioplayer.ui.youtube.YouTubeBrowser(playlistId = id, onBack = { navController.popBackStack() })
        }
        navigation(route = TabGraphs.Mine, startDestination = Routes.Mine) {
            composable(Routes.Mine) {
                MineScreen(
                    onOpenPurchases = { navController.navigate(Routes.Purchases) },
                    onOpenFavorites = { navController.navigate(Routes.favorites(null)) },
                    onOpenYouTubeList = { navController.navigate("youtube/list/$it") },
                )
            }
        }
        navigation(route = TabGraphs.Settings, startDestination = Routes.Settings) {
            composable(Routes.Settings) {
                SettingsScreen(
                    onOpenStored = { navController.navigate(Routes.Stored) },
                    onOpenModels = { navController.navigate(Routes.ModelSettings) },
                    onOpenCache = { navController.navigate(Routes.CacheSettings) },
                    onOpenAppearance = { navController.navigate(Routes.Appearance) },
                    onOpenAbout = { navController.navigate(Routes.About) },
                )
            }
        }
        composable(Routes.Playlist) { entry ->
            val id = entry.arguments?.getString("id")?.toLongOrNull() ?: return@composable
            PlaylistDetailScreen(
                id,
                onBack = { if (navController.currentBackStackEntry?.id == entry.id) navController.popBackStack() },
                onDeleted = { if (navController.currentBackStackEntry?.id == entry.id) navController.popBackStack() },
            )
        }
        composable(Routes.NowPlaying) {
            val playingScope = this
            val verificationScope = androidx.compose.runtime.rememberCoroutineScope()
            NowPlayingScreen(
                onBack = { navController.popBackStack() },
                onVerifyJapaneseAsmr = { page, queueId, mediaKey ->
                    verificationScope.launch {
                        val post = com.nanahoshi.audioplayer.japaneseasmr.jaPostId(page)
                        val rj = post?.let { graph.db.japaneseAsmr().post(it)?.rj ?: graph.japaneseAsmr.postRef(it)?.rj }
                        if (graph.player.state.value.queueItemId == queueId) {
                            navController.navigate(Routes.japaneseAsmrVerification(page, rj, queueId, mediaKey, operation = "PLAYBACK"))
                        }
                    }
                },
                onOpenCollection = { ref -> navController.navigate(Routes.biliCollection(ref)) { launchSingleTop = true } },
                coverModifier = with(shared) {
                    Modifier.sharedElement(
                        rememberSharedContentState(key = "player-cover"),
                        animatedVisibilityScope = playingScope,
                    )
                },
            )
        }
        composable(Routes.Downloads) {
            DownloadScreen(onBack = { navController.popBackStack() },
                onVerifyJaDownload = { token -> navController.navigate(Routes.jaDownloadVerification(token)) })
        }
        composable(Routes.JaDownloadVerification) { entry ->
            val token = entry.arguments?.getString("token").orEmpty()
            // 网页结果移交给下载准备或队列后，验证记录可先于页面退出而回收。
            val pending = remember(entry.id) { graph.downloads.jaVerification(token) }
            val close: (Boolean) -> Boolean = { cancel ->
                val closed = navController.popJaDownloadVerification(entry)
                if (closed && cancel) graph.downloads.cancelJaVerification(token)
                closed
            }
            if (pending == null) {
                LaunchedEffect(entry.id) { close(false) }
            } else {
                var page by remember(token) { mutableStateOf(pending.address) }
                androidx.compose.runtime.key(page) {
                    JapaneseAsmrVerificationScreen(
                        url = page, expectedRj = pending.entry.post.rj, onBack = { close(true) },
                        autoRead = true,
                        onDownload = { visible ->
                            when (val result = graph.downloads.completeJaVerification(token, visible)) {
                                is com.nanahoshi.audioplayer.data.DownloadCenter.WorkVerificationResult.OpenEntry -> page = result.address
                                is com.nanahoshi.audioplayer.data.DownloadCenter.WorkVerificationResult.Finished -> {
                                    if (close(!result.accepted) && !result.accepted) graph.show(R.string.download_cancelled)
                                }
                            }
                        },
                    )
                }
            }
        }
        composable(Routes.SubtitleTasks) {
            DownloadScreen(
                onBack = { navController.popBackStack() },
                initialSubtitles = true,
                onVerifyJaDownload = { token -> navController.navigate(Routes.jaDownloadVerification(token)) },
            )
        }
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
        composable(Routes.About) {
            AboutScreen(onBack = { navController.popBackStack() })
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
        ) {
            FavoriteScreen(onBack = { navController.popBackStack() })
        }
    }
}
