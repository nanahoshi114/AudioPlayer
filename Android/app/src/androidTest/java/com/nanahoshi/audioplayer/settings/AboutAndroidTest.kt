package com.nanahoshi.audioplayer.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.data.APP_RELEASES_URL
import com.nanahoshi.audioplayer.data.AppRelease
import com.nanahoshi.audioplayer.data.UpdateRelation
import com.nanahoshi.audioplayer.ui.settings.AboutContent
import com.nanahoshi.audioplayer.ui.settings.InstalledAppVersion
import com.nanahoshi.audioplayer.ui.settings.UpdateStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AboutAndroidTest {
    @get:Rule val compose = createComposeRule()
    private val strings = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun enteringAboutDoesNotCheckOrOpenTheBrowserUntilClicked() {
        var checks = 0;var page: String? = null
        compose.setContent { MaterialTheme {
            AboutContent(InstalledAppVersion("1.2",3),UpdateStatus.Idle,{}, { checks++ }, { page = it })
        } }
        compose.onNodeWithText(strings.getString(R.string.about_version, "1.2", 3)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0,checks);assertNull(page) }
        compose.onNodeWithText(strings.getString(R.string.about_check_action)).performClick()
        compose.runOnIdle { assertEquals(1,checks);assertNull(page) }
        compose.onNodeWithText(strings.getString(R.string.about_open_page)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(APP_RELEASES_URL,page) }
    }

    @Test fun availableUpdateOpensReleasePageAndCheckingDisablesRepeatRequests() {
        var page: String? = null
        val release = AppRelease("v1.3","v1.3")
        val status = mutableStateOf<UpdateStatus>(UpdateStatus.Checking)
        compose.setContent { MaterialTheme {
            AboutContent(InstalledAppVersion("1.2",3),status.value,{}, {}, { page = it })
        } }
        compose.onNodeWithText(strings.getString(R.string.about_check_action)).assertIsNotEnabled()
        compose.runOnIdle { status.value = UpdateStatus.Ready(release,UpdateRelation.AVAILABLE) }
        compose.onNodeWithText(strings.getString(R.string.about_available, "v1.3")).assertIsDisplayed()
        compose.runOnIdle { assertNull(page) }
        compose.onAllNodesWithText(strings.getString(R.string.common_download),substring=false).assertCountEquals(0)
        compose.onNodeWithText(strings.getString(R.string.about_open_page)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(release.pageUrl,page);assertFalse(page!!.endsWith(".apk")) }
    }
}