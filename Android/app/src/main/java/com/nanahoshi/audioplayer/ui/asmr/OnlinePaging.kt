package com.nanahoshi.audioplayer.ui.asmr

import com.nanahoshi.audioplayer.text.resolveUserError
import com.nanahoshi.audioplayer.text.UserFacingException
import com.nanahoshi.audioplayer.asmr.AsmrException
import com.nanahoshi.audioplayer.asmr.AsmrErrorKind
import com.nanahoshi.audioplayer.R
import androidx.annotation.StringRes
import com.nanahoshi.audioplayer.text.TextRef
import androidx.compose.ui.res.stringResource
import com.nanahoshi.audioplayer.japaneseasmr.JaArticle
import com.nanahoshi.audioplayer.japaneseasmr.JaMediaRef
import com.nanahoshi.audioplayer.japaneseasmr.JaPostRef
import com.nanahoshi.audioplayer.japaneseasmr.JapaneseAsmrClient
import com.nanahoshi.audioplayer.japaneseasmr.JapaneseAsmrException
import com.nanahoshi.audioplayer.ui.components.JapaneseAsmrVerificationState
import com.nanahoshi.audioplayer.ui.components.openJapaneseAsmrInBrowser
import com.nanahoshi.audioplayer.data.storageKey
import com.nanahoshi.audioplayer.data.sanitizeEntryName
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.activity.compose.BackHandler
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nanahoshi.audioplayer.bilibili.BilibiliException
import com.nanahoshi.audioplayer.bilibili.QrPoll
import com.nanahoshi.audioplayer.bilibili.qrBitmap
import com.nanahoshi.audioplayer.data.toHttps
import com.nanahoshi.audioplayer.dlsite.DlsiteClient
import com.nanahoshi.audioplayer.asmr.AsmrCatalogItem
import com.nanahoshi.audioplayer.asmr.AsmrNode
import com.nanahoshi.audioplayer.asmr.AsmrWork
import com.nanahoshi.audioplayer.asmr.CatalogLookup
import com.nanahoshi.audioplayer.asmr.WorkFacts
import com.nanahoshi.audioplayer.data.LibrarySort
import com.nanahoshi.audioplayer.data.LocalAudio
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.FileKind
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.LibraryFileEntity
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.data.previewKind
import com.nanahoshi.audioplayer.data.rawFileKey
import com.nanahoshi.audioplayer.data.isAsmrAudio
import com.nanahoshi.audioplayer.data.isContainer
import com.nanahoshi.audioplayer.data.isSubtitleFile
import com.nanahoshi.audioplayer.data.sortedForLibrary
import com.nanahoshi.audioplayer.graph
import com.nanahoshi.audioplayer.playback.pickSubtitleName
import com.nanahoshi.audioplayer.ui.CollapsingTop
import com.nanahoshi.audioplayer.ui.canCollapseHeader
import com.nanahoshi.audioplayer.ui.components.AppTopBar
import com.nanahoshi.audioplayer.ui.components.AccountCard
import com.nanahoshi.audioplayer.ui.components.BadgeItem
import com.nanahoshi.audioplayer.ui.components.ConfirmActionDialog
import com.nanahoshi.audioplayer.ui.components.CoverImage
import com.nanahoshi.audioplayer.ui.components.DropdownFilterButton
import com.nanahoshi.audioplayer.ui.components.ErrorState
import com.nanahoshi.audioplayer.ui.components.EmptyState
import com.nanahoshi.audioplayer.ui.components.ImmersiveTopBar
import com.nanahoshi.audioplayer.ui.components.ImmersiveWorkScaffold
import com.nanahoshi.audioplayer.ui.components.InfoNoticeDialog
import com.nanahoshi.audioplayer.ui.components.LoadMoreOnScrollEnd
import com.nanahoshi.audioplayer.ui.components.LoadingState
import com.nanahoshi.audioplayer.ui.components.SecretTextField
import com.nanahoshi.audioplayer.ui.components.SectionHeader
import com.nanahoshi.audioplayer.ui.components.SegmentedTabBar
import com.nanahoshi.audioplayer.ui.components.WorkHeader
import com.nanahoshi.audioplayer.ui.components.WorkCard
import com.nanahoshi.audioplayer.ui.components.SkeletonWorkList
import com.nanahoshi.audioplayer.ui.components.toWorkFacts
import com.nanahoshi.audioplayer.ui.theme.LocalReduceMotion
import com.nanahoshi.audioplayer.ui.theme.Motion
import com.nanahoshi.audioplayer.ui.theme.enterSpec
import com.nanahoshi.audioplayer.ui.theme.exitSpec
import com.nanahoshi.audioplayer.ui.library.LibraryEntry
import com.nanahoshi.audioplayer.ui.library.LibraryCrumb
import com.nanahoshi.audioplayer.ui.library.LoadedWorkFolder
import com.nanahoshi.audioplayer.ui.library.TrackMetaRow
import com.nanahoshi.audioplayer.ui.library.WorkFolderRequest
import com.nanahoshi.audioplayer.ui.library.observeFolderListing
import com.nanahoshi.audioplayer.ui.library.observeWorkPath
import com.nanahoshi.audioplayer.ui.library.observeWorkSubtitleTrackIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

