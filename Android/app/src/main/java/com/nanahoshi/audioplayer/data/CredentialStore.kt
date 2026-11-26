package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.youtube.normalizeYouTubeCookie

import android.content.Context
import android.content.SharedPreferences

class CredentialStore(context: Context) {
    private val appContext = context.applicationContext
    internal val plainPrefs: SharedPreferences =
        appContext.getSharedPreferences("player_prefs", Context.MODE_PRIVATE)

    internal val secretPrefs: SharedPreferences by lazy { createSecretPreferences(appContext) }
    private val biliLock = Any()
    private val whisperLock = Any()
    private val translateLock = Any()

    val biliSession = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val youtubeSession = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val biliRefreshFailure = kotlinx.coroutines.flow.MutableStateFlow(
        plainPrefs.getLong(CredentialKeys.KEY_BILI_REFRESH_FAILED, 0L) > 0L,
    )

    fun youtubeCookie(): String? = secretPrefs.getString("youtube_cookie", null)?.takeIf { it.isNotBlank() }

    fun saveYouTubeCookie(raw: String) {
        val value = normalizeYouTubeCookie(raw)
        if (value == youtubeCookie().orEmpty()) return
        secretPrefs.edit().apply {
            if (value.isBlank()) remove("youtube_cookie") else putString("youtube_cookie", value)
        }.apply()
        youtubeSession.value += 1
    }

    fun cookie(): String? = secretPrefs.getString(CredentialKeys.KEY_COOKIE, null)?.takeIf { it.isNotBlank() }

    fun saveCookie(raw: String) {
        synchronized(biliLock) {
            val value = raw.trim()
            if (value.isEmpty()) {
                clearCookie()
                return
            }
            val changed = value != cookie()
            val edit = secretPrefs.edit().putString(CredentialKeys.KEY_COOKIE, value)
            if (changed) {
                edit.remove(CredentialKeys.KEY_BILI_REFRESH)
                edit.remove(CredentialKeys.KEY_BILI_PENDING_REVOKE_TOKEN)
            }
            edit.apply()
            if (changed) {
                plainPrefs.edit().remove(CredentialKeys.KEY_BILI_REFRESH_CHECKED).remove(CredentialKeys.KEY_BILI_REFRESH_FAILED).apply()
                biliRefreshFailure.value = false
                biliSession.value += 1
            }
        }
    }

    fun saveBiliSession(cookie: String, refreshToken: String) {
        synchronized(biliLock) {
            val value = cookie.trim()
            if (value.isEmpty()) {
                clearCookie()
                return
            }
            val edit = secretPrefs.edit()
                .putString(CredentialKeys.KEY_COOKIE, value)
                .remove(CredentialKeys.KEY_BILI_PENDING_REVOKE_TOKEN)
            val token = refreshToken.trim()
            if (token.isEmpty()) edit.remove(CredentialKeys.KEY_BILI_REFRESH) else edit.putString(CredentialKeys.KEY_BILI_REFRESH, token)
            edit.apply()
            plainPrefs.edit().remove(CredentialKeys.KEY_BILI_REFRESH_CHECKED).remove(CredentialKeys.KEY_BILI_REFRESH_FAILED).apply()
            biliRefreshFailure.value = false
            biliSession.value += 1
        }
    }

    fun clearCookie() {
        synchronized(biliLock) {
            val changed = cookie() != null || biliRefreshToken() != null || pendingBiliRevokeToken() != null
            secretPrefs.edit()
                .remove(CredentialKeys.KEY_COOKIE)
                .remove(CredentialKeys.KEY_BILI_REFRESH)
                .remove(CredentialKeys.KEY_BILI_PENDING_REVOKE_TOKEN)
                .apply()
            plainPrefs.edit().remove(CredentialKeys.KEY_BILI_REFRESH_CHECKED).remove(CredentialKeys.KEY_BILI_REFRESH_FAILED).apply()
            biliRefreshFailure.value = false
            if (changed) biliSession.value += 1
        }
    }

    fun biliSessionSnapshot(): BiliSessionSnapshot = synchronized(biliLock) {
        BiliSessionSnapshot(
            generation = biliSession.value,
            cookie = cookie().orEmpty(),
            refreshToken = biliRefreshToken().orEmpty(),
            pendingRevokeToken = pendingBiliRevokeToken(),
        )
    }

    fun commitRefreshedBiliSession(
        expectedGeneration: Long,
        newCookie: String,
        newRefreshToken: String,
        oldRefreshToken: String,
    ): Boolean = synchronized(biliLock) {
        if (biliSession.value != expectedGeneration) return false
        val value = newCookie.trim()
        val token = newRefreshToken.trim()
        if (value.isEmpty() || token.isEmpty()) return false
        val edit = secretPrefs.edit()
            .putString(CredentialKeys.KEY_COOKIE, value)
            .putString(CredentialKeys.KEY_BILI_REFRESH, token)
        val pending = oldRefreshToken.trim()
        if (pending.isNotEmpty() && pending != token) {
            edit.putString(CredentialKeys.KEY_BILI_PENDING_REVOKE_TOKEN, pending)
        }
        edit.apply()
        true
    }

