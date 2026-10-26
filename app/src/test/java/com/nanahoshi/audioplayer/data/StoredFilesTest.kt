package com.nanahoshi.audioplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class StoredFilesTest {
    @Test
    fun generatedSubtitleUsesTheAudioName() {
        assertEquals("song.json", generatedSubtitleName("song.mp3"))
        assertEquals("song.json", generatedSubtitleName("song"))
    }

    @Test
    fun nestedSubtitleInTheWorkDirectoryIsListed() {
        val root = createTempDirectory(prefix = "stored-work").toFile()
        try {
            val asmr = File(root, "asmr")
            val audio = File(asmr, "RJ001/wav/song.mp3")
            val lyric = File(asmr, "RJ001/text/song.lrc")
            val other = File(asmr, "RJ002/song.lrc")
            audio.parentFile?.mkdirs()
            lyric.parentFile?.mkdirs()
            other.parentFile?.mkdirs()
            audio.writeText("audio")
            lyric.writeText("lyric")
            other.writeText("other")
            val work = downloadWorkDirectory(audio, asmr, File(root, "dlsite"))
            assertEquals(File(asmr, "RJ001").canonicalPath, work?.canonicalPath)
            val found = subtitleFilesIn(work!!).map { it.canonicalPath }
            assertEquals(listOf(lyric.canonicalPath), found)

            val cue = File(root, "subtitles/abc.json")
            cue.parentFile?.mkdirs()
            cue.writeText("{}")
            val listed = ListedAudio(
                file = audio,
                storedPath = audio.absolutePath,
                trackId = 4L,
                remoteUrl = null,
                workTitle = "作品",
                workCode = "RJ001",
                sourceId = "RJ001",
            )
            val job = ListedJob(
                id = 1L,
                fingerprint = "ABC",
                trackId = 4L,
                localPath = File(root, "whisper-src/staged").absolutePath,
                remoteUrl = null,
                title = "song.mp3",
            )
            val items = buildStoredItems(
                audios = listOf(listed),
                jobs = listOf(job),
                cueFile = { fingerprint -> if (cueKey(fingerprint) == "abc") cue else null },
                looseSubtitles = emptyList(),
            ) { subtitleFilesIn(downloadWorkDirectory(it.file, asmr, File(root, "dlsite"))!!) }
            assertEquals(listOf("song.mp3", "song.json", "song.lrc"), items.map { it.name })
            assertEquals(StoredKind.GENERATED, items[1].kind)
            assertEquals(cue.absolutePath, items[1].path)
            assertEquals(StoredKind.SUBTITLE, items[2].kind)
            assertEquals(5L, items[0].bytes)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cueOutsideTheDownloadFolderIsStillListedOnce() {
        val root = createTempDirectory(prefix = "stored-cues").toFile()
        try {
            val cue = File(root, "abc.json")
            cue.writeText("{}")
            File(root, "extra-1.json").writeText("{}")
            val job = ListedJob(2L, "abc", null, null, null, "track.mp3")
            val files = generatedCueFiles(root)
            assertEquals(listOf(cue.name), files.map { it.name })
            val first = remainingGeneratedItems(files, emptySet(), listOf(job)) { file, matched ->
                StoredItem(
                    path = file.absolutePath,
                    name = generatedSubtitleName(matched?.title ?: file.name),
                    kind = StoredKind.GENERATED,
                    workTitle = null,
                    workCode = null,
                    sourceId = null,
                    trackId = matched?.trackId,
                    remoteUrl = matched?.remoteUrl,
                    audioPath = matched?.localPath,
                )
            }
            assertEquals(listOf("track.json"), first.map { it.name })
            val again = remainingGeneratedItems(files, first.map { canonicalPath(it.path) }.toSet(), listOf(job)) { file, _ ->
                StoredItem(file.absolutePath, file.name, StoredKind.GENERATED, null, null, null, null, null, null)
            }
            assertTrue(again.isEmpty())
            assertTrue(storedItemMatches(first.single(), "字幕"))
            assertTrue(storedItemMatches(first.single(), "abc.json"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun audioSittingOnTheDownloadRootHasNoWorkDirectory() {
        val root = createTempDirectory(prefix = "stored-root").toFile()
        try {
            val asmr = File(root, "asmr")
            val audio = File(asmr, "song.mp3")
            asmr.mkdirs()
            audio.writeText("audio")
            assertNull(downloadWorkDirectory(audio, asmr, File(root, "dlsite")))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun byteSizeUsesOneDecimal() {
        assertEquals("0 B", formatByteSize(0))
        assertEquals("1.5 KB", formatByteSize(1536))
        assertEquals("1.0 MB", formatByteSize(1024 * 1024))
    }

    @Test
    fun biliCachePathStaysInsideTheBvidFolder() {
        val root = createTempDirectory(prefix = "bili-cache").toFile()
        try {
            val file = biliAudioFile(root, "BV1xx411c7m2", 9)
            assertEquals(File(root, "bilibili/BV1xx411c7m2/9.m4s").path, file?.path)
            assertNull(biliAudioFile(root, "../BV1xx411c7m2", 9))
            assertNull(biliAudioFile(root, "BV1xx411c7m2", 0))
        } finally {
            root.deleteRecursively()
        }
    }
}
