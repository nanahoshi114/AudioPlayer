package com.nanahoshi.audioplayer.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** 安全偏好与普通偏好的固定名称；改变值会使旧凭证与迁移标记失效。 */
internal object CredentialKeys {
    const val SECRET_FILE = "bili_secret_prefs"
    const val KEY_COOKIE = "bili_cookie"
    const val KEY_BILI_REFRESH = "bili_refresh_token"
    const val KEY_BILI_PENDING_REVOKE_TOKEN = "bili_pending_revoke_token"
    const val KEY_BILI_REFRESH_CHECKED = "bili_refresh_checked_at"
    const val KEY_BILI_REFRESH_FAILED = "bili_refresh_failed_at"
    const val KEY_DLSITE_COOKIE = "dlsite_cookie"
    const val KEY_WHISPER_BASE = "whisper_base_url"
    const val KEY_WHISPER_TOKEN = "whisper_token"
    const val KEY_WHISPER_MODEL = "whisper_model"
    const val KEY_WHISPER_MIGRATED = "whisper_credentials_migrated"
    const val KEY_WHISPER_MODEL_MIGRATED_V2 = "whisper_model_migrated_v2"

    fun whisperBaseKey(model: String) = "whisper_base_$model"

    fun whisperTokenKey(model: String) = "whisper_token_$model"
    const val KEY_TRANSLATE_BASE = "translate_base_url"
    const val KEY_TRANSLATE_TOKEN = "translate_token"
    const val KEY_TRANSLATE_MODEL = "translate_model"
    const val KEY_TRANSLATE_PLATFORM = "translate_platform"
    const val KEY_TRANSLATE_THINKING = "translate_thinking"
    const val KEY_TRANSLATE_AUTO_POLISH = "translate_auto_polish"
    const val KEY_TRANSLATE_AUTO_CORRECT = "translate_auto_correct"
    const val KEY_TRANSLATE_JP_PROOFREAD = "translate_japanese_proofread"
    const val KEY_TRANSLATE_JP_MERGE = "translate_japanese_merge"
    const val KEY_TRANSLATE_MIGRATED = "translate_credentials_migrated"

    fun translateTokenKey(platform: TranslatePlatform) = "translate_token_${platform.name}"

    fun translateModelKey(platform: TranslatePlatform) = "translate_model_${platform.name}"

    fun translateEffortKey(platform: TranslatePlatform) = "translate_effort_${platform.name}"
    const val KEY_BUVID = "buvid3"
    const val KEY_BUVID4 = "buvid4"
    const val KEY_TICKET = "bili_ticket"
    const val KEY_TICKET_EXPIRES = "bili_ticket_expires"

    val MANAGED_COOKIE_KEYS = setOf("buvid3", "buvid4", "bili_ticket", "bili_ticket_expires")
}

internal fun normalizeWhisperBase(raw: String?): String? {
    val value = raw?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
    return value
}

internal fun createSecretPreferences(context: Context): SharedPreferences {
    val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    return EncryptedSharedPreferences.create(
        context,
        CredentialKeys.SECRET_FILE,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}