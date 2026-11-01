package com.nanahoshi.audioplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleCuesTest {
    @Test
    fun keepsOriginalExtensionBeforeStrippingIt() {
        val names = listOf("01.lrc", "01.mp3.srt", "01.mp3.lrc", "note.txt")
        assertEquals("01.mp3.lrc", pickSubtitleName("01.mp3", names))
        assertEquals("01.lrc", pickSubtitleName("01.mp3", listOf("01.vtt", "01.lrc")))
        assertEquals("01.vtt", pickSubtitleName("01.flac", listOf("01.srt", "01.vtt")))
    }

    @Test
    fun parsesBilibiliBody() {
        val cues = parseSubtitleDocument(
            """{"body":[{"from":1.5,"to":3,"content":"你好"}]}""",
        )
        assertEquals(1, cues.size)
        assertEquals(1500L, cues[0].startMs)
        assertEquals(3000L, cues[0].endMs)
        assertEquals("你好", cues[0].text)
        assertEquals("你好", cues.textAt(2000))
        assertEquals(0, cues.indexAt(2000))
        assertEquals("", cues.textAt(3000))
        assertEquals(-1, cues.indexAt(3000))
    }

    @Test
    fun parsesSrtAndLrc() {
        val srt = parseSubtitleDocument(
            """
            1
            00:00:01,000 --> 00:00:02,500
            第一句

            2
            00:00:03.000 --> 00:00:04.000
            第二句
            """.trimIndent(),
        )
        assertEquals(listOf(1000L, 3000L), srt.map { it.startMs })
        assertEquals("第一句", srt.textAt(1000))

        val lrc = parseSubtitleDocument(
            """
            [00:01.00]开头
            [00:02.50]后面
            """.trimIndent(),
        )
        assertEquals(1000L, lrc[0].startMs)
        assertEquals(2500L, lrc[0].endMs)
        assertEquals(Long.MAX_VALUE, lrc[1].endMs)
        assertEquals("后面", lrc.textAt(9000))
    }

    @Test
    fun parsesGeneratedCues() {
        val cues = parseGeneratedCues(
            """{"language":"ja","cues":[{"startMs":1200,"endMs":3400,"text":"こんにちは"}]}""",
        )
        assertEquals(1, cues.size)
        assertEquals(1200L, cues[0].startMs)
        assertEquals(3400L, cues[0].endMs)
        assertEquals("こんにちは", cues[0].text)
        assertEquals(emptyList<SubtitleCue>(), parseGeneratedCues("""{"cues":[]}"""))
    }

    @Test
    fun keepsTranslationBesideJapanese() {
        val encoded = encodeGeneratedCues(
            listOf(SubtitleCue(1200, 3400, "こんにちは", "你好")),
        )
        val cues = parseGeneratedCues(encoded)
        assertEquals("こんにちは", cues[0].text)
        assertEquals("你好", cues[0].translation)
        assertEquals("你好", cues.textAt(1200, chinese = true))
        assertEquals("こんにちは", cues.textAt(1200, chinese = false))
        assertEquals("只今日文", SubtitleCue(1, 2, "只今日文").line(chinese = true))
    }
}
