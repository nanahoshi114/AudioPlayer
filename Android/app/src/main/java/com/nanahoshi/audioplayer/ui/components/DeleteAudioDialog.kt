package com.nanahoshi.audioplayer.ui.components

import com.nanahoshi.audioplayer.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/** 删除音频前的确认；有生成字幕时让用户决定是否一并删除。 */
@Composable
internal fun DeleteAudioDialog(
    itemCount: Int,
    subtitleCount: Int,
    onConfirm: (deleteSubtitles: Boolean) -> Unit,
    onCancel: () -> Unit,
    associationCount: Int = 0,
) {
    val withSubtitles = subtitleCount > 0
    val mixed = associationCount > 0
    val message = buildList {
        if (mixed) {
            add(stringResource(R.string.delete_dialog_message_downloads, itemCount, associationCount))
        }
        if (withSubtitles) {
            add(
                    if (mixed) stringResource(R.string.delete_dialog_subtitles_note, subtitleCount)
                    else stringResource(R.string.delete_dialog_subtitles_note_with_files, itemCount, subtitleCount),
                )
            add(stringResource(R.string.delete_dialog_subtitles_only))
        } else if (!mixed) {
            add(stringResource(R.string.delete_dialog_files_confirm, itemCount))
        }
    }.joinToString("\n\n")
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(
            if (mixed) R.string.delete_dialog_title_mixed
            else if (withSubtitles) R.string.delete_dialog_title_subtitles
            else R.string.delete_dialog_title_files,
        )) },
        text = {
            Text(
                message,
                modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(true) }) {
                Text(stringResource(
                    if (withSubtitles) R.string.delete_dialog_confirm_all
                    else if (mixed) R.string.delete_dialog_confirm_copy
                    else R.string.delete_dialog_confirm_delete,
                ))
            }
        },
        dismissButton = {
            Row {
                if (withSubtitles) TextButton(onClick = { onConfirm(false) }) {
                    Text(stringResource(R.string.delete_dialog_keep_subtitles))
                }
                TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
            }
        },
    )
}
