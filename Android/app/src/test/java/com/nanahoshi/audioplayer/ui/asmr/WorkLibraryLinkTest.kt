package com.nanahoshi.audioplayer.ui.asmr

import com.nanahoshi.audioplayer.data.db.FolderEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkLibraryLinkTest {
    private val routeKey = "RJ01234567"

    @Test
    fun originalFolderWithoutWorkMarkerDoesNotLinkToLibrary() {
        listOf(null, " ").forEach { marker ->
            val link = resolveWorkLibraryLink(routeKey, listOf(folder(12L, marker)), 12L)

            assertEquals(12L, link.originalRootId)
            assertNull(link.localRootId)
            assertNull(link.libraryFolderId)
            assertFalse(link.saved)
        }
    }

    @Test
    fun originalFolderMarkedForAnotherWorkDoesNotLinkToLibrary() {
        val link = resolveWorkLibraryLink(routeKey, listOf(folder(12L, "RJ07654321")), 12L)

        assertEquals(12L, link.originalRootId)
        assertNull(link.localRootId)
        assertNull(link.libraryFolderId)
        assertFalse(link.saved)
    }

    @Test
    fun exactWorkMarkerTakesPriorityOverDifferentOriginalFolder() {
        val original = folder(12L, "rj1234567")
        val local = folder(34L, routeKey)
        val link = resolveWorkLibraryLink(routeKey, listOf(original, local), original.id)

        assertEquals(routeKey, link.rawKey)
        assertEquals(12L, link.originalRootId)
        assertEquals(34L, link.localRootId)
        assertEquals(34L, link.libraryFolderId)
        assertTrue(link.saved)
    }

    @Test
    fun canonicalMatchUsesLibraryFolderOrder() {
        val first = folder(12L, "rj1234567", position = 1)
        val later = folder(34L, "RJ001234567", position = 2)
        val link = resolveWorkLibraryLink(routeKey, listOf(later, first), null)

        assertEquals(first.id, link.libraryFolderId)
        assertTrue(link.saved)
    }

    @Test
    fun folderSnapshotsReflectImportRemovalAndReimportWithNewId() {
        val original = folder(12L, null)
        val imported = folder(34L, routeKey)
        val reimported = imported.copy(id = 56L)
        val snapshots = listOf(emptyList(), listOf(original, imported), listOf(original), listOf(original, reimported))
        val links = snapshots.map { folders -> resolveWorkLibraryLink(routeKey, folders, original.id) }

        assertEquals(listOf(null, 34L, null, 56L), links.map { it.libraryFolderId })
        assertEquals(listOf(false, true, false, true), links.map { it.saved })
        assertEquals(listOf(null, 12L, 12L, 12L), links.map { it.originalRootId })
    }

    private fun folder(id: Long, sourceId: String?, position: Int = 0) =
        FolderEntity(id = id, name = "作品 $id", position = position, asmrSourceId = sourceId)
}
