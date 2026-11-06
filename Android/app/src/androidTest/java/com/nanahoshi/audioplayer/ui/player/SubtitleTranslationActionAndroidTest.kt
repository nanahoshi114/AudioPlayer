package com.nanahoshi.audioplayer.ui.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nanahoshi.audioplayer.playback.LoadedSubtitles
import com.nanahoshi.audioplayer.playback.SubtitleCue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubtitleTranslationActionAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nativeSimplifiedGeneratedSimplifiedAndOtherLanguagesUpdateTheAction() {
        val subtitles = mutableStateOf(LoadedSubtitles(listOf(SubtitleCue(0, 1000, "你好")), false, false, language = "zh-CN"))
        val translating = mutableStateOf(false)
        compose.setContent {
            val loaded = subtitles.value
            MaterialTheme {
                SubtitleReader(
                    mediaId = 1, cues = loaded.cues, positionMs = 0, chinese = true,
                    translating = translating.value, canDelete = loaded.generated,
                    onChinese = {}, onJapanese = {}, onTranslate = {}, onStop = {},
                    polishing = false, onPolish = {}, onStopPolish = {}, onDelete = {},
                    onSeek = {}, onBack = {}, canTranslate = loaded.canTranslate,
                )
            }
        }
        compose.onNodeWithText("生成翻译").assertDoesNotExist()
        compose.runOnIdle {
            subtitles.value = LoadedSubtitles(listOf(SubtitleCue(0, 1000, "ありがとう")), false, false, language = "ja")
        }
        compose.onNodeWithText("生成翻译").assertIsDisplayed()
        compose.runOnIdle {
            subtitles.value = LoadedSubtitles(listOf(SubtitleCue(0, 1000,
                "这是我们为你准备的简体中文字幕，希望你听完以后能够轻松地入睡。")), false, true)
        }
        compose.onNodeWithText("生成翻译").assertDoesNotExist()
        compose.runOnIdle {
            subtitles.value = LoadedSubtitles(listOf(SubtitleCue(0, 1000, "大家晚上好")), false, false)
        }
        compose.onNodeWithText("生成翻译").assertIsDisplayed()
        compose.runOnIdle {
            subtitles.value = LoadedSubtitles(listOf(SubtitleCue(0, 1000, "おやすみ", "晚安")), false, true)
        }
        compose.onNodeWithText("生成翻译").assertDoesNotExist()
        compose.onNodeWithText("润色").assertIsDisplayed()
        compose.onNodeWithText("中文").assertIsDisplayed()
        compose.onNodeWithText("日文").assertIsDisplayed()
        compose.runOnIdle { translating.value = true }
        compose.onNodeWithText("中止").assertIsDisplayed()
    }
}
