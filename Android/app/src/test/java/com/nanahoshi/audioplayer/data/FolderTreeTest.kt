package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.data.db.FolderEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderTreeTest {
    private val folders = listOf(
        folder(1, null, "root"),
        folder(2, 1, "work-a"),
        folder(3, 2, "disc"),
        folder(4, 1, "work-b"),
        folder(5, 99, "orphan"),
    )
    private val byId = folders.associateBy { it.id }

    @Test
    fun ancestorChainStartsAtTheFolderAndStopsOnCycles() {
        assertEquals(listOf(3L, 2L, 1L), FolderTree.ancestorChain(3, byId).map { it.id }.toList())
        assertEquals(listOf(5L), FolderTree.ancestorChain(5, byId).map { it.id }.toList())
        assertNull(FolderTree.ancestorChain(404, byId).firstOrNull())
    }

    @Test
    fun nearestRootFindsClosestMatchingAncestor() {
        val nearest = FolderTree.nearestRoot(3, byId) { it.name.startsWith("work") }
        assertEquals(2L, nearest?.id)
    }

    @Test
    fun descendantIdsIncludesSelfAndSkipsOtherBranches() {
        assertEquals(setOf(1L, 2L, 3L, 4L), FolderTree.descendantIds(1, folders))
        assertEquals(setOf(2L, 3L), FolderTree.descendantIds(2, folders))
    }

    @Test
    fun relativePathExcludesTheRootFolder() {
        assertEquals(listOf("work-a", "disc"), FolderTree.relativePath(3, 1, byId))
        assertEquals(emptyList<String>(), FolderTree.relativePath(1, 1, byId))
    }

    private fun folder(id: Long, parent: Long?, name: String) =
        FolderEntity(id = id, parentId = parent, name = name, position = 0)
}