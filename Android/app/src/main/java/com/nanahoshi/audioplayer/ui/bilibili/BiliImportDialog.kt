package com.nanahoshi.audioplayer.ui.bilibili

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanahoshi.audioplayer.bilibili.BiliImportMode
import com.nanahoshi.audioplayer.data.BiliImportRequests

@Composable
fun BiliImportDialog(requests: BiliImportRequests) {
    val current by requests.prompt.collectAsStateWithLifecycle()
    val prompt = current ?: return
    AlertDialog(
        onDismissRequest = { requests.cancel(prompt.id) },
        title = { Text(if (prompt.confirming) "确认转换入库方式" else "选择入库方式") },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                prompt.options.forEachIndexed { index, option ->
                    item(key = "video-$index") {
                        Text(option.details.video.title, style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(vertical = 8.dp))
                        if (prompt.confirming) {
                            if (option.converts) Text(if (option.mode == BiliImportMode.GROUPED)
                                "将这个视频已有的分 P 归为一个视频项，保留下载、字幕和播放列表关联。"
                            else "原组内所有已入库分 P 都会保留为独立项，并放回原分组的父目录。勾选只控制新增内容，不删除未勾选的已有分 P。")
                        } else if (option.details.parts.size > 1 || option.existing == BiliImportMode.GROUPED) {
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                SegmentedButton(selected = option.mode == BiliImportMode.SPLIT,
                                    onClick = { requests.edit(prompt.id, index) { it.copy(mode = BiliImportMode.SPLIT) } },
                                    shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("分开导入") }
                                SegmentedButton(selected = option.mode == BiliImportMode.GROUPED,
                                    onClick = { requests.edit(prompt.id, index) { it.copy(mode = BiliImportMode.GROUPED) } },
                                    shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("统一导入") }
                            }
                            if (option.mode == BiliImportMode.GROUPED) Text("全部 ${option.details.parts.size} P 合为一个视频项", Modifier.padding(top = 8.dp))
                        }
                    }
                    if (!prompt.confirming && option.mode == BiliImportMode.SPLIT && option.details.parts.size > 1) {
                        item(key = "select-$index") {
                            TextButton(onClick = { requests.edit(prompt.id, index) {
                                val all = it.details.parts.map { part -> part.cid }.toSet()
                                it.copy(selected = if (it.selected == all) emptySet() else all)
                            } }) { Text("已选 ${option.selected.size} P · 全选 / 取消全选") }
                        }
                        option.details.parts.forEach { part ->
                            item(key = "part-$index-${part.cid}") {
                                val toggle = { requests.edit(prompt.id, index) {
                                    it.copy(selected = if (part.cid in it.selected) it.selected - part.cid else it.selected + part.cid)
                                } }
                                Row(Modifier.fillMaxWidth().clickable(onClick = toggle), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(part.cid in option.selected, { toggle() })
                                    Text("P${part.page} · ${part.title}")
                                }
                            }
                        }
                    }
                }
                if (prompt.failed.isNotEmpty()) item(key = "failures") {
                    Text("以下视频读取失败，其余内容可继续：", color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp))
                    prompt.failed.forEach { Text(it, modifier = Modifier.padding(top = 4.dp)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { requests.confirm(prompt.id) }, enabled = prompt.ready) {
            Text(if (prompt.confirming) "确认转换并继续" else "继续")
        } },
        dismissButton = { TextButton(onClick = { requests.cancel(prompt.id) }) { Text("取消") } },
    )
}
