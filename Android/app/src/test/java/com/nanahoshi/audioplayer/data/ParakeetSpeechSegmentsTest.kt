package com.nanahoshi.audioplayer.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParakeetSpeechSegmentsTest {
    @Test
    fun speechCrossesTheOldThirtySecondBoundaryAndKeepsThePartialLastFrame() {
        val result = windows(31_005) { ms -> if (ms in 29_600..31_005) 0.9f else 0f }
        val only = result.single()
        assertTrue(only.coreStart < samples(30_000))
        assertEquals(samples(31_005), only.coreEnd)
        assertEquals(only.coreEnd, only.inputEnd)
        assertFalse(only.hasOverlap)
    }

    @Test
    fun longestQuietRunWinsAndEqualRunsPreferTheLaterCut() {
        val result = windows(29_000) { ms ->
            if (ms in 22_720..22_880 || ms in 24_000..24_160 || ms in 25_280..25_344) 0.1f else 0.9f
        }
        assertEquals(2, result.size)
        assertEquals(samples(24_096), result[0].coreEnd)
        assertEquals(result[0].inputEnd, result[1].inputStart)
        assertFalse(result.any { it.hasOverlap })
    }

    @Test
    fun lowHumanVoiceProbabilityCanBeUsedWithoutAFullQuietRun() {
        val result = windows(29_000) { ms -> if (ms in 24_000..24_064) 0.3f else 0.9f }
        assertEquals(samples(24_048), result[0].coreEnd)
        assertFalse(result.any { it.hasOverlap })
    }

    @Test
    fun sustainedSpeechGetsContextOnBothSidesAndRespectsTheInputLimit() {
        val result = windows(80_005) { 0.9f }
        assertEquals(4, result.size)
        for ((left, right) in result.zipWithNext()) {
            assertEquals(left.coreEnd, right.coreStart)
            assertEquals(samples(26_000), left.coreEnd - left.coreStart)
            assertEquals(samples(800), left.inputEnd - left.coreEnd)
            assertEquals(samples(800), right.coreStart - right.inputStart)
        }
        assertEquals(samples(80_005), result.last().inputEnd)
        assertTrue(result.all { it.inputEnd - it.inputStart <= samples(28_000) })
    }

    @Test
    fun naturalSpeechEndingNearTheLimitDoesNotMakeAnExtraCore() {
        val result = windows(27_000) { ms -> if (ms < 25_888) 0.9f else 0.1f }
        val only = result.single()
        assertEquals(samples(25_888), only.coreEnd)
        assertEquals(samples(26_088), only.inputEnd)
        assertFalse(only.hasOverlap)
    }

    @Test
    fun endOfFileAtTheCoreLimitDoesNotLeaveAnOverlapWithoutANeighbor() {
        val result = windows(26_000) { 0.9f }
        val only = result.single()
        assertEquals(samples(26_000), only.coreEnd)
        assertEquals(only.coreEnd, only.inputEnd)
        assertFalse(only.hasOverlap)
    }

    @Test
    fun confirmedSilencePadsBothEndsWithoutOverlappingOrEmittingSilenceOnly() {
        val result = windows(3_000) { ms ->
            if (ms in 320..959 || ms in 1_504..2_047) 0.9f else 0.1f
        }
        assertEquals(2, result.size)
        assertEquals(samples(120), result[0].inputStart)
        assertEquals(samples(1160), result[0].inputEnd)
        assertEquals(samples(1304), result[1].inputStart)
        assertEquals(samples(2248), result[1].inputEnd)
        assertTrue(windows(1_000) { 0f }.isEmpty())
    }

    @Test
    fun bufferKeepsBothChannelsInSyncAcrossWraparound() {
        val buffer = ParakeetAudioBuffer(2)
        val left = FloatArray(449_024) { it.toFloat() }
        val right = FloatArray(left.size) { -left[it] }
        val channels = arrayOf(left, right)
        for (offset in left.indices step PARAKEET_VAD_WINDOW) {
            buffer.append(channels, offset, PARAKEET_VAD_WINDOW)
        }
        val start = 448_500
        val actual = buffer.read(start.toLong(), left.size.toLong())
        assertArrayEquals(left.copyOfRange(start, left.size), actual[0], 0f)
        assertArrayEquals(right.copyOfRange(start, right.size), actual[1], 0f)
    }

    @Test
    fun joinsSentenceAcrossRecognitionBoundaryAndKeepsSharedTextOnce() {
        val left = transcript(leftWindow, piece(1_500, "今日は"), piece(1_860, "いい"), piece(2_020, "天気"))
        val right = transcript(rightWindow, piece(1_880, "いい天気"), piece(2_220, "ですね。"))
        assertEquals(
            listOf(WhisperCue(1_500, 2_300, "今日はいい天気ですね。")),
            stitchParakeetTranscripts(listOf(left, right)),
        )
    }

    @Test
    fun repeatedWordsAreKeptAndMismatchedPiecesUseTheirOwnCore() {
        val left = transcript(leftWindow, piece(1_300, "はい"), piece(1_550, "はい"), piece(2_080, "余分"))
        val right = transcript(rightWindow, piece(1_420, "別"), piece(2_030, "はい"), piece(2_240, "はい。"))
        assertEquals("はいはいはいはい。", stitchParakeetTranscripts(listOf(left, right)).joinToString("") { it.text })
    }

    @Test
    fun identicalWordsOnOppositeSidesOfTheCutAreNotDeduplicated() {
        val left = transcript(leftWindow, piece(1_890, "はい"))
        val right = transcript(rightWindow, piece(2_060, "はい"))
        assertEquals("はいはい", stitchParakeetTranscripts(listOf(left, right)).single().text)
    }

    @Test
    fun unmatchedRightCoreTextIsNotDiscardedByAMatchLaterInTheOverlap() {
        val left = transcript(leftWindow, piece(1_730, "左側"), piece(2_300, "共通"))
        val right = transcript(rightWindow, piece(2_020, "右側"), piece(2_300, "共通"), piece(2_500, "の続き"))
        val result = stitchParakeetTranscripts(listOf(left, right))
        assertEquals("左側右側共通の続き", result.joinToString("") { it.text })
    }

    @Test
    fun invalidTimedResultRetriesTheCoreExactlyOnceAndKeepsItsText() = runBlocking {
        val attempts = mutableListOf<ParakeetSpeechWindow>()
        val core = WhisperCue(0, 2_000, "全文")
        val recovered = recognizeParakeetWithContext(leftWindow) { window ->
            attempts += window
            ParakeetTranscript(window, null, core)
        }
        assertEquals(listOf(leftWindow, ParakeetSpeechWindow(0, samples(2_000), 0, samples(2_000))), attempts)
        val right = transcript(rightWindow, piece(1_900, "全文"), piece(2_080, "続き"))
        assertEquals(listOf(core, piece(2_080, "続き")), stitchParakeetTranscripts(listOf(recovered, right)))
    }

    @Test
    fun emptyCoreRetryPreservesTheFirstRecognitionAtCoreTimes() = runBlocking {
        val recovered = recognizeParakeetWithContext(rightWindow) { window ->
            if (window.hasOverlap) {
                ParakeetTranscript(window, null, WhisperCue(1_200, 4_000, "続き"))
            } else {
                ParakeetTranscript(window, emptyList())
            }
        }
        assertEquals(listOf(WhisperCue(2_000, 4_000, "続き")), stitchParakeetTranscripts(listOf(recovered)))
    }

    @Test
    fun cancellingRecognitionDoesNotStartAFallbackAttempt() = runBlocking {
        var calls = 0
        val failure = runCatching {
            recognizeParakeetWithContext(leftWindow) {
                calls++
                throw CancellationException("已中止")
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(1, calls)
    }

    private fun windows(durationMs: Int, scoreAt: (Int) -> Float): List<ParakeetSpeechWindow> = buildList {
        val detector = ParakeetSpeechSegments()
        for (start in 0 until durationMs * 16 step PARAKEET_VAD_WINDOW) {
            val count = minOf(PARAKEET_VAD_WINDOW, durationMs * 16 - start)
            detector.accept(scoreAt(start / 16), count, ::add)
        }
        detector.finish(::add)
    }

    private fun samples(ms: Int) = ms * 16L
    private fun piece(ms: Long, text: String) = WhisperCue(ms, ms + 80, text)
    private fun transcript(window: ParakeetSpeechWindow, vararg pieces: WhisperCue) =
        ParakeetTranscript(window, pieces.toList())

    private val leftWindow = ParakeetSpeechWindow(0, samples(2_000), 0, samples(2_800), overlapAfter = true)
    private val rightWindow = ParakeetSpeechWindow(
        samples(2_000), samples(4_000), samples(1_200), samples(4_000), overlapBefore = true,
    )
}
