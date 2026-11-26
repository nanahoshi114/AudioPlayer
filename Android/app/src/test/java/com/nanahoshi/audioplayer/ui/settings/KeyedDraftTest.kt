package com.nanahoshi.audioplayer.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyedDraftTest {
    @Test
    fun stashesRestoresAndClearsPerKey() {
        val drafts = KeyedDraft<String, Pair<String, String>>()
        assertFalse(drafts.hasDraft("a"))
        assertEquals("persisted" to "url", drafts.restoreOr("a") { "persisted" to "url" })

        drafts.stash("a", "edited" to "url2")
        assertTrue(drafts.hasDraft("a"))
        assertEquals("edited" to "url2", drafts.restoreOr("a") { "persisted" to "url" })
        assertEquals("other" to "", drafts.restoreOr("b") { "other" to "" })

        drafts.clear("a")
        assertFalse(drafts.hasDraft("a"))
        assertEquals("persisted" to "url", drafts.restoreOr("a") { "persisted" to "url" })
    }
}
