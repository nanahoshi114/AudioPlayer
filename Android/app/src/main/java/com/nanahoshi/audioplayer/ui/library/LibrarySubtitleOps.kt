package com.nanahoshi.audioplayer.ui.library

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.lazy.LazyListState
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nanahoshi.audioplayer.AppGraph
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.asmr.AsmrErrorKind
import com.nanahoshi.audioplayer.asmr.AsmrException
import com.nanahoshi.audioplayer.asmr.AsmrPreview
import com.nanahoshi.audioplayer.asmr.WorkFacts
import com.nanahoshi.audioplayer.bilibili.BilibiliException
import com.nanahoshi.audioplayer.data.BatchExternalPair
import com.nanahoshi.audioplayer.data.BindExternalResult
import com.nanahoshi.audioplayer.data.CacheCandidate
import com.nanahoshi.audioplayer.data.DeleteResult
import com.nanahoshi.audioplayer.data.DlsiteDownloadMode
import com.nanahoshi.audioplayer.data.FormatConflict
import com.nanahoshi.audioplayer.data.LibrarySort
import com.nanahoshi.audioplayer.data.LocalAudio
import com.nanahoshi.audioplayer.data.LocalRemoval
import com.nanahoshi.audioplayer.data.LocalRemovalPlan
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.WorkFill
import com.nanahoshi.audioplayer.data.canonicalPath
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.LibraryFileEntity
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.data.db.TrackSubtitleBindingEntity
import com.nanahoshi.audioplayer.data.findFormatConflicts
import com.nanahoshi.audioplayer.data.idsToDrop
import com.nanahoshi.audioplayer.data.isSubtitleFile
import com.nanahoshi.audioplayer.data.loadWorkSubtitleCatalog
import com.nanahoshi.audioplayer.data.localRemoval
import com.nanahoshi.audioplayer.data.resolveLibraryFileSource
import com.nanahoshi.audioplayer.data.sortedForLibrary
import com.nanahoshi.audioplayer.graph
import com.nanahoshi.audioplayer.playback.SubtitleCandidateEntry
import com.nanahoshi.audioplayer.playback.WorkSubtitleCandidate
import com.nanahoshi.audioplayer.playback.nearestSubtitleWorkRoot
import com.nanahoshi.audioplayer.playback.pickSubtitleName
import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.UserFacingException
import com.nanahoshi.audioplayer.text.resolveNoUrl
import com.nanahoshi.audioplayer.text.resolveUserError
import com.nanahoshi.audioplayer.ui.components.SelectionState
import com.nanahoshi.audioplayer.ui.components.SubtitlePickerActions
import com.nanahoshi.audioplayer.ui.components.subtitlePickerActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 作品字幕绑定与批量匹配所需的查询与提交，由 [LibraryViewModel] 委托调用。 */
internal class LibrarySubtitleOps(
    private val graph: AppGraph,
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val workFileSource: () -> TrackSource?,
    private val workRootId: () -> Long?,
) {
    private fun text(@StringRes id: Int, vararg args: Any): String = appContext.getString(id, *args)

    internal suspend fun listWorkTracksForSubtitle(
        file: LibraryFileEntity,
    ): List<Pair<TrackEntity, TrackSubtitleBindingEntity?>> = withContext(Dispatchers.IO) {
        val catalog = loadWorkSubtitleCatalog(graph.db, file.folderId)
        val source = workFileSource()
        val candidateTracks = if (catalog != null) {
            val filtered = if (source == null) catalog.tracks else catalog.tracks.filter { it.source == source }
            filtered.ifEmpty { catalog.tracks }
        } else {
            val folderTracks = graph.library.tracksIn(file.folderId)
            val filtered = if (source == null) folderTracks else folderTracks.filter { it.source == source }
            filtered.ifEmpty { folderTracks }
        }.sortedForLibrary(LibrarySort.NAME)
        if (candidateTracks.isEmpty()) return@withContext emptyList()
        val bindings = candidateTracks.map { it.id }.chunked(900)
            .flatMap { graph.db.trackSubtitles().getForTracks(it) }
            .associateBy { it.trackId }
        candidateTracks.map { track -> track to bindings[track.id] }
    }

    internal suspend fun bindSubtitleFileToTrack(file: LibraryFileEntity, track: TrackEntity) {
        graph.subtitleBindings.bindLibraryFile(track.id, file.id, file.name)
        graph.show(R.string.subtitle_file_bound_notice, track.title)
    }

    internal suspend fun loadBatchSubtitleSetup(currentFolderId: Long?): BatchSubtitleSetup = withContext(Dispatchers.IO) {
        val targetFolderId = currentFolderId ?: workRootId()
        val catalog = loadWorkSubtitleCatalog(graph.db, targetFolderId)
            ?: return@withContext BatchSubtitleSetup(emptyList(), emptyList(), null, null)
        val root = nearestSubtitleWorkRoot(targetFolderId, catalog.folders)
        val source = workFileSource()
        val rootLabel = text(R.string.library_root_label)
        val folderPath = { folderId: Long ->
            relativeWorkPath(folderId, root?.id, catalog.folders).ifBlank { rootLabel }
        }
        val audioFolders = catalog.tracks
            .filter { source == null || it.source == source }
            .groupBy { it.folderId }
            .entries
            .mapNotNull { (folderId, tracks) ->
                folderId ?: return@mapNotNull null
                BatchAudioFolderChoice(
                    folderId = folderId,
                    relativePath = folderPath(folderId),
                    tracks = tracks.sortedForLibrary(LibrarySort.NAME),
                )
            }
            .sortedBy { it.relativePath.lowercase() }
        val subtitleFolders = catalog.files
            .mapNotNull { file ->
                file.folderId?.let { BatchSubtitleFolderKey(it, resolveLibraryFileSource(file, catalog.jaFileIds)) to file }
            }
            .groupBy({ it.first }, { it.second })
            .map { (key, files) ->
                val entries = files.sortedBy { it.name.lowercase() }
                    .map { SubtitleCandidateEntry(name = it.name, fileId = it.id) }
                BatchSubtitleFolderChoice(
                    key = key,
                    relativePath = folderPath(key.folderId),
                    candidates = entries,
                )
            }
            .sortedWith(compareBy({ it.relativePath.lowercase() }, { it.key.source?.name }))
        val preferredAudio = audioFolders.firstOrNull { it.folderId == targetFolderId }?.folderId
            ?: audioFolders.firstOrNull()?.folderId
        val preferredSub = subtitleFolders.firstOrNull { it.key.folderId == targetFolderId && it.key.source == source }?.key
            ?: subtitleFolders.firstOrNull { it.key.folderId == targetFolderId }?.key
            ?: subtitleFolders.firstOrNull()?.key
        BatchSubtitleSetup(audioFolders, subtitleFolders, preferredAudio, preferredSub)
    }

    private fun relativeWorkPath(
        folderId: Long?,
        rootId: Long?,
        folders: Map<Long, FolderEntity>,
    ): String {
        val parts = ArrayList<String>()
        var current = folderId
        while (current != null && current != rootId) {
            val folder = folders[current] ?: break
            parts += folder.name
            current = folder.parentId
        }
        return parts.asReversed().joinToString("/")
    }

    internal suspend fun commitBatchLibrarySubtitles(pairs: List<Pair<Long, Long>>) {
        if (pairs.isEmpty()) return
        graph.subtitleBindings.batchBindLibraryFiles(pairs)
        graph.show(R.string.subtitle_batch_bound_count, pairs.size)
    }

    internal suspend fun commitBatchExternalSubtitles(
        pairs: List<BatchExternalPair>,
        treeUri: Uri,
    ): BindExternalResult {
        val result = graph.subtitleBindings.batchBindExternalTree(pairs, treeUri)
        if (result == BindExternalResult.Bound) {
            graph.show(R.string.subtitle_batch_bound_count, pairs.size)
        }
        return result
    }

    /** 复制在 ViewModel 作用域中执行并返回成功数，弹层关闭或旋转不会中断批量导入。 */
    internal suspend fun commitBatchExternalCopies(pairs: List<BatchExternalPair>): Int =
        scope.async(Dispatchers.IO) {
            var imported = 0
            var failed = 0
            for (pair in pairs) {
                val ok = try {
                    graph.subtitleBindings.importAndBindCopy(pair.trackId, pair.uri, pair.displayName)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                if (ok) imported++ else failed++
            }
            when {
                imported > 0 && failed > 0 -> graph.show(R.string.subtitle_batch_imported_partial, imported, failed)
                imported > 0 -> graph.show(R.string.subtitle_batch_imported_count, imported)
                else -> graph.show(R.string.subtitle_picker_import_failed)
            }
            imported
        }.await()
}