enum class OnlineSort(@param:StringRes val labelRes: Int, val order: String, val direction: String) {
    RELEASE_DESC(R.string.online_sort_release_desc, "release", "desc"),
    NEWEST(R.string.online_sort_newest, "create_date", "desc"),
    RELEASE_ASC(R.string.online_sort_release_asc, "release", "asc"),
    SALES(R.string.online_sort_sales, "dl_count", "desc"),
    PRICE_ASC(R.string.online_sort_price_asc, "price", "asc"),
    PRICE_DESC(R.string.online_sort_price_desc, "price", "desc"),
    RATING(R.string.online_sort_rating, "rate_average_2dp", "desc"),
    REVIEWS(R.string.online_sort_reviews, "review_count", "desc"),
    RJ_DESC(R.string.online_sort_rj_desc, "id", "desc"),
    RJ_ASC(R.string.online_sort_rj_asc, "id", "asc"),
    RANDOM(R.string.online_sort_random, "random", "desc"),
}

internal fun dlsiteOrder(sort: OnlineSort): String = when (sort) {
    OnlineSort.SALES -> "dl_d"
    OnlineSort.RELEASE_DESC -> "release_d"
    OnlineSort.RELEASE_ASC -> "release"
    OnlineSort.PRICE_ASC -> "price"
    OnlineSort.PRICE_DESC -> "price_d"
    OnlineSort.RATING -> "rate_d"
    OnlineSort.REVIEWS -> "review_d"
    else -> "trend"
}

internal suspend fun <T> catchPage(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Result.failure(failure)
}

/** 异常消息已经带来源名时不再重复加前缀，避免「asmr.one：暂时无法连接 asmr.one」。 */
internal fun sourceError(context: android.content.Context, source: String, failure: Throwable): String {
    val message = context.resolveUserError(failure, R.string.online_connection_failed)
    return if (message.contains(source, ignoreCase = true)) message
    else context.getString(R.string.online_source_error, source, message)
}

enum class PieceKind(@param:StringRes val labelRes: Int, val key: String) {
    TAG(R.string.online_piece_tag, "tag"),
    VA(R.string.online_piece_va, "va"),
    CIRCLE(R.string.online_piece_circle, "circle"),
}

data class SearchPiece(val kind: PieceKind, val name: String, val genreId: String? = null) {
    fun token(): String = "$${kind.key}:$name$"
    fun caption(context: android.content.Context): String = "${context.getString(kind.labelRes)}:$name"
}

data class OnlineHit(
    val sourceId: String,
    val title: String,
    val circle: String,
    val vas: List<String>,
    val tags: List<String>,
    val release: String,
    val coverUrl: String?,
    val fromAsmr: Boolean = true,
    val asmrMissing: Boolean = false,
) {
    fun facts() = WorkFacts(title, circle, vas, tags, release, sourceId)
}

internal fun dedupeKey(sourceId: String): String {
    val canonical = DlsiteClient.canonicalWorkno(sourceId)
    return if (canonical.isNotEmpty() && canonical.all { it.isDigit() }) "RJ$canonical" else canonical
}

/** 每个来源独立记页：只在本来源成功时前进；失败保留页码和 hasMore，重试仍读失败的那一页。 */
internal class SourcePaging {
    var lastPage = 0
        private set
    var hasMore = true
        private set

    fun next(): Int = lastPage + 1

    fun succeed(page: Int, hasMore: Boolean) {
        lastPage = page
        this.hasMore = hasMore
    }

    fun reset() {
        lastPage = 0
        hasMore = true
    }
}

/** 每页 20 条；读满一页且后面还有内容才继续。 */
internal fun pageHasMore(fetched: Int, page: Int, total: Int): Boolean =
    fetched >= 20 && page * 20 < total

enum class WorkFileSource {
    PLAY,
    ASMR,
    JAPANESE_ASMR,
}

internal fun WorkFileSource.trackSource(): TrackSource = when (this) {
    WorkFileSource.PLAY -> TrackSource.DLSITE
    WorkFileSource.ASMR -> TrackSource.ASMR
    WorkFileSource.JAPANESE_ASMR -> TrackSource.JAPANESE_ASMR
}

internal fun TrackSource.workLabel() = when (this) {
    TrackSource.DLSITE -> "DLsite Play"
    TrackSource.ASMR -> "asmr.one"
    TrackSource.JAPANESE_ASMR -> "Japanese ASMR"
    else -> name
}

internal fun Context.onMeteredNetwork(): Boolean {
    val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = manager.activeNetwork ?: return true
    val caps = manager.getNetworkCapabilities(network) ?: return true
    return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}

internal fun AsmrCatalogItem.isStub(): Boolean {
    val id = com.nanahoshi.audioplayer.dlsite.DlsiteClient.canonicalWorkno(sourceId)
    val titleIsId = title.isBlank() || com.nanahoshi.audioplayer.dlsite.DlsiteClient.canonicalWorkno(title) == id
    return titleIsId && circle.isBlank() && vas.isEmpty() && tags.isEmpty() && release.isBlank()
}

internal fun com.nanahoshi.audioplayer.dlsite.DlsiteHit.toHit() = OnlineHit(
    sourceId = workno,
    title = title,
    circle = circle,
    vas = vas,
    tags = tags,
    release = release,
    coverUrl = coverUrl,
    fromAsmr = false,
)

internal fun AsmrCatalogItem.toHit() = OnlineHit(
    sourceId = sourceId,
    title = title,
    circle = circle,
    vas = vas,
    tags = tags,
    release = release,
    coverUrl = coverUrl,
)
