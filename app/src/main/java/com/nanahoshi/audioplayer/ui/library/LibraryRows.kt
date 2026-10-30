package com.nanahoshi.audioplayer.ui.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.FileKind
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.LibraryFileEntity
import com.nanahoshi.audioplayer.data.db.PlaylistEntity
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.ui.CoverImage
import com.nanahoshi.audioplayer.ui.components.LabelBadge
import com.nanahoshi.audioplayer.ui.components.SourceBadge
import com.nanahoshi.audioplayer.ui.formatDuration
import com.nanahoshi.audioplayer.ui.theme.LocalReduceMotion
import com.nanahoshi.audioplayer.asmr.WorkFacts
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal data class LibraryCrumb(val id: Long, val name: String)

internal fun libraryTrail(trail: List<LibraryCrumb>, folder: FolderEntity?): List<LibraryCrumb> {
    if (folder == null) return emptyList()
    val index = trail.indexOfFirst { it.id == folder.id }
    val crumb = LibraryCrumb(folder.id, folder.name)
    return if (index >= 0) trail.take(index) + crumb else trail + crumb
}

@Composable
internal fun rememberLibraryClimb(
    currentFolderId: () -> Long?,
    up: () -> Unit,
    floorId: Long? = null,
): (Int) -> Unit {
    val scope = rememberCoroutineScope()
    var climbing by remember { mutableStateOf(false) }
    val floor by rememberUpdatedState(floorId)
    val climb: (Int) -> Unit = climb@{ steps ->
        if (steps <= 0 || climbing) return@climb
        climbing = true
        scope.launch {
            try {
                repeat(steps) {
                    val before = currentFolderId()
                    if (before == null || before == floor) return@launch
                    up()
                    withTimeoutOrNull(2000) {
                        snapshotFlow { currentFolderId() }.first { it != before }
                    }
                }
            }             finally {
                climbing = false
            }
        }
    }
    return climb
}

@Composable
internal fun LibraryBreadcrumb(
    trail: List<LibraryCrumb>,
    onRoot: () -> Unit,
    onCrumb: (Int) -> Unit,
    rootLabel: String = "音频库",
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onRoot) { Text(rootLabel) }
        trail.forEachIndexed { index, crumb ->
            Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant)
            val last = index == trail.lastIndex
            TextButton(onClick = { if (!last) onCrumb(index) }, enabled = !last) {
                Text(
                    crumb.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.widthIn(max = 160.dp),
                )
            }
        }
    }
}

@Composable
internal fun LazyItemScope.libraryMotionModifier(): Modifier {
    val reduce = LocalReduceMotion.current
    return if (reduce) {
        Modifier.animateItem(
            fadeInSpec = snap(),
            placementSpec = snap<IntOffset>(),
            fadeOutSpec = snap(),
        )
    } else {
        Modifier.animateItem()
    }
}

