package com.nanahoshi.audioplayer.ui.library

import com.nanahoshi.audioplayer.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.isSubtitleFile
import com.nanahoshi.audioplayer.data.localRemoval
import com.nanahoshi.audioplayer.data.db.FileKind
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.LibraryFileEntity
import com.nanahoshi.audioplayer.data.db.PlaylistEntity
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.ui.CoverImage
import com.nanahoshi.audioplayer.ui.components.BadgeItem
import com.nanahoshi.audioplayer.ui.components.BadgeLine
import com.nanahoshi.audioplayer.ui.components.LabelBadge
import com.nanahoshi.audioplayer.ui.components.SourceBadge
import com.nanahoshi.audioplayer.ui.formatDuration
import com.nanahoshi.audioplayer.ui.theme.LocalReduceMotion
import com.nanahoshi.audioplayer.ui.theme.LocalSourceColors
import com.nanahoshi.audioplayer.ui.theme.Motion
import com.nanahoshi.audioplayer.asmr.WorkFacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal data class LibraryCrumb(val id: Long, val name: String)

/** 音频库普通目录的路径栏跨旋转保留。 */
internal val LibraryTrailSaver: Saver<List<LibraryCrumb>, Any> = listSaver<List<LibraryCrumb>, String>(
    save = { trail -> trail.flatMap { listOf(it.id.toString(), it.name) } },
    restore = { flat ->
        List(flat.size / 2) { index ->
            LibraryCrumb(flat[index * 2].toLong(), flat[index * 2 + 1])
        }
    },
)

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
): (Int) -> Unit {
    val scope = rememberCoroutineScope()
    var climbing by remember { mutableStateOf(false) }
    val climb: (Int) -> Unit = climb@{ steps ->
        if (steps <= 0 || climbing) return@climb
        climbing = true
        scope.launch {
            try {
                climbUp(steps, currentFolderId, up) { before ->
                    withTimeoutOrNull(2000) {
                        snapshotFlow { currentFolderId() }.first { it != before }
                    } != null
                }
            } finally {
                climbing = false
            }
        }
    }
    return climb
}

/** 逐层上移；某层未在等待内刷新即停止，避免超时后继续 up() 越过目标层级。 */
internal suspend fun climbUp(
    steps: Int,
    currentFolderId: () -> Long?,
    up: () -> Unit,
    awaitChange: suspend (Long) -> Boolean,
) {
    repeat(steps) {
        val before = currentFolderId() ?: return
        up()
        if (!awaitChange(before)) return
    }
}

@Composable
internal fun LibraryBreadcrumb(
    trail: List<LibraryCrumb>,
    onRoot: () -> Unit,
    onCrumb: (Int) -> Unit,
    rootLabel: String = stringResource(R.string.nav_library),
    revealCurrent: Boolean = false,
) {
    val scroll = rememberScrollState()
    if (revealCurrent) {
        val pathKey = trail.joinToString("/") { it.id.toString() }
        var shownPath by rememberSaveable { mutableStateOf(pathKey) }
        LaunchedEffect(pathKey) {
            if (shownPath != pathKey) {
                scroll.scrollTo(0)
                shownPath = pathKey
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scroll, reverseScrolling = revealCurrent).padding(horizontal = 4.dp),
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
        title = { Text(stringResource(R.string.library_add_playlist)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(playlists, key = { it.id }) { playlist ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { onPick(playlist.id) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(playlist.name)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
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
        title = { Text(stringResource(R.string.library_remove_from_library)) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.common_remove), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
internal fun ItemName(name: String, expanded: Boolean) {
    Text(
        name,
        maxLines = if (expanded) Int.MAX_VALUE else 1,
        softWrap = expanded,
        overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FolderRow(
    folder: FolderEntity,
    biliArtist: String? = null,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCache: (() -> Unit)?,
    onRecognize: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val reduce = LocalReduceMotion.current
    if (folder.biliBvid != null) {
        BiliVideoPreviewRow(folder, biliArtist, selected, selecting, onClick, onLongClick, onDelete, modifier)
        return
    }
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
        animationSpec = if (reduce) snap() else tween(Motion.short),
        label = "folderSelected",
    )
    ListItem(
        headlineContent = { ItemName(folder.name, selected) },
        supportingContent = { Text(stringResource(R.string.library_folder)) },
        leadingContent = {
            AnimatedContent(
                targetState = selecting,
                transitionSpec = {
                    if (reduce) EnterTransition.None togetherWith ExitTransition.None
                    else fadeIn(tween(Motion.short)) togetherWith fadeOut(tween(Motion.short))
                },
                label = "folderLead",
            ) { on ->
                if (on) {
                    Checkbox(checked = selected, onCheckedChange = { onClick() })
                } else {
                    Icon(Icons.Default.Folder, contentDescription = null)
                }
            }
        },
        trailingContent = {
            if (!selecting && (onCache != null || onRecognize != null || onDelete != null)) {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.a11y_folder_actions))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (onCache != null) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.library_download_whole_work)) }, onClick = { menu = false; onCache() })
                    }
                    if (onRecognize != null) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.library_recognize_work)) }, onClick = { menu = false; onRecognize() })
                    }
                    if (onDelete != null) DropdownMenuItem(text = { Text(stringResource(R.string.library_remove_from_library)) }, onClick = { menu = false; onDelete() })
                }
            }
        },
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = ListItemDefaults.colors(containerColor = container),
    )
}

