package com.nanahoshi.audioplayer.ui.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nanahoshi.audioplayer.playback.LoadedSubtitles
import com.nanahoshi.audioplayer.playback.SubtitleCue
import com.nanahoshi.audioplayer.playback.isSimplifiedChinese
import com.nanahoshi.audioplayer.playback.usesJapaneseCorrection
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 字幕初始化和正则必须在 Android ICU 上验证，桌面 JVM 不能覆盖其语法差异。 */
@RunWith(AndroidJUnit4::class)
class SubtitleLanguageAndroidTest {
    @Test fun startupWithEmptySubtitlesDoesNotFailClassInitialization() {
        assertFalse(LoadedSubtitles(emptyList(), false, false).canTranslate)
        assertFalse(LoadedSubtitles(listOf(SubtitleCue(0, 1000, "你好")), false, false, language = "zh-CN").canTranslate)
    }

    @Test fun markupAndBundledCharacterResourcesWorkOnAndroid() {
        val chinese = "这是我们为你准备的简体中文字幕，希望你听完以后能够轻松地入睡。"
        assertTrue(isSimplifiedChinese(listOf(SubtitleCue(0, 1000, "<font color='red'>{\\an8}$chinese</font>"))))
        assertTrue(LoadedSubtitles(listOf(SubtitleCue(0, 1000, "おやすみなさい")), false, false).canTranslate)
        assertTrue(LoadedSubtitles(listOf(SubtitleCue(0, 1000, chinese + "hello")), false, false).canTranslate)
    }

    @Test fun unlabeledChineseIsNotTreatedAsJapaneseOnAndroid() {
        // 字表资源与 ICU 判定须在 Android 上一致：专用简繁字视为中文证据，共用汉字仍按日文处理。
        assertFalse(usesJapaneseCorrection(listOf(SubtitleCue(0, 1000, "這是一個測試"))))
        assertFalse(usesJapaneseCorrection(listOf(SubtitleCue(0, 1000, "我们说"))))
        assertTrue(usesJapaneseCorrection(listOf(SubtitleCue(0, 1000, "山水日月天地人生"))))
        assertTrue(usesJapaneseCorrection(listOf(SubtitleCue(0, 1000, "这是一个人ありがとう"))))
    }
}
