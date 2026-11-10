package com.nanahoshi.audioplayer.ui.library

import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.validWorkPath
import com.nanahoshi.audioplayer.data.db.FolderEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkFolderLocationTest {
    private val root = folder(1, null)
    private val a = folder(2, root.id)
    private val b = folder(3, a.id)
    private val c = folder(4, b.id)

    @Test
    fun jumpsToAnAncestorByIdEvenWhenFolderNamesMatch() {
        val deep = WorkFolderLocation(listOf(root)).enter(a).enter(b).enter(c)

        assertEquals(listOf(root, a), deep.ancestor(a.id).path)
        assertEquals(listOf(root), deep.ancestor(root.id).path)
        assertSame(deep, deep.ancestor(99))
        assertSame(deep, deep.enter(folder(5, a.id)))
    }

    @Test
    fun revisitingTheSameDirectoryAndChangingSourceCreateNewRequests() {
        val initial = WorkFolderLocation(listOf(root))
        val reopened = initial.enter(a).ancestor(root.id)
        val switched = reopened.move(listOf(root), TrackSource.DLSITE)

        assertEquals(root.id, reopened.request.folderId)
        assertNotSame(initial.request, reopened.request)
        assertEquals(TrackSource.DLSITE, switched.request.source)
        assertNotSame(reopened.request, switched.request)
        assertSame(switched.request, switched.move(listOf(root.copy(name = "新名称"))).request)
    }

    @Test
    fun removedOrMovedDirectoriesFallBackToTheNearestValidAncestor() {
        val ids = listOf(root.id, a.id, b.id, c.id)
        val remaining = listOf(root, a, c).associateBy { it.id }
        val moved = listOf(root, a, b.copy(parentId = root.id), c).associateBy { it.id }

        assertEquals(listOf(root, a), validWorkPath(ids, remaining))
        assertEquals(listOf(root, a), validWorkPath(ids, moved))
        assertTrue(validWorkPath(ids, remaining - root.id).isEmpty())
    }

    @Test
    fun aDirectoryHiddenByTheActiveSourceOrOriginalManifestIsNotAValidPath() {
        val parent = a.copy(contentSource = TrackSource.DLSITE.name, asmrWorkId = 1)
        val child = b.copy(contentSource = TrackSource.DLSITE.name, asmrWorkId = 1)
        val folders = listOf(root, parent, child).associateBy { it.id }
        val ids = listOf(root.id, parent.id, child.id)
        val originals = mapOf(root.id to setOf(parent.id), parent.id to setOf(parent.id))

        assertEquals(listOf(root, parent), validWorkPath(ids, folders, TrackSource.DLSITE, originals))
        assertEquals(listOf(root), validWorkPath(ids, folders, TrackSource.ASMR, originals))
        assertEquals(listOf(root, parent, child), validWorkPath(ids, folders, TrackSource.DLSITE))
    }

    private fun folder(id: Long, parent: Long?) = FolderEntity(
        id = id,
        parentId = parent,
        name = "同名目录",
        position = 0,
        addedAt = 0,
    )
}
