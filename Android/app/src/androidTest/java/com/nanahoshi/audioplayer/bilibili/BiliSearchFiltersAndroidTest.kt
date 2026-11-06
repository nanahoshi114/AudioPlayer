package com.nanahoshi.audioplayer.bilibili

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nanahoshi.audioplayer.ui.bilibili.BiliSearchFilters
import com.nanahoshi.audioplayer.ui.components.ResetOnNewSearch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BiliSearchFiltersAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test fun choosingFiltersResetsTheResultsAndScrollWhileRetainingSelections() {
        val order = mutableStateOf(BiliVideoOrder.TOTAL_RANK)
        val duration = mutableStateOf(BiliVideoDuration.ALL)
        val requested = mutableListOf<Triple<Int, BiliVideoOrder, BiliVideoDuration>>()
        compose.setContent {
            val scope = rememberCoroutineScope()
            val page = remember { BiliPagedState<String, String>(scope) { it } }
            val list = rememberLazyListState()
            fun search() {
                val sort = order.value
                val length = duration.value
                page.start { index ->
                    requested += Triple(index, sort, length)
                    BiliPage((0 until 50).map { "结果$it" }, false)
                }
            }
            LaunchedEffect(Unit) { search() }
            ResetOnNewSearch(list, page.revision)
            MaterialTheme {
                Column(Modifier.width(280.dp)) {
                    BiliSearchFilters(order.value, duration.value,
                        onOrder = { order.value = it; search() },
                        onDuration = { duration.value = it; search() })
                    LazyColumn(Modifier.height(240.dp), state = list) {
                        items(page.items.size) { Text(page.items[it]) }
                    }
                }
            }
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(35)
        compose.onNodeWithText("综合排序").performClick()
        compose.onNodeWithText("最多收藏").performClick()
        compose.onNodeWithText("结果0").assertIsDisplayed()
        compose.onNodeWithText("最多收藏").assertIsDisplayed()
        compose.onNodeWithText("全部时长").performClick()
        compose.onNodeWithText("60 分钟以上").performClick()
        compose.onNodeWithText("结果0").assertIsDisplayed()
        compose.onNodeWithText("最多收藏").assertIsDisplayed()
        compose.onNodeWithText("60 分钟以上").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(
                Triple(1, BiliVideoOrder.TOTAL_RANK, BiliVideoDuration.ALL),
                Triple(1, BiliVideoOrder.FAVORITES, BiliVideoDuration.ALL),
                Triple(1, BiliVideoOrder.FAVORITES, BiliVideoDuration.OVER_SIXTY),
            ), requested)
        }
    }
}
