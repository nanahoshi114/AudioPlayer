package com.nanahoshi.audioplayer.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.nanahoshi.audioplayer.R

/** 二次确认弹窗；破坏性操作默认使用语义 `error` 确认文字色。 */
@Composable
internal fun ConfirmActionDialog(
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    title: String? = null,
    dismissLabel: String = stringResource(R.string.common_cancel),
    destructive: Boolean = true,
    enabled: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = { if (enabled) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = enabled, dismissOnClickOutside = enabled),
        title = title?.let { { Text(it) } },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = enabled) {
                Text(
                    confirmLabel,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = enabled) {
                Text(dismissLabel)
            }
        },
    )
}

/** 单按钮提示弹窗。 */
@Composable
internal fun InfoNoticeDialog(
    message: String,
    onDismiss: () -> Unit,
    title: String? = null,
    confirmLabel: String = stringResource(R.string.common_close),
    scrollable: Boolean = false,
    dismissOnOutside: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = { if (dismissOnOutside) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = dismissOnOutside,
            dismissOnClickOutside = dismissOnOutside,
        ),
        title = title?.let { { Text(it) } },
        text = {
            Text(
                message,
                modifier = if (scrollable) {
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())
                } else {
                    Modifier
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(confirmLabel)
            }
        },
    )
}