/** BadgeLine uses BoxWithConstraints, so this card must not use ListItem's intrinsic slots. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BiliVideoPreviewRow(
    folder: FolderEntity,
    artist: String?,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val reduce = LocalReduceMotion.current
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        animationSpec = if (reduce) snap() else tween(Motion.short),
        label = "biliVideoSelected",
    )
    Row(
        modifier
            .fillMaxWidth()
            .background(container)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
            if (selecting) {
                Checkbox(checked = selected, onCheckedChange = { onClick() })
            } else {
                CoverImage(folder.coverUri, size = 64.dp, shape = MaterialTheme.shapes.small)
            }
        }
        Column(Modifier.padding(start = 16.dp).weight(1f)) {
            Text(
                folder.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = if (selected) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                artist?.ifBlank { null } ?: stringResource(R.string.library_uploader_unknown),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            BadgeLine(
                leading = listOf(BadgeItem(stringResource(R.string.library_badge_bilibili), LocalSourceColors.current.bilibili),
                    BadgeItem(stringResource(R.string.library_badge_multi_part), MaterialTheme.colorScheme.secondary)),
                tags = listOf(BadgeItem(folder.biliBvid.orEmpty(), MaterialTheme.colorScheme.onSurfaceVariant)),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (!selecting && onDelete != null) Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.a11y_video_actions)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.library_remove_from_library)) }, onClick = { menu = false; onDelete() })
            }
        }
    }
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
    onDelete: (() -> Unit)?,
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
    val reduce = LocalReduceMotion.current
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        animationSpec = if (reduce) snap() else tween(Motion.short),
        label = "workSelected",
    )
    Row(
        modifier
            .fillMaxWidth()
            .background(container)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
            AnimatedContent(
                targetState = selecting,
                transitionSpec = {
                    if (reduce) EnterTransition.None togetherWith ExitTransition.None
                    else fadeIn(tween(Motion.short)) togetherWith fadeOut(tween(Motion.short))
                },
                label = "workLead",
            ) { on ->
                if (on) {
                    Checkbox(checked = selected, onCheckedChange = { onClick() })
                } else {
                    CoverImage(folder.coverUri, size = 64.dp, shape = MaterialTheme.shapes.small)
                }
            }
        }
        Column(Modifier.padding(start = 16.dp).weight(1f)) {
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
            BadgeLine(
                leading = buildList {
                    if (facts.sourceId.isNotBlank()) {
                        add(BadgeItem(facts.sourceId, MaterialTheme.colorScheme.primary))
                    }
                },
                tags = facts.tags.take(8).map {
                    BadgeItem(it, MaterialTheme.colorScheme.onSurfaceVariant)
                },
                totalTags = facts.tags.size,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (!selecting) {
            Box {
                IconButton(onClick = { onMenu(true) }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.a11y_work_actions))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { onMenu(false) }) {
                    if (onCache != null) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.library_download_whole_work)) }, onClick = { onMenu(false); onCache() })
                    }
                    if (onDelete != null) DropdownMenuItem(text = { Text(stringResource(R.string.library_remove_from_library)) }, onClick = { onMenu(false); onDelete() })
                }
            }
        }
    }
}

/** App 自己下载到 filesDir 的副本；关联的用户文件是 content: / file: URI。 */
internal fun TrackEntity.isAppCopy(): Boolean = localUri?.startsWith("/") == true

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
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
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onBindSubtitle: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    val reduce = LocalReduceMotion.current
    val downloaded = when (track.source) {
        TrackSource.BILIBILI, TrackSource.YOUTUBE -> track.localUri?.startsWith("/") == true &&
            java.io.File(track.localUri).isFile
        TrackSource.ASMR, TrackSource.DLSITE, TrackSource.JAPANESE_ASMR ->
            com.nanahoshi.audioplayer.data.LocalAudio.hasStoredCopy(track.localUri)
        else -> false
    }
    val appCopy = track.isAppCopy()
    ListItem(
        headlineContent = { ItemName(track.title, selected) },
        supportingContent = {
            val artist = track.artist?.takeIf { it.isNotBlank() }
            Column {
                if (artist != null) {
                    Text(artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SourceBadge(track.source)
                    Text(formatDuration(track.durationMs), style = MaterialTheme.typography.labelMedium)
                    if (downloaded) {
                        LabelBadge(stringResource(R.string.library_badge_downloaded), MaterialTheme.colorScheme.tertiary)
                    }
                    if (hasSubtitle) {
                        LabelBadge(stringResource(R.string.library_badge_has_subtitle), MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        leadingContent = {
            AnimatedContent(
                targetState = selecting,
                transitionSpec = {
                    if (reduce) EnterTransition.None togetherWith ExitTransition.None
                    else fadeIn(tween(Motion.short)) togetherWith fadeOut(tween(Motion.short))
                },
                label = "trackLead",
            ) { on ->
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
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.a11y_track_actions))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.library_play_next)) }, onClick = { menu = false; onPlayNext() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.library_add_queue)) }, onClick = { menu = false; onAppend() })
                    if (onBindSubtitle != null) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.library_track_bind_subtitle)) }, onClick = { menu = false; onBindSubtitle() })
                    }
                    if (!downloaded && onDownload != null) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.library_download_track)) }, onClick = { menu = false; onDownload() })
                    }
                    if (downloaded && onDeleteDownloaded != null) {
                        val label = stringResource(if (!appCopy) R.string.library_action_unlink else R.string.library_action_delete_downloads)
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = { menu = false; onDeleteDownloaded() },
                        )
                    }
                    if (onDelete != null) DropdownMenuItem(text = { Text(stringResource(R.string.library_remove_from_library)) }, onClick = { menu = false; onDelete() })
                }
            }
        },
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        ),
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun FileRow(
    file: LibraryFileEntity,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
    selecting: Boolean = false,
    selected: Boolean = false,
    onLongClick: () -> Unit = {},
    onBindSubtitle: (() -> Unit)? = null,
    onDeleteDownloaded: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val reduce = LocalReduceMotion.current
    var menu by remember { mutableStateOf(false) }
    val subtitleFile = isSubtitleFile(file.name)
    val downloaded by produceState(initialValue = false, key1 = file.id, key2 = file.localUri) {
        value = withContext(Dispatchers.IO) { file.localRemoval(context.filesDir) != null }
    }
    val icon = when {
        subtitleFile -> Icons.Default.Subtitles
        file.kind == FileKind.IMAGE -> Icons.Default.Image
        file.kind == FileKind.TEXT -> Icons.Default.Description
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
    val label = when {
        subtitleFile -> stringResource(R.string.file_kind_subtitle)
        file.kind == FileKind.IMAGE -> stringResource(R.string.file_kind_image)
        file.kind == FileKind.TEXT -> stringResource(R.string.file_kind_text)
        else -> stringResource(R.string.file_kind_other)
    }
    val hasMenuActions = (subtitleFile && onBindSubtitle != null) ||
        (downloaded && onDeleteDownloaded != null) ||
        onDelete != null
    ListItem(
        headlineContent = { ItemName(file.name, selected) },
        supportingContent = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(label)
                if (downloaded) {
                    LabelBadge(stringResource(R.string.library_badge_downloaded), MaterialTheme.colorScheme.tertiary)
                }
            }
        },
        leadingContent = {
            AnimatedContent(
                targetState = selecting,
                transitionSpec = {
                    if (reduce) EnterTransition.None togetherWith ExitTransition.None
                    else fadeIn(tween(Motion.short)) togetherWith fadeOut(tween(Motion.short))
                },
                label = "fileLead",
            ) { on ->
                if (on) {
                    Checkbox(checked = selected, onCheckedChange = { onClick() })
                } else {
                    Icon(icon, contentDescription = null)
                }
            }
        },
        trailingContent = {
            if (!selecting && hasMenuActions) {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.a11y_file_actions))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (subtitleFile && onBindSubtitle != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_bind_subtitle_to_audio)) },
                            onClick = { menu = false; onBindSubtitle() },
                        )
                    }
                    if (downloaded && onDeleteDownloaded != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_action_delete_downloads)) },
                            onClick = { menu = false; onDeleteDownloaded() },
                        )
                    }
                    if (onDelete != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_remove_from_library)) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
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
internal fun CachePromptDialog(
    prompt: CachePrompt?,
    onAnswer: (Any?) -> Unit,
    onCancelWaiting: (CachePrompt.JapaneseWaiting) -> Unit,
    onVerifyJaDownload: (String) -> Unit,
    onVerifyJaRefresh: (String) -> Unit = {},
) {
    when (prompt) {
        null -> Unit
        is CachePrompt.Source -> CachePickDialog(
            title = stringResource(R.string.library_choose_source_title),
            message = stringResource(R.string.library_choose_source_message),
            options = prompt.sources.map { source -> source to when (source) {
                TrackSource.DLSITE -> stringResource(R.string.library_source_dlsite_archive)
                TrackSource.JAPANESE_ASMR -> "Japanese ASMR"
                else -> "asmr.one"
            } },
            onPick = onAnswer,
            onDismiss = { onAnswer(null) },
        )
        is CachePrompt.JapanesePosts -> CachePickDialog(
            title = stringResource(if (prompt.refresh) R.string.library_ja_refresh_title else R.string.library_ja_download_title),
            message = stringResource(if (prompt.refresh) R.string.library_ja_multiple_message else R.string.library_ja_multiple_download_message),
            options = (if (prompt.refresh) listOf(true to stringResource(R.string.library_ja_all)) else emptyList()) +
                prompt.posts.map { it.postId to it.title },
            onPick = onAnswer,
            onDismiss = { onAnswer(null) },
        )
        is CachePrompt.JapaneseChoice -> CachePickDialog(
            title = prompt.title, message = prompt.message, options = prompt.options,
            onPick = onAnswer, onDismiss = { onAnswer(null) },
        )
        is CachePrompt.JapaneseVerify -> CachePickDialog(
            title = when {
                prompt.buzzheavier -> stringResource(R.string.library_ja_verify_buzz_open)
                prompt.articleTitle != null -> stringResource(R.string.library_ja_verify_article)
                else -> stringResource(R.string.library_ja_verify_read)
            },
            message = when {
                prompt.buzzheavier -> stringResource(R.string.library_ja_verify_buzz_help)
                prompt.articleTitle != null -> stringResource(R.string.library_ja_verify_article_help, prompt.articleTitle)
                else -> stringResource(R.string.library_ja_verify_help)
            },
            options = listOf(true to stringResource(
                if (prompt.buzzheavier) R.string.library_ja_open_hosting else R.string.library_ja_open_verify,
            )),
            onPick = {
                onAnswer(true)
                if (prompt.articleTitle == null) onVerifyJaDownload(prompt.token) else onVerifyJaRefresh(prompt.token)
            },
            onDismiss = { onAnswer(null) },
        )
        is CachePrompt.JapaneseWaiting -> AlertDialog(
            onDismissRequest = { onCancelWaiting(prompt) },
            title = { Text(prompt.title) },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { onCancelWaiting(prompt) }) { Text(stringResource(R.string.common_cancel)) } },
        )
        is CachePrompt.Format -> CachePickDialog(
            title = stringResource(R.string.library_format_title),
            message = stringResource(
                R.string.library_format_message,
                prompt.conflict.extensions.joinToString(" / ") { it.uppercase() },
            ),
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
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(options) { option ->
                        val (value, label) = option
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable { onPick(value) }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(label, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
