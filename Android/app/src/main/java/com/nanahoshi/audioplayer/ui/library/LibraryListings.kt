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

internal data class LibraryRequest(
    val listKey: LibraryListKey,
    val query: String,
    val sort: LibrarySort,
    val workSource: TrackSource?,
)

internal data class LibraryListKey(
    val folderId: Long?,
    val filter: LibrarySourceFilter,
    val isSearching: Boolean,
)

internal data class LoadedLibraryListing(val key: LibraryListKey, val listing: LibraryListing)

internal fun libraryListKey(folderId: Long?, filter: LibrarySourceFilter, query: String): LibraryListKey =
    LibraryListKey(folderId, if (folderId == null) filter else LibrarySourceFilter.ALL, query.isNotBlank())

internal val RJ_QUERY = Regex("\\d{1,7}")

internal fun effectiveLibrarySort(global: LibrarySort, workRootId: Long?): LibrarySort =
    if (workRootId != null) LibrarySort.NAME else global

internal fun searchListing(
    query: String,
    sort: LibrarySort,
    folders: List<FolderEntity>,
    tracks: List<TrackEntity>,
    files: List<LibraryFileEntity>,
    scopeFolderId: Long? = null,
): LibraryListing {
    val byId = folders.associateBy { it.id }
    val scope = if (scopeFolderId == null) null else {
        val ids = mutableSetOf(scopeFolderId)
        var changed: Boolean
        do { changed = false; folders.forEach { if (it.parentId in ids && ids.add(it.id)) changed = true } } while (changed)
        ids
    }
    fun groupRoot(parent: Long?) = workRootOf(parent, byId)?.takeIf {
        scope == null || (it.id in scope && it.id != scopeFolderId)
    }
    val workRoots = LinkedHashMap<Long, FolderEntity>()
    val directFolders = LinkedHashMap<Long, FolderEntity>()

    fun addFolder(folder: FolderEntity) {
        // 作品内部的子文件夹和音频都归到作品根，搜索结果里一个作品只出现一次。
        val root = groupRoot(folder.id)
        if (root != null) {
            workRoots[root.id] = root
        } else {
            directFolders[folder.id] = folder
        }
    }

    val digits = RJ_QUERY.matches(query)
    for (folder in folders) {
        if (scope != null && (folder.id !in scope || folder.id == scopeFolderId)) continue
        val rjMatch = digits && folder.matchesRj(query)
        val nameMatch = folder.name.contains(query, ignoreCase = true) || folder.biliBvid?.contains(query, ignoreCase = true) == true
        val tagMatch = folder.asmrSourceId != null &&
            !rjMatch &&
            !nameMatch &&
            folder.workTags().any { it.contains(query, ignoreCase = true) }
        if (rjMatch || nameMatch || tagMatch) addFolder(folder)
    }

    val directTracks = mutableListOf<TrackEntity>()
    tracks.forEach { track ->
        if (scope != null && track.folderId !in scope) return@forEach
        if (!track.title.contains(query, ignoreCase = true)) return@forEach
        val root = groupRoot(track.folderId)
        if (root != null) workRoots[root.id] = root else directTracks += track
    }

    val directFiles = mutableListOf<LibraryFileEntity>()
    files.forEach { file ->
        if (scope != null && file.folderId !in scope) return@forEach
        if (isSubtitleFile(file.name) || !file.name.contains(query, ignoreCase = true)) return@forEach
        val root = groupRoot(file.folderId)
        if (root != null) workRoots[root.id] = root else directFiles += file
    }

    return LibraryListing(
        folders = (workRoots.values + directFolders.values).sortedForLibrary(sort),
        entries = mixedEntries(directTracks, directFiles, sort, files),
        browsing = false,
    )
}

/** 向上找最近的作品根；普通文件夹返回 null。 */
internal fun workRootOf(folderId: Long?, byId: Map<Long, FolderEntity>): FolderEntity? {
    var current = folderId?.let(byId::get)
    while (current != null) {
        if (!current.asmrSourceId.isNullOrBlank() || current.biliBvid != null) return current
        current = current.parentId?.let(byId::get)
    }
    return null
}

private fun FolderEntity.workTags(): List<String> =
    WorkFacts.fromJson(workMeta)?.tags.orEmpty()

