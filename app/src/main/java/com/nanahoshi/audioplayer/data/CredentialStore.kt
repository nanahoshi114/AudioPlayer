package com.nanahoshi.audioplayer.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
class CredentialStore(context: Context) {
    private val appContext = context.applicationContext
    private val plainPrefs: SharedPreferences =
        appContext.getSharedPreferences("player_prefs", Context.MODE_PRIVATE)

    private val secretPrefs: SharedPreferences by lazy { createSecretPrefs() }

    fun cookie(): String? = secretPrefs.getString(KEY_COOKIE, null)?.takeIf { it.isNotBlank() }

    fun saveCookie(raw: String) {
        val value = raw.trim()
        if (value.isEmpty()) {
            clearCookie()
        } else {
            secretPrefs.edit().putString(KEY_COOKIE, value).apply()
        }
    }

    fun clearCookie() {
        secretPrefs.edit().remove(KEY_COOKIE).apply()
    }

    fun hasCookie(): Boolean = cookie() != null

    fun dlsiteCookie(): String? = secretPrefs.getString(KEY_DLSITE_COOKIE, null)?.takeIf { it.isNotBlank() }

    fun saveDlsiteCookie(raw: String) {
        val value = raw.trim()
        if (value.isEmpty()) {
            clearDlsiteCookie()
        } else {
            secretPrefs.edit().putString(KEY_DLSITE_COOKIE, value).apply()
        }
    }

    fun clearDlsiteCookie() {
        secretPrefs.edit().remove(KEY_DLSITE_COOKIE).apply()
    }

    fun buvid3(): String = plainPrefs.getString(KEY_BUVID, null).orEmpty()

    fun buvid4(): String = plainPrefs.getString(KEY_BUVID4, null).orEmpty()

    fun needsSpiBuvid(): Boolean {
        val current = buvid3()
        return current.isBlank() || !current.contains("-")
    }

    fun saveSpiBuvid(buvid3: String, buvid4: String) {
        plainPrefs.edit()
            .putString(KEY_BUVID, buvid3)
            .putString(KEY_BUVID4, buvid4)
            .apply()
    }

    fun ticket(): String? = plainPrefs.getString(KEY_TICKET, null)?.takeIf { it.isNotBlank() }

    fun ticketExpiresAt(): Long = plainPrefs.getLong(KEY_TICKET_EXPIRES, 0L)

    fun hasFreshTicket(): Boolean = ticketExpiresAt() > System.currentTimeMillis() / 1000 + 60

    fun saveTicket(ticket: String, expiresAtEpochSec: Long) {
        plainPrefs.edit()
            .putString(KEY_TICKET, ticket)
            .putLong(KEY_TICKET_EXPIRES, expiresAtEpochSec)
            .apply()
    }

    fun clearTicket() {
        plainPrefs.edit().remove(KEY_TICKET).putLong(KEY_TICKET_EXPIRES, 0L).apply()
    }

    fun guestCookieHeader(): String = buildList {
        val buvid3 = buvid3()
        if (buvid3.contains("-")) add("buvid3=$buvid3")
        val buvid4 = buvid4()
        if (buvid4.isNotBlank()) add("buvid4=$buvid4")
    }.joinToString("; ")

    fun cookieHeader(): String {
        val user = cookie()?.replace("\r", "")?.replace("\n", "")?.trim()
        val parts = mutableListOf<String>()
        val buvid3 = buvid3()
        if (buvid3.contains("-")) parts += "buvid3=$buvid3"
        if (buvid4().isNotBlank()) parts += "buvid4=${buvid4()}"
        ticket()?.let { parts += "bili_ticket=$it" }
        if (ticketExpiresAt() > 0) parts += "bili_ticket_expires=${ticketExpiresAt()}"
        if (!user.isNullOrBlank()) {
            parts += if (user.contains("=")) stripManagedCookies(user) else "SESSDATA=$user"
        }
        return parts.filter { it.isNotBlank() }.joinToString("; ")
    }

    private fun stripManagedCookies(header: String): String =
        header.split(';')
            .map { it.trim() }
            .filter { part ->
                val key = part.substringBefore('=').trim()
                key.isNotBlank() && key !in MANAGED_COOKIE_KEYS
            }
            .joinToString("; ")

    private fun createSecretPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appContext,
            SECRET_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private companion object {
        const val SECRET_FILE = "bili_secret_prefs"
        const val KEY_COOKIE = "bili_cookie"
        const val KEY_DLSITE_COOKIE = "dlsite_cookie"
        const val KEY_BUVID = "buvid3"
        const val KEY_BUVID4 = "buvid4"
        const val KEY_TICKET = "bili_ticket"
        const val KEY_TICKET_EXPIRES = "bili_ticket_expires"
        val MANAGED_COOKIE_KEYS = setOf("buvid3", "buvid4", "bili_ticket", "bili_ticket_expires")
    }
}
