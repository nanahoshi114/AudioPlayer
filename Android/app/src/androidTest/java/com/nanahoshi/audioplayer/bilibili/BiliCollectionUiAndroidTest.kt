package com.nanahoshi.audioplayer.bilibili

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nanahoshi.audioplayer.ui.bilibili.BiliCollectionRouteError
import com.nanahoshi.audioplayer.ui.bilibili.CollectionAction
import com.nanahoshi.audioplayer.ui.bilibili.CollectionFailureDialog
import com.nanahoshi.audioplayer.ui.bilibili.CollectionMenuItems
import com.nanahoshi.audioplayer.ui.bilibili.CollectionPreparation
import com.nanahoshi.audioplayer.ui.bilibili.CollectionPlaybackActions
import com.nanahoshi.audioplayer.ui.bilibili.CollectionSort
import com.nanahoshi.audioplayer.ui.bilibili.CollectionSortSelector
import com.nanahoshi.audioplayer.ui.playlist.OnlinePlaylistSummary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BiliCollectionUiAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectingHidesWholeCollectionPlaybackAndRestoresItWhenCleared() {
        val selecting = mutableStateOf(false)
        var action: CollectionAction? = null
        compose.setContent {
            MaterialTheme {
                CollectionPlaybackActions(selecting.value, enabled = true, onAction = { action = it })
            }
        }
        compose.onNodeWithText("按当前排序播放全部").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(CollectionAction.PLAY, action); selecting.value = true }
        compose.onNodeWithText("按当前排序播放全部").assertDoesNotExist()
        compose.onNodeWithText("加入播放队列").assertDoesNotExist()
        compose.runOnIdle { selecting.value = false }
        compose.onNodeWithText("按当前排序播放全部").assertIsDisplayed()
        compose.onNodeWithText("加入播放队列").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(CollectionAction.APPEND, action) }
    }

    @Test fun sortMenuSupportsAllOrdersAndReselectingRandomAtLargeFontSize() {
        val sort = mutableStateOf(CollectionSort.FORWARD)
        var changes = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.8f)) {
                MaterialTheme {
                    Column(Modifier.width(320.dp)) {
                        CollectionSortSelector(sort.value, { sort.value = it; changes++ })
                    }
                }
            }
        }
        for (option in listOf(CollectionSort.REVERSE, CollectionSort.RANDOM, CollectionSort.RANDOM, CollectionSort.FORWARD)) {
            compose.onNodeWithText("排序：${sort.value.label}").assertIsDisplayed().performClick()
            compose.onNodeWithText(option.label).assertIsDisplayed().performClick()
            compose.onNodeWithText("排序：${option.label}").assertIsDisplayed()
        }
        compose.runOnIdle { assertEquals(4, changes) }
    }

    @Test fun sourceListShowsVideoCountAndReadonlyMenuAtLargeFontSize() {
        var opened = 0
        var action: CollectionAction? = null
        var unbookmarked = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.8f)) {
                MaterialTheme {
                    Column(Modifier.width(320.dp)) {
                        OnlinePlaylistSummary("收藏的系列", BiliCollection(BiliCollectionKind.SERIES, 1, 2, "系列", total = 12)) { opened++ }
                        CollectionMenuItems(true, { action = it }, { unbookmarked++ })
                    }
                }
            }
        }
        compose.onNodeWithText("B站系列 · 12 个视频").assertIsDisplayed()
        compose.onNodeWithText("收藏的系列").performClick()
        compose.onNodeWithText("改名").assertDoesNotExist()
        compose.onNodeWithContentDescription("拖动排序").assertDoesNotExist()
        compose.onNodeWithText("复制为自建播放列表").performClick()
        compose.onNodeWithText("取消收藏").performClick()
        compose.runOnIdle { assertEquals(1, opened); assertEquals(CollectionAction.COPY, action); assertEquals(1, unbookmarked) }
    }

    @Test fun allFailedShowsFailureTitleWithoutContinue() {
        var continued = 0
        var retried = 0
        var cancelled = 0
        val video = BiliVideoHit("BV1234567890", "视频A", "UP", "", 2000)
        val prepared = CollectionPreparation(CollectionAction.PLAY, emptyList(), listOf("视频A：读取失败"), listOf(video))
        compose.setContent {
            MaterialTheme {
                CollectionFailureDialog(prepared, { continued++ }, { retried++ }, { cancelled++ })
            }
        }
        compose.onNodeWithText("视频读取失败").assertIsDisplayed()
        compose.onNodeWithText("视频A：读取失败").assertIsDisplayed()
        compose.onNodeWithText("继续处理其余", substring = true).assertDoesNotExist()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(1, cancelled); assertEquals(0, continued); assertEquals(0, retried) }
    }

    @Test fun partialFailureKeepsContinueWithCount() {
        var continued = 0
        var retried = 0
        val video = BiliVideoHit("BV1234567890", "视频A", "UP", "", 2000)
        val part = BiliPart(video.bvid, 101L, 1, "P1", "UP", 1000, "")
        val prepared = CollectionPreparation(
            CollectionAction.PLAY, listOf(BiliVideoDetails(video, listOf(part))), listOf("视频B：读取失败"), listOf(video))
        compose.setContent {
            MaterialTheme {
                CollectionFailureDialog(prepared, { continued++ }, { retried++ }, {})
            }
        }
        compose.onNodeWithText("部分视频读取失败").assertIsDisplayed()
        compose.onNodeWithText("继续处理其余 1 个视频").assertIsDisplayed().performClick()
        compose.onNodeWithText("重试").performClick()
        compose.runOnIdle { assertEquals(1, continued); assertEquals(1, retried) }
    }

    @Test fun invalidRouteShowsErrorPageWithBack() {
        var backs = 0
        compose.setContent {
            MaterialTheme { BiliCollectionRouteError { backs++ } }
        }
        compose.onNodeWithText("无法打开这个合集").assertIsDisplayed()
        compose.onNodeWithText("链接参数无效，请返回后重试").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.runOnIdle { assertEquals(1, backs) }
        compose.onNodeWithText("返回").performClick()
        compose.runOnIdle { assertEquals(2, backs) }
    }
}