internal fun FolderEntity.isOrdinaryFolder(): Boolean =
    asmrSourceId.isNullOrBlank() && asmrWorkId == null && biliBvid == null

internal fun applySearchSourceFilter(found: LibraryListing, filter: LibrarySourceFilter): LibraryListing = when (filter) {
    LibrarySourceFilter.ALL -> found
    LibrarySourceFilter.ASMR -> found.copy(
        folders = found.folders.filter { !it.asmrSourceId.isNullOrBlank() },
        entries = emptyList(),
    )
    LibrarySourceFilter.BILIBILI -> found.copy(
        folders = found.folders.filter { it.biliBvid != null },
        entries = found.entries.filter { it is LibraryEntry.Audio && it.track.source == TrackSource.BILIBILI },
    )
    LibrarySourceFilter.YOUTUBE -> found.copy(
        folders = emptyList(),
        entries = found.entries.filter { it is LibraryEntry.Audio && it.track.source == TrackSource.YOUTUBE },
    )
    LibrarySourceFilter.LOCAL -> found.copy(
        folders = found.folders.filter { it.isOrdinaryFolder() },
        entries = found.entries.filter { it is LibraryEntry.File || (it is LibraryEntry.Audio && it.track.source == TrackSource.LOCAL) },
    )
}

internal fun filteredListing(
    filter: LibrarySourceFilter,
    sort: LibrarySort,
    folders: List<FolderEntity>,
    tracks: List<TrackEntity>,
): LibraryListing = when (filter) {
    LibrarySourceFilter.BILIBILI -> {
        val byId = folders.associateBy { it.id }
        LibraryListing(
            folders = folders.filter { it.biliBvid != null }.sortedForLibrary(sort),
            entries = mixedEntries(
                tracks.filter {
                    it.source == TrackSource.BILIBILI &&
                        workRootOf(it.folderId, byId)?.biliBvid == null
                },
                emptyList(),
                sort,
            ),
            browsing = false,
        )
    }
    LibrarySourceFilter.YOUTUBE -> LibraryListing(
        folders = emptyList(),
        entries = mixedEntries(tracks.filter { it.source == TrackSource.YOUTUBE }, emptyList(), sort),
        browsing = false,
    )
    LibrarySourceFilter.ASMR -> LibraryListing(
        folders = folders.filter { it.asmrSourceId != null }.sortedForLibrary(sort),
        entries = emptyList(),
        browsing = false,
    )
    LibrarySourceFilter.LOCAL,
    LibrarySourceFilter.ALL,
    -> LibraryListing(emptyList(), emptyList(), browsing = false)
}

internal fun mixedEntries(
    tracks: List<TrackEntity>,
    files: List<LibraryFileEntity>,
    sort: LibrarySort,
    subtitlePool: List<LibraryFileEntity> = files,
    showSubtitleFiles: Boolean = false,
    subtitleTrackIds: Set<Long>? = null,
): List<LibraryEntry> {
    val visibleFiles = if (showSubtitleFiles) files else files.filter { !isSubtitleFile(it.name) }
    val subtitleNamesByFolder = if (subtitleTrackIds == null) {
        subtitlePool.groupBy({ it.folderId }, { it.name })
    } else {
        emptyMap()
    }
    val rows = buildList {
        tracks.forEach { track ->
            val hasSubtitle = subtitleTrackIds?.contains(track.id)
                ?: (pickSubtitleName(track.title, subtitleNamesByFolder[track.folderId].orEmpty()) != null)
            add(LibraryEntry.Audio(track, hasSubtitle))
        }
        visibleFiles.forEach { add(LibraryEntry.File(it)) }
    }
    return when (sort) {
        LibrarySort.NAME -> rows.sortedBy { it.name.lowercase() }
        LibrarySort.ADDED -> rows.sortedByDescending { it.addedAt }
    }
}

internal fun FolderEntity.matchesRj(digits: String): Boolean {
    val sourceId = asmrSourceId ?: return false
    return rjNumber(sourceId) == rjNumber(digits)
}

private fun rjNumber(raw: String): String =
    raw.uppercase().removePrefix("RJ").removePrefix("VJ").filter { it.isDigit() }.trimStart('0').ifEmpty { "0" }
