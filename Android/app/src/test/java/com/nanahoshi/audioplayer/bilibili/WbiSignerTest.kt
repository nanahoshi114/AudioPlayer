package com.nanahoshi.audioplayer.bilibili

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WbiSignerTest {
    private val mixin = "ea1db124af3c7062474693fa704f4ff8"

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    @Test
    fun mixinKeyFollowsThePublishedPermutation() {
        assertEquals(
            mixin,
            WbiSigner.mixinKey(
                "7cd084941338484aae1ad9425b84077c",
                "4932caff0ff746eab6f01bf08b70ac45",
            ),
        )
    }

    @Test
    fun fixedTimestampProducesDeterministicWtsAndRid() {
        val params = mapOf("foo" to "bar", "baz" to "qux")
        val signed = WbiSigner.signAt(params, mixin, 1_700_000_000L)

        assertEquals("1700000000", signed["wts"])
        assertEquals("baz=qux&foo=bar&wts=1700000000", WbiSigner.canonicalQuery(signed - "w_rid"))
        assertEquals(md5("baz=qux&foo=bar&wts=1700000000$mixin"), signed["w_rid"])
        assertEquals(signed, WbiSigner.signAt(params, mixin, 1_700_000_000L))
    }

    @Test
    fun forbiddenCharactersAreStrippedBeforeSortingAndEncoding() {
        val params = mapOf("a" to "1!2", "b" to "x y(z)", "z" to "日本")
        val signed = WbiSigner.signAt(params, mixin, 1_700_000_000L)

        assertEquals("a=12&b=x%20yz&wts=1700000000&z=%E6%97%A5%E6%9C%AC", WbiSigner.canonicalQuery(signed - "w_rid"))
        assertEquals(
            md5("a=12&b=x%20yz&wts=1700000000&z=%E6%97%A5%E6%9C%AC$mixin"),
            signed["w_rid"],
        )
        assertTrue(!signed["w_rid"].isNullOrBlank())
    }
}