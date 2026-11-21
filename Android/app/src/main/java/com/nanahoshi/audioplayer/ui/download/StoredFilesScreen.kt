package com.nanahoshi.audioplayer.ui.download

import com.nanahoshi.audioplayer.text.resolveUserError
import com.nanahoshi.audioplayer.R
import androidx.compose.ui.res.stringResource

import android.app.Application
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.nanahoshi.audioplayer.AppGraph
import com.nanahoshi.audioplayer.data.TrackSource
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import com.nanahoshi.audioplayer.ui.components.AppTopBar
import com.nanahoshi.audioplayer.ui.components.DeleteAudioDialog
import com.nanahoshi.audioplayer.ui.components.EmptyState
import com.nanahoshi.audioplayer.ui.components.ErrorState
import com.nanahoshi.audioplayer.ui.components.LoadingState
import com.nanahoshi.audioplayer.ui.components.SelectionAction
import com.nanahoshi.audioplayer.ui.components.SelectionTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
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
        viewModelScope.launch {
            val track = item.trackId?.let { graph.library.track(it) } ?: graph.db.tracks().findByLocalUri(item.path)
            if (track != null) {
                graph.player.replaceAndPlay(listOf(track))
                return@launch
            }
            if (item.source == TrackSource.JAPANESE_ASMR && item.remoteUrl?.startsWith("japaneseasmr://file?") == true) {
                graph.player.replaceAndPlayRemote(listOf(com.nanahoshi.audioplayer.dlsite.RemotePlayable(
                    item.name, item.workTitle, null, item.remoteUrl, item.source, "",
                    workno = item.workCode, fileKey = item.fileKey,
                )))
                return@launch
            }
            if (item.source == TrackSource.JAPANESE_ASMR) {
                val postId = item.workKey ?: return@launch
                val key = item.fileKey ?: return@launch
                val post = graph.db.japaneseAsmr().post(postId) ?: return@launch
                graph.player.replaceAndPlayRemote(listOf(com.nanahoshi.audioplayer.dlsite.RemotePlayable(
                    item.name, item.workTitle, post.coverUri, post.url, item.source,
                    com.nanahoshi.audioplayer.japaneseasmr.JapaneseAsmrClient.REFERER,
                    workno = postId, fileKey = key, mediaKind = graph.db.japaneseAsmr().media(postId, key)?.kind,
                )))
                return@launch
            }
            val app = getApplication<Application>()
            val file = File(item.path).canonicalFile
            val youtube = file.startsWith(File(app.filesDir, "youtube").canonicalFile)
            val bili = file.startsWith(File(app.filesDir, "bilibili").canonicalFile)
            val id = item.workCode.orEmpty()
            graph.player.replaceAndPlayRemote(listOf(com.nanahoshi.audioplayer.dlsite.RemotePlayable(
                title = item.name, artist = item.workTitle, coverUri = null,
                remoteUrl = if (youtube) "https://www.youtube.com/watch?v=$id" else item.path,
                source = when { youtube -> com.nanahoshi.audioplayer.data.TrackSource.YOUTUBE; bili -> com.nanahoshi.audioplayer.data.TrackSource.BILIBILI; else -> com.nanahoshi.audioplayer.data.TrackSource.LOCAL },
                referer = "", workno = id.takeIf { youtube || bili },
                fileKey = if (youtube) "audio" else if (bili) file.nameWithoutExtension else null,
            )))
        }
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
    var selected by remember { mutableStateOf(setOf<String>()) }
    val categoryItems = remember(viewModel.items, showAudio) {
        viewModel.items.filter { (it.kind == StoredKind.AUDIO) == showAudio }
    }
    val visible = remember(categoryItems, query) {
        categoryItems.filter { storedItemMatches(it, query) }
    }
    val chosen = categoryItems.filter { it.path in selected }
    val single = chosen.singleOrNull()
    BackHandler(enabled = selecting) {
        selecting = false
        selected = emptySet()
    }
    val pendingDelete = viewModel.pendingDelete
    if (pendingDelete != null) {
        val onDone = {
            selecting = false
            selected = emptySet()
        }
        DeleteAudioDialog(
            itemCount = pendingDelete.items.size,
            subtitleCount = pendingDelete.subtitles,
            onConfirm = { deleteSubtitles -> viewModel.confirmDelete(deleteSubtitles, onDone) },
            onCancel = viewModel::cancelDelete,
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
            AppTopBar(title = stringResource(R.string.stored_title), onBack = onBack)
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            SegmentedButton(
                selected = showAudio,
                onClick = { showAudio = true },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                enabled = !selecting,
            ) { Text(stringResource(R.string.stored_segment_audio)) }
            SegmentedButton(
                selected = !showAudio,
                onClick = { showAudio = false },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                enabled = !selecting,
            ) { Text(stringResource(R.string.stored_segment_subtitle)) }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.stored_search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        when {
            viewModel.loading && visible.isEmpty() -> LoadingState(stringResource(R.string.stored_loading), Modifier.fillMaxSize())
            viewModel.error != null && visible.isEmpty() -> ErrorState(
                message = viewModel.error!!,
                onRetry = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
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
                modifier = Modifier.fillMaxSize(),
            )
            visible.isEmpty() -> EmptyState(
                title = stringResource(R.string.stored_search_empty_title),
                message = stringResource(R.string.stored_search_empty_message),
                actionLabel = stringResource(R.string.stored_clear_search),
                onAction = { query = "" },
                modifier = Modifier.fillMaxSize(),
            )
            else -> LazyColumn(
                state = if (showAudio) audioListState else subtitleListState,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (viewModel.loading) {
                    item(key = "loading") { LoadingState(stringResource(R.string.stored_loading)) }
                } else if (viewModel.error != null) {
                    item(key = "error") { ErrorState(viewModel.error!!, viewModel::refresh) }
                }
                if (!selecting) {
                    item(key = "selection-hint") {
                        Text(
                            stringResource(if (showAudio) R.string.stored_audio_tap_hint else R.string.stored_long_press_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                items(visible, key = { it.path }) { item ->
                    val on = item.path in selected
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
                            Text(bits.joinToString(" · "))
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
                                    viewModel.play(item)
                                    return@combinedClickable
                                }
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
