package com.nanahoshi.audioplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BiliCacheTest {
    private val bvid = "BV1234567890"

    @Test
    fun subtitleRoundTrip() {
        val dir = Files.createTempDirectory("bili-cache").toFile()
        try {
            assertNull(readBiliSubtitle(dir, bvid, 12L))
            assertTrue(writeBiliSubtitle(dir, bvid, 12L, "{\"body\":[{\"from\":1}]}"))
            assertEquals("{\"body\":[{\"from\":1}]}", readBiliSubtitle(dir, bvid, 12L))
            deleteBiliSubtitle(dir, bvid, 12L)
            assertNull(readBiliSubtitle(dir, bvid, 12L))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun invalidIdsHaveNoFile() {
        val dir = Files.createTempDirectory("bili-cache").toFile()
        try {
            assertNull(biliSubtitleFile(dir, "not-a-bvid", 12L))
            assertNull(biliSubtitleFile(dir, bvid, 0L))
            assertNull(biliAudioFile(dir, bvid, 0L))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun deletingAudioAlsoDeletesItsSubtitle() {
        val dir = Files.createTempDirectory("bili-cache").toFile()
        try {
            val audio = File(dir, "bilibili/$bvid/12.m4s")
            audio.parentFile?.mkdirs()
            audio.writeText("audio")
            val subtitle = File(audio.parentFile, "12.json")
            subtitle.writeText("{}")
            deleteBiliSubtitleBeside(audio)
            assertFalse(subtitle.exists())
            assertTrue(audio.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
