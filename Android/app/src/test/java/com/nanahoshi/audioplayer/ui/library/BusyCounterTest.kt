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
    fun endWithoutMatchingBeginDoesNotLeaveTheCounterStuck() {
        val counter = BusyCounter()
        counter.end()
        assertFalse(counter.busy.value)
        counter.begin()
        counter.end()
        counter.end() // 取消路径遗留的多余 end() 不得把计数压成负数。
        assertFalse(counter.busy.value)
        counter.begin()
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