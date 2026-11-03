package com.nanahoshi.audioplayer.bilibili

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nanahoshi.audioplayer.data.BiliImportRequests
import com.nanahoshi.audioplayer.ui.bilibili.BiliImportDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BiliImportDialogAndroidTest {
    @get:Rule val compose = createComposeRule()
    private val video = BiliVideoHit("BV1234567890", "video", "UP", "", 2000)
    private val parts = listOf(BiliPart(video.bvid, 41, 1, "one", "UP", 1000, ""),
        BiliPart(video.bvid, 42, 2, "two", "UP", 1000, ""))

    @Test fun splitModeExposesThePartChecklistAndRequiresOneSelection() {
        val requests = BiliImportRequests { null }
        var imported: List<BiliImportRequest>? = null
        compose.setContent { MaterialTheme {
            LaunchedEffect(Unit) { imported = requests.choose(listOf(BiliVideoDetails(video, parts))) }
            BiliImportDialog(requests)
        } }
        compose.onNodeWithText("继续").assertIsNotEnabled()
        compose.onNodeWithText("分开导入").performClick()
        compose.onNodeWithText("全选 / 取消全选", substring = true).performClick()
        compose.onNodeWithText("继续").assertIsNotEnabled()
        compose.onNodeWithText("P2 · two").performClick()
        compose.onNodeWithText("继续").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(parts[1]), imported!!.single().parts) }
    }

    @Test fun groupedModeRequiresExplicitConversionConfirmationAndIncludesAllParts() {
        val requests = BiliImportRequests { BiliImportMode.SPLIT }
        var imported: List<BiliImportRequest>? = null
        compose.setContent { MaterialTheme {
            LaunchedEffect(Unit) { imported = requests.choose(listOf(BiliVideoDetails(video, parts))) }
            BiliImportDialog(requests)
        } }
        compose.onNodeWithText("统一导入").performClick()
        compose.onNodeWithText("继续").performClick()
        compose.onNodeWithText("确认转换入库方式").assertIsDisplayed()
        compose.runOnIdle { assertNull(imported) }
        compose.onNodeWithText("确认转换并继续").performClick()
        compose.runOnIdle {
            assertTrue(imported!!.single().conversionConfirmed)
            assertEquals(parts, imported!!.single().parts)
        }
    }
}
