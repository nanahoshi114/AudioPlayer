package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.data.db.FolderEntity
import org.junit.Assert.*
import org.junit.Test

class LibraryRemovalPolicyTest {
    private val policy = LibraryRemovalPolicy(listOf(
        FolderEntity(1, name = "ordinary", position = 0),
        FolderEntity(2, parentId = 1, name = "work", position = 0, asmrSourceId = "RJ123456"),
        FolderEntity(3, parentId = 2, name = "wav", position = 0),
        FolderEntity(4, parentId = 3, name = "bonus", position = 0),
        FolderEntity(5, name = "normal", position = 0),
    ))

    @Test fun onlyWholeWorkAndOrdinaryFoldersCanBeRemoved() {
        assertTrue(policy.canRemoveFolder(2))
        assertFalse(policy.canRemoveFolder(3))
        assertFalse(policy.canRemoveFolder(4))
        assertTrue(policy.canRemoveFolder(1))
        assertTrue(policy.canRemoveFolder(5))
    }

    @Test fun entriesAreProtectedAtEveryDepthRegardlessOfTheirSource() {
        assertFalse(policy.canRemoveEntry(2))
        assertFalse(policy.canRemoveEntry(4))
        assertTrue(policy.canRemoveEntry(5))
        assertTrue(policy.canRemoveEntry(null))
    }

    @Test fun mixedPartialSelectionIsRejectedAsAWhole() {
        assertFalse(policy.allows(listOf(null, 5, 4), emptySet()))
        assertFalse(policy.allows(listOf(5), setOf(3, 5)))
    }

    @Test fun wholeWorkOrAnAncestorCoversRedundantSelectedChildren() {
        assertTrue(policy.allows(listOf(4, null), setOf(2, 3, 4)))
        assertTrue(policy.allows(listOf(4, 5), setOf(1, 4)))
    }

    @Test fun selectingEveryChildDoesNotSubstituteForSelectingTheWorkRoot() {
        assertFalse(policy.allows(listOf(2, 3, 4), setOf(3, 4)))
    }
    @Test fun groupedBiliOnlyAllowsRemovingTheWholeVideo() {
        val group = FolderEntity(10, name = "video", position = 0, biliBvid = "BV1234567890")
        val nested = FolderEntity(11, parentId = 10, name = "nested", position = 0)
        val rules = LibraryRemovalPolicy(listOf(group, nested))
        assertTrue(rules.canRemoveFolder(10))
        assertFalse(rules.canRemoveFolder(11))
        assertFalse(rules.canRemoveEntry(10))
        assertFalse(rules.allows(listOf(10, 11), emptySet()))
        assertTrue(rules.allows(listOf(10, 11), setOf(10)))
    }

}
