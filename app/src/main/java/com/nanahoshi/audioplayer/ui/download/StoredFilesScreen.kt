package com.nanahoshi.audioplayer.ui.download

import android.app.Application
import android.content.ClipData
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellationException
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import com.nanahoshi.audioplayer.ui.components.AppTopBar
import com.nanahoshi.audioplayer.ui.components.EmptyState
import com.nanahoshi.audioplayer.ui.components.SelectionAction
import com.nanahoshi.audioplayer.ui.components.SelectionTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nanahoshi.audioplayer.data.StoredCatalog
import com.nanahoshi.audioplayer.data.StoredItem
import com.nanahoshi.audioplayer.data.StoredKind
import com.nanahoshi.audioplayer.data.formatByteSize
import com.nanahoshi.audioplayer.data.storedItemMatches
import com.nanahoshi.audioplayer.graph
import com.nanahoshi.audioplayer.ui.EmptyHint
import kotlinx.coroutines.launch
import java.io.File

class StoredFilesViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
    private val catalog = StoredCatalog(app, graph.db, graph.subtitles)
    var items by mutableStateOf<List<StoredItem>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            loading = true
            items = try {
                catalog.list()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            loading = false
        }
    }

    fun delete(selected: List<StoredItem>, onDone: () -> Unit) {
        viewModelScope.launch {
            catalog.delete(selected)
            items = catalog.list()
            onDone()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StoredFilesScreen(
    onBack: () -> Unit,
    onOpenWork: (String) -> Unit,
    viewModel: StoredFilesViewModel = viewModel(),
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val visible = remember(viewModel.items, query) {
        viewModel.items.filter { storedItemMatches(it, query) }
    }
    val chosen = viewModel.items.filter { it.path in selected }
    val single = chosen.singleOrNull()
    BackHandler(enabled = selecting) {
        selecting = false
        selected = emptySet()
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text("删除选中的 ${chosen.size} 个文件？") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val pending = chosen
                    viewModel.delete(pending) {
                        selecting = false
                        selected = emptySet()
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
    Column(Modifier.fillMaxSize()) {
        if (selecting) {
            SelectionTopBar(
                count = chosen.size,
                onClose = {
                    selecting = false
                    selected = emptySet()
                },
                actions = buildList {
                    add(
                        SelectionAction(
                            "导出",
                            Icons.Default.Share,
                            { shareStored(context, chosen.map { File(it.path) }) },
                            enabled = chosen.isNotEmpty(),
                        ),
                    )
                    if (single?.sourceId != null) {
                        add(SelectionAction("作品", Icons.Default.Album, { onOpenWork(single.sourceId) }))
                    }
                    add(
                        SelectionAction(
                            "删除",
                            Icons.Default.Delete,
                            { if (chosen.isNotEmpty()) confirmDelete = true },
                            destructive = true,
                            enabled = chosen.isNotEmpty(),
                        ),
                    )
                },
            )
        } else {
            AppTopBar(title = "下载管理", onBack = onBack)
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("文件名、RJ 号或作品名") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        when {
            viewModel.loading && viewModel.items.isEmpty() -> EmptyState(title = "正在读取已下载的文件", modifier = Modifier.fillMaxSize())
            viewModel.items.isEmpty() -> EmptyState(title = "没有已下载的文件", modifier = Modifier.fillMaxSize())
            visible.isEmpty() -> EmptyState(title = "没有匹配的文件", modifier = Modifier.fillMaxSize())
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(visible, key = { it.path }) { item ->
                    val on = item.path in selected
                    ListItem(
                        headlineContent = { Text(item.name) },
                        supportingContent = {
                            val bits = listOfNotNull(
                                when (item.kind) {
                                    StoredKind.GENERATED -> "生成字幕"
                                    StoredKind.SUBTITLE -> "字幕"
                                    StoredKind.AUDIO -> null
                                },
                                item.workTitle?.takeIf { it.isNotBlank() },
                                item.workCode?.takeIf { it.isNotBlank() },
                                formatByteSize(item.bytes),
                            )
                            Text(bits.joinToString(" · "))
                        },
                        colors = if (on) {
                            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        } else {
                            ListItemDefaults.colors()
                        },
                        modifier = Modifier.combinedClickable(
                            onClick = {
                                if (!selecting) return@combinedClickable
                                selected = if (on) selected - item.path else selected + item.path
                                if (selected.isEmpty()) selecting = false
                            },
                            onLongClick = {
                                selecting = true
                                selected = if (on) selected - item.path else selected + item.path
                                if (selected.isEmpty()) selecting = false
                            },
                        ),
                    )
                }
            }
        }
    }
}

private fun shareStored(context: Context, files: List<File>) {
    val ready = files.filter { it.isFile }
    if (ready.isEmpty()) return
    try {
        val authority = "${context.packageName}.files"
        val uris = ready.map { FileProvider.getUriForFile(context, authority, it) }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = shareType(ready[0])
                putExtra(Intent.EXTRA_STREAM, uris[0])
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }
        val clip = ClipData.newRawUri("files", uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        intent.clipData = clip
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(intent, "导出")
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(chooser)
    } catch (_: Exception) {
        (context.applicationContext as Application).graph().messages.value = "无法导出"
    }
}

private fun shareType(file: File): String = when (file.extension.lowercase()) {
    "json" -> "application/json"
    "lrc", "srt", "vtt" -> "text/plain"
    "mp3" -> "audio/mpeg"
    "flac" -> "audio/flac"
    "wav" -> "audio/wav"
    "m4a", "aac" -> "audio/mp4"
    "ogg" -> "audio/ogg"
    else -> "*/*"
}