@Composable
internal fun PlaylistPickDialog(
    playlists: List<PlaylistEntity>,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加入播放列表") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(playlists, key = { it.id }) { playlist ->
                    Text(
                        playlist.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(playlist.id) }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun ConfirmRemoveDialog(
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从音频库移除") },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("移除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun ItemName(name: String, expanded: Boolean) {
    Text(
        name,
        maxLines = if (expanded) Int.MAX_VALUE else 1,
        overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FolderRow(
    folder: FolderEntity,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCache: (() -> Unit)?,
    onRecognize: (() -> Unit)?,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val work = folder.asmrSourceId != null
    if (work) {
        WorkPreviewRow(
            folder = folder,
            selected = selected,
            selecting = selecting,
            menu = menu,
            onMenu = { menu = it },
            onClick = onClick,
            onLongClick = onLongClick,
            onCache = onCache,
            onDelete = onDelete,
            modifier = modifier,
        )
        return
    }
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        label = "folderSelected",
    )
    ListItem(
        headlineContent = { ItemName(folder.name, selected) },
        supportingContent = { Text("文件夹") },
        leadingContent = {
            AnimatedContent(targetState = selecting, label = "folderLead") { on ->
                if (on) {
                    Checkbox(checked = selected, onCheckedChange = { onClick() })
                } else {
                    Icon(Icons.Default.Folder, contentDescription = null)
                }
            }
        },
        trailingContent = {
            if (!selecting) {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (onCache != null) {
                        DropdownMenuItem(text = { Text("缓存到本地") }, onClick = { menu = false; onCache() })
                    }
                    if (onRecognize != null) {
                        DropdownMenuItem(text = { Text("识别为作品") }, onClick = { menu = false; onRecognize() })
                    }
                    DropdownMenuItem(text = { Text("从音频库移除") }, onClick = { menu = false; onDelete() })
                }
            }
        },
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = ListItemDefaults.colors(containerColor = container),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorkPreviewRow(
    folder: FolderEntity,
    selected: Boolean,
    selecting: Boolean,
    menu: Boolean,
    onMenu: (Boolean) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCache: (() -> Unit)?,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val facts = WorkFacts.fromJson(folder.workMeta) ?: WorkFacts(
        title = folder.name,
        circle = "",
        vas = emptyList(),
        tags = emptyList(),
        release = "",
        sourceId = folder.asmrSourceId.orEmpty(),
    )
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        label = "workSelected",
    )
    Row(
        modifier
            .fillMaxWidth()
            .background(container)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        AnimatedContent(targetState = selecting, label = "workLead") { on ->
            if (on) {
                Checkbox(checked = selected, onCheckedChange = { onClick() })
            } else {
                CoverImage(folder.coverUri, size = 64.dp)
            }
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(
                facts.title.ifBlank { facts.sourceId },
                style = MaterialTheme.typography.titleMedium,
                maxLines = if (selected) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(facts.circle, facts.vas.joinToString("、")).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "—" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (facts.sourceId.isNotBlank()) {
                    LabelBadge(facts.sourceId, MaterialTheme.colorScheme.primary)
                }
                facts.tags.take(3).forEach { tag ->
                    LabelBadge(tag, MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (!selecting) {
            Box {
                IconButton(onClick = { onMenu(true) }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { onMenu(false) }) {
                    if (onCache != null) {
                        DropdownMenuItem(text = { Text("缓存到本地") }, onClick = { onMenu(false); onCache() })
                    }
                    DropdownMenuItem(text = { Text("从音频库移除") }, onClick = { onMenu(false); onDelete() })
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TrackRow(
    track: TrackEntity,
    hasSubtitle: Boolean,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onAppend: () -> Unit,
    onPlayNext: () -> Unit,
    onDownload: (() -> Unit)?,
    onDeleteDownloaded: (() -> Unit)?,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val downloaded = when (track.source) {
        TrackSource.BILIBILI -> track.localUri?.startsWith("/") == true &&
            java.io.File(track.localUri).isFile
        TrackSource.ASMR, TrackSource.DLSITE ->
            com.nanahoshi.audioplayer.data.LocalAudio.hasStoredCopy(track.localUri)
        else -> false
    }
    val appCopy = track.localUri?.startsWith("/") == true
    ListItem(
        headlineContent = { ItemName(track.title, selected) },
        supportingContent = {
            val artist = track.artist?.takeIf { it.isNotBlank() }
            Column {
                if (artist != null) {
                    Text(artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SourceBadge(track.source)
                    Text(formatDuration(track.durationMs), style = MaterialTheme.typography.labelMedium)
                    if (downloaded) {
                        LabelBadge(
                            if (track.source == TrackSource.BILIBILI) "已缓存" else "已下载",
                            MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    if (hasSubtitle) {
                        LabelBadge("有字幕", MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        leadingContent = {
            AnimatedContent(targetState = selecting, label = "trackLead") { on ->
                if (on) {
                    Checkbox(checked = selected, onCheckedChange = { onClick() })
                } else {
                    CoverImage(track.coverUri)
                }
            }
        },
        trailingContent = {
            if (!selecting) {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("下一个播放") }, onClick = { menu = false; onPlayNext() })
                    DropdownMenuItem(text = { Text("加入当前列表末尾") }, onClick = { menu = false; onAppend() })
                    if (!downloaded && onDownload != null) {
                        val label = if (track.source == TrackSource.BILIBILI) "缓存音轨" else "下载"
                        DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; onDownload() })
                    }
                    if (downloaded && onDeleteDownloaded != null) {
                        val label = when {
                            track.source == TrackSource.BILIBILI -> "删除已缓存的音轨"
                            !appCopy -> "取消识别"
                            else -> "删除已下载的文件"
                        }
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = { menu = false; onDeleteDownloaded() },
                        )
                    }
                    DropdownMenuItem(text = { Text("从音频库移除") }, onClick = { menu = false; onDelete() })
                }
            }
        },
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        ),
    )
}

@Composable
internal fun FileRow(
    file: LibraryFileEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val icon = when (file.kind) {
        FileKind.IMAGE -> Icons.Default.Image
        FileKind.TEXT -> Icons.Default.Description
        FileKind.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
    val label = when (file.kind) {
        FileKind.IMAGE -> "图片"
        FileKind.TEXT -> "文本"
        FileKind.OTHER -> "文件"
    }
    ListItem(
        headlineContent = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(label) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "更多")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("从音频库移除") }, onClick = { menu = false; onDelete() })
            }
        },
        modifier = modifier.clickable(onClick = onClick),
    )
}

@Composable
internal fun CachePromptDialog(
    prompt: CachePrompt?,
    onAnswer: (Any?) -> Unit,
) {
    when (prompt) {
        null -> Unit
        is CachePrompt.Source -> CachePickDialog(
            title = "缓存哪个来源？",
            message = "这部作品已购买，asmr.one 上也有收录。",
            options = listOf(TrackSource.DLSITE to "DLsite（已购买）", TrackSource.ASMR to "asmr.one"),
            onPick = onAnswer,
            onDismiss = { onAnswer(null) },
        )
        is CachePrompt.Format -> CachePickDialog(
            title = "下载哪种格式？",
            message = "有些音频同时有 ${prompt.conflict.extensions.joinToString(" / ") { it.uppercase() }} 两种格式（文件名前缀相同），只下载你选的那种。",
            options = prompt.conflict.extensions.map { it to it.uppercase() },
            onPick = onAnswer,
            onDismiss = { onAnswer(null) },
        )
    }
}

@Composable
private fun CachePickDialog(
    title: String,
    message: String,
    options: List<Pair<Any, String>>,
    onPick: (Any) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(message)
                options.forEach { (value, label) ->
                    Text(
                        label,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(value) }
                            .padding(vertical = 14.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
