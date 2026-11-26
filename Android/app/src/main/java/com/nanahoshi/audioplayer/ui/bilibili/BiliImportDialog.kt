package com.nanahoshi.audioplayer.ui.bilibili

import com.nanahoshi.audioplayer.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanahoshi.audioplayer.bilibili.BiliImportMode
import com.nanahoshi.audioplayer.data.BiliImportPrompt
import com.nanahoshi.audioplayer.data.BiliImportRequests
import com.nanahoshi.audioplayer.ui.components.SegmentedTabBar

@Composable
internal fun BiliImportDialog(
    requests: BiliImportRequests,
    prompt: BiliImportPrompt? = requests.prompt.collectAsStateWithLifecycle().value,
) {
    if (prompt == null) return
    AlertDialog(
        onDismissRequest = { requests.cancel(prompt.id) },
        title = { Text(stringResource(if (prompt.confirming) R.string.bilibili_import_change_title else R.string.bilibili_import_choose_title)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                prompt.options.forEachIndexed { index, option ->
                    if (option.details.parts.size < 2) return@forEachIndexed
                    item(key = "video-$index") {
                        Text(option.details.video.title, style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(vertical = 8.dp))
                        if (prompt.confirming) {
                            if (option.converts) Text(
                                stringResource(
                                    if (option.mode == BiliImportMode.GROUPED) R.string.bilibili_import_grouped_help
                                    else R.string.bilibili_import_separate_help,
                                ),
                            )
                        } else {
                            val separateLabel = stringResource(R.string.bilibili_import_separate)
                            val groupedLabel = stringResource(R.string.bilibili_import_grouped)
                            SegmentedTabBar(
                                options = listOf(BiliImportMode.SPLIT, BiliImportMode.GROUPED),
                                selected = option.mode,
                                label = { mode -> if (mode == BiliImportMode.SPLIT) separateLabel else groupedLabel },
                                onSelect = { mode -> requests.edit(prompt.id, index) { it.copy(mode = mode) } },
                            )
                            if (option.mode == BiliImportMode.GROUPED) Text(
                                stringResource(R.string.bilibili_import_grouped_summary, option.details.parts.size),
                                Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                    if (!prompt.confirming && option.mode == BiliImportMode.SPLIT) {
                        item(key = "select-$index") {
                            val all = option.details.parts.map { it.cid }.toSet()
                            TextButton(onClick = { requests.edit(prompt.id, index) {
                                it.copy(selected = if (it.selected == all) emptySet() else all)
                            } }) {
                                Text(
                                    stringResource(
                                        if (option.selected.size == option.details.parts.size) R.string.bilibili_import_selected_deselect
                                        else R.string.bilibili_import_selected_select,
                                        option.selected.size,
                                    ),
                                )
                            }
                        }
                        option.details.parts.forEach { part ->
                            item(key = "part-$index-${part.cid}") {
                                val toggle = { requests.edit(prompt.id, index) {
                                    it.copy(selected = if (part.cid in it.selected) it.selected - part.cid else it.selected + part.cid)
                                } }
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = toggle),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(part.cid in option.selected, { toggle() })
                                    Text("P${part.page} · ${part.title}")
                                }
                            }
                        }
                    }
                }
                if (prompt.failed.isNotEmpty()) item(key = "failures") {
                    Text(stringResource(R.string.bilibili_import_failures), color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp))
                    prompt.failed.forEach { Text(it, modifier = Modifier.padding(top = 4.dp)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { requests.confirm(prompt.id) }, enabled = prompt.ready) {
            Text(stringResource(if (prompt.confirming) R.string.bilibili_import_confirm_change else R.string.bilibili_import_confirm))
        } },
        dismissButton = { TextButton(onClick = { requests.cancel(prompt.id) }) { Text(stringResource(R.string.common_cancel)) } },
    )
}
