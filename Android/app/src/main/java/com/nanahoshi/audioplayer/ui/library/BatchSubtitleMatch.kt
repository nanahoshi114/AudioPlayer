package com.nanahoshi.audioplayer.ui.library

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.data.BatchExternalPair
import com.nanahoshi.audioplayer.data.BindExternalResult
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.data.isSubtitleFile
import com.nanahoshi.audioplayer.playback.BatchPairMode
import com.nanahoshi.audioplayer.playback.SubtitleCandidateEntry
import com.nanahoshi.audioplayer.playback.WorkSubtitleMatcher
import com.nanahoshi.audioplayer.ui.components.LoadingState
import com.nanahoshi.audioplayer.ui.components.SectionHeader
import com.nanahoshi.audioplayer.ui.components.SourceBadge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class BatchAudioFolderChoice(
    val folderId: Long,
    val relativePath: String,
    val tracks: List<TrackEntity>,
)

internal data class BatchSubtitleFolderKey(val folderId: Long, val source: TrackSource?)

internal data class BatchSubtitleFolderChoice(
    val key: BatchSubtitleFolderKey,
    val relativePath: String,
    val candidates: List<SubtitleCandidateEntry>,
)

internal data class BatchSubtitleSetup(
    val audioFolders: List<BatchAudioFolderChoice>,
    val subtitleFolders: List<BatchSubtitleFolderChoice>,
    val preferredAudioFolderId: Long?,
    val preferredSubtitleFolder: BatchSubtitleFolderKey?,
)

private data class ExternalSubtitleFolder(
    val treeUri: Uri,
    val name: String,
    val candidates: List<SubtitleCandidateEntry>,
)

private sealed interface BatchSubtitleSelection {
    data class WorkFolder(val key: BatchSubtitleFolderKey) : BatchSubtitleSelection
    data class External(val folder: ExternalSubtitleFolder) : BatchSubtitleSelection
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun BatchSubtitleMatchSheet(
    currentFolderId: Long?,
    viewModel: LibraryViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var setup by remember { mutableStateOf<BatchSubtitleSetup?>(null) }
    var selectedAudioId by remember { mutableStateOf<Long?>(null) }
    var selectedSubSource by remember { mutableStateOf<BatchSubtitleSelection?>(null) }
    var externalFolder by remember { mutableStateOf<ExternalSubtitleFolder?>(null) }
    var stepPreview by remember { mutableStateOf(false) }
    var pairMode by remember { mutableStateOf(BatchPairMode.BY_NAME) }
    val assignedByTrack = remember { mutableStateMapOf<Long, SubtitleCandidateEntry?>() }
    var busy by remember { mutableStateOf(false) }
    var pendingExternalCopies by remember { mutableStateOf<List<BatchExternalPair>?>(null) }
    var pickerTrack by remember { mutableStateOf<TrackEntity?>(null) }
    var candidateQuery by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { !busy },
    )

    // 换音频目录或字幕来源后，旧的配对不再对应当前来源，退回第一步重新预览。
    LaunchedEffect(selectedAudioId, selectedSubSource) {
        stepPreview = false
        assignedByTrack.clear()
        pickerTrack = null
    }

    LaunchedEffect(currentFolderId) {
        try {
            val loaded = viewModel.loadBatchSubtitleSetup(currentFolderId)
            setup = loaded
            selectedAudioId = loaded.preferredAudioFolderId
            selectedSubSource = loaded.preferredSubtitleFolder?.let(BatchSubtitleSelection::WorkFolder)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            setup = BatchSubtitleSetup(emptyList(), emptyList(), null, null)
            viewModel.showMessage(R.string.library_operation_failed)
        }
    }

    val currentSetup = setup
    val audioFolder = currentSetup?.audioFolders?.firstOrNull { it.folderId == selectedAudioId }
    val activeSubCandidates: List<SubtitleCandidateEntry> = when (val src = selectedSubSource) {
        is BatchSubtitleSelection.WorkFolder ->
            currentSetup?.subtitleFolders?.firstOrNull { it.key == src.key }?.candidates.orEmpty()
        is BatchSubtitleSelection.External -> src.folder.candidates
        null -> emptyList()
    }

