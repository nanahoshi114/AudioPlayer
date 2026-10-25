package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.asmr.AsmrNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkFolderMatchTest {
    @Test
    fun missingWorkFilesStillFit() {
        val work = asmrMatchTree(
            listOf(
                audio("01.mp3"),
                audio("02.mp3"),
                folder("scans", image("cover.jpg")),
            ),
        )
        val local = LocalLevel(
            files = listOf(LocalMatchFile("01.mp3", trackId = 7, libraryFileId = null)),
            folders = emptyList(),
        )
        val fit = fitLocalFolder(local, work)
        val ready = fit as FolderFit.Ready
        assertEquals("01.mp3", ready.assignment.tracks.getValue(7).name)
        assertTrue(ready.assignment.folderIds.isEmpty())
    }

    @Test
    fun extraFileDoesNotFit() {
        val work = asmrMatchTree(listOf(audio("01.mp3")))
        val local = LocalLevel(
            files = listOf(
                LocalMatchFile("01.mp3", 1, null),
                LocalMatchFile("bonus.mp3", 2, null),
            ),
            folders = emptyList(),
        )
        val fit = fitLocalFolder(local, work)
        assertEquals("bonus.mp3", (fit as FolderFit.Extra).path)
    }

    @Test
    fun extraNestedFolderDoesNotFit() {
        val work = asmrMatchTree(listOf(folder("wav", audio("01.wav"))))
        val local = LocalLevel(
            files = emptyList(),
            folders = listOf(
                LocalMatchFolder(
                    id = 4,
                    name = "wav",
                    level = LocalLevel(
                        files = listOf(LocalMatchFile("01.wav", 3, null)),
                        folders = listOf(
                            LocalMatchFolder(5, "notes", LocalLevel(emptyList(), emptyList())),
                        ),
                    ),
                ),
            ),
        )
        val fit = fitLocalFolder(local, work)
        assertEquals("wav/notes", (fit as FolderFit.Extra).path)
    }

    @Test
    fun namesMatchIgnoringCase() {
        val work = asmrMatchTree(listOf(folder("WAV", audio("Voice.MP3"))))
        val local = LocalLevel(
            files = emptyList(),
            folders = listOf(
                LocalMatchFolder(
                    id = 9,
                    name = "wav",
                    level = LocalLevel(
                        files = listOf(LocalMatchFile("voice.mp3", 8, null)),
                        folders = emptyList(),
                    ),
                ),
            ),
        )
        val fit = fitLocalFolder(local, work) as FolderFit.Ready
        assertEquals(listOf(9L), fit.assignment.folderIds)
        assertEquals(8L, fit.assignment.tracks.keys.single())
    }

    @Test
    fun emptyFolderDoesNotMatchEveryWork() {
        val work = asmrMatchTree(listOf(audio("01.mp3")))
        val fit = fitLocalFolder(LocalLevel(emptyList(), emptyList()), work)
        assertTrue(fit is FolderFit.Empty)
    }

    private fun audio(name: String) = AsmrNode(name, "audio", emptyList(), "https://example/$name", 1_000, name)

    private fun image(name: String) = AsmrNode(name, "image", emptyList(), "https://example/$name", 0, name)

    private fun folder(name: String, vararg children: AsmrNode) =
        AsmrNode(name, "folder", children.toList(), null, 0, "")
}
