package com.nanahoshi.audioplayer.ui.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nanahoshi.audioplayer.ui.components.DeleteAudioDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalRemovalDialogAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun mixedSelectionShowsBothCountsAndKeepsCancelSeparateFromSubtitleChoices() {
        val shown = mutableStateOf(true)
        val subtitles = mutableStateOf(1)
        val confirmed = mutableListOf<Boolean>()
        compose.setContent {
            MaterialTheme {
                if (shown.value) DeleteAudioDialog(
                    itemCount = 1,
                    associationCount = 2,
                    subtitleCount = subtitles.value,
                    onConfirm = { confirmed += it; shown.value = false },
                    onCancel = { shown.value = false },
                )
            }
        }

        compose.onNodeWithText("将删除 1 个 App 内下载副本，并取消 2 首曲目的本地关联", substring = true).assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle {
            assertEquals(emptyList<Boolean>(), confirmed)
            shown.value = true
        }
        compose.onNodeWithText("保留字幕").performClick()
        compose.runOnIdle { shown.value = true }
        compose.onNodeWithText("全部删除").performClick()
        compose.runOnIdle {
            assertEquals(listOf(false, true), confirmed)
            subtitles.value = 0
            shown.value = true
        }
        compose.onNodeWithText("保留字幕").assertDoesNotExist()
        compose.onNodeWithText("删除副本并取消关联").assertIsDisplayed()
    }

    @Test
    fun subtitleFilesOnlyHidesKeepSubtitlesAndConfirmsDeletion() {
        val shown = mutableStateOf(true)
        val confirmed = mutableListOf<Boolean>()
        compose.setContent {
            MaterialTheme {
                if (shown.value) DeleteAudioDialog(
                    itemCount = 0,
                    subtitleCount = 0,
                    workSubtitleCount = 2,
                    onConfirm = { confirmed += it; shown.value = false },
                    onCancel = { shown.value = false },
                )
            }
        }

        compose.onNodeWithText("删除选中的 2 个字幕文件", substring = true).assertIsDisplayed()
        compose.onNodeWithText("保留字幕").assertDoesNotExist()
        compose.onNodeWithText("删除").performClick()
        compose.runOnIdle { assertEquals(listOf(true), confirmed) }
    }

    @Test
    fun mixedAudioOtherFilesAndSubtitlesShowsCountsAndBothOptions() {
        val shown = mutableStateOf(true)
        val confirmed = mutableListOf<Boolean>()
        compose.setContent {
            MaterialTheme {
                if (shown.value) DeleteAudioDialog(
                    itemCount = 1,
                    subtitleCount = 1,
                    otherFileCount = 2,
                    workSubtitleCount = 1,
                    customSubtitleCount = 1,
                    onConfirm = { confirmed += it; shown.value = false },
                    onCancel = { shown.value = false },
                )
            }
        }

        compose.onNodeWithText("1 个音频下载副本和 2 个附属文件", substring = true).assertIsDisplayed()
        compose.onNodeWithText("包含 1 个作品内字幕文件", substring = true).assertIsDisplayed()
        compose.onNodeWithText("包含 1 个仅当前曲目使用的导入字幕副本", substring = true).assertIsDisplayed()
        compose.onNodeWithText("保留字幕").performClick()
        compose.runOnIdle { shown.value = true }
        compose.onNodeWithText("全部删除").performClick()
        compose.runOnIdle { assertEquals(listOf(false, true), confirmed) }
    }
}
