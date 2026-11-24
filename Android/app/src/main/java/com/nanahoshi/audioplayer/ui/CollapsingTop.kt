package com.nanahoshi.audioplayer.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.offset
import androidx.compose.ui.zIndex
import com.nanahoshi.audioplayer.ui.theme.LocalReduceMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal fun canCollapseLazyList(
    firstVisibleIndex: Int,
    firstVisibleScrollOffset: Int,
    canScroll: Boolean,
    viewportEndOffset: Int,
    lastItemBottom: Int?,
    afterContentPadding: Int,
    collapsedPx: Float,
): Boolean {
    if (firstVisibleIndex > 0 || firstVisibleScrollOffset > 0) return true
    if (lastItemBottom == null) return canScroll
    return lastItemBottom + afterContentPadding > viewportEndOffset - collapsedPx.roundToInt()
}

internal fun LazyListState.canCollapseHeader(collapsedPx: Float): Boolean {
    val layout = layoutInfo
    val last = layout.visibleItemsInfo.lastOrNull { it.index == layout.totalItemsCount - 1 }
    return canCollapseLazyList(
        firstVisibleIndex = firstVisibleItemIndex,
        firstVisibleScrollOffset = firstVisibleItemScrollOffset,
        canScroll = canScrollForward || canScrollBackward,
        viewportEndOffset = layout.viewportEndOffset,
        lastItemBottom = last?.let { it.offset + it.size },
        afterContentPadding = layout.afterContentPadding,
        collapsedPx = collapsedPx,
    )
}

@Composable
fun CollapsingTop(
    header: @Composable ColumnScope.() -> Unit,
    resetKey: Any? = null,
    pinned: @Composable ColumnScope.() -> Unit = {},
    isEmpty: Boolean = false,
    canCollapse: (Float) -> Boolean = { true },
    content: @Composable (Modifier) -> Unit,
) {
    val headerHeight = remember { mutableFloatStateOf(0f) }
    val pinnedHeight = remember { mutableFloatStateOf(0f) }
    val offset = androidx.compose.runtime.saveable.rememberSaveable(resetKey) { mutableFloatStateOf(0f) }
    val reduceMotion by rememberUpdatedState(LocalReduceMotion.current)
    val empty by rememberUpdatedState(isEmpty)
    val canCollapseHeader by rememberUpdatedState(canCollapse)
    fun collapsible() = canCollapseHeader((-offset.floatValue).coerceAtLeast(0f))
    val scope = rememberCoroutineScope()
    val connection = remember(offset) {
        object : NestedScrollConnection {
            private var settleJob: Job? = null

            fun cancelSettle() {
                settleJob?.cancel()
                settleJob = null
            }

            fun expand() {
                cancelSettle()
                offset.floatValue = 0f
            }

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (empty) return Offset.Zero
                // 手指重新按下拖动：立刻停掉回弹，让手指接管，避免动画和手指同时改 offset 互相拉扯。
                if (source == NestedScrollSource.UserInput) {
                    cancelSettle()
                } else if (settleJob?.isActive == true) {
                    // 回弹动画还在跑，列表惯性带来的位移不再动顶部。
                    return Offset.Zero
                }
                val limit = headerHeight.floatValue
                if (limit <= 0f || available.y == 0f || (available.y < 0f && !collapsible())) return Offset.Zero
                val next = (offset.floatValue + available.y).coerceIn(-limit, 0f)
                val consumed = next - offset.floatValue
                if (consumed == 0f) return Offset.Zero
                offset.floatValue = next
                return Offset(0f, consumed)
            }

            // 不能在这里等动画：onPreFling 不返回，列表的惯性滑动就不会开始。
            override suspend fun onPreFling(available: Velocity): Velocity {
                settle()
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                settle()
                return Velocity.Zero
            }

            private fun settle() {
                if (empty || !collapsible()) {
                    expand()
                    return
                }
                if (settleJob?.isActive == true) return
                val limit = headerHeight.floatValue
                if (limit <= 0f) return
                val current = offset.floatValue
                if (current == 0f || current == -limit) return
                val target = if (current < -limit / 2f) -limit else 0f
                if (reduceMotion) {
                    offset.floatValue = target
                    return
                }
                settleJob = scope.launch {
                    animate(
                        initialValue = current,
                        targetValue = target,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    ) { value, _ ->
                        offset.floatValue = value.coerceIn(-headerHeight.floatValue, 0f)
                    }
                }
            }
        }
    }
    DisposableEffect(connection) {
        onDispose { connection.cancelSettle() }
    }
    LaunchedEffect(connection) {
        snapshotFlow { empty || !collapsible() }.collect { expand ->
            if (expand) connection.expand()
        }
    }
    Box(Modifier.fillMaxSize().nestedScroll(connection)) {
        // offset 只在布局阶段读取：顶部移动时只重新布局，不再重组整个列表页。
        content(
            Modifier.layout { measurable, constraints ->
                val top = (headerHeight.floatValue + offset.floatValue + pinnedHeight.floatValue)
                    .coerceAtLeast(0f)
                    .roundToInt()
                val placeable = measurable.measure(constraints.offset(vertical = -top))
                layout(placeable.width, constraints.constrainHeight(placeable.height + top)) {
                    placeable.place(0, top)
                }
            },
        )
        Column(
            Modifier
                .zIndex(1f)
                .fillMaxWidth()
                .offset { IntOffset(0, offset.floatValue.roundToInt()) }
                .background(MaterialTheme.colorScheme.background),
        ) {
            Column(
                Modifier.onSizeChanged {
                    connection.cancelSettle()
                    val height = it.height.toFloat()
                    headerHeight.floatValue = height
                    offset.floatValue = offset.floatValue.coerceIn(-height, 0f)
                },
                content = header,
            )
            Column(Modifier.onSizeChanged { pinnedHeight.floatValue = it.height.toFloat() }, content = pinned)
        }
    }
}
