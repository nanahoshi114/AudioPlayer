package com.nanahoshi.audioplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkIdsTest {
    @Test
    fun findsAllSupportedPrefixes() {
        assertEquals("RJ01234567", WorkIds.find("作品 RJ01234567 的音频"))
        assertEquals("VJ123", WorkIds.find("vj123"))
        assertEquals("BJ000123", WorkIds.find("bj000123"))
        assertNull(WorkIds.find("没有编号"))
    }

    @Test
    fun canonicalKeepsPrefixAndRemovesOnlyLeadingZeros() {
        assertEquals("RJ1234567", WorkIds.canonical("RJ01234567"))
        assertEquals("BJ123", WorkIds.canonical("bj0000123"))
        assertEquals("VJ0", WorkIds.canonical("VJ0"))
    }

    @Test
    fun bareDigitsBecomeRjWithoutLosingLeadingZeros() {
        assertEquals("RJ01234567", WorkIds.fromInput("01234567"))
        assertEquals("RJ1234567", WorkIds.fromInput("1234567"))
        assertNull(WorkIds.fromInput("没有数字"))
    }

    @Test
    fun candidatesRestoreSixAndEightDigitSpellings() {
        assertEquals(listOf("RJ1234", "RJ00001234", "RJ001234"), WorkIds.candidates("RJ1234"))
        assertEquals(listOf("RJ01014447", "RJ1014447"), WorkIds.candidates("RJ01014447"))
    }

    @Test
    fun strictMatchRejectsEmbeddedAndLowercaseDirectoryNames() {
        assertTrue(WorkIds.matches("RJ01234567"))
        assertTrue(WorkIds.matches("BJ1"))
        assertFalse(WorkIds.matches("prefixRJ01234567"))
        assertFalse(WorkIds.matches("rj01234567"))
    }

    @Test
    fun partsSeparatesPrefixFromDigits() {
        assertEquals(WorkIds.Parts("VJ", "000123"), WorkIds.parts("vj000123"))
        assertNull(WorkIds.parts("RJ"))
    }
}