package com.nanahoshi.audioplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavigationTest {
    @Test
    fun sharedPagesKeepTheTabFoundOnTheBackStack() {
        val routesByTab = mapOf(
            TabGraphs.Library to listOf(Routes.Library, Routes.NowPlaying, Routes.BiliCollection),
            TabGraphs.Playlists to listOf(Routes.Playlists, Routes.Playlist),
            TabGraphs.Online to listOf(Routes.Online, Routes.BiliCollection),
            TabGraphs.Mine to listOf(Routes.Mine, Routes.Purchases, Routes.OnlineWork),
            TabGraphs.Settings to listOf(Routes.Settings, Routes.Stored, Routes.OnlineWork, Routes.LibraryWork),
        )
        routesByTab.forEach { (tab, pages) ->
            assertEquals(tab, activeTabGraph(listOf(null, tab) + pages))
        }
        assertEquals(
            TabGraphs.Online,
            activeTabGraph(listOf(null, TabGraphs.Library, Routes.Library, TabGraphs.Online, Routes.Online, Routes.OnlineWork)),
        )
        assertNull(activeTabGraph(emptyList()))
        assertNull(activeTabGraph(listOf(null, Routes.LibraryWork)))
    }

    @Test
    fun selectingATabEitherStaysPopsToItsRootOrSwitchesStacks() {
        assertEquals(
            TabNavigationAction.Stay,
            tabNavigationAction(TabGraphs.Online, Routes.Online, TabGraphs.Online, Routes.Online),
        )
        assertEquals(
            TabNavigationAction.PopToRoot,
            tabNavigationAction(TabGraphs.Online, Routes.BiliCollection, TabGraphs.Online, Routes.Online),
        )
        assertEquals(
            TabNavigationAction.PopToRoot,
            tabNavigationAction(TabGraphs.Playlists, Routes.Playlist, TabGraphs.Playlists, Routes.Playlists),
        )
        assertEquals(
            TabNavigationAction.PopToRoot,
            tabNavigationAction(TabGraphs.Library, Routes.BiliCollection, TabGraphs.Library, Routes.Library),
        )
        assertEquals(
            TabNavigationAction.Switch,
            tabNavigationAction(TabGraphs.Online, Routes.BiliCollection, TabGraphs.Library, Routes.Library),
        )
    }

    @Test
    fun dockIncludesPlaylistAndCollectionAndExcludesStandalonePages() {
        val visible = listOf(
            Routes.Library, Routes.Playlists, Routes.Online, Routes.Mine, Routes.Settings,
            Routes.OnlineWork, Routes.LibraryWork, Routes.BiliVideo, Routes.Playlist, Routes.BiliCollection,
        )
        val hidden = listOf(
            Routes.Downloads, Routes.SubtitleTasks, Routes.Stored, Routes.YouTubeList, Routes.Favorites,
            Routes.Purchases, Routes.ModelSettings, Routes.CacheSettings, Routes.Appearance, Routes.About,
            Routes.Preview, Routes.RemotePreview, Routes.JapaneseAsmrVerification, Routes.JaDownloadVerification,
            Routes.NowPlaying, null,
        )
        visible.forEach { assertTrue(it, showBottomDock(it)) }
        hidden.forEach { assertFalse(it, showBottomDock(it)) }
    }

    @Test
    fun systemBackSwitchesToLibraryOnlyFromOtherTabRoots() {
        listOf(Routes.Playlists, Routes.Online, Routes.Mine, Routes.Settings).forEach {
            assertTrue(it, returnsToLibraryOnBack(it))
        }
        val otherRoutes = listOf(
            Routes.Library, Routes.LibraryWork, Routes.Playlist, Routes.BiliCollection, Routes.OnlineWork,
            Routes.NowPlaying, Routes.Downloads, null,
        )
        otherRoutes.forEach { assertFalse(it, returnsToLibraryOnBack(it)) }
    }

    @Test
    fun openLibraryWorkReturnsToPreviousEntryOnlyForTheSameFolder() {
        assertTrue(isSameLibraryWork(Routes.LibraryWork, 42L, 42L))
        assertFalse(isSameLibraryWork(Routes.LibraryWork, 43L, 42L))
        assertFalse(isSameLibraryWork(Routes.BiliVideo, 42L, 42L))
        assertFalse(isSameLibraryWork(null, null, 42L))
        assertEquals("library/work/42", Routes.libraryWork(42L))
    }
}
