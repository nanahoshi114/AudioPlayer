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
    fun parakeetUsesSegmentOffsetAndKeepsJapanesePunctuationAndSpaces() {
        val cues = parakeetSegmentToCues(
            "日本語? OK。", arrayOf(" ", "日本", "語", "?", " ", "OK", "。"),
            floatArrayOf(0f, 0.16f, 0.32f, 0.48f, 0.64f, 0.8f, 0.96f), 10_000, 11_000,
        )
        assertEquals(listOf(
            WhisperCue(10_160, 10_560, "日本語?"),
            WhisperCue(10_800, 11_000, "OK。"),
        ), cues)
    }

    @Test
    fun parakeetSplitsUnpunctuatedTextAtPausesAndKeepsInternalSpaces() {
        val cues = parakeetSegmentToCues(
            "聞こえますか 次の話", arrayOf("聞こえ", "ますか", " ", "次", "の", "話"),
            floatArrayOf(0f, 0.32f, 0.48f, 0.88f, 1.2f, 1.44f), 10_000, 12_000,
        )
        assertEquals(listOf(
            WhisperCue(10_000, 10_400, "聞こえますか"),
            WhisperCue(10_880, 11_520, "次の話"),
        ), cues)

        val shortPause = parakeetSegmentToCues(
            "お茶と コーヒー", arrayOf("お茶と", " ", "コーヒー"),
            floatArrayOf(0f, 0.16f, 0.48f), 0, 1_000,
        )
        assertEquals(listOf(WhisperCue(0, 560, "お茶と コーヒー")), shortPause)
    }

    @Test
    fun parakeetKeepsUnpunctuatedSpeechTogetherWithoutANaturalBreak() {
        val tokens = Array(28) { "あ" }
        val timestamps = FloatArray(tokens.size) { it * 0.24f }
        val cues = parakeetSegmentToCues(tokens.joinToString(""), tokens, timestamps, 1_000, 8_000)
        assertEquals(listOf(WhisperCue(1_000, 7_560, "あ".repeat(28))), cues)
    }

    @Test
    fun parakeetLooksBackToCommaBeforeALaterShortPause() {
        val tokens = Array(17) { "あ" }.also { it[12] = "あ、" }
        val timestamps = FloatArray(tokens.size) {
            when (it) {
                13 -> 5f
                14 -> 5.48f
                else -> it * 0.4f
            }
        }
        val cues = parakeetSegmentToCues(tokens.joinToString(""), tokens, timestamps, 1_000, 8_000)
        assertEquals(listOf(
            WhisperCue(1_000, 5_880, "あ".repeat(13) + "、"),
            WhisperCue(6_000, 7_480, "あ".repeat(4)),
        ), cues)
    }

    @Test
    fun parakeetLooksBackToShortPauseWhenNoCommaFits() {
        val tokens = Array(30) { "あ" }
        val timestamps = FloatArray(tokens.size) { index ->
            index * 0.24f + if (index >= 21) 0.08f else 0f
        }
        val cues = parakeetSegmentToCues(tokens.joinToString(""), tokens, timestamps, 0, 8_000)
        assertEquals(listOf(
            WhisperCue(0, 4_880, "あ".repeat(21)),
            WhisperCue(5_120, 7_120, "あ".repeat(9)),
        ), cues)
    }

    @Test
    fun parakeetIgnoresCommaThatWouldLeaveATinyCue() {
        val tokens = arrayOf("短い、", "あ".repeat(36), "続き")
        val cues = parakeetSegmentToCues(
            tokens.joinToString(""), tokens, floatArrayOf(0f, 0.32f, 0.64f), 0, 1_000,
        )
        assertEquals(listOf(WhisperCue(0, 720, tokens.joinToString(""))), cues)
    }

    @Test
    fun parakeetMixesSimilarStereoAndKeepsOneChannelWhenSignalsWouldCancel() {
        val left = floatArrayOf(0f, 1f, -0.5f, 0f)
        val right = floatArrayOf(0f, 0.5f, -0.25f, 0f)
        assertArrayEquals(floatArrayOf(0.75f, -0.375f), selectStereoSpeech(left, right, 1, 3), 0f)
        assertArrayEquals(
            left, selectStereoSpeech(left, FloatArray(left.size) { -left[it] }, 0, left.size), 0f,
        )
        assertArrayEquals(right, selectStereoSpeech(FloatArray(right.size), right, 0, right.size), 0f)
        assertArrayEquals(left, selectStereoSpeech(left, FloatArray(left.size), 0, left.size), 0f)
    }

    @Test
    fun parakeetKeepsTrailingPunctuationWithTextAndAllowsLongerSentences() {
        val firstLine = "あ".repeat(40)
        val tokens = arrayOf("あ".repeat(20), "あ".repeat(20), "？", "」", "続き")
        val timestamps = floatArrayOf(0f, 0.16f, 0.32f, 0.48f, 0.64f)
        val cues = parakeetSegmentToCues(tokens.joinToString(""), tokens, timestamps, 0, 1_000)
        assertEquals(listOf(
            WhisperCue(0, 560, "$firstLine？」"),
            WhisperCue(640, 720, "続き"),
        ), cues)

        val plainTokens = arrayOf("あ".repeat(20), "あ".repeat(20), "続き")
        val plainCues = parakeetSegmentToCues(
            plainTokens.joinToString(""), plainTokens, floatArrayOf(0f, 0.16f, 0.32f), 0, 1_000,
        )
        assertEquals(listOf(WhisperCue(0, 400, firstLine + "続き")), plainCues)
    }

    @Test
    fun parakeetKeepsOpeningQuoteWithTheNextSentence() {
        val tokens = arrayOf("こんにちは。", "「", "聞こえます", "？」", "はい")
        val cues = parakeetSegmentToCues(
            tokens.joinToString(""), tokens, floatArrayOf(0f, 0.16f, 0.32f, 0.48f, 0.64f), 0, 1_000,
        )
        assertEquals(listOf(
            WhisperCue(0, 80, "こんにちは。"),
            WhisperCue(160, 560, "「聞こえます？」"),
            WhisperCue(640, 720, "はい"),
        ), cues)
    }

    @Test
    fun parakeetDoesNotCreateOverlappingCuesAtIdenticalTimestamps() {
        val tokens = arrayOf("こんにちは。", "はい", "次へ")
        val cues = parakeetSegmentToCues(
            tokens.joinToString(""), tokens, floatArrayOf(0f, 0f, 0.8f), 0, 1_000,
        )
        assertEquals(listOf(
            WhisperCue(0, 80, "こんにちは。はい"),
            WhisperCue(800, 880, "次へ"),
        ), cues)
    }

    @Test
    fun parakeetFallsBackToTheSpeechSegmentWhenTimestampsAreUnusable() {
        val tokens = arrayOf("こん", "にちは")
        val fallback = listOf(WhisperCue(2000, 3000, "こんにちは"))
        for (timestamps in listOf(
            floatArrayOf(), floatArrayOf(0f), floatArrayOf(Float.NaN, 0.5f),
            floatArrayOf(0.8f, 0.5f), floatArrayOf(0f, 1f),
        )) {
            assertEquals(fallback, parakeetSegmentToCues("こんにちは", tokens, timestamps, 2000, 3000))
        }
        assertTrue(parakeetSegmentToCues("", emptyArray(), floatArrayOf(), 2000, 3000).isEmpty())
    }

    @Test
    fun parakeetFallsBackToOriginalTextWhenPiecesDoNotMatch() {
        val cues = parakeetSegmentToCues(
            "はい。そうです。", arrayOf("はい", "そうです"), floatArrayOf(0f, 0.8f), 2_000, 3_000,
        )
        assertEquals(listOf(WhisperCue(2_000, 3_000, "はい。そうです。")), cues)
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
