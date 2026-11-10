package com.nanahoshi.audioplayer.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nanahoshi.audioplayer.ui.components.WorkFileBrowser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkBrowserAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun loadingTheParentDoesNotMeasureItsSavedPositionAgainstTheChildRows() {
        val parent = LazyListState()
        val child = LazyListState()
        val location = mutableStateOf(parent)
        val count = mutableStateOf(80)
        val loading = mutableStateOf(false)
        lateinit var scope: CoroutineScope
        compose.setContent {
            scope = rememberCoroutineScope()
            MaterialTheme {
                WorkFileBrowser(
                    state = location.value,
                    modifier = Modifier.fillMaxSize(),
                    loading = loading.value,
                    header = {},
                    controls = {},
                ) {
                    items(count.value) { Text("文件 $it", Modifier.height(48.dp)) }
                }
            }
        }
        compose.runOnIdle { scope.launch { parent.scrollToItem(30) } }
        compose.waitUntil { parent.firstVisibleItemIndex == 30 }
        compose.runOnIdle {
            location.value = child
            count.value = 2
        }
        compose.onNodeWithText("文件 0").assertIsDisplayed()
        compose.runOnIdle {
            location.value = parent
            loading.value = true
        }
        compose.onNodeWithText("正在读取文件…").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(30, parent.firstVisibleItemIndex)
            loading.value = false
            count.value = 80
        }
        compose.onNodeWithText("文件 30").assertIsDisplayed()
        compose.runOnIdle { assertEquals(30, parent.firstVisibleItemIndex) }
    }

    @Test
    fun longPathsRevealTheCurrentFolderAndStillAllowClickingAnAncestor() {
        val folders = (1L..4L).map { LibraryCrumb(it, "第${it}层作品文件目录") }
        val trail = mutableStateOf(folders.take(3))
        val clicked = mutableListOf<Int>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme {
                Box(Modifier.width(220.dp)) {
                    LibraryBreadcrumb(
                        trail = trail.value,
                        rootLabel = "根目录",
                        onRoot = {},
                        onCrumb = { clicked += it },
                        revealCurrent = true,
                    )
                }
            }
        }
        compose.onNodeWithText(folders[2].name).assertIsDisplayed()
        compose.onNodeWithText(folders[0].name).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(0), clicked) }
        compose.onNodeWithText(folders[2].name).assertIsNotDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(folders[0].name).assertIsDisplayed()
        compose.runOnIdle { trail.value = folders }
        compose.onNodeWithText(folders.last().name).assertIsDisplayed()
    }
}
