package com.nanahoshi.audioplayer.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

@Composable
internal fun ResetOnNewSearch(state: LazyListState, revision: Long) {
    var seen by rememberSaveable { mutableLongStateOf(revision) }
    LaunchedEffect(state, revision) {
        if (seen != revision) {
            state.scrollToItem(0)
            seen = revision
        }
    }
}
