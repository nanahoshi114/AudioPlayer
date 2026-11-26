package com.nanahoshi.audioplayer.ui.download

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.data.ProgressResult
import com.nanahoshi.audioplayer.text.PersistedErrors
import com.nanahoshi.audioplayer.text.persistedErrorText
import com.nanahoshi.audioplayer.text.resolveNoUrl
import com.nanahoshi.audioplayer.ui.components.InfoNoticeDialog
import com.nanahoshi.audioplayer.ui.components.StatusBadge
import com.nanahoshi.audioplayer.ui.components.StatusTone

@Composable
internal fun TaskRowScaffold(
    title: String,
    badgeText: String,
    badgeTone: StatusTone,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
    supporting: @Composable ColumnScope.() -> Unit,
) {
    ListItem(
        modifier = modifier,
        headlineContent = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(Modifier.fillMaxWidth()) {
                StatusBadge(
                    badgeText,
                    badgeTone,
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                supporting()
            }
        },
        trailingContent = trailing,
    )
}

@Composable
internal fun TaskFailureMessage(text: String, onDetails: () -> Unit) {
    var truncated by remember(text) { mutableStateOf(false) }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { truncated = it.hasVisualOverflow },
    )
    if (truncated || '\n' in text) {
        TextButton(onClick = onDetails) { Text(stringResource(R.string.download_error_details)) }
    }
}

@Composable
internal fun TaskReplyDialog(text: String, onDismiss: () -> Unit) {
    InfoNoticeDialog(
        title = stringResource(R.string.download_error_details_title),
        message = text,
        confirmLabel = stringResource(R.string.common_close),
        scrollable = true,
        onDismiss = onDismiss,
    )
}

@Composable
internal fun taskFailureText(error: String?, @StringRes fallback: Int): String? {
    val context = LocalContext.current
    return remember(context, error, fallback) {
        persistedErrorText(error)?.let { context.resolveNoUrl(it, fallback) }
    }
}

@StringRes
internal fun taskFailedLabelRes(
    error: String?,
    result: ProgressResult?,
    @StringRes fallback: Int = R.string.status_failed,
): Int = when {
    PersistedErrors.isCancelled(error) || result == ProgressResult.CANCELLED -> R.string.status_cancelled
    result == ProgressResult.INTERRUPTED || (result == null && PersistedErrors.isInterrupted(error)) ->
        R.string.progress_result_interrupted
    else -> fallback
}
