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
        assertEquals("", cues.textAt(3000))
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
}
