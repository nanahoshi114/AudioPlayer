package com.nanahoshi.audioplayer.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerValidationTest {
    @Test
    fun presetsAndSecondOnlyInputsAreValid() {
        val fifteenMinutes = validateSleepTimerInput("15", "")
        assertEquals(15 * 60_000L, fifteenMinutes.totalMs)
        assertFalse(fifteenMinutes.secondsOutOfRange)
        assertTrue(fifteenMinutes.valid)

        val thirtyMinutes = validateSleepTimerInput("30", "")
        assertEquals(30 * 60_000L, thirtyMinutes.totalMs)
        assertTrue(thirtyMinutes.valid)

        val sixtyMinutes = validateSleepTimerInput("60", "")
        assertEquals(60 * 60_000L, sixtyMinutes.totalMs)
        assertTrue(sixtyMinutes.valid)

        val secondOnly = validateSleepTimerInput("0", "30")
        assertEquals(30_000L, secondOnly.totalMs)
        assertTrue(secondOnly.valid)

        val mixed = validateSleepTimerInput("2", "59")
        assertEquals(179_000L, mixed.totalMs)
        assertTrue(mixed.valid)
    }

    @Test
    fun emptyOrZeroInputIsInvalid() {
        for ((minutes, seconds) in listOf("" to "", "0" to "", "0" to "0", "00" to "00")) {
            val input = validateSleepTimerInput(minutes, seconds)
            assertEquals(0L, input.totalMs)
            assertFalse(input.valid)
        }
    }

    @Test
    fun secondsAboveFiftyNineFailValidationWithoutClamping() {
        val sixtySeconds = validateSleepTimerInput("1", "60")
        assertEquals(120_000L, sixtySeconds.totalMs)
        assertTrue(sixtySeconds.secondsOutOfRange)
        assertFalse(sixtySeconds.valid)

        val ninetyNineSeconds = validateSleepTimerInput("0", "99")
        assertEquals(99_000L, ninetyNineSeconds.totalMs)
        assertTrue(ninetyNineSeconds.secondsOutOfRange)
        assertFalse(ninetyNineSeconds.valid)
    }

    @Test
    fun nonNumericInputFallsBackToZero() {
        val input = validateSleepTimerInput("abc", "x")
        assertEquals(0L, input.totalMs)
        assertFalse(input.secondsOutOfRange)
        assertFalse(input.valid)
    }

    @Test
    fun partiallyNumericInputIsInvalid() {
        val badMinutes = validateSleepTimerInput("abc", "30")
        assertTrue(badMinutes.invalidInput)
        assertEquals(0L, badMinutes.totalMs)
        assertFalse(badMinutes.valid)

        val badSeconds = validateSleepTimerInput("1", "1x")
        assertTrue(badSeconds.invalidInput)
        assertEquals(0L, badSeconds.totalMs)
        assertFalse(badSeconds.valid)
    }

    @Test
    fun digitStringsTooLargeForLongAreInvalid() {
        val huge = "9".repeat(100)
        for (input in listOf(
            validateSleepTimerInput(huge, ""),
            validateSleepTimerInput("1", huge),
        )) {
            assertTrue(input.invalidInput)
            assertEquals(0L, input.totalMs)
            assertFalse(input.valid)
        }
    }

    @Test
    fun multiplicationOverflowIsInvalidInsteadOfWrapping() {
        val overflowingMinutes = validateSleepTimerInput(Long.MAX_VALUE.toString(), "")
        assertTrue(overflowingMinutes.overflow)
        assertEquals(0L, overflowingMinutes.totalMs)
        assertFalse(overflowingMinutes.valid)

        // 最大可换算分钟数之后只剩不足 59 秒的余量，再加 59 秒会溢出。
        val maxMinutes = (Long.MAX_VALUE / 60_000L).toString()
        val overflowingSum = validateSleepTimerInput(maxMinutes, "59")
        assertTrue(overflowingSum.overflow)
        assertEquals(0L, overflowingSum.totalMs)
        assertFalse(overflowingSum.valid)
    }
}