    fun pendingBiliRevokeToken(): String? = synchronized(biliLock) {
        secretPrefs.getString(CredentialKeys.KEY_BILI_PENDING_REVOKE_TOKEN, null)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun clearPendingBiliRevocation(expectedGeneration: Long, revokedToken: String): Boolean = synchronized(biliLock) {
        if (biliSession.value != expectedGeneration) return false
        val current = pendingBiliRevokeToken() ?: return true
        if (current != revokedToken.trim()) return false
        secretPrefs.edit().remove(CredentialKeys.KEY_BILI_PENDING_REVOKE_TOKEN).apply()
        true
    }

    fun biliRefreshToken(): String? = secretPrefs.getString(CredentialKeys.KEY_BILI_REFRESH, null)?.takeIf { it.isNotBlank() }

    fun hasBiliRefresh(): Boolean = biliRefreshToken() != null

    fun biliRefreshDue(): Boolean = biliRefreshDueAt(
        plainPrefs.getLong(CredentialKeys.KEY_BILI_REFRESH_CHECKED, 0L),
        plainPrefs.getLong(CredentialKeys.KEY_BILI_REFRESH_FAILED, 0L),
        System.currentTimeMillis(),
    )

    fun markBiliRefreshChecked(expectedGeneration: Long? = null) {
        synchronized(biliLock) {
            if (expectedGeneration != null && biliSession.value != expectedGeneration) return
            plainPrefs.edit()
                .putLong(CredentialKeys.KEY_BILI_REFRESH_CHECKED, System.currentTimeMillis())
                .remove(CredentialKeys.KEY_BILI_REFRESH_FAILED)
                .apply()
            biliRefreshFailure.value = false
        }
    }

    fun markBiliRefreshFailed(expectedGeneration: Long? = null) {
        synchronized(biliLock) {
            if (expectedGeneration != null && biliSession.value != expectedGeneration) return
            plainPrefs.edit().putLong(CredentialKeys.KEY_BILI_REFRESH_FAILED, System.currentTimeMillis()).apply()
            biliRefreshFailure.value = true
        }
    }

    fun biliRefreshFailed(): Boolean = plainPrefs.getLong(CredentialKeys.KEY_BILI_REFRESH_FAILED, 0L) > 0L

    fun hasCookie(): Boolean = cookie() != null

    fun dlsiteCookie(): String? = secretPrefs.getString(CredentialKeys.KEY_DLSITE_COOKIE, null)?.takeIf { it.isNotBlank() }

    fun saveDlsiteCookie(raw: String) {
        val value = raw.trim()
        if (value.isEmpty()) {
            clearDlsiteCookie()
        } else {
            secretPrefs.edit().putString(CredentialKeys.KEY_DLSITE_COOKIE, value).apply()
        }
    }

    fun clearDlsiteCookie() {
        secretPrefs.edit().remove(CredentialKeys.KEY_DLSITE_COOKIE).apply()
    }

    fun whisperBaseUrl(model: String? = null): String? {
        synchronized(whisperLock) {
            migrateWhisper()
            val id = knownWhisperModel(model) ?: resolvedWhisperModel()
            return normalizeWhisperBase(plainPrefs.getString(CredentialKeys.whisperBaseKey(id), null))
        }
    }

    fun whisperToken(model: String? = null): String? {
        synchronized(whisperLock) {
            migrateWhisper()
            val id = knownWhisperModel(model) ?: resolvedWhisperModel()
            return secretPrefs.getString(CredentialKeys.whisperTokenKey(id), null)?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    fun whisperCredentials(model: String? = null): Pair<String?, String?> = synchronized(whisperLock) {
        migrateWhisper()
        val id = knownWhisperModel(model) ?: resolvedWhisperModel()
        val configured = canonicalWhisperEndpoint(effectiveWhisperBase(id, whisperBaseUrl(id)), id)
        val token = secretPrefs.getString(CredentialKeys.whisperTokenKey(id), null)?.trim()?.takeIf { it.isNotEmpty() }
        configured to token
    }

    fun hasWhisper(model: String? = null): Boolean {
        synchronized(whisperLock) {
            migrateWhisper()
            val id = knownWhisperModel(model) ?: resolvedWhisperModel()
            return whisperToken(id) != null && effectiveWhisperBase(id, whisperBaseUrl(id)) != null
        }
    }

    fun whisperModel(): String? {
        synchronized(whisperLock) {
            migrateWhisper()
            return knownWhisperModel(plainPrefs.getString(CredentialKeys.KEY_WHISPER_MODEL, null))
        }
    }

    fun resolvedWhisperModel(): String {
        synchronized(whisperLock) {
            migrateWhisper()
            return whisperModel() ?: WHISPER_MODEL_QWEN
        }
    }

    fun saveWhisper(baseUrl: String, token: String, model: String) {
        synchronized(whisperLock) {
            migrateWhisper()
            val known = knownWhisperModel(model) ?: return
            plainPrefs.edit().putString(CredentialKeys.KEY_WHISPER_MODEL, known).apply()
            if (isOnDeviceWhisperModel(known)) return
            val base = normalizeWhisperBase(baseUrl)
            val edit = plainPrefs.edit()
            if (base == null) edit.remove(CredentialKeys.whisperBaseKey(known)) else edit.putString(CredentialKeys.whisperBaseKey(known), base)
            edit.apply()
            val secret = token.trim()
            if (secret.isEmpty()) {
                secretPrefs.edit().remove(CredentialKeys.whisperTokenKey(known)).apply()
            } else {
                secretPrefs.edit().putString(CredentialKeys.whisperTokenKey(known), secret).apply()
            }
        }
    }

    fun clearWhisper(model: String? = null) {
        synchronized(whisperLock) {
            migrateWhisper()
            val id = knownWhisperModel(model) ?: resolvedWhisperModel()
            plainPrefs.edit().remove(CredentialKeys.whisperBaseKey(id)).apply()
            secretPrefs.edit().remove(CredentialKeys.whisperTokenKey(id)).apply()
        }
    }

    fun translatePlatform(): TranslatePlatform {
        migrateTranslate()
        return translatePlatformOf(plainPrefs.getString(CredentialKeys.KEY_TRANSLATE_PLATFORM, null))
    }

    fun translateBaseUrl(): String = translatePlatform().baseUrl

    fun translateToken(platform: TranslatePlatform = translatePlatform()): String? {
        migrateTranslate()
        return secretPrefs.getString(CredentialKeys.translateTokenKey(platform), null)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun translateModelInput(platform: TranslatePlatform = translatePlatform()): String {
        migrateTranslate()
        return plainPrefs.getString(CredentialKeys.translateModelKey(platform), null)?.trim().orEmpty()
    }

    fun translateModel(platform: TranslatePlatform = translatePlatform()): String {
        val saved = translateModelInput(platform)
        return saved.ifEmpty { platform.defaultModel }
    }

    fun hasTranslate(): Boolean = translateToken() != null

    fun translateThinking(): Boolean {
        migrateTranslate()
        return plainPrefs.getBoolean(CredentialKeys.KEY_TRANSLATE_THINKING, false)
    }

    fun translateEffort(platform: TranslatePlatform = translatePlatform()): String {
        migrateTranslate()
        return platform.effortOrDefault(plainPrefs.getString(CredentialKeys.translateEffortKey(platform), null))
    }

    fun translateAutoPolish(): Boolean {
        migrateTranslate()
        return plainPrefs.getBoolean(CredentialKeys.KEY_TRANSLATE_AUTO_POLISH, false)
    }

    fun translateAutoCorrect(): Boolean {
        migrateTranslate()
        return plainPrefs.getBoolean(CredentialKeys.KEY_TRANSLATE_AUTO_CORRECT, false)
    }

    fun translateJapaneseProofread(): Boolean {
        migrateTranslate()
        return plainPrefs.getBoolean(CredentialKeys.KEY_TRANSLATE_JP_PROOFREAD, false)
    }

    fun translateJapaneseMerge(): Boolean {
        migrateTranslate()
        return plainPrefs.getBoolean(CredentialKeys.KEY_TRANSLATE_JP_MERGE, false)
    }

    fun translateConfig(): TranslateConfig = synchronized(translateLock) {
        migrateTranslate()
        val platform = translatePlatform()
        TranslateConfig(
            platform = platform,
            baseUrl = platform.baseUrl,
            token = translateToken(platform),
            model = translateModel(platform),
            thinking = translateThinking(),
            effort = translateEffort(platform),
        )
    }

    fun saveTranslate(platform: TranslatePlatform, token: String, model: String) {
        synchronized(translateLock) {
            migrateTranslate()
            plainPrefs.edit().putString(CredentialKeys.KEY_TRANSLATE_PLATFORM, platform.name).apply()
            val secret = token.trim()
            if (secret.isEmpty()) {
                secretPrefs.edit().remove(CredentialKeys.translateTokenKey(platform)).apply()
            } else {
                secretPrefs.edit().putString(CredentialKeys.translateTokenKey(platform), secret).apply()
            }
            val name = model.trim()
            if (name.isEmpty()) {
                plainPrefs.edit().remove(CredentialKeys.translateModelKey(platform)).apply()
            } else {
                plainPrefs.edit().putString(CredentialKeys.translateModelKey(platform), name).apply()
            }
        }
    }

    fun clearTranslate(platform: TranslatePlatform = translatePlatform()) {
        synchronized(translateLock) {
            migrateTranslate()
            plainPrefs.edit().remove(CredentialKeys.translateModelKey(platform)).apply()
            secretPrefs.edit().remove(CredentialKeys.translateTokenKey(platform)).apply()
        }
    }

    fun saveTranslateThinking(enabled: Boolean) {
        synchronized(translateLock) {
            migrateTranslate()
            plainPrefs.edit().putBoolean(CredentialKeys.KEY_TRANSLATE_THINKING, enabled).apply()
        }
    }

    fun saveTranslateEffort(platform: TranslatePlatform, effort: String) {
        synchronized(translateLock) {
            migrateTranslate()
            plainPrefs.edit().putString(CredentialKeys.translateEffortKey(platform), platform.effortOrDefault(effort)).apply()
        }
    }

    fun saveTranslateAutoPolish(enabled: Boolean) {
        migrateTranslate()
        plainPrefs.edit().putBoolean(CredentialKeys.KEY_TRANSLATE_AUTO_POLISH, enabled).apply()
    }

    fun saveTranslateAutoCorrect(enabled: Boolean) {
        migrateTranslate()
        plainPrefs.edit().putBoolean(CredentialKeys.KEY_TRANSLATE_AUTO_CORRECT, enabled).apply()
    }

    fun saveTranslateJapaneseProofread(enabled: Boolean) {
        migrateTranslate()
        plainPrefs.edit().putBoolean(CredentialKeys.KEY_TRANSLATE_JP_PROOFREAD, enabled).apply()
    }

    fun saveTranslateJapaneseMerge(enabled: Boolean) {
        migrateTranslate()
        plainPrefs.edit().putBoolean(CredentialKeys.KEY_TRANSLATE_JP_MERGE, enabled).apply()
    }

    fun buvid3(): String = plainPrefs.getString(CredentialKeys.KEY_BUVID, null).orEmpty()

    fun buvid4(): String = plainPrefs.getString(CredentialKeys.KEY_BUVID4, null).orEmpty()

    fun needsSpiBuvid(): Boolean {
        val current = buvid3()
        return current.isBlank() || !current.contains("-")
    }

    fun saveSpiBuvid(buvid3: String, buvid4: String) {
        plainPrefs.edit()
            .putString(CredentialKeys.KEY_BUVID, buvid3)
            .putString(CredentialKeys.KEY_BUVID4, buvid4)
            .apply()
    }

    fun ticket(): String? = plainPrefs.getString(CredentialKeys.KEY_TICKET, null)?.takeIf { it.isNotBlank() }

    fun ticketExpiresAt(): Long = plainPrefs.getLong(CredentialKeys.KEY_TICKET_EXPIRES, 0L)

    fun hasFreshTicket(): Boolean = ticketIsFresh(ticketExpiresAt(), System.currentTimeMillis() / 1000)

    fun saveTicket(ticket: String, expiresAtEpochSec: Long) {
        plainPrefs.edit()
            .putString(CredentialKeys.KEY_TICKET, ticket)
            .putLong(CredentialKeys.KEY_TICKET_EXPIRES, expiresAtEpochSec)
            .apply()
    }

    fun clearTicket() {
        plainPrefs.edit().remove(CredentialKeys.KEY_TICKET).putLong(CredentialKeys.KEY_TICKET_EXPIRES, 0L).apply()
    }

    fun guestCookieHeader(): String = buildList {
        val buvid3 = buvid3()
        if (buvid3.contains("-")) add("buvid3=$buvid3")
        val buvid4 = buvid4()
        if (buvid4.isNotBlank()) add("buvid4=$buvid4")
    }.joinToString("; ")

    fun cookieHeader(userCookie: String? = cookie()): String {
        val user = userCookie?.replace("\r", "")?.replace("\n", "")?.trim()
        val parts = mutableListOf<String>()
        val buvid3 = buvid3()
        if (buvid3.contains("-")) parts += "buvid3=$buvid3"
        if (buvid4().isNotBlank()) parts += "buvid4=${buvid4()}"
        if (hasFreshTicket()) {
            ticket()?.let { parts += "bili_ticket=$it" }
            parts += "bili_ticket_expires=${ticketExpiresAt()}"
        }
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
                key.isNotBlank() && key !in CredentialKeys.MANAGED_COOKIE_KEYS
            }
            .joinToString("; ")
}