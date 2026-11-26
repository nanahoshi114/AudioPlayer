package com.nanahoshi.audioplayer.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nanahoshi.audioplayer.ui.theme.LocalReduceMotion
import com.nanahoshi.audioplayer.ui.theme.Motion

@Composable
internal fun SelectableLeading(
    selecting: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String = "selectableLeading",
    normalContent: @Composable () -> Unit,
) {
    val reduce = LocalReduceMotion.current
    AnimatedContent(
        targetState = selecting,
        transitionSpec = {
            if (reduce) EnterTransition.None togetherWith ExitTransition.None
            else fadeIn(tween(Motion.short)) togetherWith fadeOut(tween(Motion.short))
        },
        modifier = modifier,
        label = label,
    ) { on ->
        if (on) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() }, enabled = enabled)
        } else {
            normalContent()
        }
    }
}
