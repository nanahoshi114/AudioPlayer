package com.nanahoshi.audioplayer.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliTicketTest {
    private val now = 1_700_000_000L

    @Test
    fun expiredTicketIsNotFresh() {
        assertFalse(ticketIsFresh(now - 1, now))
    }

    @Test
    fun zeroExpiryIsNotFresh() {
        assertFalse(ticketIsFresh(0, now))
    }

    @Test
    fun exactlySixtySecondsIsNotFresh() {
        assertFalse(ticketIsFresh(now + 60, now))
    }

    @Test
    fun sixtyOneSecondsIsFresh() {
        assertTrue(ticketIsFresh(now + 61, now))
    }

    @Test
    fun negativeExpiryIsNotFresh() {
        assertFalse(ticketIsFresh(-100, now))
    }

    @Test
    fun farFutureExpiryIsFresh() {
        assertTrue(ticketIsFresh(now + 3600, now))
    }
}