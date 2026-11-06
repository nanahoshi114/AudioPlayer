package com.nanahoshi.audioplayer.playback

import java.util.Locale

/** 只确认原文为简体的情况；不确定、混合语言及只有简繁同形字时保留翻译入口。 */
fun isSimplifiedChinese(cues: List<SubtitleCue>, language: String? = null): Boolean {
    val tag = language.orEmpty().trim().lowercase(Locale.ROOT).replace('_', '-').removePrefix("ai-")
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
