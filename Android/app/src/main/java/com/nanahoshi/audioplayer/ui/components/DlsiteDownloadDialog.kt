package com.nanahoshi.audioplayer.ui.components

import com.nanahoshi.audioplayer.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanahoshi.audioplayer.data.DlsiteDownloadMode
import com.nanahoshi.audioplayer.data.DlsiteDownloadRequests

@Composable
fun DlsiteDownloadDialog(requests: DlsiteDownloadRequests) {
    val pending by requests.pending.collectAsStateWithLifecycle()
    val prompt = pending ?: return
    AlertDialog(
        onDismissRequest = { requests.answer(prompt.id, null) },
        title = { Text(stringResource(R.string.dlsite_download_pick_title)) },
        text = {
            Column(
                Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(
                        if (prompt.allowPartial) R.string.dlsite_download_partial_warning
                        else R.string.dlsite_download_archive_required,
                    ),
                )
                Text(stringResource(R.string.dlsite_download_archive_note), style = MaterialTheme.typography.bodySmall)
                if (prompt.workNames.isNotEmpty()) {
                    Text(prompt.workNames.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = { requests.answer(prompt.id, DlsiteDownloadMode.ARCHIVE) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.dlsite_download_archive))
                }
                if (prompt.allowPartial) {
                    TextButton(
                        onClick = { requests.answer(prompt.id, DlsiteDownloadMode.PARTIAL) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.dlsite_download_selected))
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { requests.answer(prompt.id, null) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
