package com.nanahoshi.audioplayer.ui.library

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryClimbTest {
    @Test fun walksAllStepsWhenEachLevelRefreshes() = runBlocking {
        var folder: Long? = 4L
        val ups = mutableListOf<Long?>()
        climbUp(3, { folder }, { ups += folder; folder = folder?.minus(1) }) { true }
        assertEquals(listOf<Long?>(4L, 3L, 2L), ups)
        assertEquals(1L, folder)
    }

    @Test fun stopsAfterTimeoutWithoutFurtherUp() = runBlocking {
        var folder: Long? = 4L
        var ups = 0
        climbUp(3, { folder }, { ups++; folder = folder?.minus(1) }) { before -> before != 3L }
        assertEquals(2, ups)
        assertEquals(2L, folder)
    }

    @Test fun reachingRootMidClimbStopsWithoutExtraUp() = runBlocking {
        var folder: Long? = 1L
        var ups = 0
        climbUp(3, { folder }, { ups++; folder = null }) { true }
        assertEquals(1, ups)
    }

    @Test fun rootDoesNotCallUp() = runBlocking {
        var ups = 0
        climbUp(3, { null }, { ups++ }) { true }
        assertEquals(0, ups)
    }

    @Test fun nonPositiveStepsDoNothing() = runBlocking {
        var ups = 0
        climbUp(0, { 5L }, { ups++ }) { true }
        climbUp(-2, { 5L }, { ups++ }) { true }
        assertEquals(0, ups)
    }
}
