package com.nanahoshi.audioplayer.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidatePlacement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import com.nanahoshi.audioplayer.ui.theme.LocalReduceMotion
import com.nanahoshi.audioplayer.ui.theme.Motion
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 按压轻微缩放反馈；遵循 [LocalReduceMotion]，不使用已弃用的 `Modifier.composed`。 */
fun Modifier.pressScale(interactionSource: InteractionSource): Modifier =
    this then PressScaleElement(interactionSource)

private data class PressScaleElement(
    val interactionSource: InteractionSource,
) : ModifierNodeElement<PressScaleNode>() {
    override fun create(): PressScaleNode = PressScaleNode(interactionSource)

    override fun update(node: PressScaleNode) {
        node.updateInteractionSource(interactionSource)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "pressScale"
        properties["interactionSource"] = interactionSource
    }
}

private class PressScaleNode(
    private var interactionSource: InteractionSource,
) : Modifier.Node(), LayoutModifierNode, CompositionLocalConsumerModifierNode {
    private val scale = Animatable(1f)
    private val activePresses = mutableSetOf<PressInteraction.Press>()
    private var collectJob: Job? = null
    private var animJob: Job? = null

    override fun onAttach() {
        startCollecting()
    }

    override fun onDetach() {
        collectJob?.cancel()
        collectJob = null
        animJob?.cancel()
        animJob = null
        activePresses.clear()
    }

    fun updateInteractionSource(next: InteractionSource) {
        if (interactionSource === next) return
        interactionSource = next
        if (isAttached) {
            startCollecting()
        }
    }

    private fun startCollecting() {
        collectJob?.cancel()
        activePresses.clear()
        collectJob = coroutineScope.launch {
            animatePressed(false)
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> activePresses += interaction
                    is PressInteraction.Release -> activePresses -= interaction.press
                    is PressInteraction.Cancel -> activePresses -= interaction.press
                }
                animatePressed(activePresses.isNotEmpty())
            }
        }
    }

    private fun animatePressed(pressed: Boolean) {
        val target = if (pressed) 0.96f else 1f
        if (scale.targetValue == target && scale.isRunning) return
        animJob?.cancel()
        val reduce = currentValueOf(LocalReduceMotion)
        if (reduce) {
            animJob = coroutineScope.launch {
                scale.snapTo(target)
                invalidatePlacement()
            }
        } else {
            animJob = coroutineScope.launch {
                scale.animateTo(target, animationSpec = Motion.press()) {
                    invalidatePlacement()
                }
            }
        }
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            val current = scale.value
            placeable.placeWithLayer(0, 0) {
                scaleX = current
                scaleY = current
            }
        }
    }
}
