package com.nanahoshi.audioplayer.ui.library

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimation
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDecay
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.util.lerp
import kotlin.math.abs

/**
 * 作品页两层滚动：
 * - 向下翻时先滚外层作品页，外层到底后才滚文件区；
 * - 向上翻时先滚文件区，文件区到顶后才滚回外层。
 */
internal class WorkPageScrollConnection(
    private val page: ScrollableState,
    private val file: ScrollableState,
    private val decay: DecayAnimationSpec<Float>,
) : NestedScrollConnection {
    val fileFlingBehavior: FlingBehavior = object : FlingBehavior {
        override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
            if (initialVelocity >= 0f || !page.canScrollBackward) return flingWithDecay(file, initialVelocity)

            // 外层 verticalScroll 默认会接收内层未消费的位移。占用外层的滚动会话，
            // 让文件列表先完成惯性滑动，再由 onPostFling 交接余速。
            var remaining = initialVelocity
            page.scroll { remaining = this@performFling.flingWithDecay(file, initialVelocity) }
            return remaining
        }
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || available.y >= 0f) return Offset.Zero
        if (!page.canScrollForward) return Offset.Zero
        val consumed = page.dispatchRawDelta(-available.y)
        return Offset(0f, -consumed)
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
        if (!page.canScrollBackward) return Offset.Zero
        val consumed = page.dispatchRawDelta(-available.y)
        return Offset(0f, -consumed)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (available.y >= 0f || !page.canScrollForward) return Velocity.Zero
        return flingPage(available.y)
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        if (available.y <= 0f || file.canScrollBackward || !page.canScrollBackward) return Velocity.Zero
        return flingPage(available.y)
    }

    private suspend fun flingPage(velocityY: Float): Velocity {
        // ScrollScope 的正方向与手势速度相反。保留列表的滚动会话，使新手势能中止交接。
        val initialVelocity = -velocityY
        var remaining = initialVelocity
        file.scroll {
            page.scroll { remaining = flingWithDecay(page, initialVelocity) }
        }
        return Velocity(0f, velocityY + remaining)
    }

    private suspend fun ScrollScope.flingWithDecay(state: ScrollableState, initialVelocity: Float): Float {
        // 与 Compose 的默认 fling 一致，跳过样条曲线在极低速度下的计算。
        if (abs(initialVelocity) <= 1f) return initialVelocity
        val forward = initialVelocity > 0f
        if (!state.canScroll(forward)) return initialVelocity

        var previousValue = 0f
        var remaining = 0f
        AnimationState(0f, initialVelocity).animateDecay(decay) {
            val delta = value - previousValue
            val consumed = scrollBy(delta)
            if (!state.canScroll(forward) || abs(delta - consumed) > 0.5f) {
                remaining = velocityAtDistance(initialVelocity, previousValue + consumed)
                cancelAnimation()
            }
            previousValue = value
        }
        return remaining
    }

    private fun ScrollableState.canScroll(forward: Boolean): Boolean =
        if (forward) canScrollForward else canScrollBackward

    private fun velocityAtDistance(initialVelocity: Float, distance: Float): Float {
        if (distance == 0f) return initialVelocity
        val animation = DecayAnimation(decay, Float.VectorConverter, 0f, initialVelocity)
        var before = 0L
        var after = animation.durationNanos
        // 边界可能在两帧之间。用实际滚过的距离求边界速度，避免把越界部分也算作已消耗。
        while (after - before > 1L) {
            val middle = before + (after - before) / 2
            if (abs(animation.getValueFromNanos(middle)) < abs(distance)) before = middle
            else after = middle
        }
        val beforeDistance = abs(animation.getValueFromNanos(before))
        val afterDistance = abs(animation.getValueFromNanos(after))
        val fraction = if (afterDistance > beforeDistance) {
            ((abs(distance) - beforeDistance) / (afterDistance - beforeDistance)).coerceIn(0f, 1f)
        } else {
            1f
        }
        return lerp(
            animation.getVelocityVectorFromNanos(before).value,
            animation.getVelocityVectorFromNanos(after).value,
            fraction,
        ).coerceIn(minOf(0f, initialVelocity), maxOf(0f, initialVelocity))
    }
}
