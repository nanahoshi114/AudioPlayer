package com.nanahoshi.audioplayer

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController

internal fun NavController.popJaDownloadVerification(entry: NavBackStackEntry): Boolean =
    currentBackStackEntry?.id == entry.id && popBackStack()

internal object Routes {
    const val Library = "library"
    const val BiliCollection = "bilibili/collection?ref={ref}"
    fun biliCollection(ref: com.nanahoshi.audioplayer.bilibili.BiliCollection): String {
        val value = com.nanahoshi.audioplayer.bilibili.biliLibraryJson.encodeToString(com.nanahoshi.audioplayer.bilibili.BiliCollection.serializer(), ref)
        return "bilibili/collection?ref=${android.net.Uri.encode(value)}"
    }
    const val BiliVideo = "library/bilibili/{folderId}"
    fun biliVideo(id: Long) = "library/bilibili/$id"
    const val LibraryWork = "library/work/{folderId}"
    fun libraryWork(folderId: Long) = "library/work/$folderId"
    const val Playlists = "playlists"
    const val Online = "online"
    const val OnlineWork = "online/work/{sourceId}?jaPost={jaPost}"
    fun onlineWork(sourceId: String, jaPost: String? = null): String =
        "online/work/${android.net.Uri.encode(sourceId)}" +
            (jaPost?.let { "?jaPost=${android.net.Uri.encode(it)}" } ?: "")
    const val JapaneseAsmrVerification = "online/japanese-asmr/verify?url={url}&rj={rj}&queueId={queueId}&mediaKey={mediaKey}&operation={operation}&token={token}"
    fun japaneseAsmrVerification(url: String = "", rj: String? = null, queueId: Long = -1L, mediaKey: String? = null,
        operation: String = "SYNC", token: String? = null): String =
        "online/japanese-asmr/verify?url=${android.net.Uri.encode(url)}" +
            "&rj=${android.net.Uri.encode(rj.orEmpty())}&queueId=$queueId&mediaKey=${android.net.Uri.encode(mediaKey.orEmpty())}" +
            "&operation=$operation&token=${token.orEmpty()}"
    const val JaDownloadVerification = "download/ja/verify/{token}"
    fun jaDownloadVerification(token: String) = "download/ja/verify/$token"
    const val YouTubeList = "youtube/list/{listId}"
    const val Mine = "mine"
    const val Settings = "settings"
    const val ModelSettings = "settings/models"
    const val CacheSettings = "settings/cache"
    const val Appearance = "settings/appearance"
    const val About = "settings/about"
    const val Purchases = "purchases"
    const val NowPlaying = "now"
    const val Downloads = "downloads"
    const val SubtitleTasks = "downloads/subtitles"
    const val Stored = "stored"
    const val Preview = "preview/{fileId}"
    fun preview(fileId: Long) = "preview/$fileId"
    const val RemotePreview = "preview/remote"
    const val Favorites = "favorites?folderId={folderId}"
    fun favorites(folderId: Long?) = "favorites?folderId=${folderId ?: -1L}"
    const val Playlist = "playlist/{id}"
    fun playlist(id: Long) = "playlist/$id"
}

internal object TabGraphs {
    const val Library = "tab/library"
    const val Playlists = "tab/playlists"
    const val Online = "tab/online"
    const val Mine = "tab/mine"
    const val Settings = "tab/settings"

    val all = setOf(Library, Playlists, Online, Mine, Settings)
}

internal fun activeTabGraph(backStackRoutes: List<String?>): String? =
    backStackRoutes.lastOrNull { it in TabGraphs.all }

internal fun isTab(route: String?) = route == Routes.Library || route == Routes.Playlists ||
    route == Routes.Online || route == Routes.Mine || route == Routes.Settings

internal fun showBottomDock(route: String?) = isTab(route) ||
    route == Routes.OnlineWork || route == Routes.LibraryWork || route == Routes.BiliVideo ||
    route == Routes.Playlist || route == Routes.BiliCollection

internal fun isEdgeToEdgeTopRoute(route: String?) =
    route == Routes.LibraryWork || route == Routes.OnlineWork || route == Routes.BiliVideo

internal fun returnsToLibraryOnBack(route: String?) = isTab(route) && route != Routes.Library

internal enum class TabNavigationAction { Stay, PopToRoot, Switch }

internal fun tabNavigationAction(
    activeGraph: String?,
    currentRoute: String?,
    targetGraph: String,
    targetRootRoute: String,
): TabNavigationAction = when {
    currentRoute == targetRootRoute -> TabNavigationAction.Stay
    activeGraph == targetGraph -> TabNavigationAction.PopToRoot
    else -> TabNavigationAction.Switch
}

internal fun isSameLibraryWork(route: String?, previousFolderId: Long?, targetFolderId: Long): Boolean =
    route == Routes.LibraryWork && previousFolderId == targetFolderId

internal fun NavController.navigateTab(targetGraph: String, targetRootRoute: String) {
    val activeGraph = activeTabGraph(currentBackStack.value.map { it.destination.route })
    when (tabNavigationAction(activeGraph, currentDestination?.route, targetGraph, targetRootRoute)) {
        TabNavigationAction.Stay -> Unit
        TabNavigationAction.PopToRoot -> popBackStack(targetRootRoute, inclusive = false)
        TabNavigationAction.Switch -> navigate(targetGraph) {
            // Keep the save-state anchor separate from every tab graph.
            popUpTo(graph.id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
}
