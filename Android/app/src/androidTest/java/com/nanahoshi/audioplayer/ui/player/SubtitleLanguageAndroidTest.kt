package com.nanahoshi.audioplayer.ui.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nanahoshi.audioplayer.playback.LoadedSubtitles
import com.nanahoshi.audioplayer.playback.SubtitleCue
import com.nanahoshi.audioplayer.playback.isSimplifiedChinese
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
}
