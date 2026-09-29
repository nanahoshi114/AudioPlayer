package com.nanahoshi.audioplayer

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nanahoshi.audioplayer.ui.library.FavoriteScreen
import com.nanahoshi.audioplayer.ui.library.LibraryScreen
import com.nanahoshi.audioplayer.ui.player.MiniPlayer
import com.nanahoshi.audioplayer.ui.player.NowPlayingScreen
import com.nanahoshi.audioplayer.ui.playlist.PlaylistDetailScreen
import com.nanahoshi.audioplayer.ui.playlist.PlaylistListScreen
import com.nanahoshi.audioplayer.ui.settings.SettingsScreen
import com.nanahoshi.audioplayer.ui.theme.AudioPlayerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AudioPlayerTheme {
                PlayerApp()
            }
        }
    }
}

private object Routes {
    const val Library = "library"
    const val Playlists = "playlists"
    const val NowPlaying = "now"
    const val Settings = "settings"
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
    val showBars = route == Routes.Library || route == Routes.Playlists
    val context = LocalContext.current
    val messages = (context.applicationContext as AudioPlayerApplication).graph.messages
    val message by messages.collectAsStateWithLifecycle()
    val sleepFinished by (context.applicationContext as AudioPlayerApplication).graph.player.sleepFinished
        .collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
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
                    (context.applicationContext as AudioPlayerApplication).graph.player.acknowledgeSleepFinished()
                }) { Text("确定") }
            },
        )
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (showBars) {
                TopAppBar(
                    title = { Text(if (route == Routes.Playlists) "播放列表" else "音频库") },
                    actions = {
                        IconButton(onClick = { navController.navigate(Routes.Settings) }) {
                            Icon(Icons.Default.Settings, contentDescription = "设置")
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (showBars) {
                Column {
                    MiniPlayer(onOpen = { navController.navigate(Routes.NowPlaying) })
                    NavigationBar {
                        NavigationBarItem(
                            selected = route == Routes.Library,
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
                            selected = route == Routes.Playlists,
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
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.Library,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.Library) {
                LibraryScreen(onOpenFavorites = { folderId -> navController.navigate(Routes.favorites(folderId)) })
            }
            composable(Routes.Playlists) {
                PlaylistListScreen(onOpen = { id -> navController.navigate(Routes.playlist(id)) })
            }
            composable(Routes.Playlist) { entry ->
                val id = entry.arguments?.getString("id")?.toLongOrNull() ?: return@composable
                PlaylistDetailScreen(
                    id,
                    onBack = { navController.popBackStack() },
                    onDeleted = { navController.popBackStack() },
                )
            }
            composable(Routes.NowPlaying) { NowPlayingScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.Settings) { SettingsScreen(onBack = { navController.popBackStack() }) }
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
