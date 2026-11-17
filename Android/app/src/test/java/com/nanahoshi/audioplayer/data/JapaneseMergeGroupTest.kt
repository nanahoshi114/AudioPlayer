package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.playback.SubtitleCue
import org.junit.Assert.assertEquals
import org.junit.Test

class JapaneseMergeGroupTest {
    private fun cue(start: Long, end: Long, text: String = "あ") = SubtitleCue(start, end, text)

    @Test
    fun largeGapsDoNotSplitGroups() {
        // 真机样本：语义合并常跨 1~4 秒停顿，空隙不再作为分组约束。
        assertEquals(listOf(0..1), japaneseMergeGroups(listOf(cue(0, 1000), cue(2001, 2500))))
        assertEquals(listOf(0..1), japaneseMergeGroups(listOf(cue(0, 1000), cue(4344, 4800))))
    }

    @Test
    fun spanBoundary() {
        assertEquals(listOf(0..1), japaneseMergeGroups(listOf(cue(0, 1000), cue(1500, 12_000))))
        assertEquals(listOf(0..0, 1..1), japaneseMergeGroups(listOf(cue(0, 1000), cue(1500, 12_001))))
    }

    @Test
    fun spanUsesGroupMaxEndWithOverlappingCues() {
        val within = listOf(cue(0, 11_900), cue(5000, 6000), cue(6500, 12_000))
        assertEquals(listOf(0..2), japaneseMergeGroups(within))
        val over = listOf(cue(0, 11_900), cue(5000, 6000), cue(6500, 12_001))
        assertEquals(listOf(0..1, 2..2), japaneseMergeGroups(over))
    }

    @Test
    fun lineCountBoundary() {
        val cues = List(5) { cue(it * 200L, it * 200L + 100) }
        assertEquals(listOf(0..3, 4..4), japaneseMergeGroups(cues))
    }

    @Test
    fun characterBudget() {
        val eighty = listOf(cue(0, 500, "あ".repeat(40)), cue(600, 1100, "あ".repeat(40)))
        assertEquals(listOf(0..1), japaneseMergeGroups(eighty))
        val eightyOne = listOf(cue(0, 500, "あ".repeat(40)), cue(600, 1100, "あ".repeat(41)))
        assertEquals(listOf(0..0, 1..1), japaneseMergeGroups(eightyOne))
    }

    @Test
    fun oversizedSingleCueStandsAlone() {
        val cues = listOf(cue(0, 500, "あ".repeat(81)), cue(600, 1100))
        assertEquals(listOf(0..0, 1..1), japaneseMergeGroups(cues))
    }

    @Test
    fun overlappingTimesMayShareGroup() {
        assertEquals(listOf(0..1), japaneseMergeGroups(listOf(cue(0, 1000), cue(500, 1500))))
    }

    @Test
    fun emptyInput() {
        assertEquals(emptyList<IntRange>(), japaneseMergeGroups(emptyList()))
    }

    @Test
    fun qwenLimitsGroupByTwoAndEighteenSeconds() {
        val limits = MergeLimits.forModel("qwen-audio-3.1-asr")
        val cues = listOf(cue(0, 1000), cue(2000, 3000), cue(4000, 5000))
        assertEquals(listOf(0..1, 2..2), japaneseMergeGroups(cues, limits))
        // 15s 跨度在 18s 内可同组
        assertEquals(listOf(0..1), japaneseMergeGroups(listOf(cue(0, 1000), cue(2000, 15_000)), limits))
    }

    @Test
    fun lockedCueStandsAlone() {
        val cues = listOf(cue(0, 500), cue(600, 20_000), cue(20_500, 21_000))
        val locked = lockedCues(cues, MergeLimits.DEFAULT)
        assertEquals(listOf(false, true, false), locked.toList())
        assertEquals(listOf(0..0, 1..1, 2..2), japaneseMergeGroups(cues, MergeLimits.DEFAULT, locked))
    }
}
