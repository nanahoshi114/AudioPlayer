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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/** 删除音频或附属文件前的确认；带字幕时让用户决定是否一并删除或保留。 */
@Composable
internal fun DeleteAudioDialog(
    itemCount: Int,
    subtitleCount: Int,
    onConfirm: (deleteSubtitles: Boolean) -> Unit,
    onCancel: () -> Unit,
    associationCount: Int = 0,
    otherFileCount: Int = 0,
    workSubtitleCount: Int = 0,
    customSubtitleCount: Int = 0,
) {
    val hasGeneratedSubtitles = subtitleCount > 0
    val hasWorkOrCustomSubtitles = workSubtitleCount > 0 || customSubtitleCount > 0
    val hasAnySubtitles = hasGeneratedSubtitles || hasWorkOrCustomSubtitles
    val hasDeletes = itemCount > 0 || otherFileCount > 0
    val mixed = associationCount > 0
    val subtitleFilesOnly = itemCount == 0 && otherFileCount == 0 && associationCount == 0 && hasWorkOrCustomSubtitles

    val message = if (subtitleFilesOnly) {
        buildList {
            add(stringResource(R.string.delete_dialog_subtitles_files_confirm, workSubtitleCount + customSubtitleCount))
            if (customSubtitleCount > 0) {
                add(stringResource(R.string.delete_dialog_custom_subtitles_note, customSubtitleCount))
            }
        }.joinToString("\n\n")
    } else {
        buildList {
            val handledGeneratedWithFiles = itemCount > 0 && otherFileCount == 0 && !mixed &&
                hasGeneratedSubtitles && !hasWorkOrCustomSubtitles
            when {
                handledGeneratedWithFiles -> {
                    add(stringResource(R.string.delete_dialog_subtitles_note_with_files, itemCount, subtitleCount))
                    add(stringResource(R.string.delete_dialog_subtitles_only))
                }
                itemCount > 0 && otherFileCount > 0 && mixed ->
                    add(stringResource(R.string.delete_dialog_audio_other_assoc_line, itemCount, otherFileCount, associationCount))
                itemCount > 0 && otherFileCount > 0 ->
                    add(stringResource(R.string.delete_dialog_audio_and_other_line, itemCount, otherFileCount))
                itemCount > 0 && mixed ->
                    add(stringResource(R.string.delete_dialog_message_downloads, itemCount, associationCount))
                otherFileCount > 0 && mixed ->
                    add(stringResource(R.string.delete_dialog_other_assoc_line, otherFileCount, associationCount))
                mixed ->
                    add(stringResource(R.string.delete_dialog_assoc_only_line, associationCount))
                itemCount > 0 && !hasAnySubtitles ->
                    add(stringResource(R.string.delete_dialog_files_confirm, itemCount))
                itemCount > 0 ->
                    add(stringResource(R.string.delete_dialog_audio_downloads_line, itemCount))
                otherFileCount > 0 ->
                    add(stringResource(R.string.delete_dialog_other_files_confirm, otherFileCount))
            }
            if (!handledGeneratedWithFiles && hasGeneratedSubtitles) {
                add(stringResource(R.string.delete_dialog_subtitles_note, subtitleCount))
                if (!hasWorkOrCustomSubtitles) {
                    add(stringResource(R.string.delete_dialog_subtitles_only))
                }
            }
            if (workSubtitleCount > 0) {
                add(stringResource(R.string.delete_dialog_work_subtitles_note, workSubtitleCount))
            }
            if (customSubtitleCount > 0) {
                add(stringResource(R.string.delete_dialog_custom_subtitles_note, customSubtitleCount))
            }
        }.joinToString("\n\n")
    }

    val titleRes = when {
        subtitleFilesOnly -> R.string.delete_dialog_title_subtitles_only
        hasAnySubtitles && (otherFileCount > 0 || workSubtitleCount > 0) -> R.string.delete_dialog_title_files_and_subtitles
        hasAnySubtitles && mixed -> R.string.delete_dialog_title_mixed
        hasAnySubtitles -> R.string.delete_dialog_title_subtitles
        mixed && hasDeletes -> R.string.delete_dialog_title_mixed
        mixed -> R.string.delete_dialog_title_unlink
        else -> R.string.delete_dialog_title_files
    }

    val confirmRes = when {
        subtitleFilesOnly -> R.string.delete_dialog_confirm_delete
        hasAnySubtitles -> R.string.delete_dialog_confirm_all
        mixed && hasDeletes -> R.string.delete_dialog_confirm_copy
        mixed -> R.string.library_action_unlink
        else -> R.string.delete_dialog_confirm_delete
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(titleRes)) },
        text = {
            Text(
                message,
                modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(true) }) {
                Text(stringResource(confirmRes), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            Row {
                if (!subtitleFilesOnly && hasAnySubtitles) {
                    TextButton(onClick = { onConfirm(false) }) {
                        Text(stringResource(R.string.delete_dialog_keep_subtitles))
                    }
                }
                TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
            }
        },
    )
}
