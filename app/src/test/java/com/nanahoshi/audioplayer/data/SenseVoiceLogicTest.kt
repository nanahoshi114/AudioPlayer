package com.nanahoshi.audioplayer.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SenseVoiceLogicTest {
    @Test
    fun splitLongSegmentKeepsContinuousOffsets() {
        val max = 28 * 16000
        val short = FloatArray(100) { it.toFloat() }
        val kept = splitLongSegment(16000L, short, max)
        assertEquals(1, kept.size)
        assertEquals(16000L, kept[0].first)
        assertSame(short, kept[0].second)

        // 60 秒切成 28 + 28 + 4 秒，起始样本连续。
        val long = FloatArray(60 * 16000)
        val pieces = splitLongSegment(16000L, long, max)
        assertEquals(listOf(28 * 16000, 28 * 16000, 4 * 16000), pieces.map { it.second.size })
        assertEquals(listOf(16000L, 16000L + 28 * 16000L, 16000L + 56 * 16000L), pieces.map { it.first })
        assertEquals(60 * 16000, pieces.sumOf { it.second.size.toLong() })
    }

    @Test
    fun senseVoicePiecesCarryTheSegmentOffset() {
        val cues = senseVoicePiecesToCues(
            tokens = arrayOf("こん", "にち", "は", "。"),
            timestamps = floatArrayOf(0f, 0.5f, 1f, 1.5f),
            segmentStartMs = 10_000L,
        )
        assertEquals(listOf(WhisperCue(10_000L, 11_501L, "こんにちは。")), cues)
    }

    @Test
    fun senseVoicePiecesSplitOnSentenceEnd() {
        val cues = senseVoicePiecesToCues(
            tokens = arrayOf("あ", "。", "い", "。"),
            timestamps = floatArrayOf(0f, 0.1f, 1f, 1.1f),
            segmentStartMs = 5_000L,
        )
        assertEquals(
            listOf(
                WhisperCue(5_000L, 5_101L, "あ。"),
                WhisperCue(6_000L, 6_101L, "い。"),
            ),
            cues,
        )
    }

    @Test
    fun senseVoicePiecesWithoutTimestampsFallBackToEmpty() {
        assertTrue(
            senseVoicePiecesToCues(arrayOf("こ", "ん"), FloatArray(0), 1_000L).isEmpty(),
        )
        assertEquals(
            listOf(WhisperCue(1_500L, 1_501L, "こ")),
            senseVoicePiecesToCues(arrayOf("こ", "ん"), floatArrayOf(0.5f), 1_000L),
        )
    }

    @Test
    fun toMonoAveragesChannels() {
        val stereo = floatArrayOf(1f, -1f, 0.5f, 0.5f, 2f, 4f)
        assertArrayEquals(floatArrayOf(0f, 0.5f, 3f), toMono(stereo, 2), 0f)
        val mono = floatArrayOf(1f, 2f, 3f)
        assertSame(mono, toMono(mono, 1))
    }

    @Test
    fun resamplePassesThroughWhenRatesMatch() {
        val samples = floatArrayOf(1f, 2f, 3f)
        val (out, carry) = resampleLinear(samples, 16000, 16000, 0.7f)
        assertSame(samples, out)
        assertEquals(0.7f, carry)
    }

    @Test
    fun resampleDecimatesWithLinearInterpolation() {
        val (out, carry) = resampleLinear(floatArrayOf(0f, 3f, 6f, 9f, 12f, 15f), 48000, 16000)
        assertArrayEquals(floatArrayOf(0f, 9f), out, 0f)
        assertEquals(1f, carry)

        val (mid, midCarry) = resampleLinear(floatArrayOf(0f, 10f), 2, 1)
        assertArrayEquals(floatArrayOf(0f), mid, 0f)
        assertEquals(1f, midCarry)
    }

    @Test
    fun resampleStaysContinuousAcrossChunks() {
        // 上采样 8k→16k：第二块开头拼上一块最后一个样本（锚点），输出与一次整块重采样一致。
        val (first, carry) = resampleLinear(floatArrayOf(0f, 1f), 8000, 16000)
        assertArrayEquals(floatArrayOf(0f, 0.5f), first, 0f)
        assertEquals(0f, carry)
        val (second, secondCarry) = resampleLinear(floatArrayOf(1f, 2f, 3f), 8000, 16000, carry)
        assertArrayEquals(floatArrayOf(1f, 1.5f, 2f, 2.5f), second, 0f)
        assertEquals(0f, secondCarry)

        // 下采样 48k→16k 同样按锚点接续，不丢也不重。
        val (a, aCarry) = resampleLinear(floatArrayOf(0f, 3f, 6f, 9f, 12f, 15f), 48000, 16000)
        val (b, bCarry) = resampleLinear(floatArrayOf(15f, 18f, 21f, 24f), 48000, 16000, aCarry)
        assertArrayEquals(floatArrayOf(0f, 9f), a, 0f)
        assertArrayEquals(floatArrayOf(18f), b, 0f)
        assertEquals(1f, bCarry)
    }
}
