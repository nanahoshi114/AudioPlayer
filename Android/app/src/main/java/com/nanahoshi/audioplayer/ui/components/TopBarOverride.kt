package com.nanahoshi.audioplayer.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

internal data class TopBarOverrideEntry(
    val ownerKey: String,
    val content: @Composable () -> Unit,
)

internal class TopBarOverrideController {
    var entry by mutableStateOf<TopBarOverrideEntry?>(null)
        private set

    fun set(ownerKey: String, content: @Composable () -> Unit) {
        entry = TopBarOverrideEntry(ownerKey, content)
    }

    fun clear(ownerKey: String) {
        if (entry?.ownerKey == ownerKey) entry = null
    }
}

internal val LocalTopBarOverride = staticCompositionLocalOf<TopBarOverrideController?> { null }
internal val LocalActiveTopBarOwner = compositionLocalOf<String?> { null }

internal fun libraryTopBarOwnerKey(entryId: String) = "library:$entryId"

@Composable
internal fun TopBarOverride(ownerKey: String, content: @Composable () -> Unit) {
    val controller = LocalTopBarOverride.current ?: return
    if (LocalActiveTopBarOwner.current != ownerKey) return

    val currentContent by rememberUpdatedState(content)
    DisposableEffect(controller, ownerKey) {
        controller.set(ownerKey) { currentContent() }
        onDispose { controller.clear(ownerKey) }
    }
}
