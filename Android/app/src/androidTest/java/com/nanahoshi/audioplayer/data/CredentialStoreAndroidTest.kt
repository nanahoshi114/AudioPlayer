package com.nanahoshi.audioplayer.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
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
}