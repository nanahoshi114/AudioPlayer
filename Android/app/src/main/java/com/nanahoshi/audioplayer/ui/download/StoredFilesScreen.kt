package com.nanahoshi.audioplayer.ui.download

import android.app.Application
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nanahoshi.audioplayer.AppGraph
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.data.StoredCatalog
import com.nanahoshi.audioplayer.data.StoredItem
import com.nanahoshi.audioplayer.data.StoredKind
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.decodeTextBytes
import com.nanahoshi.audioplayer.data.formatByteSize
import com.nanahoshi.audioplayer.data.readBoundedBytes
import com.nanahoshi.audioplayer.data.storedItemMatches
import com.nanahoshi.audioplayer.graph
import com.nanahoshi.audioplayer.playback.StoredAudioRequest
import com.nanahoshi.audioplayer.playback.SubtitleCue
import com.nanahoshi.audioplayer.playback.SubtitleLanguageDelete
import com.nanahoshi.audioplayer.playback.SubtitleLanguageDeleteResult
import com.nanahoshi.audioplayer.playback.hasTranslation
import com.nanahoshi.audioplayer.playback.parseGeneratedSubtitles
import com.nanahoshi.audioplayer.text.resolveUserError
import com.nanahoshi.audioplayer.ui.components.AppTopBar
import com.nanahoshi.audioplayer.ui.components.DeleteAudioDialog
import com.nanahoshi.audioplayer.ui.components.DeleteSubtitleLanguageDialog
import com.nanahoshi.audioplayer.ui.components.EmptyState
import com.nanahoshi.audioplayer.ui.components.ErrorState
import com.nanahoshi.audioplayer.ui.components.LoadingState
import com.nanahoshi.audioplayer.ui.components.SearchField
import com.nanahoshi.audioplayer.ui.components.SegmentedTabBar
import com.nanahoshi.audioplayer.ui.components.SelectionAction
import com.nanahoshi.audioplayer.ui.components.SelectionState
import com.nanahoshi.audioplayer.ui.components.SelectionTopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class StoredFilesViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
    private val catalog: StoredCatalog = graph.storedCatalog
    var items by mutableStateOf<List<StoredItem>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                items = catalog.list()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = getApplication<Application>().resolveUserError(failure, R.string.stored_read_failed)
            } finally {
                loading = false
            }
        }
    }

    fun openArticle(item: StoredItem) {
        viewModelScope.launch { openJapaneseArticle(getApplication(), graph, item.workKey) }
    }

    fun play(item: StoredItem) {
        if (item.kind != StoredKind.AUDIO) return
        graph.player.playStoredAudio(
            StoredAudioRequest(
                path = item.path,
                title = item.name,
                artist = item.workTitle,
                trackId = item.trackId,
                source = item.source,
                workKey = item.workKey,
                workCode = item.workCode,
                fileKey = item.fileKey,
                remoteUrl = item.remoteUrl,
            ),
        )
    }

    data class PendingDelete(val items: List<StoredItem>, val subtitles: Int)
    var pendingDelete by mutableStateOf<PendingDelete?>(null)
        private set

    fun requestDelete(items: List<StoredItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            try {
                pendingDelete = PendingDelete(items, catalog.generatedSubtitleCount(items))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = getApplication<Application>().resolveUserError(failure, R.string.stored_subtitle_info_failed)
            }
        }
    }

    fun cancelDelete() {
        pendingDelete = null
    }

    fun confirmDelete(deleteSubtitles: Boolean, onDone: () -> Unit) {
        val pending = pendingDelete ?: return
        pendingDelete = null
        viewModelScope.launch {
            try {
                catalog.delete(pending.items, deleteSubtitles)
                items = catalog.list()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = getApplication<Application>().resolveUserError(failure, R.string.stored_delete_failed)
            } finally {
                onDone()
            }
        }
    }

    suspend fun deleteSubtitleLanguage(
        item: StoredItem,
        target: SubtitleLanguageDelete,
    ): SubtitleLanguageDeleteResult {
        val result = try {
            if (item.translationOverlay) {
                if (target == SubtitleLanguageDelete.CHINESE) catalog.deleteTranslationOverlay(item)
                else SubtitleLanguageDeleteResult.NOT_APPLICABLE
            } else {
                catalog.deleteGeneratedLanguage(item, target)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SubtitleLanguageDeleteResult.FAILED
        }
        when (result) {
            SubtitleLanguageDeleteResult.DELETED -> {
                runCatching { items = catalog.list() }
            }
            SubtitleLanguageDeleteResult.BUSY -> graph.show(R.string.subtitle_delete_language_busy)
            SubtitleLanguageDeleteResult.FAILED -> graph.show(R.string.stored_delete_failed)
            SubtitleLanguageDeleteResult.NOT_APPLICABLE -> Unit
        }
        return result
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
    var showAudio by rememberSaveable { mutableStateOf(true) }
    val audioListState = rememberLazyListState()
    val subtitleListState = rememberLazyListState()
    var query by rememberSaveable { mutableStateOf("") }
    var selecting by remember { mutableStateOf(false) }
    val selection = remember { SelectionState<String>() }
    var preview by remember { mutableStateOf<StoredItem?>(null) }
    val categoryItems = remember(viewModel.items, showAudio) {
        viewModel.items.filter { (it.kind == StoredKind.AUDIO) == showAudio }
    }
    val visible = remember(categoryItems, query) {
        categoryItems.filter { storedItemMatches(it, query) }
    }
    val chosen = remember(categoryItems, selection.ids) {
        categoryItems.filter { it.path in selection }
    }
    val single = chosen.singleOrNull()
    val clearSelection = {
        selecting = false
        selection.clear()
    }
    BackHandler(enabled = selecting, onBack = clearSelection)
    val pendingDelete = viewModel.pendingDelete
    if (pendingDelete != null) {
        DeleteAudioDialog(
            itemCount = pendingDelete.items.size,
            subtitleCount = pendingDelete.subtitles,
            onConfirm = { deleteSubtitles -> viewModel.confirmDelete(deleteSubtitles, clearSelection) },
            onCancel = viewModel::cancelDelete,
        )
    }
    preview?.let { item ->
        SubtitlePreviewDialog(
            item = item,
            onDeleteLanguage = viewModel::deleteSubtitleLanguage,
            onDismiss = { preview = null },
        )
    }
    Column(Modifier.fillMaxSize()) {
        if (selecting) {
            SelectionTopBar(
                count = chosen.size,
                onClose = clearSelection,
                pinDestructiveWhenFits = true,
                actions = buildList {
                    add(
                        SelectionAction(
                            stringResource(R.string.stored_share),
                            Icons.Default.Share,
                            { shareStored(context, chosen.map { File(it.path) }) },
                            enabled = chosen.isNotEmpty(),
                        ),
                    )
                    if (single?.source == TrackSource.JAPANESE_ASMR) {
                        add(SelectionAction(stringResource(R.string.download_open_article), Icons.Default.Album, { viewModel.openArticle(single) }))
                    } else if (single?.sourceId != null) {
                        add(SelectionAction(stringResource(R.string.stored_open_work), Icons.Default.Album, { onOpenWork(single.sourceId) }))
                    }
                    add(
                        SelectionAction(
                            stringResource(R.string.common_delete),
                            Icons.Default.Delete,
                            { if (chosen.isNotEmpty()) viewModel.requestDelete(chosen) },
                            destructive = true,
                            enabled = chosen.isNotEmpty(),
                        ),
                    )
                },
            )
        } else {
            AppTopBar(
                title = stringResource(R.string.stored_title),
                onBack = onBack,
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !viewModel.loading) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.a11y_refresh))
                    }
                },
            )
        }
        SegmentedTabBar(
            options = listOf(true, false),
            selected = showAudio,
            label = { audio ->
                stringResource(if (audio) R.string.stored_segment_audio else R.string.stored_segment_subtitle)
            },
            onSelect = { showAudio = it },
            enabled = !selecting,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        SearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.stored_search_hint),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        when {
            viewModel.loading && visible.isEmpty() -> LoadingState(
                stringResource(R.string.stored_loading),
                Modifier.weight(1f).fillMaxWidth(),
            )
            viewModel.error != null && visible.isEmpty() -> ErrorState(
                message = viewModel.error!!,
                onRetry = viewModel::refresh,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            categoryItems.isEmpty() -> EmptyState(
                title = stringResource(
                    if (showAudio) R.string.stored_audio_empty_title
                    else R.string.stored_subtitle_empty_title,
                ),
                message = stringResource(
                    if (showAudio) R.string.stored_audio_empty_message
                    else R.string.stored_subtitle_empty_message,
                ),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            visible.isEmpty() -> EmptyState(
                title = stringResource(R.string.stored_search_empty_title),
                message = stringResource(R.string.stored_search_empty_message),
                actionLabel = stringResource(R.string.stored_clear_search),
                onAction = { query = "" },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            else -> LazyColumn(
                state = if (showAudio) audioListState else subtitleListState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                if (viewModel.loading) {
                    item(key = "loading") { LoadingState(stringResource(R.string.stored_loading)) }
                } else if (viewModel.error != null) {
                    item(key = "error") { ErrorState(viewModel.error!!, viewModel::refresh) }
                }
                if (!selecting) {
                    item(key = "selection-hint") {
                        Text(
                            stringResource(if (showAudio) R.string.stored_audio_tap_hint else R.string.stored_subtitle_tap_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                items(visible, key = { it.path }) { item ->
                    val on = item.path in selection
                    ListItem(
                        headlineContent = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        leadingContent = {
                            if (selecting) Checkbox(checked = on, onCheckedChange = null)
                        },
                        supportingContent = {
                            val bits = listOfNotNull(
                                when (item.kind) {
                                    StoredKind.GENERATED -> stringResource(R.string.stored_generated_subtitle)
                                    StoredKind.SUBTITLE -> stringResource(R.string.stored_subtitle_file)
                                    StoredKind.AUDIO -> null
                                },
                                item.workTitle?.takeIf { it.isNotBlank() },
                                item.workCode?.takeIf { it.isNotBlank() },
                                formatByteSize(item.bytes),
                            )
                            Text(bits.joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        },
                        colors = if (on) {
                            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        } else {
                            ListItemDefaults.colors()
                        },
                        modifier = Modifier.semantics { this.selected = on }.combinedClickable(
                            onLongClickLabel = stringResource(R.string.stored_select_file),
                            onClick = {
                                if (!selecting) {
                                    when (item.kind) {
                                        StoredKind.AUDIO -> viewModel.play(item)
                                        else -> preview = item
                                    }
                                    return@combinedClickable
                                }
                                selection.toggle(item.path)
                                if (selection.isEmpty) selecting = false
                            },
                            onLongClick = {
                                selecting = true
                                selection.toggle(item.path)
                                if (selection.isEmpty) selecting = false
                            },
                        ),
                    )
                }
            }
        }
    }
}

private const val SUBTITLE_PREVIEW_LIMIT = 512 * 1024

private class SubtitlePreviewContent(
    val raw: String,
    val cues: List<SubtitleCue>?,
    val truncated: Boolean,
    val hasTranslation: Boolean,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubtitlePreviewDialog(
    item: StoredItem,
    onDeleteLanguage: suspend (StoredItem, SubtitleLanguageDelete) -> SubtitleLanguageDeleteResult,
    onDismiss: () -> Unit,
) {
    var content by remember(item.path) { mutableStateOf<SubtitlePreviewContent?>(null) }
    var failed by remember(item.path) { mutableStateOf(false) }
    var reloadKey by remember(item.path) { mutableStateOf(0) }
    var busy by remember(item.path) { mutableStateOf(false) }
    var confirmTarget by remember(item.path) { mutableStateOf<SubtitleLanguageDelete?>(null) }
    val previewListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(item.path, reloadKey) {
        content = null
        failed = false
        val loaded = withContext(Dispatchers.IO) {
            runCatching { readSubtitlePreview(File(item.path), item) }
        }
        loaded.onSuccess { content = it }.onFailure { failed = true }
    }
    confirmTarget?.let { target ->
        DeleteSubtitleLanguageDialog(
            target = target,
            translationOverlay = item.translationOverlay,
            enabled = !busy,
            onConfirm = {
                confirmTarget = null
                busy = true
                scope.launch {
                    try {
                        val result = onDeleteLanguage(item, target)
                        if (result == SubtitleLanguageDeleteResult.DELETED) {
                            if (item.translationOverlay) onDismiss() else reloadKey++
                        }
                    } finally {
                        busy = false
                    }
                }
            },
            onDismiss = { confirmTarget = null },
        )
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            val loaded = content
            when {
                failed -> Text(stringResource(R.string.stored_preview_failed))
                loaded == null -> LoadingState(stringResource(R.string.stored_loading))
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val showDeleteChinese = item.kind == StoredKind.GENERATED && loaded.hasTranslation
                    val showDeleteOriginal = item.kind == StoredKind.GENERATED && !item.translationOverlay && loaded.hasTranslation
                    if (showDeleteChinese || showDeleteOriginal) {
                        FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (showDeleteChinese) {
                                TextButton(
                                    enabled = !busy,
                                    onClick = { confirmTarget = SubtitleLanguageDelete.CHINESE },
                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                                ) {
                                    Text(
                                        stringResource(R.string.player_delete_chinese_subtitle),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            if (showDeleteOriginal) {
                                TextButton(
                                    enabled = !busy,
                                    onClick = { confirmTarget = SubtitleLanguageDelete.ORIGINAL },
                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                                ) {
                                    Text(
                                        stringResource(R.string.player_delete_original_subtitle),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                    val cues = loaded.cues
                    LazyColumn(
                        state = previewListState,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (loaded.truncated) {
                            item(key = "truncated") {
                                Text(
                                    stringResource(R.string.stored_preview_truncated),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (cues == null) {
                            item(key = "raw") {
                                SelectionContainer {
                                    Text(loaded.raw, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        } else {
                            itemsIndexed(cues, key = { index, cue -> "$index-${cue.startMs}" }) { _, cue ->
                                SelectionContainer {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            "${subtitleTime(cue.startMs)} → ${subtitleTime(cue.endMs)}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(cue.text, style = MaterialTheme.typography.bodyMedium)
                                        cue.translation?.takeIf { it.isNotBlank() }?.let { translation ->
                                            Text(
                                                translation,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}

private fun readSubtitlePreview(file: File, item: StoredItem): SubtitlePreviewContent {
    if (!file.isFile) throw java.io.IOException("subtitle missing")
    val (bytes, clipped) = file.inputStream().use { readBoundedBytes(it, SUBTITLE_PREVIEW_LIMIT) }
    val raw = decodeTextBytes(bytes, clipped)
    val cues = if (item.kind == StoredKind.GENERATED) parseGeneratedSubtitles(raw)?.displayCues else null
    val hasTranslation = when {
        item.kind != StoredKind.GENERATED -> false
        item.translationOverlay -> true
        cues != null -> cues.hasTranslation()
        clipped -> runCatching { parseGeneratedSubtitles(file.readText())?.displayCues?.hasTranslation() == true }.getOrDefault(false)
        else -> false
    }
    return SubtitlePreviewContent(raw, cues, clipped, hasTranslation)
}

private fun subtitleTime(milliseconds: Long): String {
    val safe = milliseconds.coerceAtLeast(0L)
    val hours = safe / 3_600_000L
    val minutes = safe / 60_000L % 60L
    val seconds = safe / 1_000L % 60L
    val millis = safe % 1_000L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d.%03d", hours, minutes, seconds, millis)
    } else {
        String.format(Locale.US, "%02d:%02d.%03d", minutes, seconds, millis)
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
        val chooser = Intent.createChooser(intent, context.getString(R.string.stored_share))
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(chooser)
    } catch (_: Exception) {
        (context.applicationContext as Application).graph().show(R.string.download_error_share_file)
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

internal suspend fun openJapaneseArticle(app: Application, graph: AppGraph, postId: String?) {
    val article = postId?.let { graph.db.japaneseAsmr().post(it) } ?: return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(article.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { app.startActivity(intent) }.onFailure { graph.show(R.string.download_error_open_article) }
}
