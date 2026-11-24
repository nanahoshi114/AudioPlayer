package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.data.db.FileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryNamesTest {
    @Test
    fun vttFilesClassifyAsTextAndSubtitle() {
        assertEquals(FileKind.TEXT, previewKind("captions.vtt"))
        assertEquals(FileKind.TEXT, previewKind("CAPTIONS.VTT"))
        assertTrue(isSubtitleFile("captions.vtt"))
        assertTrue(isSubtitleFile("CAPTIONS.VTT"))
    }

    @Test
    fun otherTextImageAndUnknownExtensionsKeepTheirKinds() {
        assertEquals(FileKind.TEXT, previewKind("readme.txt"))
        assertEquals(FileKind.TEXT, previewKind("lyrics.lrc"))
        assertEquals(FileKind.TEXT, previewKind("captions.srt"))
        assertEquals(FileKind.IMAGE, previewKind("cover.png"))
        assertEquals(FileKind.OTHER, previewKind("archive.zip"))
        assertEquals(FileKind.OTHER, previewKind("no-extension"))
    }
}