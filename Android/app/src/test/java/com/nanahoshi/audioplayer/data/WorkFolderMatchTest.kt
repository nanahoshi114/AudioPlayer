package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.asmr.AsmrNode
import com.nanahoshi.audioplayer.data.db.FileKind
import com.nanahoshi.audioplayer.dlsite.PlayEntry
import com.nanahoshi.audioplayer.dlsite.RemotePlayable
import com.nanahoshi.audioplayer.dlsite.RemotePreview
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
    fun fullwidthQuestionMarkMatchesTheHalfwidthName() {
        val remote = "2.何してほしい?【肩揉み、肩叩き】.mp3"
        val localName = "2.何してほしい？【肩揉み、肩叩き】.mp3"
        assertEquals(matchKey(remote), matchKey(localName))
        val work = asmrMatchTree(listOf(folder("MP3", audio(remote))))
        val local = LocalLevel(
            files = emptyList(),
            folders = listOf(
                LocalMatchFolder(
                    id = 1,
                    name = "ＭＰ３",
                    level = LocalLevel(
                        files = listOf(LocalMatchFile(localName, 5, null)),
                        folders = emptyList(),
                    ),
                ),
            ),
        )
        val fit = fitLocalFolder(local, work) as FolderFit.Ready
        assertEquals(5L, fit.assignment.tracks.keys.single())
        assertEquals(listOf(1L), fit.assignment.folderIds)
    }

    @Test
    fun widthVariantsShareAMatchKey() {
        assertEquals(matchKey("voice.mp3"), matchKey("ＶＯＩＣＥ．ｍｐ３"))
        assertEquals(matchKey("read me.png"), matchKey("read　me.png"))
        assertEquals(matchKey("カタカナ.mp3"), matchKey("ｶﾀｶﾅ.mp3"))
        assertEquals(matchKey("ガ.mp3"), matchKey("ｶﾞ.mp3"))
        assertTrue(matchKey("2.何してほしい？.mp3") != matchKey("2.何してほしい！.mp3"))
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
    fun purchasedDlsiteTreeUsesPlayFileKey() {
        val work = dlsiteMatchTree(
            listOf(
                PlayEntry(
                    title = "wav",
                    children = listOf(playAudio("Voice.mp3", "optimized.mp3")),
                    audio = null,
                    preview = null,
                ),
                playImage("cover.jpg"),
            ),
        )
        val local = LocalLevel(
            files = emptyList(),
            folders = listOf(
                LocalMatchFolder(
                    id = 2,
                    name = "WAV",
                    level = LocalLevel(
                        files = listOf(LocalMatchFile("voice.mp3", trackId = 4, libraryFileId = null)),
                        folders = emptyList(),
                    ),
                ),
            ),
        )
        val fit = fitLocalFolder(local, work) as FolderFit.Ready
        assertEquals("optimized.mp3", fit.assignment.tracks.getValue(4).storageKey)
        assertEquals(listOf(2L), fit.assignment.folderIds)
    }

    @Test
    fun recognizeNoticeNamesTheCatalog() {
        assertEquals("DLsite匹配成功", recognizeNotice(CatalogSide.Matched, asmrMatched = false))
        assertEquals("asmr.one匹配成功", recognizeNotice(CatalogSide.NotPurchased, asmrMatched = true))
        assertEquals("asmr.one匹配成功", recognizeNotice(CatalogSide.Unreachable, asmrMatched = true))
        assertEquals("asmr.one匹配成功", recognizeNotice(CatalogSide.Missed, asmrMatched = true))
        assertEquals(
            "DLsite未连接且asmr.one匹配失败",
            recognizeNotice(CatalogSide.Unreachable, asmrMatched = false),
        )
        assertEquals(
            "DLsite/asmr.one均匹配失败：多出来的内容：bonus.mp3",
            recognizeNotice(CatalogSide.Missed, asmrMatched = false, extraPath = "bonus.mp3"),
        )
        assertEquals(
            "DLsite/asmr.one均匹配失败",
            recognizeNotice(CatalogSide.NotPurchased, asmrMatched = false),
        )
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

    private fun playAudio(name: String, key: String) = PlayEntry(
        title = name,
        children = emptyList(),
        audio = RemotePlayable(
            title = name,
            artist = null,
            coverUri = null,
            remoteUrl = "https://example/$key",
            source = TrackSource.DLSITE,
            referer = "https://play.dlsite.com/",
            fileKey = key,
        ),
        preview = null,
    )

    private fun playImage(name: String) = PlayEntry(
        title = name,
        children = emptyList(),
        audio = null,
        preview = RemotePreview(
            name = name,
            url = "https://example/$name",
            kind = FileKind.IMAGE,
            referer = "https://play.dlsite.com/",
            authed = true,
        ),
    )
}
