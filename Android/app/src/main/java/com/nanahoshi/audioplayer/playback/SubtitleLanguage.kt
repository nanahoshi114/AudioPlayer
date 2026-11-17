package com.nanahoshi.audioplayer.playback

import java.util.Locale

/** 只确认原文为简体的情况；不确定、混合语言及只有简繁同形字时保留翻译入口。 */
fun isSimplifiedChinese(cues: List<SubtitleCue>, language: String? = null): Boolean {
    val tag = normalizedSubtitleLanguage(language)
    if (tag.isNotEmpty() && tag != "zh") {
        val parts = tag.split('-')
        if (parts.first() != "zh" || "hant" in parts) return false
        return "hans" in parts || "cn" in parts || "sg" in parts
    }
    var hanCount = 0
    val simplified = mutableSetOf<Int>()
    for (cue in cues) {
        val text = subtitleMarkup.replace(cue.text, "")
        var index = 0
        while (index < text.length) {
            val point = Character.codePointAt(text, index)
            index += Character.charCount(point)
            if (point in traditionalOnly) return false
            if (Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN) {
                hanCount++
                if (point in simplifiedOnly) simplified += point
            } else if (Character.isLetter(point)) {
                return false
            }
        }
    }
    return hanCount >= 20 && simplified.size >= 3
}

fun canGenerateTranslation(cues: List<SubtitleCue>, language: String? = null): Boolean =
    cues.isNotEmpty() && !cues.hasTranslation() && !isSimplifiedChinese(cues, language)

internal fun usesJapaneseCorrection(cues: List<SubtitleCue>, language: String? = null): Boolean {
    val tag = normalizedSubtitleLanguage(language)
    val base = tag.substringBefore('-')
    if (base.isNotEmpty() && base != "und" && base != "auto") return base == "ja" || base == "jpn"
    var han = false
    var otherScript = false
    // 命中简繁专用字即视为中文证据（含短简体与繁体），但不立即返回：假名信号仍然优先。
    // 已知取舍：字表与日文新字体重叠（声/当/強/語 等），零假名纯汉字日文含这些字时
    // 降级为通用翻译（仍翻译，只是不做日文校对）；真实日文听写字幕几乎必含假名。
    var chineseEvidence = false
    for (cue in cues) {
        val text = subtitleMarkup.replace(cue.text, "")
        var index = 0
        while (index < text.length) {
            val point = Character.codePointAt(text, index)
            index += Character.charCount(point)
            if (!Character.isLetter(point)) continue
            when (Character.UnicodeScript.of(point)) {
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> return true
                Character.UnicodeScript.HAN -> {
                    han = true
                    if (point in traditionalOnly || point in simplifiedOnly) chineseEvidence = true
                }
                Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED -> Unit
                else -> otherScript = true
            }
        }
    }
    // 无专用字命中时 isSimplifiedChinese 必为 false，无需再全量扫描；纯共用汉字维持日文判定。
    return han && !otherScript && !chineseEvidence
}

private fun normalizedSubtitleLanguage(language: String?): String =
    language.orEmpty().trim().lowercase(Locale.ROOT).replace('_', '-').removePrefix("ai-")

// Android 的 ICU 正则要求字面的右花括号也转义，桌面 JVM 对此更宽松。
private val subtitleMarkup = Regex("""<[^>]*>|\{\\[^}]*\}""")

// OpenCC 1.1.9 派生字表；来源、派生规则与许可见 assets/licenses/OpenCC-NOTICE.txt。
private val simplifiedOnly by lazy { featureCharacters("simplified-only") }
private val traditionalOnly by lazy { featureCharacters("traditional-only") }

private fun featureCharacters(name: String): Set<Int> =
    checkNotNull(SubtitleCue::class.java.getResourceAsStream("/subtitles/$name.txt")) {
        "Missing bundled subtitle character data"
    }.bufferedReader(Charsets.UTF_8).use { reader ->
        val text = reader.readText().trim()
        val points = mutableSetOf<Int>()
        var index = 0
        while (index < text.length) {
            val point = Character.codePointAt(text, index)
            points += point
            index += Character.charCount(point)
        }
        points
    }
