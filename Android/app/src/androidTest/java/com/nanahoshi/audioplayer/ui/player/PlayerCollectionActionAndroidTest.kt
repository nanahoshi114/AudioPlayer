package com.nanahoshi.audioplayer.ui.player

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
import com.nanahoshi.audioplayer.bilibili.BiliCollection
import com.nanahoshi.audioplayer.bilibili.BiliCollectionKind
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import com.nanahoshi.audioplayer.playback.PlayingBiliCollectionLink
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerCollectionActionAndroidTest {
    @get:Rule val compose = createComposeRule()
    private val bvid = "BV1234567890"
    private val collection = BiliCollection(BiliCollectionKind.SEASON, 7, 123, "所属合集")
    private fun row() = QueuedTrack(1, 0, null, TrackSource.BILIBILI, "音轨", "UP", 1000,
        null, null, null, null, null, null, null, bvid, "101")

    @Test fun buttonOpensTheExactCollectionAndDisappearsWhenCurrentIdentityChanges() {
        val current = mutableStateOf<QueuedTrack?>(row())
        val link = mutableStateOf<PlayingBiliCollectionLink?>(PlayingBiliCollectionLink(bvid, collection))
        var opened: BiliCollection? = null
        compose.setContent { MaterialTheme { PlayerCollectionAction(current.value, link.value) { opened = it } } }
        compose.onNodeWithText("合集：所属合集").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(collection, opened); current.value = row().copy(workno = "BV1234567891") }
        compose.onNodeWithText("合集：所属合集").assertDoesNotExist()
        compose.runOnIdle { current.value = row().copy(source = TrackSource.YOUTUBE) }
        compose.onNodeWithText("合集：所属合集").assertDoesNotExist()
        compose.runOnIdle { current.value = row(); link.value = null }
        compose.onNodeWithText("合集：所属合集").assertDoesNotExist()
    }

    @Test fun longCollectionTitleRemainsClickableAtNarrowWidthAndLargeFontSize() {
        val long = collection.copy(title = "很长的合集标题".repeat(20))
        var opened: BiliCollection? = null
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.8f)) {
                MaterialTheme {
                    Column(Modifier.width(280.dp)) {
                        PlayerCollectionAction(row(), PlayingBiliCollectionLink(bvid, long)) { opened = it }
                    }
                }
            }
        }
        compose.onNodeWithText("合集：${long.title}").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(long, opened) }
    }
}
