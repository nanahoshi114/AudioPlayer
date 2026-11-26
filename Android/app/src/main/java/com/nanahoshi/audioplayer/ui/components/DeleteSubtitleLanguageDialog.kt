package com.nanahoshi.audioplayer.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.playback.SubtitleLanguageDelete

@Composable
internal fun DeleteSubtitleLanguageDialog(
    target: SubtitleLanguageDelete,
    translationOverlay: Boolean,
    enabled: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val message = when (target) {
        SubtitleLanguageDelete.CHINESE -> stringResource(
            if (translationOverlay) R.string.player_delete_chinese_overlay_confirm
            else R.string.player_delete_chinese_generated_confirm,
        )
        SubtitleLanguageDelete.ORIGINAL -> stringResource(R.string.player_delete_original_generated_confirm)
    }
    ConfirmActionDialog(
        message = message,
        confirmLabel = stringResource(R.string.common_delete),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        destructive = true,
        enabled = enabled,
    )
}
