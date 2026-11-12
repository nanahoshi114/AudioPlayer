package com.nanahoshi.audioplayer.ui.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BusyCounterTest {
    @Test
    fun beginsBusyAndEndsIdle() {
        val counter = BusyCounter()
        assertFalse(counter.busy.value)
        counter.begin()
        assertTrue(counter.busy.value)
        counter.end()
        assertFalse(counter.busy.value)
    }

    @Test
    fun overlappingOperationsKeepBusyUntilAllEnd() {
        val counter = BusyCounter()
        counter.begin()
        counter.begin()
        counter.end()
        assertTrue(counter.busy.value)
        counter.end()
        assertFalse(counter.busy.value)
    }

    @Test
    fun olderOperationEndingAfterNewerBeginDoesNotClearBusy() {
        val counter = BusyCounter()
        counter.begin() // 旧操作（例如被取消的 openJob，finally 晚到）
        counter.begin() // 新操作
        counter.end()
        assertTrue(counter.busy.value)
        counter.end()
        assertFalse(counter.busy.value)
    }

    @Test
    fun repeatedCyclesReturnToIdle() {
        val counter = BusyCounter()
        repeat(3) {
            counter.begin()
            counter.end()
            assertFalse(counter.busy.value)
        }
        counter.begin()
        assertTrue(counter.busy.value)
        counter.end()
        assertFalse(counter.busy.value)
    }
}