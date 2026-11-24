package com.nanahoshi.audioplayer.ui.library

import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.data.DeleteResult
import com.nanahoshi.audioplayer.data.LibrarySort
import com.nanahoshi.audioplayer.data.LocalRemovalPlan
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.FileKind
import com.nanahoshi.audioplayer.data.db.LibraryFileEntity
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.text.TextRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryListingSubtitleTest {
    private fun track(id: Long, title: String, folderId: Long? = 10L) = TrackEntity(
        id = id,
        source = TrackSource.ASMR,
        title = title,
        artist = null,
        durationMs = 1000L,
        coverUri = null,
        localUri = null,
        bvid = null,
        cid = null,
        page = null,
        addedAt = id,
        folderId = folderId,
    )

    private fun file(id: Long, name: String, kind: FileKind = FileKind.TEXT, folderId: Long? = 10L) = LibraryFileEntity(
        id = id,
        folderId = folderId,
        name = name,
        kind = kind,
        remoteUrl = "https://example.com/$name",
        addedAt = id,
        asmrWorkId = 1L,
        fileHash = "asmr:$name",
    )

    @Test
    fun mixedEntriesRespectsShowSubtitleFilesFlag() {
        val tracks = listOf(track(1L, "Track 01"))
        val files = listOf(
            file(10L, "Track 01.lrc"),
            file(11L, "notes.txt"),
            file(12L, "cover.png", FileKind.IMAGE),
        )

        val hidden = mixedEntries(tracks, files, LibrarySort.NAME, showSubtitleFiles = false)
        assertEquals(listOf("notes.txt", "Track 01", "cover.png").sortedBy { it.lowercase() }, hidden.map { it.name })

        val shown = mixedEntries(tracks, files, LibrarySort.NAME, showSubtitleFiles = true)
        assertEquals(4, shown.size)
        assertTrue(shown.any { it is LibraryEntry.File && it.file.name == "Track 01.lrc" })
    }

    @Test
    fun mixedEntriesUsesPrecomputedSubtitleTrackIdsWhenProvided() {
        val first = track(1L, "01 Intro")
        val second = track(2L, "02 Main")
        val files = listOf(file(10L, "01 Intro.lrc"))

        val resolved = mixedEntries(
            tracks = listOf(first, second),
            files = files,
            sort = LibrarySort.NAME,
            subtitleTrackIds = setOf(2L),
        ).filterIsInstance<LibraryEntry.Audio>().associateBy { it.track.id }

        assertFalse(resolved.getValue(1L).hasSubtitle)
        assertTrue(resolved.getValue(2L).hasSubtitle)
    }

    @Test
    fun deleteDownloadedNoticeIncludesDeletedFilesCount() {
        val mixed = deleteDownloadedNotice(DeleteResult(deleted = 2, unlinked = 1, changed = 0, deletedFiles = 3))
        assertEquals(
            TextRef.Joined(
                listOf(
                    TextRef.Res(R.string.library_deleted_downloads, listOf(2)),
                    TextRef.Res(R.string.library_deleted_files, listOf(3)),
                    TextRef.Res(R.string.library_unlinked_tracks, listOf(1)),
                ),
                separator = "。",
            ),
            mixed,
        )

        val filesOnly = deleteDownloadedNotice(DeleteResult(deleted = 0, unlinked = 0, changed = 0, deletedFiles = 1))
        assertEquals(
            TextRef.Joined(
                listOf(TextRef.Res(R.string.library_deleted_files, listOf(1))),
                separator = "。",
            ),
            filesOnly,
        )

        val empty = deleteDownloadedNotice(DeleteResult(deleted = 0, unlinked = 0, changed = 0, deletedFiles = 0))
        assertEquals(TextRef.Res(R.string.library_nothing_to_delete), empty)
    }

    @Test
    fun localRemovalPlanLabelReflectsFilesAndAssociations() {
        val subOnly = LocalRemovalPlan(subtitleFiles = listOf(file(10L, "a.lrc")))
        assertTrue(subOnly.hasTargets)
        assertEquals(R.string.library_action_delete_downloads, subOnly.labelRes)

        val mixed = LocalRemovalPlan(
            otherFiles = listOf(file(11L, "a.png", FileKind.IMAGE)),
            audioAssociations = listOf(track(1L, "Track 01")),
        )
        assertTrue(mixed.hasTargets)
        assertEquals(R.string.library_action_delete_and_unlink, mixed.labelRes)
    }
}
