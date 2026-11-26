package com.nanahoshi.audioplayer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ByteSizeFormatterTest {
    @Test
    fun formatsCommonUnits() {
        assertEquals("—", ByteSizeFormatter.format(null))
        assertEquals("0 B", ByteSizeFormatter.format(0))
        assertEquals("1023 B", ByteSizeFormatter.format(1023))
        assertEquals("1.0 KiB", ByteSizeFormatter.format(1024))
        assertEquals("1.5 MiB", ByteSizeFormatter.format(1024L * 1024 + 512 * 1024))
        assertEquals("1.00 GiB", ByteSizeFormatter.format(1024L * 1024 * 1024))
    }

    @Test
    fun negativeValuesClampToZero() {
        assertEquals("0 B", ByteSizeFormatter.format(-5))
    }

    @Test
    fun wholeMiBRoundsToNearestInteger() {
        assertEquals(1L, ByteSizeFormatter.wholeMiB(1024L * 1024))
        assertEquals(2L, ByteSizeFormatter.wholeMiB(1024L * 1024 + 512 * 1024))
        assertEquals(0L, ByteSizeFormatter.wholeMiB(0))
    }
}