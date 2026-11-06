package com.nanahoshi.audioplayer.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliRefreshScheduleTest {
    private val hour = 60 * 60 * 1000L
    private val minute = 60 * 1000L
    private val now = 1_000 * hour

    @Test fun firstCheckIsDueImmediately() {
        assertTrue(biliRefreshDueAt(0L, 0L, now))
    }

    @Test fun recentSuccessSuppressesCheckUntilTheIntervalElapses() {
        assertFalse(biliRefreshDueAt(now - hour, 0L, now))
        assertTrue(biliRefreshDueAt(now - 21 * hour, 0L, now))
    }

    @Test fun failureRetriesAfterTenMinutesInsteadOfTwentyHours() {
        val checked = now - hour
        assertFalse(biliRefreshDueAt(checked, now - 9 * minute, now))
        assertTrue(biliRefreshDueAt(checked, now - 11 * minute, now))
    }
}
