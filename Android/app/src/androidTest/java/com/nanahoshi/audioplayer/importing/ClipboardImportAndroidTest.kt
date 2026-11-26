package com.nanahoshi.audioplayer.importing

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.bilibili.BiliPart
import com.nanahoshi.audioplayer.data.ClipboardImportPreview
import com.nanahoshi.audioplayer.data.ClipboardImportState
import com.nanahoshi.audioplayer.data.ClipboardSource
import com.nanahoshi.audioplayer.data.ClipboardTarget
import com.nanahoshi.audioplayer.data.ClipboardDlsiteWork
import com.nanahoshi.audioplayer.dlsite.DlsiteShowcase
import com.nanahoshi.audioplayer.ui.importing.ClipboardImportDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardImportAndroidTest {
    @get:Rule val compose = createComposeRule()
    private val strings = InstrumentationRegistry.getInstrumentation().targetContext
    private val target = ClipboardTarget(ClipboardSource.BILIBILI, "BV1234567890")

    @Test fun readyPreviewShowsTitleAndCoverBeforeConfirmation() {
        var state by mutableStateOf(ClipboardImportState(
            ClipboardImportPreview(target, "视频标题", null,
                parts = listOf(BiliPart(target.id, 42, 1, "one", "UP", 1000, "")))))
        var imports = 0
        compose.setContent { MaterialTheme {
            ClipboardImportDialog(state, {}, { imports++ }, {})
        } }
        compose.onNodeWithText(strings.getString(R.string.import_confirm_title)).assertIsDisplayed()
        compose.onNodeWithText("视频标题").assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.getString(R.string.a11y_import_cover)).assertExists()
        compose.runOnIdle { assertEquals(0, imports) }
        compose.onNodeWithText(strings.getString(R.string.import_choose_mode)).assertDoesNotExist()
        compose.onNodeWithText(strings.getString(R.string.import_action)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, imports) }
        compose.runOnIdle { state = state.copy(preview = state.preview.copy(alreadySaved = true)) }
        compose.onNodeWithText(strings.getString(R.string.import_already_saved)).assertIsDisplayed()
        compose.onNodeWithText(strings.getString(R.string.import_action)).assertDoesNotExist()
        compose.onNodeWithText(strings.getString(R.string.common_close)).assertIsEnabled()
        compose.onNodeWithText(strings.getString(R.string.import_choose_mode)).assertDoesNotExist()
    }

    @Test fun multiPartPreviewDefersSelectionToTheSharedImportDialog() {
        val parts = listOf(BiliPart(target.id, 42, 1, "one", "UP", 1000, ""), BiliPart(target.id, 43, 2, "two", "UP", 1000, ""))
        val state = ClipboardImportState(ClipboardImportPreview(target, "标题", null, parts = parts))
        var choices = 0
        compose.setContent { MaterialTheme { ClipboardImportDialog(state, {}, { choices++ }, {}) } }
        compose.onNodeWithText("P2 · two").assertDoesNotExist()
        compose.onNodeWithText(strings.getString(R.string.import_choose_mode)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, choices) }
    }

    @Test fun unpurchasedDlsiteWorkCanOpenDetails() {
        val work = ClipboardTarget(ClipboardSource.ASMR, "RJ01234567")
        val product = DlsiteShowcase(work.id, "商品标题", "", emptyList(), emptyList(), "",
            null, "maniax", emptyList(), emptyList())
        val state = ClipboardImportState(
            ClipboardImportPreview(work, product.title, null, dlsiteWork = ClipboardDlsiteWork(product, purchased = false)))
        var opened: String? = null
        compose.setContent { MaterialTheme { ClipboardImportDialog(state, {}, { error("Unexpected import") }) { opened = it } } }
        compose.onNodeWithText(strings.getString(R.string.import_no_source)).assertIsDisplayed()
        compose.onNodeWithText(strings.getString(R.string.import_action)).assertDoesNotExist()
        compose.onNodeWithText(strings.getString(R.string.common_retry)).assertDoesNotExist()
        compose.onNodeWithText(strings.getString(R.string.import_view_details)).performClick()
        compose.runOnIdle { assertEquals(work.id, opened) }
    }
}
