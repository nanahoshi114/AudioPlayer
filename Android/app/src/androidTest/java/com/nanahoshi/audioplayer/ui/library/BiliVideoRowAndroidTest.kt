package com.nanahoshi.audioplayer.ui.library

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nanahoshi.audioplayer.data.db.FolderEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BiliVideoRowAndroidTest {
    @get:Rule val compose = createComposeRule()
    private val video = FolderEntity(
        id = 1,
        name = "统一导入的多 P 视频",
        position = 0,
        biliBvid = "BV1234567890",
    )

    @Test fun groupedRowCanAppearAfterImportAndFilterChanges() {
        val folders = mutableStateOf(emptyList<FolderEntity>())
        val selecting = mutableStateOf(false)
        var opened = 0
        var removed = 0
        compose.setContent {
            MaterialTheme {
                LazyColumn {
                    items(folders.value, key = { it.id }) { folder ->
                        FolderRow(
                            folder = folder,
                            biliArtist = "测试 UP 主",
                            selected = selecting.value,
                            selecting = selecting.value,
                            onClick = { opened++ },
                            onLongClick = { selecting.value = true },
                            onCache = null,
                            onRecognize = null,
                            onDelete = { removed++ },
                            modifier = libraryMotionModifier().testTag("video-row"),
                        )
                    }
                }
            }
        }
        // Import publishes a new animated folder row into the existing list.
        compose.runOnIdle { folders.value = listOf(video) }
        compose.onNodeWithText("B站").assertIsDisplayed()
        compose.onNodeWithText("多P").assertIsDisplayed()
        compose.onNodeWithText("测试 UP 主").assertIsDisplayed()
        compose.onNodeWithTag("video-row").performClick()
        compose.runOnIdle { assertEquals(1, opened) }

        // Filtering away and back must remeasure the same grouped row safely.
        compose.runOnIdle { folders.value = emptyList() }
        compose.waitForIdle()
        compose.runOnIdle { folders.value = listOf(video) }
        compose.onNodeWithText(video.name).assertIsDisplayed()
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("从音频库移除").performClick()
        compose.runOnIdle { assertEquals(1, removed) }
        compose.onNodeWithTag("video-row").performTouchInput { longClick() }
        compose.onNodeWithText("多P").assertIsDisplayed()
    }

    @Test fun groupedBadgesCanMeasureAtNarrowWidthsAndLargeFontSizes() {
        val width = mutableStateOf(320.dp)
        val fontScale = mutableStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale.value)) {
                MaterialTheme {
                    LazyColumn(Modifier.width(width.value)) {
                        item {
                            FolderRow(
                                folder = video.copy(name = video.name.repeat(8)),
                                selected = false,
                                selecting = false,
                                onClick = {},
                                onLongClick = {},
                                onCache = null,
                                onRecognize = null,
                                onDelete = {},
                                modifier = libraryMotionModifier(),
                            )
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("UP 主未知").assertIsDisplayed()
        compose.runOnIdle { width.value = 240.dp; fontScale.value = 2f }
        compose.onNodeWithText("B站").assertExists()
        compose.onNodeWithText("多P").assertExists()
        compose.runOnIdle { width.value = 320.dp; fontScale.value = 1f }
        compose.onNodeWithText("多P").assertIsDisplayed()
    }
}
