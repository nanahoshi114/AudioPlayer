package com.nanahoshi.audioplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpByteRangeTest {
    @Test
    fun parsesACompleteRange() {
        assertEquals(HttpByteRange(0, 1023, 4096), HttpByteRange.parse("bytes 0-1023/4096"))
    }

    @Test
    fun acceptsWhitespaceAndCaseVariants() {
        assertEquals(HttpByteRange(10, 20, 100), HttpByteRange.parse("  BYTES 10-20/100 "))
    }

    @Test
    fun rejectsInvalidOrOutOfBoundsRanges() {
        assertNull(HttpByteRange.parse("bytes 10-5/100"))
        assertNull(HttpByteRange.parse("bytes 10-100/100"))
        assertNull(HttpByteRange.parse("bytes */100"))
        assertNull(HttpByteRange.parse(null))
    }

    @Test
    fun parsesUnsatisfiedTotalFor416() {
        assertEquals(4096L, HttpByteRange.unsatisfiedTotal("bytes */4096"))
        assertNull(HttpByteRange.unsatisfiedTotal("bytes 0-1/2"))
    }

    @Test
    fun lengthIsInclusive() {
        assertEquals(10L, HttpByteRange(5, 14, 100).length)
    }
}