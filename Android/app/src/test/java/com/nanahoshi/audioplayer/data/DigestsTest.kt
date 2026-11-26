package com.nanahoshi.audioplayer.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class DigestsTest {
    @Test
    fun sha256MatchesKnownVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc"),
        )
    }

    @Test
    fun sha1AndMd5MatchKnownVectors() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", sha1Hex("abc"))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", md5Hex("abc"))
    }

    @Test
    fun streamHashingMatchesStringHashing() {
        assertEquals(sha256Hex("abc"), sha256Hex(ByteArrayInputStream("abc".toByteArray())))
    }

    @Test
    fun hexUsesLowercaseAndPadsEachByte() {
        assertEquals("000f10ff", byteArrayOf(0, 15, 16, -1).toHex())
    }

    @Test
    fun digestHexSelectsAlgorithm() {
        assertEquals(md5Hex("abc"), digestHex(MD5, "abc".toByteArray()))
    }
}