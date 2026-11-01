package com.nanahoshi.audioplayer.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.content.pm.PackageInfoCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nanahoshi.audioplayer.data.*
import com.nanahoshi.audioplayer.ui.settings.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AboutAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Suppress("DEPRECATION")
    @Test fun currentVersionIsTheInstalledPackageVersion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expected = context.packageManager.getPackageInfo(context.packageName,0)
        val actual = installedAppVersion(context)
        assertEquals(expected.versionName,actual.name)
        assertEquals(PackageInfoCompat.getLongVersionCode(expected),actual.code)
    }
    @Test fun enteringAboutDoesNotCheckOrOpenTheBrowserUntilClicked() {
        var checks = 0;var page: String? = null
        compose.setContent { MaterialTheme {
            AboutContent(InstalledAppVersion("1.2",3),UpdateStatus.Idle,{}, { checks++ }, { page = it })
        } }
        compose.onNodeWithText("当前版本：1.2（3）").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0,checks);assertNull(page) }
        compose.onNodeWithText("检查更新").performClick()
        compose.runOnIdle { assertEquals(1,checks);assertNull(page) }
        compose.onNodeWithText("在浏览器打开发布页").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(APP_RELEASES_URL,page) }
    }
    @Test fun availableUpdateOpensReleasePageAndCheckingDisablesRepeatRequests() {
        var page: String? = null
        val release = AppRelease("v1.3","v1.3")
        val status = mutableStateOf<UpdateStatus>(UpdateStatus.Checking)
        compose.setContent { MaterialTheme {
            AboutContent(InstalledAppVersion("1.2",3),status.value,{}, {}, { page = it })
        } }
        compose.onNodeWithText("检查更新").assertIsNotEnabled()
        compose.runOnIdle { status.value = UpdateStatus.Ready(release,UpdateRelation.AVAILABLE) }
        compose.onNodeWithText("发现新版本 v1.3").assertIsDisplayed()
        compose.runOnIdle { assertNull(page) }
        compose.onAllNodesWithText("下载",substring=false).assertCountEquals(0)
        compose.onNodeWithText("在浏览器打开发布页").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(release.pageUrl,page);assertFalse(page!!.endsWith(".apk")) }
    }
}
