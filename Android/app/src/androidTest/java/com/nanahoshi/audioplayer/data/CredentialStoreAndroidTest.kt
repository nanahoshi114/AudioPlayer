package com.nanahoshi.audioplayer.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CredentialStoreAndroidTest {
    private fun isolatedStore(): CredentialStore {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) =
                app.getSharedPreferences("credential-test-$suffix-$name", mode)
        }
        return CredentialStore(context)
    }

    @Test
    fun expiredTicketIsOmittedButFreshTicketIsSent() {
        val store = isolatedStore()
        store.saveSpiBuvid("00000000-0000-0000-0000-000000000000", "")
        val nowSec = System.currentTimeMillis() / 1000
        store.saveTicket("fixture-ticket", nowSec - 10)
        val expired = store.cookieHeader()
        assertTrue(expired.contains("buvid3="))
        assertFalse(expired.contains("bili_ticket"))
        store.saveTicket("fixture-ticket", nowSec + 3600)
        val fresh = store.cookieHeader()
        assertTrue(fresh.contains("bili_ticket=fixture-ticket"))
        assertTrue(fresh.contains("bili_ticket_expires="))
    }

    @Test
    fun savingNewSessionMakesRefreshDue() {
        val store = isolatedStore()
        store.saveCookie("SESSDATA=fixture")
        store.markBiliRefreshChecked()
        assertFalse(store.biliRefreshDue())
        store.saveBiliSession("SESSDATA=fixture2", "fixture-token")
        assertTrue(store.biliRefreshDue())
    }

    @Test
    fun savingChangedCookieClearsRefreshTokenAndPendingRevocation() {
        val store = isolatedStore()
        store.saveBiliSession("SESSDATA=old; bili_jct=jct", "old-refresh")
        store.saveCookie("SESSDATA=new; bili_jct=jct")
        assertNull(store.biliRefreshToken())
        assertNull(store.pendingBiliRevokeToken())
        assertEquals("SESSDATA=new; bili_jct=jct", store.cookie())
    }

    @Test
    fun ticketAloneIsNotAWebLoginSession() {
        val store = isolatedStore()
        val nowSec = System.currentTimeMillis() / 1000
        store.saveTicket("ticket-only", nowSec + 3600)
        assertFalse(store.hasCookie())
        assertNull(store.cookie())
        val header = store.cookieHeader()
        assertTrue(header.contains("bili_ticket=ticket-only"))
        assertFalse(header.contains("SESSDATA="))
    }

    @Test
    fun clearCookieDropsPendingRevocationAndAdvancesGeneration() {
        val store = isolatedStore()
        store.saveBiliSession("SESSDATA=old; bili_jct=jct", "old-refresh")
        val generation = store.biliSession.value
        assertTrue(store.commitRefreshedBiliSession(generation, "SESSDATA=new; bili_jct=jct", "new-refresh", "old-refresh"))
        assertEquals("old-refresh", store.pendingBiliRevokeToken())

        val beforeClear = store.biliSession.value
        store.clearCookie()
        assertNull(store.cookie())
        assertNull(store.biliRefreshToken())
        assertNull(store.pendingBiliRevokeToken())
        assertTrue(store.biliSession.value > beforeClear)
    }

    @Test
    fun japaneseProofreadAndMergeDefaultOffAndPersist() {
        val store = isolatedStore()
        assertFalse(store.translateJapaneseProofread())
        assertFalse(store.translateJapaneseMerge())
        store.saveTranslateJapaneseProofread(true)
        store.saveTranslateJapaneseMerge(true)
        assertTrue(store.translateJapaneseProofread())
        assertTrue(store.translateJapaneseMerge())
        store.saveTranslateJapaneseProofread(false)
        assertFalse(store.translateJapaneseProofread())
        assertTrue(store.translateJapaneseMerge())
    }

    @Test
    fun staleGenerationCannotOverwriteAClearedOrSwitchedSession() {
        val store = isolatedStore()
        store.saveBiliSession("SESSDATA=old; bili_jct=jct", "old-refresh")
        val stale = store.biliSessionSnapshot()
        store.clearCookie()
        assertFalse(store.commitRefreshedBiliSession(stale.generation, "SESSDATA=stale", "stale-token", "old-refresh"))
        assertNull(store.cookie())

        store.saveBiliSession("SESSDATA=other; bili_jct=jct", "other-refresh")
        assertFalse(store.commitRefreshedBiliSession(stale.generation, "SESSDATA=stale", "stale-token", "old-refresh"))
        assertEquals("SESSDATA=other; bili_jct=jct", store.cookie())
        assertEquals("other-refresh", store.biliRefreshToken())
    }
}