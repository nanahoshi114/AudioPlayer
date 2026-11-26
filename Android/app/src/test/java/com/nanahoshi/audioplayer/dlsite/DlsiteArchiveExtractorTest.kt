package com.nanahoshi.audioplayer.dlsite

import com.nanahoshi.audioplayer.zipBytes
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class DlsiteArchiveExtractorTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun safeRelativePathRejectsNullControlsRootsWindowsDrivesAndEmptySegments() {
        val unsafe = listOf(
            "a\u0000b",
            "a\u0001b",
            "a\u007fb",
            "/absolute/file.wav",
            "\\windows\\file.wav",
            "C:\\work\\file.wav",
            "C:/work/file.wav",
            "a/./b.wav",
            "a//b.wav",
            ".",
            "..",
            "",
            "a/../b.wav",
        )
        for (raw in unsafe) {
            val failure = runCatching { DlsiteArchiveExtractor.safeRelativePath(raw) }.exceptionOrNull()
            assertTrue("应拒绝非法路径 $raw，实际 $failure", failure is DlsiteArchiveException)
        }
        assertEquals("a/b.wav", DlsiteArchiveExtractor.safeRelativePath("a\\b.wav"))
        assertEquals("a/b.wav", DlsiteArchiveExtractor.safeRelativePath("a/b.wav/"))
    }

    @Test
    fun rejectsZipUnixSymlinkEntries() {
        val archive = temporary.newFile("symlink.zip")
        ZipArchiveOutputStream(archive).use { zip ->
            val entry = ZipArchiveEntry("voice.wav").apply { unixMode = 0xA1FF }
            zip.putArchiveEntry(entry)
            zip.write("../../outside".toByteArray())
            zip.closeArchiveEntry()
        }
        val destination = File(temporary.root, "symlink-out")
        val failure = runCatching { DlsiteArchiveExtractor.extract(listOf(archive), destination) }.exceptionOrNull()
        assertTrue(failure is DlsiteArchiveException)
        assertFalse(destination.exists())
    }

    @Test
    fun rejectsDuplicateCanonicalEntries() {
        val archive = temporary.newFile("duplicate.zip").apply {
            writeBytes(zipBytes("a\\b.wav" to "one".toByteArray(), "a/b.wav" to "two".toByteArray()))
        }
        val destination = File(temporary.root, "duplicate-out")
        val failure = runCatching { DlsiteArchiveExtractor.extract(listOf(archive), destination) }.exceptionOrNull()
        assertTrue(failure is DlsiteArchiveException)
        assertFalse(destination.exists())
    }

    @Test
    fun rejectsFileVersusDirectoryCollisions() {
        for (entries in listOf(
            arrayOf("a" to "file".toByteArray(), "a/" to ByteArray(0)),
            arrayOf("a/" to ByteArray(0), "a" to "file".toByteArray()),
            arrayOf("a" to "file".toByteArray(), "a/b.wav" to "nested".toByteArray()),
        )) {
            val archive = temporary.newFile("collision-${entries.hashCode()}.zip").apply { writeBytes(zipBytes(*entries)) }
            val destination = File(temporary.root, "collision-${entries.hashCode()}")
            val failure = runCatching { DlsiteArchiveExtractor.extract(listOf(archive), destination) }.exceptionOrNull()
            assertTrue("冲突条目必须整体失败：$failure", failure is DlsiteArchiveException)
            assertFalse(destination.exists())
        }
    }

    @Test
    fun abortsWhenAnEntryWritesMoreThanItsDeclaredSize() {
        val valid = zipBytes("voice.wav" to ByteArray(64 * 1024) { 7 })
        val archive = temporary.newFile("overrun.zip").apply { writeBytes(shrinkCentralUncompressedSize(valid, 1)) }
        val destination = File(temporary.root, "overrun-out")
        val failure = runCatching { DlsiteArchiveExtractor.extract(listOf(archive), destination) }.exceptionOrNull()
        assertTrue("超出声明大小的条目必须被拒绝，实际 $failure", failure is DlsiteArchiveIntegrityException)
        assertFalse(destination.exists())
        assertTrue("[解压越界不得留下半成品]", destination.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun normalArchiveStillExtractsAfterTheSizeGuard() {
        val archive = temporary.newFile("normal.zip").apply {
            writeBytes(zipBytes("RJ123/voice.wav" to "audio".toByteArray()))
        }
        val destination = File(temporary.root, "normal-out")
        val entries = DlsiteArchiveExtractor.extract(listOf(archive), destination)
        assertEquals("audio", File(destination, "RJ123/voice.wav").readText())
        assertTrue(entries.any { it.relativePath == "RJ123/voice.wav" && it.size == 5L })
        assertArrayEquals("audio".toByteArray(), File(destination, "RJ123/voice.wav").readBytes())
    }

    @Test
    fun extractsSplitZipVolumesInNumericOrder() {
        val bytes = zipBytes("RJ123/voice.wav" to "split-audio".toByteArray())
        val cut = bytes.size / 2
        val first = temporary.newFile("RJ123.zip.001").apply { writeBytes(bytes.copyOfRange(0, cut)) }
        val second = temporary.newFile("RJ123.zip.002").apply { writeBytes(bytes.copyOfRange(cut, bytes.size)) }
        val destination = File(temporary.root, "split-out")
        val entries = DlsiteArchiveExtractor.extract(listOf(first, second), destination).filterNot { it.isDirectory }
        assertEquals(1, entries.size)
        assertEquals("split-audio", File(destination, "RJ123/voice.wav").readText())
        assertTrue("[分卷归档不得被删除]", first.exists() && second.exists())
        assertTrue(
            "[临时拼接文件必须清理]",
            temporary.root.listFiles().orEmpty().none { it.name.startsWith("dlsite-split") },
        )
    }

    @Test
    fun skippedDuplicateEntriesAreNotCountedAsExtractedFiles() {
        val archive = temporary.newFile("skip.zip").apply {
            writeBytes(zipBytes(
                "voice/a.wav" to "first".toByteArray(),
                "Voice/A.wav" to "duplicate".toByteArray(),
                "voice/b.wav" to "second".toByteArray(),
            ))
        }
        val destination = File(temporary.root, "skip-out")
        var maxFiles = 0
        val contents = DlsiteArchiveExtractor.extractContents(
            archives = listOf(archive),
            destination = destination,
            onProgress = { files, _ -> maxFiles = maxOf(maxFiles, files) },
            checkCancelled = {},
            normalize = { it },
            pathKey = { it.lowercase() },
        )
        assertEquals(1, contents.skipped)
        assertEquals(
            setOf("voice/a.wav", "voice/b.wav"),
            contents.entries.filterNot { it.isDirectory }.map { it.relativePath }.toSet(),
        )
        assertEquals("跳过的重复条目不得计入已完成文件数", 2, maxFiles)
    }

    @Test
    fun skippedRarEntriesAreConsumedWithoutCountingAsExtractedFiles() {
        // RAR4 fixture from the junrar test resources (see extractsRar4Rar5AndSfxWithoutExecutingIt).
        val fixture = "UmFyIRoHAM+QcwAADQAAAAAAAAAJl3QggCkABwAAAAcAAAACun0Zem4DYz0dMAkAIAAAAEZJTEUxLlRYVGZpbGUxDQo8d3QggCkABwAAAAcAAAAC48NfeHEDYz0dMAkAIAAAAEZJTEUyLlRYVGZpbGUyDQrEPXsAQAcA"
        val archive = temporary.newFile("skip.rar").apply { writeBytes(Base64.getDecoder().decode(fixture)) }
        val destination = File(temporary.root, "skip-rar-out")
        var maxFiles = 0
        var reportedBytes = 0L
        val contents = DlsiteArchiveExtractor.extractContents(
            archives = listOf(archive),
            destination = destination,
            onProgress = { files, bytes ->
                maxFiles = maxOf(maxFiles, files)
                reportedBytes = maxOf(reportedBytes, bytes)
            },
            checkCancelled = {},
            // 两个条目规范化成同一路径：第二个被判定为重复而跳过，但仍需消费解压字节。
            normalize = { "kept.txt" },
            pathKey = { it.lowercase() },
        )
        assertEquals(1, contents.skipped)
        assertTrue(File(destination, "kept.txt").isFile)
        assertEquals("skipped RAR entries must not be counted as extracted files", 1, maxFiles)
        assertEquals("skipped RAR entries must not be counted as written bytes", 7L, reportedBytes)
    }

    /** 修改中央目录中该条目的未压缩大小，模拟声明过小但实际解压更多的恶意 ZIP。 */
    private fun shrinkCentralUncompressedSize(zip: ByteArray, declared: Int): ByteArray {
        val bytes = zip.copyOf()
        val signature = byteArrayOf(0x50, 0x4B, 0x01, 0x02)
        val offset = bytes.indices.first { index ->
            index + 4 <= bytes.size && bytes.copyOfRange(index, index + 4).contentEquals(signature)
        }
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(offset + 24, declared)
        return bytes
    }
}