package com.nanahoshi.audioplayer.ui.components

import androidx.compose.runtime.Composable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TopBarOverrideControllerTest {
    @Test
    fun onlyTheCurrentOwnerCanClearAnEntry() {
        val controller = TopBarOverrideController()
        val content: @Composable () -> Unit = {}
        val library = libraryTopBarOwnerKey("library-entry")

        controller.clear(library)
        assertNull(controller.entry)

        controller.set(library, content)
        controller.clear(libraryTopBarOwnerKey("previous-entry"))
        assertEquals(library, controller.entry?.ownerKey)
        assertSame(content, controller.entry?.content)

        controller.clear(library)
        assertNull(controller.entry)
    }

    @Test
    fun outgoingSourceCannotClearTheIncomingSourceOnTheSameDestination() {
        val controller = TopBarOverrideController()
        val oldContent: @Composable () -> Unit = {}
        val newContent: @Composable () -> Unit = {}
        val bilibili = "online:search-entry:BILIBILI"
        val youtube = "online:search-entry:YOUTUBE"

        controller.set(bilibili, oldContent)
        controller.set(youtube, newContent)
        controller.clear(bilibili)

        assertEquals(youtube, controller.entry?.ownerKey)
        assertSame(newContent, controller.entry?.content)
        controller.clear(youtube)
        assertNull(controller.entry)
    }
}
