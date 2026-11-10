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

        compose.onNodeWithText("将删除 1 个下载副本，并取消 2 首曲目的本地关联", substring = true).assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle {
            assertEquals(emptyList<Boolean>(), confirmed)
            shown.value = true
        }
        compose.onNodeWithText("保留生成字幕").performClick()
        compose.runOnIdle { shown.value = true }
        compose.onNodeWithText("删除音频和字幕").performClick()
        compose.runOnIdle {
            assertEquals(listOf(false, true), confirmed)
            subtitles.value = 0
            shown.value = true
        }
        compose.onNodeWithText("保留生成字幕").assertDoesNotExist()
        compose.onNodeWithText("确认").assertIsDisplayed()
    }
}
