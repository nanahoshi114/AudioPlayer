package com.nanahoshi.audioplayer.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class TextDecodingTest {
    @Test
    fun decodesUtf8WithOrWithoutBom() {
        val text = "こんにちは，字幕🎧"
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertEquals(text, decodeTextBytes(bytes))
        assertEquals(text, decodeTextBytes(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + bytes))
    }

    @Test
    fun detectsBothUtf16ByteOrdersAndStripsTheirBoms() {
        val text = "台詞と字幕🎧"
        assertEquals(text, decodeTextBytes(byteArrayOf(-1, -2) + text.toByteArray(Charsets.UTF_16LE)))
        assertEquals(text, decodeTextBytes(byteArrayOf(-2, -1) + text.toByteArray(Charsets.UTF_16BE)))
    }

    @Test
    fun decodesShiftJisWithKanaAndGbkChinese() {
        val japanese = "カナとひらがな"
        val chinese = "简体中文字幕"
        assertEquals(japanese, decodeTextBytes(japanese.toByteArray(Charset.forName("Shift_JIS"))))
        assertEquals(chinese, decodeTextBytes(chinese.toByteArray(Charset.forName("GBK"))))
    }

    @Test
    fun distinguishesHalfWidthKanaFromGbkPunctuation() {
        val ms932 = Charset.forName("MS932")
        val gbk = Charset.forName("GBK")
        for (japanese in listOf("ｶﾀｶﾅ", "日本語ﾊﾝｶｸｶﾅ")) {
            assertEquals(japanese, decodeTextBytes(japanese.toByteArray(ms932)))
        }
        // 覆盖 0x81 0x45：Shift-JIS 为「・」，GBK 为中文字符，应保持 GBK。
        val gbkBytes = byteArrayOf(0x81.toByte(), 0x45)
        assertEquals(String(gbkBytes, gbk), decodeTextBytes(gbkBytes))
        for (chinese in listOf("丒", "测试·字幕", "中文，字幕。")) {
            assertEquals(chinese, decodeTextBytes(chinese.toByteArray(gbk)))
        }
    }

    @Test
    fun boundedReadDecodesBothUtf16ByteOrdersAndHalfWidthKatakana() {
        val text = "台詞と字幕🎧"
        for ((charset, bom) in listOf(
            Charsets.UTF_16LE to byteArrayOf(-1, -2),
            Charsets.UTF_16BE to byteArrayOf(-2, -1),
        )) {
            val bytes = bom + text.toByteArray(charset)
            val bounded = readBoundedBytes(bytes.inputStream(), bytes.size)
            assertFalse(bounded.clipped)
            assertArrayEquals(bytes, bounded.bytes)
            assertEquals(text, decodeTextBytes(bounded.bytes, bounded.clipped))
        }
        val halfWidth = "ｦｱｲｳｴｵﾝ"
        val kanaBytes = halfWidth.toByteArray(Charset.forName("MS932"))
        val bounded = readBoundedBytes(kanaBytes.inputStream(), kanaBytes.size)
        assertFalse(bounded.clipped)
        assertEquals(halfWidth, decodeTextBytes(bounded.bytes, bounded.clipped))
    }

    @Test
    fun boundedReadAtExactLimitRetainsTheLastCharacter() {
        val text = "字幕"
        val bytes = text.toByteArray()
        val bounded = readBoundedBytes(bytes.inputStream(), bytes.size)
        assertFalse(bounded.clipped)
        assertArrayEquals(bytes, bounded.bytes)
        assertEquals(text, decodeTextBytes(bounded.bytes, bounded.clipped))
    }

    @Test
    fun clippedUtf8AndShiftJisDropOnlyTheIncompleteTrailingCharacter() {
        for ((complete, trailing, charset) in listOf(
            Triple("字幕", "🎧", Charsets.UTF_8),
            Triple("かな", "字", Charset.forName("Shift_JIS")),
        )) {
            val bytes = (complete + trailing).toByteArray(charset)
            val prefixSize = complete.toByteArray(charset).size
            for (limit in prefixSize + 1 until bytes.size) {
                val bounded = readBoundedBytes(bytes.inputStream(), limit)
                assertTrue(charset.name(), bounded.clipped)
                assertArrayEquals(bytes.copyOf(limit), bounded.bytes)
                assertEquals(charset.name(), complete, decodeTextBytes(bounded.bytes, bounded.clipped))
            }
        }
    }

    @Test
    fun readFullTextReadsBeyondPreviewLimitAndFailsExplicitlyPastSafetyCap() {
        val large = "あ".repeat(250_000) // ~750 KB UTF-8, > 512 KiB preview limit
        val decoded = large.toByteArray(Charsets.UTF_8).inputStream().readFullText()
        assertEquals(large, decoded)

        val failure = runCatching { "abcdef".toByteArray(Charsets.UTF_8).inputStream().readFullText(maxBytes = 5) }.exceptionOrNull()
        assertTrue(failure is java.io.IOException)
    }
}
