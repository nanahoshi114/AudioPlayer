package com.nanahoshi.audioplayer.importing

import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nanahoshi.audioplayer.bilibili.BiliPart
import com.nanahoshi.audioplayer.data.*
import com.nanahoshi.audioplayer.ui.importing.ClipboardImportDialog
import com.nanahoshi.audioplayer.ui.importing.importCandidate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardImportAndroidTest {
    @get:Rule val compose = createComposeRule()
    private val target = ClipboardTarget(ClipboardSource.BILIBILI, "BV1234567890")
    private val candidate = ClipboardImportCandidate(target, 1)

    @Test fun adapterAcceptsTextAndWebUrisWithoutOpeningContentOrIntents() {
        val clip = ClipData.newPlainText("fixture", "ordinary text")
        clip.addItem(ClipData.Item(target.id))
        assertEquals(target, importCandidate(clip)!!.target)
        fun uriClip(value: String) = ClipData(ClipDescription("fixture", arrayOf("text/uri-list")), ClipData.Item(Uri.parse(value)))
        assertEquals(ClipboardSource.YOUTUBE, importCandidate(uriClip("https://youtu.be/jNQXAC9IVRw"))!!.target.source)
        val combined = ClipData(ClipDescription("fixture", arrayOf("text/plain")),
            ClipData.Item("description", null, null, Uri.parse("https://youtu.be/jNQXAC9IVRw")))
        assertEquals(ClipboardSource.YOUTUBE, importCandidate(combined)!!.target.source)
        assertNull(importCandidate(uriClip("content://example.org/RJ123456")))
        assertNull(importCandidate(ClipData.newIntent("fixture", Intent(Intent.ACTION_VIEW, Uri.parse("https://youtu.be/jNQXAC9IVRw")))))
    }

    @Test fun loadingCannotImportAndReadyPreviewShowsTitleAndCoverBeforeConfirmation() {
        var state by mutableStateOf(ClipboardImportState(candidate))
        var imports = 0
        compose.setContent { MaterialTheme {
            ClipboardImportDialog(state, {}, { imports++ }, {})
        } }
        compose.onNodeWithText("是否要导入：").assertIsDisplayed()
        compose.onNodeWithText("导入").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(0, imports)
            state = state.copy(loading = false, preview = ClipboardImportPreview(target, "视频标题", null,
                parts = listOf(BiliPart(target.id, 42, 1, "one", "UP", 1000, ""))), selected = setOf(42))
        }
        compose.onNodeWithText("视频标题").assertIsDisplayed()
        compose.onNodeWithContentDescription("导入内容封面").assertExists()
        compose.runOnIdle { assertEquals(0, imports) }
        compose.onNodeWithText("选择入库方式").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, imports) }
    }

    @Test fun multiPartPreviewDefersSelectionToTheSharedImportDialog() {
        val parts = listOf(BiliPart(target.id, 42, 1, "one", "UP", 1000, ""), BiliPart(target.id, 43, 2, "two", "UP", 1000, ""))
        val state = ClipboardImportState(candidate, ClipboardImportPreview(target, "标题", null, parts = parts),
            selected = setOf(42, 43), loading = false)
        var choices = 0
        compose.setContent { MaterialTheme { ClipboardImportDialog(state, {}, { choices++ }, {}) } }
        compose.onNodeWithText("P2 · two").assertDoesNotExist()
        compose.onNodeWithText("选择入库方式").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, choices) }
    }
}
