package com.nanahoshi.audioplayer.ui.components

import com.nanahoshi.audioplayer.Routes
import com.nanahoshi.audioplayer.isEdgeToEdgeTopRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmersiveHeroLayoutTest {
    @Test
    fun appBarAlphaRisesFromTransparentToOpaqueAsHeroScrollsAway() {
        val alphas = (0..440 step 10).map { heroTopBarAlpha(it.toFloat(), 320f, 80f) }

        assertEquals(0f, alphas.first(), 0f)
        assertTrue(alphas.any { it > 0f && it < 1f })
        assertTrue(alphas.zipWithNext().all { (previous, next) -> previous <= next })
        assertEquals(1f, alphas.last(), 0f)
    }

    @Test
    fun appBarAlphaIsSafeBeforeTheHeroIsMeasuredAndForShortHeroes() {
        assertEquals(1f, heroTopBarAlpha(0f, 0f, 80f), 0f)
        assertEquals(1f, heroTopBarAlpha(0f, -1f, 80f), 0f)
        assertFalse(heroTopBarAlpha(0f, 80f, 80f).isNaN())
        assertFalse(heroTopBarAlpha(1f, 40f, 80f).isNaN())
        assertFalse(heroTopBarAlpha(0f, 0f, 0f).isNaN())
    }

    @Test
    fun onlyTheDetailRoutesWithHeroesExtendIntoTheStatusBar() {
        listOf(Routes.LibraryWork, Routes.OnlineWork, Routes.BiliVideo).forEach {
            assertTrue(it, isEdgeToEdgeTopRoute(it))
        }
        listOf(Routes.Library, Routes.Online, Routes.Playlist, Routes.BiliCollection, Routes.NowPlaying, null).forEach {
            assertFalse(it, isEdgeToEdgeTopRoute(it))
        }
    }
}
