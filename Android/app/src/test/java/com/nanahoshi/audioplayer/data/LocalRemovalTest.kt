package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.data.db.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class LocalRemovalTest {
    private fun track(source: TrackSource, localUri: String?) = TrackEntity(
        source = source, title = "t", artist = null, durationMs = 1000, coverUri = null,
        localUri = localUri, bvid = null, cid = null, page = null, addedAt = 0)

    private fun withFilesDir(block: (File) -> Unit) {
        val dir = createTempDirectory(prefix = "local-removal").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun biliContentUriIsAssociation() = withFilesDir { dir ->
        assertEquals(LocalRemoval.ASSOCIATION, track(TrackSource.BILIBILI, "content://media/audio/1").localRemoval(dir))
    }

    @Test
    fun biliFileUriIsAssociation() = withFilesDir { dir ->
        assertEquals(LocalRemoval.ASSOCIATION, track(TrackSource.BILIBILI, "file:///sdcard/Music/a.mp3").localRemoval(dir))
    }

    @Test
    fun asmrAndDlsiteUrisKeepAssociationParity() = withFilesDir { dir ->
        assertEquals(LocalRemoval.ASSOCIATION, track(TrackSource.ASMR, "content://media/audio/1").localRemoval(dir))
        assertEquals(LocalRemoval.ASSOCIATION, track(TrackSource.DLSITE, "file:///sdcard/Music/a.mp3").localRemoval(dir))
    }

    @Test
    fun localTracksAreNeverRemoved() = withFilesDir { dir ->
        assertNull(track(TrackSource.LOCAL, "content://media/audio/1").localRemoval(dir))
        val file = File(dir, "song.mp3").apply { writeText("audio") }
        assertNull(track(TrackSource.LOCAL, file.absolutePath).localRemoval(dir))
    }

    @Test
    fun missingLocalUriIsNull() = withFilesDir { dir ->
        assertNull(track(TrackSource.ASMR, null).localRemoval(dir))
    }

    @Test
    fun appOwnedFileIsDownload() = withFilesDir { dir ->
        val file = File(dir, "song.mp3").apply { writeText("audio") }
        assertEquals(LocalRemoval.DOWNLOAD, track(TrackSource.ASMR, file.absolutePath).localRemoval(dir))
        assertEquals(LocalRemoval.DOWNLOAD, track(TrackSource.BILIBILI, file.absolutePath).localRemoval(dir))
    }

    @Test
    fun missingOrDirectoryAppPathIsNull() = withFilesDir { dir ->
        assertNull(track(TrackSource.ASMR, File(dir, "missing.mp3").absolutePath).localRemoval(dir))
        val sub = File(dir, "sub").apply { mkdirs() }
        assertNull(track(TrackSource.ASMR, sub.absolutePath).localRemoval(dir))
    }

    @Test
    fun fileOutsideAppDirIsNull() {
        val parent = createTempDirectory(prefix = "local-removal-parent").toFile()
        try {
            val dir = File(parent, "files").apply { mkdirs() }
            val outside = File(parent, "outside.mp3").apply { writeText("audio") }
            assertNull(track(TrackSource.ASMR, outside.absolutePath).localRemoval(dir))
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun remoteUrlIsNotALocalCopy() = withFilesDir { dir ->
        assertNull(track(TrackSource.ASMR, "https://example.com/a.mp3").localRemoval(dir))
    }
}