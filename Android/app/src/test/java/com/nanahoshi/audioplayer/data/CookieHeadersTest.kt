package com.nanahoshi.audioplayer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CookieHeadersTest {
    @Test
    fun stripsLineBreaksThatCouldInjectHeaders() {
        val sanitized = CookieHeaders.sanitize("a=1\r\nSet-Cookie: evil")
        assertEquals("a=1Set-Cookie: evil", sanitized)
        assertEquals(linkedMapOf("a" to "1", "b" to "2"), CookieHeaders.parse("a=1;\r\nb=2"))
    }

    @Test
    fun parsesAndTrimsPairs() {
        assertEquals(linkedMapOf("a" to "1", "b" to "2"), CookieHeaders.parse(" a = 1 ; b=2 ; broken ; =3"))
    }

    @Test
    fun laterHeadersOverrideEarlierOnes() {
        assertEquals("a=2; b=1", CookieHeaders.merge("a=1; b=1", "a=2"))
    }

    @Test
    fun filterDropsDisabledNames() {
        assertEquals("keep=1", CookieHeaders.merge("cf_clearance=x; keep=1", filter = { !it.startsWith("cf_") }))
    }

    @Test
    fun fieldReturnsOnlyNonEmptyValues() {
        assertEquals("1", CookieHeaders.field("a=1; b=", "a"))
        assertEquals(null, CookieHeaders.field("a=1; b=", "b"))
        assertEquals(null, CookieHeaders.field(null, "a"))
    }
}