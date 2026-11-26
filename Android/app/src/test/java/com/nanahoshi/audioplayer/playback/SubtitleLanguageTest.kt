package com.nanahoshi.audioplayer.playback

import org.junit.Assert.*
import org.junit.Test

class SubtitleLanguageTest {
    @Test
    fun japaneseCorrectionUsesExplicitLanguageAndDetectsUnlabeledJapanese() {
        assertTrue(usesJapaneseCorrection(cues("音声作品"), "ja-JP"))
        assertTrue(usesJapaneseCorrection(cues("今日はいい天気"), "ai_ja"))
        assertTrue(usesJapaneseCorrection(cues("もう起きてる？")))
        assertFalse(usesJapaneseCorrection(cues("これは日文"), "en"))
        assertFalse(usesJapaneseCorrection(cues("繁體中文字幕"), "zh-TW"))
        assertFalse(usesJapaneseCorrection(cues("A short English subtitle.")))
    }

    private val simplified = "这是我们为你准备的简体中文字幕，希望你听完以后能够轻松地入睡。"
    private fun cues(text: String, translation: String? = null) = listOf(SubtitleCue(0, 1000, text, translation))

    @Test fun explicitLanguageWinsEvenForShortOrAmbiguousText() {
        for (language in listOf("zh-CN", "zh-Hans", "zh-Hans-CN", "ZH_cn", "ai-zh-CN")) {
            assertTrue(language, isSimplifiedChinese(cues("你好"), language))
        }
        for (language in listOf("zh-TW", "zh-Hant", "zh-Hant-CN", "ja", "en", "ko")) {
            assertFalse(language, isSimplifiedChinese(cues(simplified), language))
        }
    }

    @Test fun missingOrGenericLanguageUsesOriginalTextAcrossTheWholeDocument() {
        for (language in listOf(null, "", "zh", "ai-zh")) {
            assertTrue(isSimplifiedChinese(cues(simplified), language))
        }
        val split = simplified.chunked(5).mapIndexed { index, text -> SubtitleCue(index * 1000L, (index + 1) * 1000L, text) }
        assertTrue(isSimplifiedChinese(split))
        assertTrue(isSimplifiedChinese(cues("<font color='red'>{\\an8}$simplified</font> 123！")))
    }

    @Test fun traditionalJapaneseMixedAndUncertainTextKeepTheAction() {
        for (text in listOf(
            "這是我們為你準備的繁體中文字幕，希望你聽完以後能夠輕鬆地入睡。",
            "今日はゆっくりお休みください。今夜は良い夢を見られますように。",
            "大家晚上好", "我们说", "山水日月天地人生".repeat(4),
            "这是一个人".repeat(8), // 字数多，但只有一种明确简体字。
            simplified + "好夢", simplified + "ありがとう", simplified + "hello", simplified + "안녕",
        )) assertTrue(text, canGenerateTranslation(cues(text)))
        assertFalse(canGenerateTranslation(emptyList()))
    }

    @Test fun unlabeledChineseTextDoesNotUseJapaneseCorrection() {
        // 繁体（命中繁体专用字表）与短简体（命中简体专用字表）都判为中文，不走日文校对。
        assertFalse(usesJapaneseCorrection(cues("這是我們為你準備的繁體中文字幕，希望你聽完以後能夠輕鬆地入睡。")))
        assertFalse(usesJapaneseCorrection(cues("我们说")))
        // 已知取舍：字表与日文新字体重叠，零假名纯汉字日文含这些字时降级为通用翻译。
        assertFalse(usesJapaneseCorrection(cues("音声作品")))
        // 纯共用汉字无专用字命中维持日文判定；假名信号仍然优先于汉字证据。
        assertTrue(usesJapaneseCorrection(cues("山水日月天地人生".repeat(4))))
        assertTrue(usesJapaneseCorrection(cues("这是一个人ありがとう")))
    }

    @Test fun translationsNeverCauseJapaneseOriginalToBeClassifiedAsChinese() {
        val translated = cues("今夜は良い夢を見てください。", simplified)
        assertFalse(isSimplifiedChinese(translated))
        assertFalse(canGenerateTranslation(translated))
        assertTrue(translated.hasTranslation())
        assertFalse(canGenerateTranslation(cues(simplified)))
        assertTrue(canGenerateTranslation(cues("日文", "")))
    }

    @Test fun generatedAndLrcSrtVttInputsUseTheSameOriginalTextRule() {
        val inputs = listOf(
            parseGeneratedCues(encodeGeneratedCues(cues(simplified))),
            parseSubtitleDocument("[00:00.00]$simplified"),
            parseSubtitleDocument("1\n00:00:00,000 --> 00:00:01,000\n$simplified\n"),
            parseSubtitleDocument("WEBVTT\n\n00:00:00.000 --> 00:00:01.000\n$simplified\n"),
        )
        inputs.forEach { assertFalse(canGenerateTranslation(it)) }
        assertFalse(LoadedSubtitles(inputs.first(), false, true).canTranslate)
        assertTrue(LoadedSubtitles(cues("おやすみ"), false, true).canTranslate)
    }

    @Test fun cuesClippedByNegativeOffsetCanStillBeTranslated() {
        val source = listOf(SubtitleCue(0, 1000, "こんにちは"))
        val loaded = LoadedSubtitles(source, generatedEmpty = false, generated = false)
            .copy(cues = emptyList(), appliedOffsetMs = -1_000L)
        assertTrue(loaded.canTranslate)
        assertEquals(source, loaded.sourceCues)
        assertEquals(source, loaded.unshiftedCues)
        assertTrue(loaded.cues.isEmpty())
    }
}
