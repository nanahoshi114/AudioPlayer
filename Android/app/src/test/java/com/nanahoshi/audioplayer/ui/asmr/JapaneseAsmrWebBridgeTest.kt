package com.nanahoshi.audioplayer.ui.asmr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JapaneseAsmrWebBridgeTest {
    @Test
    fun decodeJsStringUnescapesJsonLiteral() {
        assertEquals("a\"b\\c\nd", decodeJsString("\"a\\\"b\\\\c\\nd\""))
        assertNull(decodeJsString(null))
        assertNull(decodeJsString("null"))
        assertNull(decodeJsString(""))
        assertNull(decodeJsString("true"))
    }

    @Test
    fun decodeJsBooleanOnlyAcceptsTrue() {
        assertTrue(decodeJsBoolean("true"))
        assertFalse(decodeJsBoolean("false"))
        assertFalse(decodeJsBoolean(null))
        assertFalse(decodeJsBoolean("\"true\""))
    }

    @Test
    fun decodeBuzzProbeParsesNestedJson() {
        val inner = """{"direct":"https://buzzheavier.com/f/a?x=\"1\"","file":null,"next":""}"""
        val result = "\"" + inner.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val probe = decodeBuzzProbe(result)!!
        assertEquals("https://buzzheavier.com/f/a?x=\"1\"", probe.direct)
        assertNull(probe.file)
        assertNull(probe.next)
        assertNull(decodeBuzzProbe("null"))
        assertNull(decodeBuzzProbe("\"not json\""))
    }

    @Test
    fun decodeVisibleArticleParsesAndLimitsSize() {
        val inner = """{"pageUrl":"https://example.com/p","html":"<p>\"x\"</p>"}"""
        val result = "\"" + inner.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val article = decodeVisibleArticle(result)!!
        assertEquals("https://example.com/p", article.pageUrl)
        assertEquals("<p>\"x\"</p>", article.html)
        val big = """{"pageUrl":"u","html":"${"a".repeat(512_001)}"}"""
        assertNull(decodeVisibleArticle("\"" + big.replace("\"", "\\\"") + "\""))
        assertNull(decodeVisibleArticle(null))
    }

    @Test
    fun challengeAddressDetection() {
        assertTrue(isChallengeAddress("https://x.com/cdn-cgi/challenge"))
        assertTrue(isChallengeAddress("https://x.com/Challenge-Platform/h"))
        assertFalse(isChallengeAddress("https://x.com/file"))
    }

    @Test
    fun followUsesSavedFetch() {
        val script = JapaneseAsmrWebScripts.BUZZ_CAPTURE
        assertTrue(script.contains("originalFetch.call(window, url"))
        assertFalse(Regex("""(?<![.\w])fetch\(url""").containsMatchIn(script))
    }
}