    fun applySuggestedPairs(mode: BatchPairMode, onApplied: (() -> Unit)? = null) {
        if (busy) return
        val tracks = audioFolder?.tracks.orEmpty()
        val candidates = activeSubCandidates
        busy = true
        scope.launch {
            try {
                // 大量目录的规范化与排序放到默认调度器，避免卡住主线程。
                val suggestions = withContext(Dispatchers.Default) {
                    WorkSubtitleMatcher.suggestBatchPairs(tracks, candidates, mode)
                }
                val byTrack = suggestions.associate { (track, candidate) -> track.id to candidate }
                assignedByTrack.clear()
                tracks.forEach { track -> assignedByTrack[track.id] = byTrack[track.id] }
                onApplied?.invoke()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                viewModel.showMessage(R.string.library_operation_failed)
            } finally {
                busy = false
            }
        }
    }

    val openTree = rememberLauncherForActivityResult(OpenPersistableTree()) { uri ->
        if (uri == null || busy) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            try {
                val (folderName, found) = withContext(Dispatchers.IO) { scanExternalSubtitleFolder(context, uri) }
                if (found.isEmpty()) {
                    viewModel.showMessage(R.string.subtitle_batch_external_folder_empty)
                } else {
                    val ext = ExternalSubtitleFolder(uri, folderName.ifBlank { uri.lastPathSegment.orEmpty() }, found)
                    externalFolder = ext
                    selectedSubSource = BatchSubtitleSelection.External(ext)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                viewModel.showMessage(R.string.library_operation_failed)
            } finally {
                busy = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.subtitle_batch_title),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(
                            if (stepPreview) R.string.subtitle_batch_step_preview
                            else R.string.subtitle_batch_step_sources,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    enabled = !busy,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.a11y_close))
                }
            }
            HorizontalDivider()

            when {
                currentSetup == null -> LoadingState(
                    stringResource(R.string.library_file_loading),
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 36.dp),
                )
                !stepPreview -> {
                    val distinctSubSources = remember(currentSetup.subtitleFolders) {
                        currentSetup.subtitleFolders.map { it.key.source }.distinct()
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .heightIn(max = 460.dp),
                        contentPadding = PaddingValues(bottom = 12.dp),
                    ) {
                        item(key = "audio-header") {
                            SectionHeader(
                                title = stringResource(R.string.subtitle_batch_audio_folder),
                                containerColor = Color.Transparent,
                            )
                        }
                        if (currentSetup.audioFolders.isEmpty()) {
                            item(key = "audio-empty") {
                                Text(
                                    stringResource(R.string.subtitle_batch_no_audio_folders),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                )
                            }
                        } else {
                            items(currentSetup.audioFolders, key = { "audio-${it.folderId}" }) { folder ->
                                val chosen = folder.folderId == selectedAudioId
                                ListItem(
                                    headlineContent = {
                                        Text(
                                            folder.relativePath,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    supportingContent = {
                                        Text(stringResource(R.string.subtitle_batch_folder_track_count, folder.tracks.size))
                                    },
                                    leadingContent = {
                                        RadioButton(selected = chosen, onClick = null)
                                    },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .defaultMinSize(minHeight = 48.dp)
                                        .selectable(
                                            selected = chosen,
                                            enabled = !busy,
                                            role = Role.RadioButton,
                                            onClick = { selectedAudioId = folder.folderId },
                                        ),
                                )
                            }
                        }

                        item(key = "sub-header") {
                            SectionHeader(
                                title = stringResource(R.string.subtitle_batch_subtitle_source),
                                containerColor = Color.Transparent,
                            )
                        }
                        if (currentSetup.subtitleFolders.isEmpty()) {
                            item(key = "sub-empty") {
                                Text(
                                    stringResource(R.string.subtitle_batch_no_work_subtitles),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                )
                            }
                        } else {
                            items(currentSetup.subtitleFolders, key = { "sub-${it.key.folderId}-${it.key.source}" }) { folder ->
                                val chosen = (selectedSubSource as? BatchSubtitleSelection.WorkFolder)?.key == folder.key
                                ListItem(
                                    headlineContent = {
                                        Text(
                                            folder.relativePath,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    supportingContent = {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Text(stringResource(R.string.subtitle_batch_folder_subtitle_count, folder.candidates.size))
                                            if (distinctSubSources.size > 1) {
                                                folder.key.source?.let { src -> SourceBadge(src) }
                                            }
                                        }
                                    },
                                    leadingContent = {
                                        RadioButton(selected = chosen, onClick = null)
                                    },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .defaultMinSize(minHeight = 48.dp)
                                        .selectable(
                                            selected = chosen,
                                            enabled = !busy,
                                            role = Role.RadioButton,
                                            onClick = { selectedSubSource = BatchSubtitleSelection.WorkFolder(folder.key) },
                                        ),
                                )
                            }
                        }

                        externalFolder?.let { ext ->
                            item(key = "ext-folder") {
                                val chosen = selectedSubSource is BatchSubtitleSelection.External
                                ListItem(
                                    headlineContent = {
                                        Text(
                                            stringResource(
                                                R.string.subtitle_batch_external_folder_selected,
                                                ext.name,
                                                ext.candidates.size,
                                            ),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    leadingContent = {
                                        RadioButton(selected = chosen, onClick = null)
                                    },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .defaultMinSize(minHeight = 48.dp)
                                        .selectable(
                                            selected = chosen,
                                            enabled = !busy,
                                            role = Role.RadioButton,
                                            onClick = { selectedSubSource = BatchSubtitleSelection.External(ext) },
                                        ),
                                )
                            }
                        }

                        item(key = "pick-ext") {
                            OutlinedButton(
                                onClick = { openTree.launch(null) },
                                enabled = !busy,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                                    .defaultMinSize(minHeight = 48.dp),
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null)
                                Spacer(Modifier.size(8.dp))
                                Text(
                                    stringResource(R.string.subtitle_batch_pick_external_folder),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    HorizontalDivider()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Button(
                            onClick = {
                                applySuggestedPairs(pairMode) { stepPreview = true }
                            },
                            enabled = !busy && audioFolder != null && activeSubCandidates.isNotEmpty(),
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                        ) {
                            Text(stringResource(R.string.subtitle_batch_next_preview))
                        }
                    }
                }
                else -> {
                    val tracks = audioFolder?.tracks.orEmpty()
                    val matchedCount = tracks.count { assignedByTrack[it.id] != null }
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = pairMode == BatchPairMode.BY_NAME,
                                enabled = !busy,
                                onClick = {
                                    pairMode = BatchPairMode.BY_NAME
                                    applySuggestedPairs(BatchPairMode.BY_NAME)
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            ) {
                                Text(
                                    stringResource(R.string.subtitle_batch_mode_by_name),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            SegmentedButton(
                                selected = pairMode == BatchPairMode.BY_ORDER,
                                enabled = !busy,
                                onClick = {
                                    pairMode = BatchPairMode.BY_ORDER
                                    applySuggestedPairs(BatchPairMode.BY_ORDER)
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            ) {
                                Text(
                                    stringResource(R.string.subtitle_batch_mode_by_order),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.subtitle_batch_match_summary, matchedCount, tracks.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .heightIn(max = 400.dp),
                        contentPadding = PaddingValues(bottom = 8.dp),
                    ) {
                        items(tracks, key = { it.id }) { track ->
                            val picked = assignedByTrack[track.id]
                            val rowDesc = stringResource(R.string.a11y_batch_subtitle_pick_row, track.title)
                            ListItem(
                                headlineContent = {
                                    Text(
                                        track.title,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                supportingContent = {
                                    val supporting = picked?.let {
                                        if (it.relativePath != it.name) "${it.name} · ${it.relativePath}" else it.name
                                    }
                                    Text(
                                        supporting ?: stringResource(R.string.subtitle_batch_skip),
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (picked == null) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                    )
                                },
                                trailingContent = {
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 48.dp)
                                    .clickable(enabled = !busy, onClickLabel = rowDesc) {
                                        candidateQuery = ""
                                        pickerTrack = track
                                    },
                            )
                        }
                    }

                    HorizontalDivider()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { stepPreview = false },
                            enabled = !busy,
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                        ) {
                            Text(stringResource(R.string.subtitle_batch_prev_step))
                        }
                        Button(
                            enabled = !busy && matchedCount > 0,
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                            onClick = {
                                val pairs = tracks.mapNotNull { track ->
                                    assignedByTrack[track.id]?.let { track to it }
                                }
                                if (pairs.isEmpty()) return@Button
                                busy = true
                                scope.launch {
                                    try {
                                        when (val src = selectedSubSource) {
                                            is BatchSubtitleSelection.WorkFolder -> {
                                                val filePairs = pairs.mapNotNull { (track, candidate) ->
                                                    candidate.fileId?.let { track.id to it }
                                                }
                                                viewModel.commitBatchLibrarySubtitles(filePairs)
                                                onDismiss()
                                            }
                                            is BatchSubtitleSelection.External -> {
                                                val extPairs = pairs.mapNotNull { (track, candidate) ->
                                                    candidate.uri?.let {
                                                        BatchExternalPair(track.id, Uri.parse(it), candidate.name)
                                                    }
                                                }
                                                when (viewModel.commitBatchExternalSubtitles(extPairs, src.folder.treeUri)) {
                                                    BindExternalResult.Bound -> onDismiss()
                                                    BindExternalResult.NeedsAppCopy -> {
                                                        pendingExternalCopies = extPairs
                                                    }
                                                }
                                            }
                                            null -> Unit
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        viewModel.showMessage(R.string.library_operation_failed)
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                        ) {
                            Text(stringResource(R.string.subtitle_batch_confirm, matchedCount))
                        }
                    }
                }
            }
        }
    }

    pickerTrack?.let { track ->
        val filtered = remember(activeSubCandidates, candidateQuery) {
            val query = candidateQuery.trim()
            if (query.isEmpty()) {
                activeSubCandidates
            } else {
                activeSubCandidates.filter {
                    it.name.contains(query, ignoreCase = true) ||
                        it.relativePath.contains(query, ignoreCase = true)
                }
            }
        }
        AlertDialog(
            onDismissRequest = { pickerTrack = null },
            title = {
                Text(
                    stringResource(R.string.subtitle_batch_pick_candidate_title, track.title),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    if (activeSubCandidates.size > 12) {
                        OutlinedTextField(
                            value = candidateQuery,
                            onValueChange = { candidateQuery = it },
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.subtitle_batch_candidate_search)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        item(key = "skip") {
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.subtitle_batch_skip)) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 48.dp)
                                    .clickable {
                                        assignedByTrack[track.id] = null
                                        pickerTrack = null
                                    },
                            )
                        }
                        items(filtered, key = { "${it.uri}|${it.fileId}|${it.name}|${it.relativePath}" }) { candidate ->
                            val selected = assignedByTrack[track.id] == candidate
                            ListItem(
                                headlineContent = {
                                    Text(
                                        candidate.name,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                supportingContent = if (candidate.relativePath != candidate.name) {
                                    {
                                        Text(
                                            candidate.relativePath,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                } else {
                                    null
                                },
                                colors = ListItemDefaults.colors(
                                    containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 48.dp)
                                    .clickable {
                                        assignedByTrack[track.id] = candidate
                                        pickerTrack = null
                                    },
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pickerTrack = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    pendingExternalCopies?.let { pairs ->
        AlertDialog(
            onDismissRequest = { if (!busy) pendingExternalCopies = null },
            title = { Text(stringResource(R.string.subtitle_batch_needs_copy_title)) },
            text = { Text(stringResource(R.string.subtitle_batch_needs_copy_message, pairs.size)) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        pendingExternalCopies = null
                        busy = true
                        scope.launch {
                            try {
                                // 全部失败时保留配对结果，让用户可以重试。
                                if (viewModel.commitBatchExternalCopies(pairs) > 0) onDismiss()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                viewModel.showMessage(R.string.library_operation_failed)
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text(stringResource(R.string.subtitle_batch_import_and_bind)) }
            },
            dismissButton = {
                TextButton(
                    enabled = !busy,
                    onClick = { pendingExternalCopies = null },
                ) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

private suspend fun scanExternalSubtitleFolder(context: Context, treeUri: Uri): Pair<String, List<SubtitleCandidateEntry>> {
    val root = DocumentFile.fromTreeUri(context, treeUri) ?: return "" to emptyList()
    val folderName = root.name.orEmpty()
    val result = mutableListOf<SubtitleCandidateEntry>()
    val cancellation = currentCoroutineContext()
    suspend fun walk(dir: DocumentFile, prefix: String) {
        for (child in dir.listFiles()) {
            cancellation.ensureActive()
            val name = child.name ?: continue
            if (child.isDirectory) {
                walk(child, "$prefix$name/")
            } else if (child.isFile && isSubtitleFile(name)) {
                result += SubtitleCandidateEntry(
                    name = name,
                    uri = child.uri.toString(),
                    relativePath = "$prefix$name",
                )
            }
        }
    }
    walk(root, "")
    return folderName to result.sortedBy { it.relativePath.lowercase() }
}
