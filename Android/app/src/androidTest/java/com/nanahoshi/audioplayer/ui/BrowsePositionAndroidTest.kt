package com.nanahoshi.audioplayer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nanahoshi.audioplayer.ui.components.ResetOnNewSearch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowsePositionAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test fun onlyNewQueryRevisionsResetThePosition() {
        val state = LazyListState()
        val revision = mutableLongStateOf(0)
        val count = mutableIntStateOf(80)
        lateinit var scope: CoroutineScope
        compose.setContent {
            scope = rememberCoroutineScope()
            ResetOnNewSearch(state, revision.longValue)
            MaterialTheme { Column(Modifier.fillMaxSize()) {
                LazyColumn(state = state) { items((0 until count.intValue).toList()) { Text("row $it") } }
            } }
        }
        compose.runOnIdle { scope.launch { state.scrollToItem(30) } }
        compose.waitUntil { state.firstVisibleItemIndex == 30 }
        compose.runOnIdle { count.intValue = 100 }
        compose.runOnIdle { assertEquals(30, state.firstVisibleItemIndex) }
        compose.runOnIdle { revision.longValue++ }
        compose.waitUntil { state.firstVisibleItemIndex == 0 }
        compose.runOnIdle { scope.launch { state.scrollToItem(30) } }
        compose.waitUntil { state.firstVisibleItemIndex == 30 }
        compose.runOnIdle { revision.longValue++ }
        compose.waitUntil { state.firstVisibleItemIndex == 0 }
    }
}
