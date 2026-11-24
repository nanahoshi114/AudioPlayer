package com.nanahoshi.audioplayer.ui.library

import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkPageScrollConnectionTest {
    @Test
    fun dragScrollsPageFirstForwardAndFileFirstBackward() {
        val page = TestScrollState(position = 60f)
        val file = TestScrollState(position = 10f)
        val connection = connection(page, file)
        val touch = NestedScrollSource.UserInput

        assertEquals(Offset(0f, -40f), connection.onPreScroll(Offset(12f, -50f), touch))
        assertEquals(100f, page.position, 0f)
        assertEquals(Offset.Zero, connection.onPreScroll(Offset(12f, 25f), touch))
        val usedByFile = Offset(0f, -file.dispatchRawDelta(-25f))
        assertEquals(Offset(0f, 15f), connection.onPostScroll(usedByFile, Offset(12f, 15f), touch))
        assertEquals(85f, page.position, 0f)
    }

    @Test
    fun forwardFlingPassesVelocityAtThePageBoundaryToTheFileList() = runBlocking(FrameClock()) {
        val page = TestScrollState(position = 20f)
        val file = TestScrollState(max = 500f)
        val connection = connection(page, file)
        val incoming = Velocity(200f, -1_000f)

        val consumed = connection.onPreFling(incoming)

        assertEquals(100f, page.position, 0f)
        assertEquals(0f, file.position, 0f)
        assertEquals(0f, consumed.x, 0f)
        // In this exponential decay, speed drops by 4.2 px/s per pixel traveled.
        val remaining = (incoming - consumed).y
        assertEquals(-664f, remaining, 0.01f)
        with(connection.fileFlingBehavior) { file.performFling(-remaining) }
        assertEquals(157.86f, file.position, 0.01f)
    }

    @Test
    fun backwardFlingPassesVelocityAtTheFileBoundaryToThePage() = runBlocking(FrameClock()) {
        val page = TestScrollState(position = 60f)
        val file = TestScrollState(position = 80f)
        val connection = connection(page, file)
        val incoming = Velocity(200f, 1_000f)
        val nestedFileScope = object : ScrollScope {
            override fun scrollBy(pixels: Float): Float {
                val consumed = file.scrollBy(pixels)
                // Simulate verticalScroll consuming any unclaimed nested delta while idle.
                return if (page.isScrollInProgress) consumed else consumed + page.dispatchRawDelta(pixels - consumed)
            }
        }

        assertEquals(Velocity.Zero, connection.onPreFling(incoming))
        val remaining = with(connection.fileFlingBehavior) { -nestedFileScope.performFling(-incoming.y) }
        assertEquals(0f, file.position, 0f)
        assertEquals(60f, page.position, 0f)
        assertEquals(664f, remaining, 0.01f)

        val consumed = connection.onPostFling(Velocity(0f, incoming.y - remaining), Velocity(incoming.x, remaining))

        assertEquals(0f, page.position, 0f)
        assertEquals(0f, consumed.x, 0f)
        assertEquals(252f, consumed.y, 0.01f)
    }

    @Test
    fun weakFlingsDecayBeforeTheBoundary() = runBlocking(FrameClock()) {
        val page = TestScrollState(position = 50f)
        val connection = connection(page, TestScrollState())

        val forward = Velocity(0f, -100f)
        assertEquals(forward, connection.onPreFling(forward))
        assertEquals(73.57f, page.position, 0.01f)

        val backward = Velocity(0f, 100f)
        assertEquals(backward, connection.onPostFling(Velocity.Zero, backward))
        assertEquals(50f, page.position, 0.01f)
    }

    @Test
    fun pageAtItsBoundaryClaimsNoVelocity() = runBlocking {
        val page = TestScrollState(max = 0f)
        val connection = connection(page, TestScrollState())

        assertEquals(Velocity.Zero, connection.onPreFling(Velocity(200f, -1_000f)))
        assertEquals(Velocity.Zero, connection.onPostFling(Velocity.Zero, Velocity(200f, 1_000f)))
    }

    private fun connection(page: ScrollableState, file: ScrollableState) =
        WorkPageScrollConnection(page, file, exponentialDecay(absVelocityThreshold = 1f))

    private class FrameClock : MonotonicFrameClock {
        private var time = 0L

        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            time += 100_000_000L
            return onFrame(time)
        }
    }

    private class TestScrollState(var position: Float = 0f, private val max: Float = 100f) :
        ScrollableState, ScrollScope {
        override var isScrollInProgress = false
            private set
        override val canScrollForward get() = position < max
        override val canScrollBackward get() = position > 0f

        override suspend fun scroll(scrollPriority: MutatePriority, block: suspend ScrollScope.() -> Unit) {
            isScrollInProgress = true
            try {
                block(this)
            } finally {
                isScrollInProgress = false
            }
        }

        override fun dispatchRawDelta(delta: Float): Float {
            val target = position + delta
            val next = target.coerceIn(0f, max)
            val consumed = if (next == target) delta else next - position
            position = next
            return consumed
        }

        override fun scrollBy(pixels: Float): Float = dispatchRawDelta(pixels)
    }
}
