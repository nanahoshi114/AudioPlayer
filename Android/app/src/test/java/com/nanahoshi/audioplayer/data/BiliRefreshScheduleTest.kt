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

    private val ticketNow = 1_700_000_000L

    @Test fun expiredTicketIsNotFresh() {
        assertFalse(ticketIsFresh(ticketNow - 1, ticketNow))
    }

    @Test fun zeroExpiryIsNotFresh() {
        assertFalse(ticketIsFresh(0, ticketNow))
    }

    @Test fun exactlySixtySecondsIsNotFresh() {
        assertFalse(ticketIsFresh(ticketNow + 60, ticketNow))
    }

    @Test fun sixtyOneSecondsIsFresh() {
        assertTrue(ticketIsFresh(ticketNow + 61, ticketNow))
    }

    @Test fun negativeExpiryIsNotFresh() {
        assertFalse(ticketIsFresh(-100, ticketNow))
    }

    @Test fun farFutureExpiryIsFresh() {
        assertTrue(ticketIsFresh(ticketNow + 3600, ticketNow))
    }
}
