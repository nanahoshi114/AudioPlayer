package com.nanahoshi.audioplayer.ui.components

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionStateTest {
    @Test
    fun toggleSelectClearAndToggleAllUpdateIdsAndFlow() = runBlocking {
        val state = SelectionState<String>()
        assertTrue(state.isEmpty)
        assertFalse(state.isNotEmpty)

        state.toggle("a")
        state.toggle("b")
        assertEquals(setOf("a", "b"), state.ids)
        assertEquals(setOf("a", "b"), state.flow.first())
        assertTrue("a" in state)

        state.toggle("a")
        assertEquals(setOf("b"), state.ids)

        state.toggleAll(listOf("b", "c"))
        assertEquals(setOf("b", "c"), state.ids)

        state.toggleAll(listOf("b", "c"))
        assertTrue(state.isEmpty)
        assertEquals(emptySet<String>(), state.flow.first())
    }

    @Test
    fun retainKeepsOnlyValidIds() {
        val state = SelectionState<Long>()
        state.replace(setOf(1L, 2L, 3L))
        state.retain(setOf(2L, 4L))
        assertEquals(setOf(2L), state.ids)
        state.clear()
        assertTrue(state.isEmpty)
    }
}
