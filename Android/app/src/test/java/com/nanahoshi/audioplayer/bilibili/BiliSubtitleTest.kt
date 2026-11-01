package com.nanahoshi.audioplayer.bilibili

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliSubtitleTest {
    @Test
    fun missingOrExpiredLoginIsReported() {
        assertTrue(biliNeedsLogin(hasSession = false, loginMid = 0L))
        // Cookie 还在、但接口已经不认这个登录态。
        assertTrue(biliNeedsLogin(hasSession = true, loginMid = 0L))
        assertTrue(biliNeedsLogin(hasSession = false, loginMid = 12345L))
    }

    @Test
    fun loggedInOrNoSubtitleIsNotLoginProblem() {
        assertFalse(biliNeedsLogin(hasSession = true, loginMid = 12345L))
    }
}
