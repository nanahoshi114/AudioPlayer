package com.nanahoshi.audioplayer.data

/** 首次读取时把旧版明文听写 / 翻译凭证迁移到按模型、按平台保存的结构。 */
internal fun CredentialStore.migrateWhisper() {
    migrateLegacyWhisper()
    migrateRemovedWhisperModels()
}

internal fun CredentialStore.migrateRemovedWhisperModels() {
    if (plainPrefs.getBoolean(CredentialKeys.KEY_WHISPER_MODEL_MIGRATED_V2, false)) return
    if (knownWhisperModel(plainPrefs.getString(CredentialKeys.KEY_WHISPER_MODEL, null)) == null) {
        plainPrefs.edit().putString(CredentialKeys.KEY_WHISPER_MODEL, WHISPER_MODEL_QWEN).apply()
    }
    plainPrefs.edit().putBoolean(CredentialKeys.KEY_WHISPER_MODEL_MIGRATED_V2, true).apply()
}

internal fun CredentialStore.migrateLegacyWhisper() {
    if (plainPrefs.getBoolean(CredentialKeys.KEY_WHISPER_MIGRATED, false)) return
    val legacyBase = normalizeWhisperBase(plainPrefs.getString(CredentialKeys.KEY_WHISPER_BASE, null))
    val legacyToken = secretPrefs.getString(CredentialKeys.KEY_WHISPER_TOKEN, null)?.trim()?.takeIf { it.isNotEmpty() }
    val stored = knownWhisperModel(plainPrefs.getString(CredentialKeys.KEY_WHISPER_MODEL, null))
    // 旧地址对应的三个模型已删除，只有 DashScope 的旧凭证还迁得动。
    val dashscope = legacyBase != null && qwenHttpOrigin(legacyBase) == QWEN_WHISPER_BASE
    val model = stored ?: if (dashscope) WHISPER_MODEL_QWEN else null
    if (model != null) {
        if (dashscope && plainPrefs.getString(CredentialKeys.whisperBaseKey(model), null).isNullOrBlank()) {
            plainPrefs.edit().putString(CredentialKeys.whisperBaseKey(model), legacyBase).apply()
        }
        if (dashscope && legacyToken != null && secretPrefs.getString(CredentialKeys.whisperTokenKey(model), null).isNullOrBlank()) {
            secretPrefs.edit().putString(CredentialKeys.whisperTokenKey(model), legacyToken).apply()
        }
        if (stored == null) {
            plainPrefs.edit().putString(CredentialKeys.KEY_WHISPER_MODEL, model).apply()
        }
    }
    plainPrefs.edit().remove(CredentialKeys.KEY_WHISPER_BASE).putBoolean(CredentialKeys.KEY_WHISPER_MIGRATED, true).apply()
    secretPrefs.edit().remove(CredentialKeys.KEY_WHISPER_TOKEN).apply()
}

internal fun CredentialStore.migrateTranslate() {
    if (plainPrefs.getBoolean(CredentialKeys.KEY_TRANSLATE_MIGRATED, false)) return
    val legacyBase = plainPrefs.getString(CredentialKeys.KEY_TRANSLATE_BASE, null)
    val platform = platformForLegacyBase(legacyBase)
    if (platform != null) {
        plainPrefs.edit().putString(CredentialKeys.KEY_TRANSLATE_PLATFORM, platform.name).apply()
        val legacyModel = plainPrefs.getString(CredentialKeys.KEY_TRANSLATE_MODEL, null)?.trim()?.takeIf { it.isNotEmpty() }
        val legacyToken = secretPrefs.getString(CredentialKeys.KEY_TRANSLATE_TOKEN, null)?.trim()?.takeIf { it.isNotEmpty() }
        if (legacyModel != null && plainPrefs.getString(CredentialKeys.translateModelKey(platform), null).isNullOrBlank()) {
            plainPrefs.edit().putString(CredentialKeys.translateModelKey(platform), legacyModel).apply()
        }
        if (legacyToken != null && secretPrefs.getString(CredentialKeys.translateTokenKey(platform), null).isNullOrBlank()) {
            secretPrefs.edit().putString(CredentialKeys.translateTokenKey(platform), legacyToken).apply()
        }
    }
    plainPrefs.edit()
        .remove(CredentialKeys.KEY_TRANSLATE_BASE)
        .remove(CredentialKeys.KEY_TRANSLATE_MODEL)
        .putBoolean(CredentialKeys.KEY_TRANSLATE_MIGRATED, true)
        .apply()
    secretPrefs.edit().remove(CredentialKeys.KEY_TRANSLATE_TOKEN).apply()
}