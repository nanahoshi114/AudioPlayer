package com.nanahoshi.audioplayer.ui.library

import com.nanahoshi.audioplayer.data.LibrarySort
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.FileKind
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.LibraryFileEntity
import com.nanahoshi.audioplayer.data.db.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySearchTest {
    private val root = FolderEntity(
        id = 1L,
        parentId = null,
        name = "RJ01179806 作品一",
        position = 0,
        asmrWorkId = 1179806L,
        asmrSourceId = "RJ01179806",
    )
    private val chapter = FolderEntity(
        id = 2L,
        parentId = 1L,
        name = "第一章",
        position = 0,
        asmrWorkId = 1179806L,
        contentSource = "ASMR",
    )

    private fun track(id: Long, folderId: Long?, title: String, source: TrackSource = TrackSource.ASMR) =
        TrackEntity(
            id = id,
            source = source,
            title = title,
            artist = null,
            durationMs = 0,
            coverUri = null,
            localUri = null,
            bvid = null,
            cid = null,
            page = null,
            addedAt = 0,
            folderId = folderId,
        )

    private fun search(query: String, folders: List<FolderEntity>, tracks: List<TrackEntity> = emptyList()) =
        searchListing(
            query = query,
            sort = LibrarySort.NAME,
            folders = folders,
            tracks = tracks,
            files = emptyList(),
        )

    @Test
    fun matchingAChildTrackShowsTheWorkOnce() {
        val result = search(
            "第二首",
            listOf(root, chapter),
            listOf(track(10, 1L, "第一首"), track(11, 2L, "第二首")),
        )
        assertEquals(listOf(root.id), result.folders.map { it.id })
        assertTrue(result.entries.isEmpty())
    }

    @Test
    fun matchingAChildFolderShowsTheWorkOnce() {
        val result = search("第一章", listOf(root, chapter))
        assertEquals(listOf(root.id), result.folders.map { it.id })
        assertTrue(result.entries.isEmpty())
    }

    @Test
    fun matchingSeveralThingsStillShowsTheWorkOnce() {
        val result = search(
            "一",
            listOf(root, chapter),
            listOf(track(10, 1L, "第一首"), track(11, 2L, "第二首")),
        )
        assertEquals(listOf(root.id), result.folders.map { it.id })
        assertTrue(result.entries.isEmpty())
    }

    @Test
    fun rjNumberStillMatchesTheWork() {
        val result = search("1179806", listOf(root, chapter))
        assertEquals(listOf(root.id), result.folders.map { it.id })
    }

    @Test
    fun fileInsideAWorkDoesNotBecomeItsOwnRow() {
        val files = listOf(
            LibraryFileEntity(
                id = 20L,
                folderId = 2L,
                name = "插图.png",
                kind = FileKind.IMAGE,
                remoteUrl = "https://example.com/插图.png",
                addedAt = 0,
                asmrWorkId = 1179806L,
                fileHash = "asmr:插图.png",
            ),
        )
        val result = searchListing(
            query = "插图",
            sort = LibrarySort.NAME,
            folders = listOf(root, chapter),
            tracks = emptyList(),
            files = files,
        )
        assertEquals(listOf(root.id), result.folders.map { it.id })
        assertTrue(result.entries.isEmpty())
    }

    @Test
    fun localTrackOutsideAWorkStaysAnEntry() {
        val local = FolderEntity(id = 3L, parentId = null, name = "本地音乐", position = 1)
        val result = search(
            "本地歌",
            listOf(root, chapter, local),
            listOf(track(12, 3L, "本地歌", TrackSource.LOCAL)),
        )
        assertTrue(result.folders.isEmpty())
        assertEquals(1, result.entries.size)
        assertEquals("本地歌", result.entries.single().name)
    }
    @Test fun scopedSearchOnlyIncludesDescendantsAndNeverTheAnchor() {
        val parent = FolderEntity(id = 30, name = "match parent", position = 0)
        val anchor = FolderEntity(id = 31, parentId = 30, name = "match anchor", position = 0)
        val child = FolderEntity(id = 32, parentId = 31, name = "match child", position = 0)
        val sibling = FolderEntity(id = 33, parentId = 30, name = "match sibling", position = 0)
        val result = searchListing("match", LibrarySort.NAME, listOf(parent, anchor, child, sibling),
            listOf(track(40, 31, "match here"), track(41, 32, "match nested"), track(42, 33, "match elsewhere"),
                track(43, null, "match root")), emptyList(), scopeFolderId = 31)
        assertEquals(listOf(32L), result.folders.map { it.id })
        assertEquals(setOf(40L, 41L), result.entries.filterIsInstance<LibraryEntry.Audio>().map { it.track.id }.toSet())
    }

    @Test fun groupedBiliPartsCollapseWithinTheScopeButNeverToAnOutsideAncestor() {
        val anchor = FolderEntity(id = 30, name = "local", position = 0)
        val group = FolderEntity(id = 31, parentId = 30, name = "video", position = 0, biliBvid = "BV1234567890")
        val deep = FolderEntity(id = 32, parentId = 31, name = "deep", position = 0)
        val tracks = listOf(track(40, 31, "match", TrackSource.BILIBILI), track(41, 32, "match", TrackSource.BILIBILI))
        val result = searchListing("match", LibrarySort.NAME, listOf(anchor, group, deep), tracks, emptyList(), scopeFolderId = 30)
        assertEquals(listOf(group.id), result.folders.map { it.id })
        assertTrue(result.entries.isEmpty())
        val inside = searchListing("match", LibrarySort.NAME, listOf(anchor, group, deep), tracks, emptyList(), scopeFolderId = 32)
        assertTrue(inside.folders.isEmpty())
        assertEquals(listOf(41L), inside.entries.filterIsInstance<LibraryEntry.Audio>().map { it.track.id })
    }

    @Test fun groupedBiliCanBeFoundByItsBvNumber() {
        val group = FolderEntity(id = 30, name = "video", position = 0, biliBvid = "BV1234567890")
        assertEquals(listOf(30L), search("BV1234567890", listOf(group)).folders.map { it.id })
    }

}
