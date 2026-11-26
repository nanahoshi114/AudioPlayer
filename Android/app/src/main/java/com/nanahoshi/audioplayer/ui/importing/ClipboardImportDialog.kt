package com.nanahoshi.audioplayer.ui.importing

import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.text.asString
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.DialogProperties
import com.nanahoshi.audioplayer.data.ClipboardImportState
import com.nanahoshi.audioplayer.ui.components.CoverImage
import com.nanahoshi.audioplayer.ui.components.JAPANESE_ASMR_VERIFY_ACTION
import com.nanahoshi.audioplayer.ui.components.JAPANESE_ASMR_VERIFY_HINT
import com.nanahoshi.audioplayer.ui.components.RoundedProgress

@Composable
internal fun ClipboardImportDialog(
    state: ClipboardImportState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onOpenDetails: (String) -> Unit,
) {
    val preview = state.preview
    val sourceLabel = if (preview.dlsiteWork != null) "DLsite" else stringResource(preview.target.source.labelRes)
    val coverDescription = stringResource(R.string.a11y_import_cover)
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = !state.busy, dismissOnClickOutside = !state.busy),
        title = {
            Text(when {
                preview.alreadyInLibrary -> stringResource(R.string.online_in_library)
                preview.jaVerificationPage != null -> stringResource(R.string.import_ja_verify_title)
                preview.detailsOnly -> stringResource(R.string.import_found_title)
                else -> stringResource(R.string.import_confirm_title)
            })
        },
        text = { Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CoverImage(preview.cover, size = 88.dp, modifier = Modifier.semantics { contentDescription = coverDescription })
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(if (preview.jaVerificationPage != null) stringResource(JAPANESE_ASMR_VERIFY_HINT) else preview.title,
                        style = MaterialTheme.typography.titleMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Text("$sourceLabel · ${preview.target.id}", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (preview.alreadySaved && preview.multiPartBili) Text(
                stringResource(R.string.import_already_saved_multi),
                Modifier.padding(top = 12.dp),
            )
            if (preview.detailsOnly && preview.jaBrowserUrl == null) Text(
                stringResource(R.string.import_no_source),
                Modifier.padding(top = 12.dp),
            )
            if (state.busy) {
                RoundedProgress(progress = null, modifier = Modifier.padding(top = 12.dp))
                Text(stringResource(R.string.import_importing), Modifier.padding(top = 8.dp))
            }
            state.error?.let { Text(it.asString(), Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = {
            if (preview.jaBrowserUrl != null) {
                TextButton(onClick = { onOpenDetails(preview.target.id) }) {
                    Text(if (preview.jaVerificationPage != null) stringResource(JAPANESE_ASMR_VERIFY_ACTION) else stringResource(R.string.download_open_article))
                }
            } else if (preview.detailsOnly || (preview.alreadySaved && preview.jaArticle != null)) {
                TextButton(onClick = { onOpenDetails(preview.target.id) }) { Text(stringResource(R.string.import_view_details)) }
            } else if (!preview.alreadyInLibrary) {
                TextButton(onClick = onConfirm, enabled = state.canImport) {
                    Text(when {
                        preview.multiPartBili -> stringResource(R.string.import_choose_mode)
                        else -> stringResource(R.string.import_action)
                    })
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.busy) {
                Text(stringResource(if (preview.alreadyInLibrary) R.string.common_close else R.string.common_cancel))
            }
        },
    )
}
