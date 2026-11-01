package com.nanahoshi.audioplayer.data

import java.net.URI

enum class TranslatePlatform(
    val label: String,
    val baseUrl: String,
    val defaultModel: String,
    val efforts: List<TranslateEffort>,
    val defaultEffort: String,
) {
    QWEN(
        "千问AI平台",
        "https://maas.qianwenaiapi.com/compatible-mode/v1",
        "qwen3.8-flash",
        listOf(
            TranslateEffort("low", "低"),
            TranslateEffort("medium", "中"),
            TranslateEffort("xhigh", "高"),
        ),
        "medium",
    ),
    DEEPSEEK(
        "DeepSeek",
        "https://api.deepseek.com",
        "deepseek-flash",
        listOf(
            TranslateEffort("high", "高"),
            TranslateEffort("max", "最高"),
        ),
        "high",
    ),
    ;

    fun effortOrDefault(raw: String?): String =
        efforts.firstOrNull { it.value == raw?.trim() }?.value ?: defaultEffort
}

data class TranslateEffort(val value: String, val label: String)

internal fun translatePlatformOf(raw: String?): TranslatePlatform =
    TranslatePlatform.entries.firstOrNull { it.name == raw } ?: TranslatePlatform.QWEN

internal fun platformForLegacyBase(base: String?): TranslatePlatform? {
    val host = try {
        URI(base?.trim().orEmpty()).host?.lowercase()
    } catch (_: Exception) {
        null
    } ?: return null
    return when {
        "deepseek" in host -> TranslatePlatform.DEEPSEEK
        "qianwenaiapi" in host || "dashscope" in host -> TranslatePlatform.QWEN
        else -> null
    }
}

internal fun thinkingFields(
    platform: TranslatePlatform,
    enabled: Boolean,
    effort: String,
): Map<String, Any> {
    val level = platform.effortOrDefault(effort)
    return when (platform) {
        TranslatePlatform.QWEN -> if (enabled) {
            mapOf("enable_thinking" to true, "reasoning_effort" to level)
        } else {
            mapOf("enable_thinking" to false)
        }
        TranslatePlatform.DEEPSEEK -> if (enabled) {
            mapOf(
                "thinking" to mapOf("type" to "enabled"),
                "reasoning_effort" to level,
            )
        } else {
            mapOf("thinking" to mapOf("type" to "disabled"))
        }
    }
}
