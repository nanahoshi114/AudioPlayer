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

internal val LocalRemovalPlan.hasTargets: Boolean
    get() = audioDownloads.isNotEmpty() ||
        audioAssociations.isNotEmpty() ||
        subtitleFiles.isNotEmpty() ||
        otherFiles.isNotEmpty()

@get:StringRes
internal val LocalRemovalPlan.labelRes: Int
    get() {
        val hasDeletes = audioDownloads.isNotEmpty() || subtitleFiles.isNotEmpty() || otherFiles.isNotEmpty()
        val hasAssociations = audioAssociations.isNotEmpty()
        return when {
            hasDeletes && hasAssociations -> R.string.library_action_delete_and_unlink
            hasDeletes -> R.string.library_action_delete_downloads
            else -> R.string.library_action_unlink
        }
    }

internal fun deleteDownloadedNotice(result: DeleteResult): TextRef {
    val parts = buildList {
        if (result.deleted > 0) add(TextRef.Res(R.string.library_deleted_downloads, listOf(result.deleted)))
        if (result.deletedFiles > 0) add(TextRef.Res(R.string.library_deleted_files, listOf(result.deletedFiles)))
        if (result.unlinked > 0) add(TextRef.Res(R.string.library_unlinked_tracks, listOf(result.unlinked)))
        if (result.changed > 0) add(TextRef.Res(R.string.library_skipped_changed, listOf(result.changed)))
    }
    return if (parts.isEmpty()) TextRef.Res(R.string.library_nothing_to_delete)
    else TextRef.Joined(parts, separator = "。")
}
