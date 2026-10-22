package com.nanahoshi.audioplayer.bilibili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliSessionTest {
    @Test
    fun loginUrlKeepsSessionFieldsAndEncodesComma() {
        val cookie = sessionCookiesFromUrl(
            "https://passport.biligame.com/x/passport-login/web/crossDomain" +
                "?DedeUserID=12&DedeUserID__ckMd5=abcd&Expires=1&SESSDATA=abc%2Cdef" +
                "&bili_jct=jct&sid=sid&gourl=https%3A%2F%2Fwww.bilibili.com",
        )
        assertEquals(
            "SESSDATA=abc%2Cdef; bili_jct=jct; DedeUserID=12; DedeUserID__ckMd5=abcd; sid=sid",
            cookie,
        )
    }

    @Test
    fun setCookieOverridesTheSameFieldFromTheUrl() {
        val cookie = combineSessionCookies(
            sessionCookiesFromSetCookie(
                listOf(
                    "SESSDATA=from-header%2C1; Path=/; Domain=bilibili.com",
                    "bili_jct=new; Path=/",
                ),
            ),
            sessionCookiesFromUrl("https://example.com/?SESSDATA=from-url&bili_jct=old&sid=keep"),
        )
        assertEquals("SESSDATA=from-header%2C1; bili_jct=new; sid=keep", cookie)
        assertEquals("from-header%2C1", cookieField(cookie, "SESSDATA"))
    }

    @Test
    fun refreshCsrfReadsTheCorrespondPage() {
        val html = """<div id="1-name">b0cc8411ded2f9db2cff2edb3123acac</div>"""
        assertEquals("b0cc8411ded2f9db2cff2edb3123acac", refreshCsrf(html))
        assertNull(refreshCsrf("<html></html>"))
    }

    @Test
    fun correspondPathIsLowerHex() {
        val path = correspondPath(1_684_466_082_562)
        assertTrue(path.matches(Regex("[0-9a-f]{256}")))
    }
}
