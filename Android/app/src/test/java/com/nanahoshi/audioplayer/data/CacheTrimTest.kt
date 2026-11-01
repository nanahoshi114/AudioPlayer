package com.nanahoshi.audioplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheTrimTest {
    @Test
    fun underTheLimitRemovesNothing() {
        val files = listOf(
            CacheFileInfo("a", modifiedAt = 1, sizeBytes = 40),
            CacheFileInfo("b", modifiedAt = 2, sizeBytes = 40),
        )
        assertTrue(cacheFilesToRemove(files, limitBytes = 100).isEmpty())
    }

    @Test
    fun exactlyAtTheLimitRemovesNothing() {
        val files = listOf(
            CacheFileInfo("a", modifiedAt = 1, sizeBytes = 50),
            CacheFileInfo("b", modifiedAt = 2, sizeBytes = 50),
        )
        assertTrue(cacheFilesToRemove(files, limitBytes = 100).isEmpty())
    }

    @Test
    fun overTheLimitRemovesOldestFirst() {
        val files = listOf(
            CacheFileInfo("newer", modifiedAt = 3, sizeBytes = 30),
            CacheFileInfo("oldest", modifiedAt = 1, sizeBytes = 10),
            CacheFileInfo("middle", modifiedAt = 2, sizeBytes = 30),
        )
        val removed = cacheFilesToRemove(files, limitBytes = 40).map { files[it].name }
        assertEquals(listOf("oldest", "middle"), removed)
    }

    @Test
    fun stopsOnceUsageEqualsTheLimit() {
        val files = listOf(
            CacheFileInfo("oldest", modifiedAt = 1, sizeBytes = 50),
            CacheFileInfo("middle", modifiedAt = 2, sizeBytes = 50),
            CacheFileInfo("newest", modifiedAt = 3, sizeBytes = 50),
        )
        val removed = cacheFilesToRemove(files, limitBytes = 100).map { files[it].name }
        assertEquals(listOf("oldest"), removed)
    }

    @Test
    fun journalFilesAreKept() {
        val files = listOf(
            CacheFileInfo("journal", modifiedAt = 1, sizeBytes = 100),
            CacheFileInfo("journal.bk", modifiedAt = 2, sizeBytes = 20),
            CacheFileInfo("older", modifiedAt = 3, sizeBytes = 80),
            CacheFileInfo("newer", modifiedAt = 4, sizeBytes = 80),
        )
        val removed = cacheFilesToRemove(files, limitBytes = 100).map { files[it].name }
        assertEquals(listOf("older", "newer"), removed)
    }
}
