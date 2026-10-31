package com.nanahoshi.audioplayer.dlsite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DlsiteCookieTest {
    @Test
    fun dropsCloudflareCookies() {
        val merged = mergeDlsiteCookies(
            "session=abc; __cf_bm=bm; cf_clearance=clear; _cfuvid=uvid; cf_chl_2=challenge; adultchecked=1",
        )
        assertEquals("session=abc; adultchecked=1", merged)
    }

    @Test
    fun keepsDlsiteLookalikes() {
        val merged = mergeDlsiteCookies("__sess=one; cfx=two; cf=three")
        assertEquals("__sess=one; cfx=two; cf=three", merged)
        assertFalse(isCloudflareCookie("__sess"))
        assertTrue(isCloudflareCookie("__cf_bm"))
        assertTrue(isCloudflareCookie("CF_CLEARANCE"))
    }

    @Test
    fun laterHeadersReplaceSameName() {
        val merged = mergeDlsiteCookies("session=old; a=1", "session=new; b=2")
        assertEquals("session=new; a=1; b=2", merged)
    }

    @Test
    fun keepsEqualsInsideValue() {
        val merged = mergeDlsiteCookies("token=abc==; empty=; plain")
        assertEquals("token=abc==; empty=", merged)
    }

    @Test
    fun blankHeadersGiveEmpty() {
        assertEquals("", mergeDlsiteCookies(null, "", "   "))
        assertEquals("", mergeDlsiteCookies("__cf_bm=only"))
    }
}
