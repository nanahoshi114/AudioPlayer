package com.nanahoshi.audioplayer.ui.asmr

import com.nanahoshi.audioplayer.text.resolveUserError
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
import com.nanahoshi.audioplayer.ui.CoverImage
import com.nanahoshi.audioplayer.ui.EmptyHint
import com.nanahoshi.audioplayer.ui.components.AppTopBar
import com.nanahoshi.audioplayer.ui.components.AccountCard
import com.nanahoshi.audioplayer.ui.components.BadgeItem
import com.nanahoshi.audioplayer.ui.components.ConfirmActionDialog
import com.nanahoshi.audioplayer.ui.components.ErrorState
import com.nanahoshi.audioplayer.ui.components.EmptyState
import com.nanahoshi.audioplayer.ui.components.LoadingState
import com.nanahoshi.audioplayer.ui.components.SectionHeader
import com.nanahoshi.audioplayer.ui.components.WorkHeader
import com.nanahoshi.audioplayer.ui.components.WorkCard
import com.nanahoshi.audioplayer.ui.components.SkeletonWorkList
import com.nanahoshi.audioplayer.ui.formatDuration
import com.nanahoshi.audioplayer.ui.theme.LocalReduceMotion
import com.nanahoshi.audioplayer.ui.theme.Motion
import com.nanahoshi.audioplayer.ui.library.LibraryEntry
import com.nanahoshi.audioplayer.ui.library.LibraryCrumb
import com.nanahoshi.audioplayer.ui.library.LoadedWorkFolder
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

private fun dlsiteOrder(sort: OnlineSort): String = when (sort) {
    OnlineSort.SALES -> "dl_d"
    OnlineSort.RELEASE_DESC -> "release_d"
    OnlineSort.RELEASE_ASC -> "release"
    OnlineSort.PRICE_ASC -> "price"
    OnlineSort.PRICE_DESC -> "price_d"
    OnlineSort.RATING -> "rate_d"
    OnlineSort.REVIEWS -> "review_d"
    else -> "trend"
}

private suspend fun <T> catchPage(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Result.failure(failure)
}

/** 异常消息已经带来源名时不再重复加前缀，避免「asmr.one：暂时无法连接 asmr.one」。 */
private fun sourceError(context: android.content.Context, source: String, failure: Throwable): String {
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

class OnlineSearchViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
    var draftText by mutableStateOf("")
    var draftPieces by mutableStateOf<List<SearchPiece>>(emptyList())
    var text by mutableStateOf("")
    var pieces by mutableStateOf<List<SearchPiece>>(emptyList())
    var subtitleOnly by mutableStateOf(false)
        private set
    var allAges by mutableStateOf(false)
        private set
    var sort by mutableStateOf<OnlineSort?>(null)
        private set
    var works by mutableStateOf<List<OnlineHit>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var searchRevision by mutableStateOf(0L)
        private set
    private val asmrPaging = SourcePaging()
    private val dlsitePaging = SourcePaging()
    private var job: Job? = null

    init {
        reload()
    }

    fun usePopular(): Boolean = text.isBlank() && pieces.isEmpty() && !allAges && sort == null

    fun sortLabel(): String = getApplication<Application>().getString(
        when {
            usePopular() -> R.string.online_popular
            sort != null -> sort!!.labelRes
            else -> OnlineSort.SALES.labelRes
        },
    )

    fun summary(): String {
        val app = getApplication<Application>()
        val parts = buildList {
            if (text.isNotBlank()) add(text.trim())
            pieces.forEach { add(it.caption(app)) }
        }
        return parts.joinToString(" ")
    }

    fun applyQuery(nextText: String, nextPieces: List<SearchPiece>) {
        text = nextText
        pieces = nextPieces
        draftText = nextText
        draftPieces = nextPieces
        reload()
    }

    fun changeSubtitle(value: Boolean) {
        subtitleOnly = value
        reload()
    }

    fun changeAllAges(value: Boolean) {
        allAges = value
        reload()
    }

    fun chooseSort(value: OnlineSort?) {
        sort = value
        reload()
    }

    fun reload() = load(reset = true)

    fun loadMore() {
        if (loading) return
        val more = if (usePopular()) asmrPaging.hasMore else
            asmrPaging.hasMore || dlsitePaging.hasMore
        // 有错误时即使两来源都读完也允许重试（例如合并阶段的异常），成功后错误自动清除。
        if (!more && error == null) return
        load(reset = false)
    }

    private fun keyword(): String {
        val parts = buildList {
            if (text.isNotBlank()) add(text.trim())
            pieces.forEach { add(it.token()) }
            if (allAges) add("\$age:general\$")
        }
        return parts.joinToString(" ").ifBlank { " " }
    }

    private fun load(reset: Boolean) {
        job?.cancel()
        if (reset) {
            searchRevision++
            works = emptyList()
        }
        val revision = searchRevision
        val next = viewModelScope.launch {
            loading = true
            if (reset) {
                error = null
                asmrPaging.reset()
                dlsitePaging.reset()
            }
            val asmrNext = asmrPaging.next()
            val dlsiteNext = dlsitePaging.next()
            val popular = usePopular()
            try {
                val (asmrResult, dlsiteResult) = coroutineScope {
                    val asmrCall = async {
                        if (!asmrPaging.hasMore && !reset) {
                            null
                        } else {
                            catchPage {
                                if (popular) {
                                    graph.asmr.popular(asmrNext, subtitleOnly)
                                } else {
                                    val chosen = sort ?: OnlineSort.SALES
                                    graph.asmr.search(keyword(), chosen.order, chosen.direction, asmrNext, subtitleOnly)
                                }
                            }
                        }
                    }
                    val dlsiteCall = async {
                        if (popular || (!dlsitePaging.hasMore && !reset)) {
                            null
                        } else {
                            val chosen = sort ?: OnlineSort.SALES
                            catchPage {
                                graph.dlsite.search(
                                    keyword = text.trim(),
                                    creator = creatorKeyword(),
                                    genreIds = pieces.mapNotNull { if (it.kind == PieceKind.TAG) it.genreId else null },
                                    allAges = allAges,
                                    order = dlsiteOrder(chosen),
                                    page = dlsiteNext,
                                )
                            }
                        }
                    }
                    asmrCall.await() to dlsiteCall.await()
                }
                if (revision != searchRevision) return@launch
                val asmrPage = asmrResult?.getOrNull()
                val dlsitePage = dlsiteResult?.getOrNull()
                val merged = linkedMapOf<String, OnlineHit>()
                if (!reset) works.forEach { merged[dedupeKey(it.sourceId)] = it }
                asmrPage?.works?.forEach { item ->
                    if (item.isStub()) return@forEach
                    val key = dedupeKey(item.sourceId)
                    val existing = merged[key]
                    if (existing == null || !existing.fromAsmr) {
                        merged[key] = item.toHit()
                    }
                }
                dlsitePage?.works?.forEach { item ->
                    val key = dedupeKey(item.workno)
                    val existing = merged[key]
                    if (existing == null || !existing.fromAsmr) {
                        merged[key] = item.toHit()
                    }
                }
                val listed = markListed(merged.values.toList())
                if (revision != searchRevision) return@launch
                works = listed
                // 页码只在整页合并提交后前进：抓取或合并任一阶段失败都保留页码和 hasMore，
                // 重试仍读这一页，不会永久跳过。
                if (asmrPage != null) {
                    asmrPaging.succeed(asmrNext, pageHasMore(asmrPage.works.size, asmrPage.page, asmrPage.total))
                }
                if (dlsitePage != null) {
                    dlsitePaging.succeed(dlsiteNext, pageHasMore(dlsitePage.fetched, dlsiteNext, dlsitePage.total))
                }
                error = listOfNotNull(
                    asmrResult?.exceptionOrNull()?.let { sourceError(getApplication(), "asmr.one", it) },
                    dlsiteResult?.exceptionOrNull()?.let { sourceError(getApplication(), "DLsite", it) },
                ).takeIf { it.isNotEmpty() }?.joinToString("\n")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (revision == searchRevision) error = getApplication<Application>().resolveUserError(failure, R.string.online_connection_failed)
            } finally {
                if (revision == searchRevision) loading = false
            }
        }
        job = next
    }

    private suspend fun markListed(hits: List<OnlineHit>): List<OnlineHit> {
        if (hits.all { it.fromAsmr }) return hits
        val checked = coroutineScope {
            hits.map { hit ->
                async {
                    if (hit.fromAsmr) return@async hit
                    when (val lookup = graph.asmr.lookupCatalog(hit.sourceId)) {
                        is CatalogLookup.Found -> if (lookup.item.isStub()) hit else lookup.item.toHit().copy(fromAsmr = hit.fromAsmr)
                        CatalogLookup.Missing -> hit.copy(asmrMissing = true)
                        CatalogLookup.Unknown -> hit
                    }
                }
            }.awaitAll()
        }
        val seen = linkedSetOf<String>()
        return checked.filter { seen.add(dedupeKey(it.sourceId)) }
    }

    private fun creatorKeyword(): String = pieces
        .filter { it.kind == PieceKind.VA || it.kind == PieceKind.CIRCLE }
        .joinToString(" ") { it.name }

}

private fun dedupeKey(sourceId: String): String {
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

@OptIn(ExperimentalCoroutinesApi::class)
class OnlineWorkViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
    var jaPosts by mutableStateOf<List<JaPostRef>>(emptyList())
        private set
    var jaSelected by mutableStateOf<JaPostRef?>(null)
        private set
    var jaArticle by mutableStateOf<JaArticle?>(null)
        private set
    var jaError by mutableStateOf<String?>(null)
        private set
    internal var jaVerificationPage by mutableStateOf<String?>(null)
        private set
    var jaLoading by mutableStateOf(false)
        private set
    var jaChecked by mutableStateOf(false)
        private set
    var jaDiscoveryFailed by mutableStateOf(false)
        private set
    private var jaJob: Job? = null
    private var jaDiscovery: Job? = null
    val availableSources: List<WorkFileSource> get() = buildList {
        if (purchased || hasOriginalFiles) add(WorkFileSource.PLAY)
        if (work != null) add(WorkFileSource.ASMR)
        if (jaPosts.isNotEmpty()) add(WorkFileSource.JAPANESE_ASMR)
    }

    var work by mutableStateOf<AsmrWork?>(null)
        private set
    var facts by mutableStateOf<WorkFacts?>(null)
        private set
    var coverUrl by mutableStateOf<String?>(null)
        private set
    var purchased by mutableStateOf(false)
        private set
    var asmrMissing by mutableStateOf(false)
        private set
    var dlsiteSite by mutableStateOf("maniax")
        private set
    var gallery by mutableStateOf<List<String>>(emptyList())
        private set
    var trials by mutableStateOf<List<com.nanahoshi.audioplayer.dlsite.RemotePlayable>>(emptyList())
        private set
    var allowGallery by mutableStateOf(false)
        private set
    var showPlay by mutableStateOf(false)
        private set
    var fileSource by mutableStateOf(WorkFileSource.ASMR)
        private set
    var downloadedPlay by mutableStateOf<Set<String>>(emptySet())
        private set
    var downloadedAsmr by mutableStateOf<Set<String>>(emptySet())
        private set
    var playFolders by mutableStateOf<List<com.nanahoshi.audioplayer.dlsite.PlayEntry>>(emptyList())
        private set
    var playFiles by mutableStateOf<List<com.nanahoshi.audioplayer.dlsite.PlayEntry>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var working by mutableStateOf(false)
        private set
    var usingLibrary by mutableStateOf(false)
        private set
    private var libraryListing by mutableStateOf<LoadedWorkFolder?>(null)
    val libraryFolders: List<FolderEntity> get() = libraryListing?.listing?.folders.orEmpty()
    val libraryEntries: List<LibraryEntry> get() = libraryListing?.listing?.entries.orEmpty()
    val libraryLoading: Boolean get() = usingLibrary && libraryListing == null
    var nodeFolders by mutableStateOf<List<AsmrNode>>(emptyList())
        private set
    var nodeFiles by mutableStateOf<List<AsmrNode>>(emptyList())
        private set

    internal var browserPath by mutableStateOf<List<LibraryCrumb>>(emptyList())
        private set
    private var browserIdentity by mutableStateOf("")
    private val libraryNames = mutableMapOf<Long, String>()
    private val browserStates = mutableMapOf<String, androidx.compose.foundation.lazy.LazyListState>()

    fun browserListState() = browserStates.getOrPut(browserIdentity) { androidx.compose.foundation.lazy.LazyListState() }

    private fun publishLocation() {
        browserPath = when {
            showPlay -> playStack.map { LibraryCrumb(System.identityHashCode(it).toLong(), it.title) }
            usingLibrary -> libraryStack.drop(1).map { LibraryCrumb(it, libraryNames[it].orEmpty()) }
            fileSource == WorkFileSource.JAPANESE_ASMR -> listOfNotNull(
                jaSelected?.takeIf { jaPosts.size > 1 }?.let { LibraryCrumb(it.postId.toLongOrNull() ?: 0, it.title) })
            else -> nodeStack.map { LibraryCrumb(System.identityHashCode(it).toLong(), it.title) }
        }
        val path = if (usingLibrary) libraryStack.joinToString("/") else browserPath.joinToString("/") { it.id.toString() }
        browserIdentity = "$sourceKey|${fileSource.name}|$path"
    }

    fun openGallery(index: Int): Boolean {
        val url = gallery.getOrNull(index) ?: return false
        graph.remotePreview.value = com.nanahoshi.audioplayer.dlsite.RemotePreview(
            name = facts?.title.orEmpty(),
            url = url,
            kind = FileKind.IMAGE,
            referer = DlsiteClient.STORE_REFERER,
            authed = false,
            galleryUrls = gallery,
            initialIndex = index,
            titlePrefix = facts?.title.orEmpty(),
        )
        return true
    }

    private val routeSourceKey = MutableStateFlow<String?>(null)
    private val sourceKey: String get() = routeSourceKey.value.orEmpty()
    private var libraryLink by mutableStateOf<WorkLibraryLink?>(null)
    private val currentLibraryLink: WorkLibraryLink? get() = libraryLink?.takeIf { it.rawKey == sourceKey }
    private val localRootId: Long? get() = currentLibraryLink?.localRootId
    private val originalRootId: Long? get() = currentLibraryLink?.originalRootId
    val libraryFolderId: Long? get() = currentLibraryLink?.libraryFolderId
    val saved: Boolean get() = currentLibraryLink?.saved == true
    val hasOriginalFiles: Boolean get() = originalRootId != null
    private val libraryStack = mutableListOf<Long>()
    private val nodeStack = mutableListOf<AsmrNode>()
    private val playStack = mutableListOf<com.nanahoshi.audioplayer.dlsite.PlayEntry>()
    private var playRoot = emptyList<com.nanahoshi.audioplayer.dlsite.PlayEntry>()
    private val browseRequest = MutableStateFlow<WorkFolderRequest?>(null)
    private var loadJob: Job? = null
    private var loadRevision = 0L
    private var sourceRevision = 0L

    init {
        viewModelScope.launch {
            browseRequest.flatMapLatest { request ->
                if (request == null) emptyFlow()
                else graph.library.observeFolderListing(
                    request.folderId,
                    LibrarySort.NAME,
                    request.source,
                    subtitleTrackIds = graph.observeWorkSubtitleTrackIds(request.folderId, request.source),
                ).map { LoadedWorkFolder(request, it) }
            }.collect { browse ->
                if (browse.request === browseRequest.value) libraryListing = browse
            }
        }
        viewModelScope.launch {
            browseRequest.flatMapLatest { request ->
                if (request == null) emptyFlow() else graph.library.observeWorkPath(request)
            }.collect { (request, path) ->
                if (browseRequest.value === request) updateLibraryPath(path)
            }
        }
        viewModelScope.launch {
            routeSourceKey.flatMapLatest { rawKey ->
                if (rawKey.isNullOrBlank()) emptyFlow()
                else combine(
                    graph.library.observeAllFolders(),
                    graph.db.dlsiteOriginals().observeWork(DlsiteClient.canonicalWorkno(rawKey)),
                ) { folders, original ->
                    resolveWorkLibraryLink(rawKey, folders, original?.rootFolderId)
                }
            }.distinctUntilChanged().collect { link ->
                if (link.rawKey != sourceKey) return@collect
                libraryLink = link
                if (fileSource == WorkFileSource.PLAY) {
                    val root = link.originalRootId
                    if (root != null) showOriginalLibrary(root)
                    else if (usingLibrary) updateLibraryPath(emptyList())
                }
            }
        }
    }

    private var initialJaPost: String? = null

    fun load(sourceId: String, metered: Boolean, force: Boolean = false,
        jaPost: String? = initialJaPost) {
        val sameRoute = sourceKey == sourceId && initialJaPost == jaPost
        routeSourceKey.value = sourceId
        initialJaPost = jaPost
        if (!force && sameRoute && (facts != null || loadJob?.isActive == true)) {
            if (facts != null) refreshDownloads(sourceId)
            return
        }
        val revision = ++loadRevision
        val initialSourceRevision = sourceRevision
        loadJob?.cancel()
        jaJob?.cancel()
        jaDiscovery?.cancel()
        jaPosts = emptyList()
        jaSelected = null
        jaArticle = null
        jaError = null
        jaVerificationPage = null
        jaLoading = false
        jaChecked = false
        jaDiscoveryFailed = false
        verifyingJaRefresh = false
        work = null
        facts = null
        purchased = false
        loadJob = viewModelScope.launch {
            busy = true
            error = null
            nodeStack.clear()
            playStack.clear()
            libraryStack.clear()
            playRoot = emptyList()
            showPlay = false
            usingLibrary = false
            browseRequest.value = null
            libraryListing = null
            fileSource = WorkFileSource.ASMR
            publishNodes()
            downloadedPlay = emptySet()
            downloadedAsmr = emptySet()
            allowGallery = !metered
            try {
                val (link, existing) = withContext(Dispatchers.IO) {
                    val folders = graph.db.folders().all()
                    val original = graph.db.dlsiteOriginals().work(DlsiteClient.canonicalWorkno(sourceId))
                    val initialLink = resolveWorkLibraryLink(sourceId, folders, original?.rootFolderId)
                    initialLink to folders.firstOrNull { it.id == (initialLink.localRootId ?: initialLink.originalRootId) }
                }
                if (revision != loadRevision) return@launch
                libraryLink = link
                val known = existing?.let { graph.db.japaneseAsmr().postsForRoot(it.id).map { row -> row.ref() } }.orEmpty()
                val routeIds = listOfNotNull(jaPost).filter { id -> id.isNotEmpty() && id.all { it in '0'..'9' } }
                jaPosts = (routeIds.map { id ->
                    known.firstOrNull { it.postId == id } ?: graph.japaneseAsmr.postRef(id) ?: JaPostRef(id, sourceId, getApplication<Application>().getString(R.string.online_post_fallback_title, id), null, JapaneseAsmrClient.postUrl(id), null)
                } + known).distinctBy { it.postId }
                if (jaPosts.isNotEmpty() && jaPost != null) {
                    facts = jaPosts.first().facts()
                    coverUrl = jaPosts.first().coverUri
                    fileSource = WorkFileSource.JAPANESE_ASMR
                    publishLocation()
                    (jaPosts.firstOrNull { it.postId == jaPost } ?: jaPosts.singleOrNull())?.let(::openJaPost)
                }
                discoverJapaneseAsmr(sourceId, revision, initialSourceRevision)
                val original = originalRootId
                val localFacts = existing?.let { folder ->
                    WorkFacts.fromJson(folder.workMeta) ?: WorkFacts(folder.name, "", emptyList(), emptyList(), "", sourceId)
                }
                if (original != null && localFacts != null &&
                    initialSourceRevision == sourceRevision && fileSource != WorkFileSource.JAPANESE_ASMR
                ) {
                    facts = localFacts
                    coverUrl = existing.coverUri
                    fileSource = WorkFileSource.PLAY
                    showOriginalLibrary(original)
                }
                var missing = false
                val loaded = try {
                    graph.asmr.loadWork(sourceId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    missing = (failure as? AsmrException)?.kind == AsmrErrorKind.NOT_FOUND
                    if (!missing) graph.showError(failure, R.string.online_asmr_connection_failed)
                    null
                }
                val page = try {
                    graph.dlsite.showcase(sourceId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (revision != loadRevision) return@launch
                work = loaded
                val pageFacts = page?.facts()
                val nextFacts = loaded?.facts() ?: pageFacts ?: localFacts ?: jaArticle?.let { article ->
                    article.post.facts(article.voiceActors)
                } ?: jaPosts.firstOrNull()?.facts()
                if (nextFacts == null) {
                    facts = null
                    error = getApplication<Application>().getString(R.string.online_work_not_found)
                    return@launch
                }
                facts = nextFacts
                dlsiteSite = page?.siteId?.filter { it.isLetter() }?.ifBlank { null } ?: "maniax"
                coverUrl = loaded?.coverUrl ?: page?.coverUrl ?: existing?.coverUri ?: jaArticle?.post?.coverUri ?: jaPosts.firstOrNull()?.coverUri
                gallery = page?.gallery.orEmpty()
                trials = page?.trials.orEmpty().map { clip ->
                    clip.copy(artist = nextFacts.circle.ifBlank { null }, coverUri = coverUrl)
                }
                asmrMissing = missing
                purchased = try {
                    graph.dlsite.isPurchased(nextFacts.sourceId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                if (revision != loadRevision) return@launch
                if (purchased && originalRootId == null && playRoot.isEmpty()) {
                    playRoot = try {
                        graph.dlsite.playTree(nextFacts.sourceId, nextFacts.circle, coverUrl)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        graph.showError(failure, R.string.online_read_files_failed)
                        emptyList()
                    }
                }
                if (revision != loadRevision) return@launch
                val localRoot = originalRootId
                if (initialSourceRevision != sourceRevision || fileSource == WorkFileSource.JAPANESE_ASMR) {
                    if (fileSource == WorkFileSource.ASMR && !usingLibrary) publishNodes()
                } else if (localRoot != null) {
                    fileSource = WorkFileSource.PLAY
                    showOriginalLibrary(localRoot)
                } else if (purchased && playRoot.isNotEmpty()) {
                    fileSource = WorkFileSource.PLAY
                    showPlay = true
                    usingLibrary = false
                    browseRequest.value = null
                    publishPlay()

                } else if (fileSource == WorkFileSource.PLAY) {
                    useFallbackFileSource()
                } else if (loaded == null && jaPosts.isNotEmpty()) {
                    useFileSource(WorkFileSource.JAPANESE_ASMR)
                } else if (loaded != null && existing != null && graph.library.hasSavedAsmr(existing) && existing.id == localRootId) {
                    showPlay = false
                    showLibrary(existing.id)
                } else if (loaded != null) {
                    showPlay = false
                    usingLibrary = false
                    publishNodes()
                } else {
                    showPlay = false
                    usingLibrary = false
                }
                refreshDownloads(nextFacts.sourceId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (revision == loadRevision) error = getApplication<Application>().resolveUserError(failure, R.string.online_connection_failed)
            } finally {
                if (revision == loadRevision) busy = false
            }
        }
    }

    fun useFileSource(source: WorkFileSource) {
        if (source == fileSource) return
        if (source == WorkFileSource.PLAY && !purchased && originalRootId == null) return
        sourceRevision += 1
        fileSource = source
        if (source == WorkFileSource.JAPANESE_ASMR) {
            showPlay = false
            usingLibrary = false
            browseRequest.value = null
            publishLocation()
            (jaSelected ?: jaPosts.singleOrNull())?.let(::openJaPost)
            return
        }
        if (source == WorkFileSource.PLAY) {
            originalRootId?.let {
                showOriginalLibrary(it)
                return
            }
            showRemotePlay()
        } else {
            showPlay = false
            usingLibrary = false
            browseRequest.value = null
            publishNodes()
        }
    }

    private fun showRemotePlay() {
        val currentFacts = facts
        if (!purchased || currentFacts == null) {
            useFallbackFileSource()
            return
        }
        showPlay = true
        usingLibrary = false
        browseRequest.value = null
        publishPlay()
        if (playRoot.isNotEmpty()) return
        val currentLoad = loadRevision
        val currentSource = sourceRevision
        viewModelScope.launch {
            val nodes = try {
                graph.dlsite.playTree(currentFacts.sourceId, currentFacts.circle, coverUrl)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (currentLoad == loadRevision && currentSource == sourceRevision && !usingLibrary) {
                    graph.showError(failure, R.string.online_read_files_failed)
                }
                emptyList()
            }
            if (currentLoad != loadRevision || currentSource != sourceRevision ||
                fileSource != WorkFileSource.PLAY || usingLibrary
            ) return@launch
            if (nodes.isEmpty()) {
                useFallbackFileSource()
            } else {
                playRoot = nodes
                publishPlay()
            }
        }
    }

    private fun useFallbackFileSource() {
        useFileSource(if (work == null && jaPosts.isNotEmpty()) WorkFileSource.JAPANESE_ASMR else WorkFileSource.ASMR)
    }

    fun retryJaDiscovery() {
        val sourceId = sourceKey.takeIf { it.isNotEmpty() } ?: return
        jaChecked = false
        jaDiscoveryFailed = false
        jaVerificationPage = null
        verifyingJaRefresh = false
        discoverJapaneseAsmr(sourceId, loadRevision, sourceRevision)
    }

    private fun discoverJapaneseAsmr(sourceId: String, revision: Long, initialSourceRevision: Long) {
        jaDiscovery?.cancel()
        jaDiscovery = viewModelScope.launch {
            if (jaPosts.isNotEmpty()) {
                jaChecked = true
                return@launch
            }
            try {
                val found = graph.japaneseAsmr.postsFor(sourceId)
                if (revision != loadRevision) return@launch
                jaChecked = true
                jaPosts = found
                localRootId?.let { graph.library.rememberJapanesePosts(it, found) }
                val first = found.firstOrNull() ?: return@launch
                if (facts == null) {
                    facts = first.facts()
                    coverUrl = first.coverUri
                    error = null
                }
                if (!busy && fileSource == WorkFileSource.ASMR && work == null &&
                    !purchased && originalRootId == null && initialSourceRevision == sourceRevision
                ) useFileSource(WorkFileSource.JAPANESE_ASMR)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (revision == loadRevision) {
                    jaChecked = true
                    jaDiscoveryFailed = true
                    reportJaPageFailure(failure)
                }
            }
        }
    }

    private fun JaPostRef.facts(actors: List<String> = emptyList()) =
        WorkFacts(title, "", actors, emptyList(), "", rj)

    internal var verifyingJaRefresh by mutableStateOf(false)
        private set

    private fun reportJaPageFailure(failure: Exception) {
        jaVerificationPage = (failure as? JapaneseAsmrException)?.verificationPage
        jaError = getApplication<Application>().resolveUserError(failure, R.string.ja_error_connection)
    }

    fun openJaPost(post: JaPostRef, refresh: Boolean = false) {
        val old = jaSelected
        if (!refresh && old?.postId == post.postId && (jaArticle != null || jaLoading)) return
        jaSelected = post
        if (!refresh) jaArticle = null
        verifyingJaRefresh = refresh
        jaError = null
        jaVerificationPage = null
        publishLocation()
        jaJob?.cancel()
        val revision = loadRevision
        jaJob = viewModelScope.launch {
            jaLoading = true
            try {
                val article = graph.japaneseAsmr.article(post.postId, post.rj, refresh)
                if (revision != loadRevision || jaSelected?.postId != post.postId) return@launch
                acceptJaArticle(article, cleanup = refresh)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (revision == loadRevision && jaSelected?.postId == post.postId) reportJaPageFailure(failure)
            } finally {
                if (revision == loadRevision && jaSelected?.postId == post.postId) jaLoading = false
            }
        }
    }

    private suspend fun acceptJaArticle(article: JaArticle, cleanup: Boolean, sync: Boolean = false) {
        val revision = loadRevision
        val post = article.post
        if (article.media.isEmpty() && jaArticle?.let { it.post.postId == post.postId && it.media.isNotEmpty() } == true) {
            jaError = getApplication<Application>().getString(R.string.online_article_unavailable)
            return
        }
        jaArticle = article
        val old = jaPosts.firstOrNull { it.postId == post.postId }
        val displayed = post.copy(publishedAt = post.publishedAt ?: old?.publishedAt, coverUri = post.coverUri ?: old?.coverUri)
        jaPosts = if (old == null) jaPosts + displayed else jaPosts.map { if (it.postId == post.postId) displayed else it }
        jaSelected = displayed
        jaChecked = true
        jaDiscoveryFailed = false
        jaVerificationPage = null
        jaError = if (article.media.isEmpty()) getApplication<Application>().getString(R.string.online_article_unavailable) else null
        val root = localRootId
        if (root != null) {
            graph.library.rememberJapanesePosts(root, listOf(post))
            if ((cleanup || sync) && article.media.isNotEmpty()) {
                val result = graph.player.refreshJaArticle(article, root, cleanup)
                if (revision != loadRevision || jaSelected?.postId != post.postId) return
                if (cleanup) graph.show(TextRef.Raw(result.message(getApplication())))
            }
        }
        if (revision != loadRevision || jaSelected?.postId != post.postId) return
        if (facts == null || facts?.title == old?.title && old?.coverUri == null && work == null) {
            facts = post.facts(article.voiceActors)
            coverUrl = post.coverUri
        }
        if (fileSource == WorkFileSource.JAPANESE_ASMR) publishLocation()
    }

    fun acceptVerifiedJaArticle(postId: String, cleanup: Boolean) {
        val article = graph.japaneseAsmr.cachedArticle(postId) ?: return
        if (!article.post.rj.equals(sourceKey, ignoreCase = true)) return
        if (jaSelected?.postId?.let { it != postId } == true || cleanup && !verifyingJaRefresh) return
        val revision = loadRevision
        jaJob?.cancel()
        jaJob = viewModelScope.launch {
            try {
                if (revision != loadRevision) return@launch
                acceptJaArticle(article, cleanup, sync = !cleanup)
                verifyingJaRefresh = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (revision == loadRevision) reportJaPageFailure(failure)
            } finally {
                if (revision == loadRevision) jaLoading = false
            }
        }
    }

    fun playJa(media: JaMediaRef) {
        val article = jaArticle ?: return
        viewModelScope.launch {
            val post = article.post
            val savedTrack = savedJaTrack(article, media)
            if (savedTrack != null) {
                graph.player.playTrack(savedTrack)
                return@launch
            }
            val items = article.media.map { candidate -> com.nanahoshi.audioplayer.dlsite.RemotePlayable(
                candidate.title, article.voiceActors.joinToString(" / ").ifBlank { null }, post.coverUri ?: coverUrl,
                post.url, TrackSource.JAPANESE_ASMR, JapaneseAsmrClient.REFERER,
                workno = post.postId, fileKey = candidate.key, mediaKind = candidate.kind.name, mediaPathHash = candidate.pathHash,
            ) }
            graph.player.replaceAndPlayRemote(items, article.media.indexOf(media).coerceAtLeast(0))
        }
    }

    private suspend fun savedJaTrack(article: JaArticle, media: JaMediaRef): TrackEntity? {
        val dao = graph.db.japaneseAsmr()
        val postId = article.post.postId
        val key = com.nanahoshi.audioplayer.japaneseasmr.resolveStoredJaMatches(article, postId, dao)
            .entries.firstOrNull { it.value.media?.key == media.key }?.key ?: return null
        return dao.track(postId, key)
    }

    fun downloadJa(media: JaMediaRef) {
        val article = jaArticle ?: return
        val root = localRootId ?: return
        viewModelScope.launch {
            try {
                val count = graph.downloads.prepare {
                    graph.library.importJapaneseAsmrPost(article, root)
                    val track = savedJaTrack(article, media) ?: error(getApplication<Application>().getString(R.string.online_saved_track_missing))
                    graph.downloads.enqueue(listOf(track))
                }
                if (count > 0) graph.show(R.string.online_download_task_added_count, count)
                else graph.show(R.string.online_no_audio_to_download)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.showError(failure, R.string.online_prepare_download_failed)
            }
        }
    }

    fun playDownloaded(fileKey: String?): Boolean {
        val raw = rawFileKey(fileKey) ?: return false
        return raw in downloadedPlay
    }

    fun asmrDownloaded(node: AsmrNode): Boolean {
        val raw = node.hash.ifBlank { node.title }
        return raw in downloadedAsmr
    }

    private fun refreshDownloads(sourceId: String) {
        val workId = sourceId.filter { it.isDigit() }.toLongOrNull()
        viewModelScope.launch {
            val play = graph.downloads.downloadedKeys(TrackSource.DLSITE, sourceId).toMutableSet()
            val asmrKeys = graph.downloads.downloadedKeys(TrackSource.ASMR, sourceId).toMutableSet()
            graph.library.observeTracks().first().forEach { track ->
                if (workId == null || track.asmrWorkId != workId) return@forEach
                val path = track.localUri
                if (path == null || !path.startsWith("/") || !java.io.File(path).isFile) return@forEach
                val raw = rawFileKey(track.fileHash) ?: return@forEach
                when (track.source) {
                    TrackSource.DLSITE -> play += raw
                    TrackSource.ASMR -> asmrKeys += raw
                    else -> Unit
                }
            }
            downloadedPlay = play
            downloadedAsmr = asmrKeys
        }
    }

    fun revealGallery() {
        allowGallery = true
    }

    fun canGoUp(): Boolean = browserPath.isNotEmpty()

    fun up() = ancestor(browserPath.lastIndex)

    internal fun goToAncestor(folderId: Long) {
        val index = browserPath.indexOfFirst { it.id == folderId }
        if (index >= 0) ancestor(index + 1)
    }

    internal fun ancestor(depth: Int) {
        if (fileSource == WorkFileSource.JAPANESE_ASMR && !usingLibrary) {
            jaJob?.cancel()
            jaSelected = null
            jaArticle = null
            jaError = null
            jaVerificationPage = null
            jaLoading = false
            publishLocation()
            return
        }
        if (depth !in 0 until browserPath.size) return
        when {
            showPlay -> {
                playStack.subList(depth, playStack.size).clear()
                publishPlay()
            }
            usingLibrary -> {
                libraryStack.subList(depth + 1, libraryStack.size).clear()
                requestLibraryFolder(browseRequest.value?.source)
            }
            else -> {
                nodeStack.subList(depth, nodeStack.size).clear()
                publishNodes()
            }
        }
    }

    fun openLibraryFolder(folder: FolderEntity) {
        val request = browseRequest.value ?: return
        if (folder.parentId != request.folderId || libraryFolders.none { it.id == folder.id }) return
        libraryNames[folder.id] = folder.name
        libraryStack.add(folder.id)
        requestLibraryFolder(request.source)
        browserStates.remove(browserIdentity)
    }

    fun openNode(node: AsmrNode) {
        if (showPlay || usingLibrary || nodeFolders.none { it === node }) return
        nodeStack.add(node)
        publishNodes()
        browserStates.remove(browserIdentity)
    }

    fun playLibrary(track: TrackEntity) {
        viewModelScope.launch {
            val request = browseRequest.value ?: return@launch
            val tracks = graph.library.tracksIn(request.folderId).filter { it.source == request.source }
                .sortedForLibrary(LibrarySort.NAME)
            val index = tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            graph.player.replaceAndPlay(tracks.ifEmpty { listOf(track) }, index)
        }
    }

    fun playNode(node: AsmrNode) {
        val current = work ?: return
        val siblings = (if (nodeStack.isEmpty()) current.nodes else nodeStack.last().children)
            .filter { isAsmrAudio(it.type, it.title) && !it.streamUrl.isNullOrBlank() }
            .sortedBy { it.title.lowercase() }
        val parentPath = nodeStack.fold("") { path, folder ->
            val name = sanitizeEntryName(folder.title)
            if (path.isEmpty()) name else "$path/$name"
        }
        val items = siblings.map { sibling ->
            com.nanahoshi.audioplayer.dlsite.RemotePlayable(
                title = sibling.title,
                artist = current.circle.ifBlank { current.sourceId },
                coverUri = current.coverUrl,
                remoteUrl = sibling.streamUrl.orEmpty(),
                source = TrackSource.ASMR,
                referer = com.nanahoshi.audioplayer.asmr.AsmrClient.REFERER,
                durationMs = sibling.durationMs,
                workno = current.id.toString(),
                fileKey = sibling.storageKey(parentPath),
            )
        }
        val index = siblings.indexOfFirst { it.hash == node.hash && it.title == node.title }.coerceAtLeast(0)
        if (items.isEmpty()) {
            graph.show(R.string.online_no_playable_audio)
        } else {
            graph.player.replaceAndPlayRemote(items, index)
        }
    }

    fun openNodeFile(node: AsmrNode): Boolean {
        val url = node.streamUrl
        if (url.isNullOrBlank()) {
            graph.show(R.string.online_file_open_failed)
            return false
        }
        graph.remotePreview.value = com.nanahoshi.audioplayer.dlsite.RemotePreview(
            name = node.title,
            url = url,
            kind = previewKind(node.title),
            referer = com.nanahoshi.audioplayer.asmr.AsmrClient.REFERER,
            authed = false,
        )
        return true
    }

    fun openPlay(entry: com.nanahoshi.audioplayer.dlsite.PlayEntry) {
        if (!showPlay || playFolders.none { it === entry }) return
        playStack.add(entry)
        publishPlay()
        browserStates.remove(browserIdentity)
    }

    fun playPlay(entry: com.nanahoshi.audioplayer.dlsite.PlayEntry) {
        val siblings = playFiles.mapNotNull { it.audio }
        val index = siblings.indexOfFirst { it.remoteUrl == entry.audio?.remoteUrl }.coerceAtLeast(0)
        if (siblings.isEmpty()) {
            graph.show(R.string.online_no_playable_audio)
        } else {
            graph.player.replaceAndPlayRemote(siblings, index)
        }
    }

    fun openPlayFile(entry: com.nanahoshi.audioplayer.dlsite.PlayEntry): Boolean {
        val preview = entry.preview ?: return false
        graph.remotePreview.value = preview
        return true
    }

    fun playTrial(item: com.nanahoshi.audioplayer.dlsite.RemotePlayable) {
        if (trials.isEmpty()) return
        val index = trials.indexOfFirst { it.remoteUrl == item.remoteUrl }.coerceAtLeast(0)
        graph.player.replaceAndPlayRemote(trials, index)
    }

    fun nodeHasSubtitle(node: AsmrNode): Boolean {
        val siblings = if (nodeStack.isEmpty()) work?.nodes.orEmpty() else nodeStack.last().children
        return pickSubtitleName(node.title, siblings.map { it.title }) != null
    }

    fun save() {
        if (saved || working || facts == null) return
        val revision = loadRevision
        viewModelScope.launch {
            working = true
            try {
                loadJob?.join()
                if (revision != loadRevision) return@launch
                val currentFacts = facts ?: return@launch
                val current = work
                val root = graph.library.saveOnlineWork(
                    currentFacts, coverUrl, current, playRoot, jaPosts, fileSource.trackSource(),
                    selectedArticle = jaArticle.takeIf { fileSource == WorkFileSource.JAPANESE_ASMR },
                    playChecked = playRoot.isNotEmpty() || graph.dlsite.cachedPurchased(currentFacts.sourceId) == false,
                )
                if (revision != loadRevision) return@launch
                graph.library.rememberJapanesePosts(root, jaPosts)
                if (fileSource == WorkFileSource.ASMR && current != null) showLibrary(root)
                graph.show(R.string.online_added_to_library)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (revision == loadRevision) graph.showError(failure, R.string.online_save_failed)
            } finally {
                working = false
            }
        }
    }

    private fun showOriginalLibrary(rootId: Long) {
        showPlay = false
        if (!usingLibrary || libraryStack.firstOrNull() != rootId || browseRequest.value?.source != TrackSource.DLSITE) {
            showLibrary(rootId, TrackSource.DLSITE)
        }
    }

    private fun showLibrary(rootId: Long, source: TrackSource = TrackSource.ASMR) {
        usingLibrary = true
        libraryStack.clear()
        libraryStack.add(rootId)
        requestLibraryFolder(source)
    }

    private fun requestLibraryFolder(source: TrackSource?) {
        libraryListing = null
        browseRequest.value = WorkFolderRequest(libraryStack.toList(), source)
        publishLocation()
    }

    private fun updateLibraryPath(path: List<FolderEntity>) {
        if (path.isEmpty()) {
            usingLibrary = false
            libraryStack.clear()
            browseRequest.value = null
            libraryListing = null
            if (fileSource == WorkFileSource.PLAY) {
                showRemotePlay()
            } else {
                publishNodes()
            }
            return
        }
        path.forEach { libraryNames[it.id] = it.name }
        if (path.size < libraryStack.size) ancestor(path.lastIndex) else publishLocation()
    }

    private fun publishPlay() {
        publishLocation()
        val nodes = if (playStack.isEmpty()) playRoot else playStack.last().children
        playFolders = nodes.filter { it.children.isNotEmpty() }.sortedBy { it.title.lowercase() }
        playFiles = nodes.filter { it.children.isEmpty() }.sortedBy { it.title.lowercase() }
    }

    private fun publishNodes() {
        publishLocation()
        val nodes = if (nodeStack.isEmpty()) work?.nodes.orEmpty() else nodeStack.last().children
        nodeFolders = nodes.filter { it.isContainer() }.sortedBy { it.title.lowercase() }
        nodeFiles = nodes.filter { !it.isContainer() && !isSubtitleFile(it.title) }.sortedBy { it.title.lowercase() }
    }

}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun WorkSearchScreen(
    onOpenWork: (String) -> Unit,
    viewModel: OnlineSearchViewModel = viewModel(),
) {
    val listState = rememberLazyListState()
    com.nanahoshi.audioplayer.ui.components.ResetOnNewSearch(listState, viewModel.searchRevision)
    var composing by rememberSaveable { mutableStateOf(false) }
    val reduceMotion = LocalReduceMotion.current
    AnimatedContent(
        targetState = composing,
        transitionSpec = {
            if (reduceMotion) {
                EnterTransition.None togetherWith ExitTransition.None
            } else if (targetState) {
                (expandVertically(expandFrom = Alignment.Top, animationSpec = tween(Motion.medium, easing = Motion.enter)) + fadeIn(tween(Motion.medium))) togetherWith
                    fadeOut(tween(Motion.short))
            } else {
                fadeIn(tween(Motion.medium)) togetherWith
                    (shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = tween(Motion.medium, easing = Motion.exit)) + fadeOut(tween(Motion.short)))
            }
        },
        label = "searchComposer",
    ) { open ->
    if (open) {
        SearchComposer(
            text = viewModel.draftText,
            pieces = viewModel.draftPieces,
            onTextChange = { viewModel.draftText = it },
            onPiecesChange = { viewModel.draftPieces = it },
            onBack = { composing = false },
            onSearch = { text, pieces ->
                viewModel.applyQuery(text, pieces)
                composing = false
            },
        )
    } else {
    val works = viewModel.works
    LaunchedEffect(listState, works.size, viewModel.loading) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { index ->
                if (viewModel.error == null && index >= works.lastIndex - 2) viewModel.loadMore()
            }
    }
    CollapsingTop(
        resetKey = viewModel.searchRevision,
        isEmpty = works.isEmpty(),
        canCollapse = listState::canCollapseHeader,
        header = {
            val hasQuery = viewModel.text.isNotBlank() || viewModel.pieces.isNotEmpty()
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(28.dp),
                tonalElevation = 1.dp,
                onClick = { composing = true },
            ) {
                Row(
                    Modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        viewModel.summary().ifBlank { stringResource(R.string.online_search_works) },
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        color = if (viewModel.summary().isBlank()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    if (hasQuery) {
                        IconButton(onClick = { viewModel.applyQuery("", emptyList()) }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.a11y_clear_search))
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = viewModel.subtitleOnly,
                    onClick = { viewModel.changeSubtitle(!viewModel.subtitleOnly) },
                    label = { Text(stringResource(R.string.online_filter_subtitle)) },
                )
                FilterChip(
                    selected = viewModel.allAges,
                    onClick = { viewModel.changeAllAges(!viewModel.allAges) },
                    label = { Text(stringResource(R.string.online_filter_all_ages)) },
                )
                SortMenu(
                    label = viewModel.sortLabel(),
                    showPopular = viewModel.text.isBlank() && viewModel.pieces.isEmpty() && !viewModel.allAges,
                    selected = viewModel.sort,
                    onPopular = { viewModel.chooseSort(null) },
                    onSort = viewModel::chooseSort,
                )
            }
        },
    ) { topInset ->
        when {
            works.isEmpty() && viewModel.loading -> SkeletonWorkList(modifier = topInset)
            works.isEmpty() -> Box(topInset.fillMaxSize(), contentAlignment = Alignment.Center) {
                val failure = viewModel.error
                if (failure != null) {
                    ErrorState(failure, viewModel::loadMore)
                } else {
                    EmptyState(
                        title = stringResource(R.string.online_empty_search_title),
                        message = stringResource(R.string.online_empty_search_message),
                        actionLabel = stringResource(R.string.online_empty_search_action),
                        onAction = { composing = true },
                    )
                }
            }
            else -> LazyColumn(
                state = listState,
                modifier = topInset.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(works, key = { it.sourceId }) { item ->
                    OnlineResultCard(item) {
                        if (item.sourceId.isNotBlank()) onOpenWork(item.sourceId)
                    }
                }
                val failure = viewModel.error
                if (failure != null && !viewModel.loading) {
                    item(key = "error") { ErrorState(failure, viewModel::loadMore) }
                }
                if (viewModel.loading) {
                    item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
    }
    }
}

internal enum class OnlineSearchSource(val saveableKey: String) {
    WORKS("works"),
    BILIBILI("bilibili"),
    YOUTUBE("youtube");

    fun topBarOwnerKey(entryId: String) = "online:$entryId:$name"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OnlineSearchScreen(
    entryId: String,
    source: OnlineSearchSource,
    onSourceChange: (OnlineSearchSource) -> Unit,
    onOpenWork: (String) -> Unit,
    onOpenBiliCollection: (com.nanahoshi.audioplayer.bilibili.BiliCollection) -> Unit = {},
) {
    val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    Column(Modifier.fillMaxSize()) {
        val sources = OnlineSearchSource.entries
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            sources.forEachIndexed { index, item ->
                SegmentedButton(
                    selected = source == item,
                    onClick = { onSourceChange(item) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = sources.size),
                ) {
                    Text(
                        when (item) {
                            OnlineSearchSource.WORKS -> stringResource(R.string.online_source_works)
                            OnlineSearchSource.BILIBILI -> stringResource(R.string.library_badge_bilibili)
                            OnlineSearchSource.YOUTUBE -> "YouTube"
                        },
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Box(Modifier.weight(1f)) {
            holder.SaveableStateProvider(source.saveableKey) {
                when (source) {
                    OnlineSearchSource.BILIBILI -> com.nanahoshi.audioplayer.ui.bilibili.BiliBrowser(
                        topBarOwnerKey = source.topBarOwnerKey(entryId),
                        onOpenCollection = onOpenBiliCollection,
                    )
                    OnlineSearchSource.YOUTUBE -> com.nanahoshi.audioplayer.ui.youtube.YouTubeBrowser(
                        topBarOwnerKey = source.topBarOwnerKey(entryId),
                    )
                    OnlineSearchSource.WORKS -> WorkSearchScreen(onOpenWork)
                }
            }
        }
    }
}

@Composable
private fun OnlineResultCard(item: OnlineHit, onClick: () -> Unit) {
    WorkCard(
        facts = item.facts(),
        coverUrl = item.coverUrl,
        onClick = onClick,
        enabled = item.sourceId.isNotBlank(),
        extraBadges = if (item.asmrMissing) {
            listOf(BadgeItem(stringResource(R.string.online_not_listed), MaterialTheme.colorScheme.error))
        } else {
            emptyList()
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun SortMenu(
    label: String,
    showPopular: Boolean,
    selected: OnlineSort?,
    onPopular: () -> Unit,
    onSort: (OnlineSort) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (showPopular) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.online_popular)) },
                    onClick = { open = false; onPopular() },
                    trailingIcon = {
                        if (selected == null) Icon(Icons.Default.Check, contentDescription = stringResource(R.string.a11y_selected))
                    },
                )
            }
            OnlineSort.entries.forEach { item ->
                val active = selected == item ||
                    (!showPopular && selected == null && item == OnlineSort.SALES)
                DropdownMenuItem(
                    text = { Text(stringResource(item.labelRes)) },
                    onClick = { open = false; onSort(item) },
                    trailingIcon = {
                        if (active) Icon(Icons.Default.Check, contentDescription = stringResource(R.string.a11y_selected))
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchComposer(
    text: String,
    pieces: List<SearchPiece>,
    onTextChange: (String) -> Unit,
    onPiecesChange: (List<SearchPiece>) -> Unit,
    onBack: () -> Unit,
    onSearch: (String, List<SearchPiece>) -> Unit,
) {
    var pendingKind by rememberSaveable { mutableStateOf<PieceKind?>(null) }
    var pendingName by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun submit() {
        focus.clearFocus()
        keyboard?.hide()
        onSearch(text.trim(), pieces)
    }
    fun close() {
        focus.clearFocus()
        keyboard?.hide()
        onBack()
    }
    BackHandler(onBack = ::close)
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ::close) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.a11y_back))
            }
            Text(stringResource(R.string.online_search), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = ::submit) { Text(stringResource(R.string.online_search)) }
        }
        Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            shape = MaterialTheme.shapes.small,
            tonalElevation = 1.dp,
        ) {
            FlowRow(
                Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                pieces.forEach { piece ->
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(piece.caption(androidx.compose.ui.platform.LocalContext.current), modifier = Modifier.padding(start = 10.dp))
                            IconButton(onClick = { onPiecesChange(pieces.filterNot { it == piece }) }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.a11y_delete_filter))
                            }
                        }
                    }
                }
                BasicTextField(
                    value = text,
                    onValueChange = { onTextChange(it.replace("$", "")) },
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.widthIn(min = 120.dp).padding(8.dp).onPreviewKeyEvent { event ->
                        val deleting = event.type == KeyEventType.KeyDown && event.key == Key.Backspace
                        if (deleting && text.isEmpty() && pieces.isNotEmpty()) {
                            onPiecesChange(pieces.dropLast(1))
                            true
                        } else {
                            false
                        }
                    },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }),
                    decorationBox = { inner ->
                        if (text.isEmpty() && pieces.isEmpty()) {
                            Text(stringResource(R.string.online_input_text), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        inner()
                    },
                )
            }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PieceKind.entries.forEach { kind ->
                TextButton(onClick = {
                    pendingKind = kind
                    pendingName = ""
                }) { Text(stringResource(kind.labelRes)) }
            }
        }
    }
    val kind = pendingKind
    if (kind == PieceKind.TAG) {
        TagPicker(
            taken = pieces.mapNotNull { it.genreId }.toSet(),
            onPick = { genre ->
                onPiecesChange(pieces + SearchPiece(PieceKind.TAG, genre.name, genre.id))
                pendingKind = null
            },
            onDismiss = { pendingKind = null },
        )
    } else if (kind != null) {
        AlertDialog(
            onDismissRequest = { pendingKind = null },
            title = { Text(stringResource(R.string.online_add_kind, stringResource(kind.labelRes))) },
            text = {
                OutlinedTextField(
                    value = pendingName,
                    onValueChange = { pendingName = it.replace("$", "") },
                    singleLine = true,
                    label = { Text(stringResource(kind.labelRes)) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = pendingName.trim()
                    if (name.isNotEmpty()) onPiecesChange(pieces + SearchPiece(kind, name))
                    pendingKind = null
                }) { Text(stringResource(R.string.online_add_action)) }
            },
            dismissButton = { TextButton(onClick = { pendingKind = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun TagPicker(
    taken: Set<String>,
    onPick: (com.nanahoshi.audioplayer.dlsite.DlsiteGenre) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val matches = remember(query, taken) {
        val needle = query.trim()
        com.nanahoshi.audioplayer.dlsite.DlsiteGenres.all.filter { genre ->
            genre.id !in taken && (needle.isEmpty() || genre.name.contains(needle, ignoreCase = true))
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.online_add_tag_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.online_filter)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.fillMaxWidth().height(320.dp).padding(top = 8.dp)) {
                    items(matches, key = { it.id }) { genre ->
                        ListItem(
                            headlineContent = { Text(genre.name) },
                            modifier = Modifier.clickable { onPick(genre) },
                        )
                    }
                    if (matches.isEmpty()) {
                        item { Text(stringResource(R.string.online_no_matching_tags), modifier = Modifier.padding(12.dp)) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

private fun asmrWorkUrl(sourceId: String): String? {
    val id = worknoOf(sourceId) ?: return null
    return "https://asmr.one/work/$id"
}

private fun dlsiteWorkUrl(sourceId: String, site: String): String? {
    val id = worknoOf(sourceId) ?: return null
    val name = site.filter { it.isLetter() }.ifBlank { "maniax" }
    return "https://www.dlsite.com/$name/work/=/product_id/$id.html?locale=zh_CN"
}

private fun worknoOf(sourceId: String): String? = try {
    com.nanahoshi.audioplayer.dlsite.DlsiteClient.worknoOf(sourceId).takeIf { it.isNotBlank() }
} catch (_: Exception) {
    sourceId.trim().takeIf { it.isNotEmpty() }
}

private fun Context.openWorkSite(url: String?) {
    if (url.isNullOrBlank()) {
        (applicationContext as Application).graph().show(R.string.online_missing_work_id)
        return
    }
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        (applicationContext as Application).graph().show(R.string.online_no_browser)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineWorkScreen(
    sourceId: String,
    onBack: () -> Unit,
    onOpenLibraryWork: (Long) -> Unit,
    onOpenPreview: (Long) -> Unit,
    onOpenRemotePreview: () -> Unit,
    onVerifyJapaneseAsmr: (String, String, Boolean) -> Unit,
    verifiedJaResult: kotlinx.coroutines.flow.StateFlow<String?>? = null,
    onConsumeJaResult: () -> Unit = {},
    jaPost: String? = null,
    viewModel: OnlineWorkViewModel = viewModel(),
) {
    val context = LocalContext.current
    var browserChoice by remember { mutableStateOf(false) }
    var browserMenu by remember { mutableStateOf(false) }
    LaunchedEffect(sourceId, jaPost) {
        viewModel.load(sourceId, context.onMeteredNetwork(), jaPost = jaPost)
    }
    LaunchedEffect(verifiedJaResult) {
        verifiedJaResult?.collect { result ->
            if (result != null) {
                viewModel.acceptVerifiedJaArticle(result.substringBefore(':'), result.substringAfter(':') == "REFRESH")
                onConsumeJaResult()
            }
        }
    }
    BackHandler(enabled = viewModel.canGoUp()) { viewModel.up() }
    val facts = viewModel.facts
    val jaVerificationPage = viewModel.jaVerificationPage
    val verifyJapaneseAsmr: (String) -> Unit = { page ->
        onVerifyJapaneseAsmr(page, viewModel.jaSelected?.rj ?: sourceId, viewModel.verifyingJaRefresh)
    }
    val targetFolderId = viewModel.libraryFolderId
    val canSave = facts != null && (viewModel.work != null || viewModel.purchased || viewModel.jaPosts.isNotEmpty())
    if (browserChoice && facts != null) {
        AlertDialog(
            onDismissRequest = { browserChoice = false },
            title = { Text(stringResource(R.string.online_open_in_browser)) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = {
                            browserChoice = false
                            context.openWorkSite(asmrWorkUrl(facts.sourceId))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("asmr.one") }
                    TextButton(
                        onClick = {
                            browserChoice = false
                            context.openWorkSite(dlsiteWorkUrl(facts.sourceId, viewModel.dlsiteSite))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("DLsite") }
                    (viewModel.jaSelected ?: viewModel.jaPosts.firstOrNull())?.let { article ->
                        TextButton(onClick = {
                            browserChoice = false
                            context.openJapaneseAsmrInBrowser(article.url)
                        }, modifier = Modifier.fillMaxWidth()) { Text("Japanese ASMR") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { browserChoice = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            title = stringResource(R.string.online_work_title),
            onBack = { if (viewModel.canGoUp()) viewModel.up() else onBack() },
            actions = {
                if (viewModel.working || viewModel.busy) {
                    CircularProgressIndicator(Modifier.padding(end = 8.dp).size(20.dp))
                }
                if (facts != null) {
                    IconButton(onClick = { browserMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.a11y_work_actions))
                    }
                    DropdownMenu(expanded = browserMenu, onDismissRequest = { browserMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.online_open_in_browser)) },
                            onClick = {
                                browserMenu = false
                                browserChoice = true
                            },
                        )
                    }
                }
            },
        )
        when {
            facts == null && viewModel.busy -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            facts == null && jaVerificationPage != null -> Box(
                Modifier.fillMaxSize(), contentAlignment = Alignment.Center,
            ) {
                JapaneseAsmrVerificationState(jaVerificationPage, verifyJapaneseAsmr)
            }
            facts == null -> ErrorState(
                viewModel.error ?: stringResource(R.string.online_work_not_found),
                onRetry = { viewModel.load(sourceId, context.onMeteredNetwork(), force = true) },
                modifier = Modifier.fillMaxSize(),
            )
            else -> {
                val foldersEmpty = when {
                    viewModel.showPlay -> viewModel.playFolders.isEmpty()
                    viewModel.usingLibrary -> viewModel.libraryFolders.isEmpty()
                    viewModel.work != null -> viewModel.nodeFolders.isEmpty()
                    else -> true
                }
                val filesEmpty = when {
                    viewModel.showPlay -> viewModel.playFiles.isEmpty()
                    viewModel.usingLibrary -> viewModel.libraryEntries.isEmpty()
                    viewModel.work != null -> viewModel.nodeFiles.isEmpty()
                    else -> true
                }
                com.nanahoshi.audioplayer.ui.components.WorkFileBrowser(
                    state = viewModel.browserListState(),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    bottomPadding = PaddingValues(bottom = if (targetFolderId != null || canSave) 96.dp else 24.dp),
                    loading = when {
                        viewModel.fileSource == WorkFileSource.JAPANESE_ASMR ->
                            viewModel.jaSelected == null && viewModel.jaPosts.isEmpty() &&
                                (!viewModel.jaChecked || viewModel.jaLoading)
                        else -> (viewModel.libraryLoading || viewModel.busy) && foldersEmpty && filesEmpty
                    },
                    header = {
                        if (viewModel.error != null && !viewModel.busy) ErrorState(viewModel.error!!,
                            onRetry = { viewModel.load(sourceId, context.onMeteredNetwork(), force = true) })
                        WorkHeader(facts, viewModel.coverUrl, purchased = viewModel.purchased)
                        GalleryBlock(viewModel.gallery, viewModel.allowGallery, viewModel::revealGallery) { index ->
                            if (viewModel.openGallery(index)) onOpenRemotePreview()
                        }
                        TrialBlock(viewModel.trials, onPlay = viewModel::playTrial)
                    },
                    controls = {
                        SectionHeader(stringResource(R.string.online_files))
                        if (viewModel.availableSources.size > 1) {
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                                viewModel.availableSources.forEachIndexed { index, source ->
                                    SegmentedButton(selected = viewModel.fileSource == source,
                                        onClick = { viewModel.useFileSource(source) },
                                        shape = SegmentedButtonDefaults.itemShape(index, viewModel.availableSources.size)) {
                                        Text(source.trackSource().workLabel(), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                        if (viewModel.jaChecked && viewModel.jaPosts.isEmpty()) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(
                                        if (viewModel.jaDiscoveryFailed) R.string.online_ja_unknown
                                        else R.string.online_ja_missing,
                                    ),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (viewModel.jaDiscoveryFailed) {
                                    TextButton(onClick = {
                                        if (jaVerificationPage != null) verifyJapaneseAsmr(jaVerificationPage)
                                        else viewModel.retryJaDiscovery()
                                    }) {
                                        Text(stringResource(
                                            if (jaVerificationPage != null) R.string.online_verify else R.string.common_retry,
                                        ))
                                    }
                                }
                            }
                        }
                        val trail = viewModel.browserPath
                        com.nanahoshi.audioplayer.ui.library.LibraryBreadcrumb(
                            trail = trail,
                            rootLabel = stringResource(R.string.online_root), onRoot = { viewModel.ancestor(0) },
                            onCrumb = { viewModel.goToAncestor(trail[it].id) },
                            revealCurrent = true)
                    },
                ) {
                if (viewModel.fileSource == WorkFileSource.JAPANESE_ASMR && !viewModel.usingLibrary) {
                    val selected = viewModel.jaSelected
                    if (selected == null) {
                        items(viewModel.jaPosts, key = { it.postId }) { post ->
                            OnlineFolderRow(listOfNotNull(post.title, post.publishedAt).joinToString(" · "), post.coverUri, false) {
                                viewModel.openJaPost(post)
                            }
                        }
                    } else {
                        item("ja-article") {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(listOfNotNull(selected.title, selected.publishedAt).joinToString(" · "), Modifier.weight(1f))
                                TextButton(onClick = { viewModel.openJaPost(selected, refresh = true) }) { Text(stringResource(R.string.online_refresh_ja)) }
                            }
                        }
                        if (viewModel.jaLoading) item("ja-loading") { LoadingState(stringResource(R.string.online_reading_posts)) }
                        viewModel.jaError?.let { details ->
                            item("ja-error") {
                                if (jaVerificationPage != null) {
                                    JapaneseAsmrVerificationState(jaVerificationPage, verifyJapaneseAsmr)
                                } else {
                                    ErrorState(details, { viewModel.openJaPost(selected, refresh = true) })
                                }
                            }
                        }
                        if (!viewModel.saved && viewModel.jaArticle?.media?.isNotEmpty() == true) {
                            item(key = "ja_download_hint") {
                                Text(
                                    stringResource(R.string.online_add_before_download),
                                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        items(viewModel.jaArticle?.media.orEmpty(), key = { it.key }) { media ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) {
                                    OnlineTrackRow(media.title, selected.coverUri, "Japanese ASMR", onClick = { viewModel.playJa(media) })
                                }
                                if (viewModel.saved) TextButton(onClick = { viewModel.downloadJa(media) }) { Text(stringResource(R.string.online_download)) }
                            }
                        }
                    }
                } else if (viewModel.asmrMissing && viewModel.fileSource == WorkFileSource.ASMR && !viewModel.showPlay) {
                    item(key = "missing") {
                        Box(Modifier.fillMaxWidth().padding(24.dp)) {
                            EmptyHint(stringResource(R.string.online_not_listed))
                        }
                    }
                } else {
                    if (viewModel.showPlay) {
                        itemsIndexed(viewModel.playFolders, key = { index, entry -> "play-folder-$index-${entry.title}" }) { _, entry ->
                            OnlineFolderRow(entry.title, null, work = false) { viewModel.openPlay(entry) }
                        }
                        itemsIndexed(viewModel.playFiles, key = { index, entry -> "play-file-$index-${entry.title}" }) { _, entry ->
                            val audio = entry.audio
                            if (audio != null) {
                                OnlineTrackRow(
                                    title = entry.title,
                                    coverUrl = viewModel.coverUrl,
                                    supporting = trackSupporting(
                                        context,
                                        artist = facts.circle.ifBlank { facts.sourceId },
                                        durationMs = audio.durationMs,
                                        downloaded = viewModel.playDownloaded(audio.fileKey),
                                        hasSubtitle = false,
                                        source = "DLsite",
                                    ),
                                    onClick = { viewModel.playPlay(entry) },
                                )
                            } else {
                                OnlineRemoteFileRow(entry.title) {
                                    if (viewModel.openPlayFile(entry)) onOpenRemotePreview()
                                }
                            }
                        }
                    } else if (viewModel.usingLibrary) {
                        items(viewModel.libraryFolders, key = { "folder-${it.id}" }) { folder ->
                            OnlineFolderRow(folder.name, folder.coverUri, folder.asmrSourceId != null) {
                                viewModel.openLibraryFolder(folder)
                            }
                        }
                        items(viewModel.libraryEntries, key = { entry ->
                            when (entry) {
                                is LibraryEntry.Audio -> "track-${entry.track.id}"
                                is LibraryEntry.File -> "file-${entry.file.id}"
                            }
                        }) { entry ->
                            when (entry) {
                                is LibraryEntry.Audio -> {
                                    OnlineTrackRow(
                                        title = entry.track.title,
                                        coverUrl = entry.track.coverUri,
                                        supporting = trackSupporting(context, entry.track, entry.hasSubtitle),
                                        onClick = { viewModel.playLibrary(entry.track) },
                                    )
                                }
                                is LibraryEntry.File -> OnlineFileRow(entry.file) {
                                    onOpenPreview(entry.file.id)
                                }
                            }
                        }
                    } else {
                        itemsIndexed(viewModel.nodeFolders, key = { index, node -> "node-folder-$index-${node.hash}-${node.title}" }) { _, node ->
                            OnlineFolderRow(node.title, null, work = false) { viewModel.openNode(node) }
                        }
                        itemsIndexed(viewModel.nodeFiles, key = { index, node -> "node-file-$index-${node.hash}-${node.title}" }) { _, node ->
                            if (isAsmrAudio(node.type, node.title)) {
                                val savedFile = viewModel.asmrDownloaded(node)
                                OnlineTrackRow(
                                    title = node.title,
                                    coverUrl = viewModel.coverUrl,
                                    supporting = trackSupporting(
                                        context,
                                        artist = facts.circle.ifBlank { facts.sourceId },
                                        durationMs = node.durationMs,
                                        downloaded = savedFile,
                                        hasSubtitle = viewModel.nodeHasSubtitle(node),
                                    ),
                                    onClick = { viewModel.playNode(node) },
                                )
                            } else {
                                OnlineRemoteFileRow(node.title) {
                                    if (viewModel.openNodeFile(node)) onOpenRemotePreview()
                                }
                            }
                        }
                    }
                    if (foldersEmpty && filesEmpty) {
                        item(key = "empty") {
                            Box(Modifier.fillMaxWidth().padding(24.dp)) {
                                EmptyState(
                                title = stringResource(R.string.library_empty_folder_title),
                                message = stringResource(R.string.library_work_folder_empty_message),
                            )
                            }
                        }
                    }
                }
                }
            }
        }
    }
    if (targetFolderId != null) {
        ExtendedFloatingActionButton(
            onClick = { onOpenLibraryWork(targetFolderId) },
            expanded = true,
            icon = { Icon(Icons.Default.LibraryMusic, contentDescription = null) },
            text = { Text(stringResource(R.string.online_open_in_library)) },
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    } else if (canSave) {
        ExtendedFloatingActionButton(
            onClick = { if (!viewModel.working) viewModel.save() },
            expanded = true,
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text(stringResource(R.string.common_add_to_library)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
    }
}

@Composable
private fun OnlineFolderRow(name: String, coverUrl: String?, work: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(name) },
        supportingContent = { Text(stringResource(if (work) R.string.online_work_title else R.string.online_folder)) },
        leadingContent = {
            if (work) CoverImage(coverUrl) else Icon(Icons.Default.Folder, contentDescription = null)
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun OnlineTrackRow(
    title: String,
    coverUrl: String?,
    supporting: String,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(supporting) },
        leadingContent = { CoverImage(coverUrl) },
        trailingContent = trailing,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun OnlineFileRow(file: LibraryFileEntity, onClick: () -> Unit) {
    val icon = when (file.kind) {
        FileKind.IMAGE -> Icons.Default.Image
        FileKind.TEXT -> Icons.Default.Description
        FileKind.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
    val label = when (file.kind) {
        FileKind.IMAGE -> stringResource(R.string.file_kind_image)
        FileKind.TEXT -> stringResource(R.string.file_kind_text)
        FileKind.OTHER -> stringResource(R.string.file_kind_other)
    }
    ListItem(
        headlineContent = { Text(file.name) },
        supportingContent = { Text(label) },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun OnlineRemoteFileRow(name: String, onClick: () -> Unit) {
    val kind = previewKind(name)
    val icon = when (kind) {
        FileKind.IMAGE -> Icons.Default.Image
        FileKind.TEXT -> Icons.Default.Description
        FileKind.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
    val label = when (kind) {
        FileKind.IMAGE -> stringResource(R.string.file_kind_image)
        FileKind.TEXT -> stringResource(R.string.file_kind_text)
        FileKind.OTHER -> stringResource(R.string.file_kind_other)
    }
    ListItem(
        headlineContent = { Text(name) },
        supportingContent = { Text(label) },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

private fun trackSupporting(context: android.content.Context, track: TrackEntity, hasSubtitle: Boolean): String {
    val artist = track.artist?.takeIf { it.isNotBlank() } ?: when (track.source) {
        TrackSource.YOUTUBE -> "YouTube"
        TrackSource.BILIBILI -> context.getString(R.string.library_badge_bilibili)
        TrackSource.ASMR -> "asmr.one"
        TrackSource.DLSITE -> "DLsite"
        TrackSource.JAPANESE_ASMR -> "Japanese ASMR"
        TrackSource.LOCAL -> context.getString(R.string.online_source_local)
    }
    val source = when (track.source) {
        TrackSource.YOUTUBE -> "YouTube"
        TrackSource.BILIBILI -> context.getString(R.string.library_badge_bilibili)
        TrackSource.ASMR -> "asmr.one"
        TrackSource.DLSITE -> "DLsite"
        TrackSource.JAPANESE_ASMR -> "Japanese ASMR"
        TrackSource.LOCAL -> context.getString(R.string.online_source_local)
    }
    return trackSupporting(
        context,
        artist = artist,
        durationMs = track.durationMs,
        downloaded = track.source in setOf(TrackSource.ASMR, TrackSource.DLSITE, TrackSource.JAPANESE_ASMR) &&
            com.nanahoshi.audioplayer.data.LocalAudio.hasStoredCopy(track.localUri),
        hasSubtitle = hasSubtitle,
        source = source,
    )
}

private fun trackSupporting(
    context: android.content.Context,
    artist: String,
    durationMs: Long,
    downloaded: Boolean,
    hasSubtitle: Boolean,
    source: String = "asmr.one",
): String {
    val mark = if (downloaded) context.getString(R.string.online_track_downloaded_suffix) else ""
    val subtitle = if (hasSubtitle) context.getString(R.string.online_track_subtitle_suffix) else ""
    return "$artist · $source · ${formatDuration(durationMs)}$mark$subtitle"
}

@Composable
private fun GalleryBlock(urls: List<String>, revealed: Boolean, onReveal: () -> Unit, onOpen: (Int) -> Unit) {
    if (urls.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(stringResource(R.string.online_gallery))
        if (!revealed) {
            TextButton(onClick = onReveal, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.online_load_images)) }
        } else {
            val context = LocalContext.current
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            ) {
                items(urls, key = { it }) { url ->
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(url.toHttps())
                            .addHeader("Referer", DlsiteClient.STORE_REFERER)
                            .addHeader("User-Agent", DlsiteClient.USER_AGENT)
                            .build(),
                        contentDescription = stringResource(R.string.a11y_sample_image, urls.indexOf(url) + 1),
                        modifier = Modifier.height(140.dp).width(186.dp).clip(RoundedCornerShape(12.dp)).clickable { onOpen(urls.indexOf(url)) },
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrialBlock(
    trials: List<com.nanahoshi.audioplayer.dlsite.RemotePlayable>,
    onPlay: (com.nanahoshi.audioplayer.dlsite.RemotePlayable) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(stringResource(R.string.online_preview))
        if (trials.isEmpty()) {
            Text(
                stringResource(R.string.online_no_preview),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            trials.forEach { clip ->
                ListItem(
                    headlineContent = { Text(clip.title) },
                    supportingContent = { Text(stringResource(R.string.online_preview_item)) },
                    modifier = Modifier.clickable { onPlay(clip) },
                )
            }
        }
    }
}

class MineViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
    var cookie by mutableStateOf("")
    var loggedIn by mutableStateOf(graph.dlsite.hasSession())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var webLoginVisible by mutableStateOf(false)
        private set
    var webLoginLoading by mutableStateOf(false)
        private set
    var webLoginError by mutableStateOf<String?>(null)
        private set
    private var webLoginJob: Job? = null
    var biliReady by mutableStateOf(graph.credentials.hasCookie())
        private set
    var biliCanRefresh by mutableStateOf(graph.credentials.hasBiliRefresh())
        private set
    val biliRefreshFailure = graph.credentials.biliRefreshFailure
    var qrVisible by mutableStateOf(false)
        private set
    var qrImage by mutableStateOf<Bitmap?>(null)
        private set
    var qrMessage by mutableStateOf("")
        private set
    var qrExpired by mutableStateOf(false)
        private set
    var qrFailed by mutableStateOf(false)
        private set
    private var qrJob: Job? = null
    private var qrGeneration = 0

    fun openWebLogin() {
        webLoginError = null
        webLoginVisible = true
    }

    fun dismissWebLogin() {
        webLoginJob?.cancel()
        webLoginJob = null
        webLoginVisible = false
        webLoginLoading = false
        webLoginError = null
    }

    fun completeWebLogin(cookie: String) {
        if (webLoginLoading) return
        webLoginJob?.cancel()
        webLoginJob = viewModelScope.launch {
            webLoginLoading = true
            webLoginError = null
            try {
                graph.dlsite.loginWithCookie(cookie, loadPurchases = false)
                loggedIn = true
                error = null
                webLoginVisible = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                webLoginError = getApplication<Application>().resolveUserError(failure, R.string.online_login_failed)
            } finally {
                webLoginLoading = false
                webLoginJob = null
            }
        }
    }

    fun loginCookie() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                graph.dlsite.loginWithCookie(cookie)
                cookie = ""
                loggedIn = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = getApplication<Application>().resolveUserError(failure, R.string.online_login_failed)
            } finally {
                loading = false
            }
        }
    }

    fun logout() {
        graph.dlsite.logout()
        clearDlsiteWebCookies()
        loggedIn = false
    }

    fun savedBiliCookie(): String = graph.credentials.cookie().orEmpty()

    fun saveBili(value: String) {
        graph.credentials.saveCookie(value)
        biliReady = graph.credentials.hasCookie()
        biliCanRefresh = graph.credentials.hasBiliRefresh()
    }

    fun clearBili() {
        graph.credentials.clearCookie()
        biliReady = false
        biliCanRefresh = false
    }

    fun startBiliQr() {
        qrJob?.cancel()
        val generation = ++qrGeneration
        qrVisible = true
        qrImage = null
        qrExpired = false
        qrFailed = false
        qrMessage = getApplication<Application>().getString(R.string.mine_qr_getting)
        qrJob = viewModelScope.launch {
            try {
                val login = graph.bilibili.requestQrLogin()
                if (generation != qrGeneration) return@launch
                qrImage = qrBitmap(login.url)
                qrMessage = getApplication<Application>().getString(R.string.mine_qr_scan_hint)
                while (isActive) {
                    delay(2_000)
                    val state = graph.bilibili.pollQrLogin(login.key)
                    if (state == QrPoll.Confirmed) {
                        biliReady = true
                        biliCanRefresh = graph.credentials.hasBiliRefresh()
                        if (generation == qrGeneration) {
                            qrVisible = false
                            qrImage = null
                        }
                        return@launch
                    }
                    if (generation != qrGeneration) return@launch
                    when (state) {
                        QrPoll.Waiting -> Unit
                        QrPoll.Scanned -> qrMessage = getApplication<Application>().getString(R.string.mine_qr_scanned)
                        QrPoll.Expired -> {
                            qrExpired = true
                            qrMessage = getApplication<Application>().getString(R.string.mine_qr_expired)
                            return@launch
                        }
                        QrPoll.Confirmed -> Unit
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation != qrGeneration) return@launch
                qrFailed = true
                qrMessage = if (failure is BilibiliException) {
                    getApplication<Application>().resolveUserError(failure, R.string.online_qr_login_failed)
                } else {
                    getApplication<Application>().getString(R.string.online_bilibili_connection_failed)
                }
            }
        }
    }

    fun stopBiliQr() {
        qrGeneration++
        qrJob?.cancel()
        qrJob = null
        qrVisible = false
        qrImage = null
        qrExpired = false
        qrFailed = false
    }
}

class PurchasesViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
    var purchases by mutableStateOf<List<com.nanahoshi.audioplayer.dlsite.DlsitePurchase>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    val hasDlsiteSession: Boolean get() = graph.dlsite.hasSession()

    init { if (hasDlsiteSession) reload() else loading = false }

    fun reload() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                purchases = graph.dlsite.purchases()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = getApplication<Application>().resolveUserError(failure, R.string.online_purchases_read_failed)
            } finally {
                loading = false
            }
        }
    }
}

@Composable
fun MineScreen(
    onOpenPurchases: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenYouTubeList: (String) -> Unit,
    viewModel: MineViewModel = viewModel(),
) {
    var biliHelp by remember { mutableStateOf(false) }
    var sessdataLogin by remember { mutableStateOf(false) }
    var confirmDlsiteLogout by remember { mutableStateOf(false) }
    var confirmBiliClear by remember { mutableStateOf(false) }
    val biliRefreshFailed by viewModel.biliRefreshFailure.collectAsStateWithLifecycle()
    if (viewModel.qrVisible) {
        BiliQrDialog(
            image = viewModel.qrImage,
            message = viewModel.qrMessage,
            expired = viewModel.qrExpired,
            failed = viewModel.qrFailed,
            onRefresh = viewModel::startBiliQr,
            onDismiss = viewModel::stopBiliQr,
        )
    }
    if (biliHelp) {
        BiliLoginHelpDialog(onDismiss = { biliHelp = false })
    }
    if (sessdataLogin) {
        BiliSessdataDialog(
            saved = viewModel.savedBiliCookie(),
            scanLogin = viewModel.biliCanRefresh,
            onSave = { value ->
                viewModel.saveBili(value)
                sessdataLogin = false
            },
            onDismiss = { sessdataLogin = false },
        )
    }
    if (viewModel.webLoginVisible) {
        DlsiteWebLoginDialog(
            loading = viewModel.webLoginLoading,
            error = viewModel.webLoginError,
            onComplete = viewModel::completeWebLogin,
            onDismiss = viewModel::dismissWebLogin,
        )
    }
    var cookieOpen by remember { mutableStateOf(false) }
    var showCookie by rememberSaveable { mutableStateOf(false) }
    val reduceCookieMotion = LocalReduceMotion.current
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(key = "dlsite") {
            AccountCard(title = "DLsite", loggedIn = viewModel.loggedIn) {
                if (!viewModel.loggedIn) {
                    Button(
                        onClick = viewModel::openWebLogin,
                        enabled = !viewModel.loading,
                        modifier = Modifier.padding(top = 12.dp),
                    ) { Text(stringResource(R.string.mine_dlsite_web_login)) }
                    Text(
                        stringResource(R.string.mine_dlsite_web_login_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    TextButton(onClick = { cookieOpen = !cookieOpen }) {
                        Text(stringResource(if (cookieOpen) R.string.mine_cookie_collapse else R.string.mine_cookie_use))
                    }
                    AnimatedVisibility(
                        visible = cookieOpen,
                        enter = if (reduceCookieMotion) EnterTransition.None else fadeIn() + expandVertically(),
                        exit = if (reduceCookieMotion) ExitTransition.None else fadeOut() + shrinkVertically(),
                    ) {
                        Column {
                            OutlinedTextField(
                                value = viewModel.cookie,
                                onValueChange = { viewModel.cookie = it },
                                label = { Text(stringResource(R.string.mine_dlsite_cookie_label)) },
                                visualTransformation = if (showCookie) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { showCookie = !showCookie }) {
                                        Icon(
                                            if (showCookie) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = stringResource(
                                                if (showCookie) R.string.a11y_hide_password else R.string.a11y_show_password,
                                            ),
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            TextButton(
                                onClick = viewModel::loginCookie,
                                enabled = !viewModel.loading,
                            ) { Text(stringResource(R.string.mine_cookie_login)) }
                        }
                    }
                } else {
                    Button(
                        onClick = onOpenPurchases,
                        enabled = !viewModel.loading,
                        modifier = Modifier.padding(top = 12.dp),
                    ) { Text(stringResource(R.string.mine_view_purchases)) }
                    TextButton(onClick = viewModel::openWebLogin) { Text(stringResource(R.string.mine_relogin)) }
                    TextButton(onClick = { confirmDlsiteLogout = true }) { Text(stringResource(R.string.mine_logout)) }
                }
            }
        }
        viewModel.error?.let { message ->
            item(key = "dlsite-error") {
                Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
        }
        item(key = "youtube") {
            com.nanahoshi.audioplayer.ui.youtube.YouTubeAccountCard(onOpenYouTubeList)
        }
        item(key = "bilibili") {
            val biliBadge = when {
                !viewModel.biliReady -> stringResource(R.string.mine_bili_not_logged_in)
                viewModel.biliCanRefresh -> stringResource(R.string.mine_bili_badge_logged_in)
                else -> stringResource(R.string.mine_bili_badge_saved)
            }
            AccountCard(title = stringResource(R.string.library_badge_bilibili), loggedIn = viewModel.biliReady, badgeText = biliBadge) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            !viewModel.biliReady -> stringResource(R.string.mine_bili_not_logged_in_message)
                            viewModel.biliCanRefresh && biliRefreshFailed -> stringResource(R.string.mine_bili_refresh_failed)
                            viewModel.biliCanRefresh -> stringResource(R.string.mine_bili_qr_logged_in)
                            else -> stringResource(R.string.mine_bili_saved)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { biliHelp = true }) {
                        Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = stringResource(R.string.a11y_bili_login_help))
                    }
                }
                if (viewModel.biliReady) {
                    Button(onClick = onOpenFavorites, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.mine_view_favorites)) }
                    TextButton(onClick = viewModel::startBiliQr) { Text(stringResource(R.string.mine_qr_login)) }
                } else {
                    Button(onClick = viewModel::startBiliQr, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.mine_qr_login)) }
                    TextButton(onClick = onOpenFavorites, enabled = false) { Text(stringResource(R.string.mine_login_before_favorites)) }
                }
                TextButton(onClick = { sessdataLogin = true }) { Text(stringResource(R.string.mine_paste_login)) }
                TextButton(onClick = { confirmBiliClear = true }) { Text(stringResource(R.string.mine_logout)) }
            }
        }
    }
    if (confirmDlsiteLogout) {
        ConfirmActionDialog(
            title = stringResource(R.string.mine_dlsite_logout_title),
            message = stringResource(R.string.mine_dlsite_logout_message),
            confirmLabel = stringResource(R.string.mine_logout),
            onConfirm = { confirmDlsiteLogout = false; viewModel.logout() },
            onDismiss = { confirmDlsiteLogout = false },
        )
    }
    if (confirmBiliClear) {
        ConfirmActionDialog(
            title = stringResource(R.string.mine_bili_logout_title),
            message = stringResource(
                if (viewModel.biliCanRefresh) R.string.mine_bili_logout_message_qr
                else R.string.mine_bili_logout_message_cookie,
            ),
            confirmLabel = stringResource(R.string.mine_logout),
            onConfirm = { confirmBiliClear = false; viewModel.clearBili() },
            onDismiss = { confirmBiliClear = false },
        )
    }
}

@Composable
private fun BiliLoginHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mine_bili_login_title)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.mine_bili_login_help))
                Text(
                    stringResource(R.string.mine_bili_login_help_qr),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}

@Composable
private fun BiliSessdataDialog(
    saved: String,
    scanLogin: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(saved) }
    var confirming by remember { mutableStateOf(false) }
    var showSecret by rememberSaveable { mutableStateOf(false) }
    fun submit() {
        val value = text.trim()
        if (value.isEmpty()) return
        if (scanLogin && value != saved.trim()) {
            confirming = true
        } else {
            onSave(value)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mine_paste_login_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.mine_cookie_label)) },
                visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showSecret = !showSecret }) {
                        Icon(
                            if (showSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = stringResource(
                                if (showSecret) R.string.a11y_hide_password else R.string.a11y_show_password,
                            ),
                        )
                    }
                },
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = ::submit, enabled = text.isNotBlank()) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.mine_replace_login_title)) },
            text = {
                Text(stringResource(R.string.mine_replace_login_message))
            },
            confirmButton = {
                TextButton(onClick = { onSave(text.trim()) }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun BiliQrDialog(
    image: Bitmap?,
    message: String,
    expired: Boolean,
    failed: Boolean,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val canRetry = expired || failed
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mine_qr_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (image != null) {
                    // 白色底与静区保证二维码在深色主题下仍可被 B 站 App 识别。
                    Surface(
                        color = Color.White,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Box(Modifier.padding(12.dp)) {
                            Image(
                                bitmap = image.asImageBitmap(),
                                contentDescription = stringResource(R.string.a11y_bili_qr),
                                modifier = Modifier.size(220.dp),
                            )
                        }
                    }
                } else if (!canRetry) {
                    Box(Modifier.size(244.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(48.dp))
                    }
                }
                if (message.isNotBlank()) {
                    Text(message, modifier = Modifier.padding(top = 12.dp))
                }
            }
        },
        confirmButton = {
            if (canRetry) {
                TextButton(onClick = onRefresh) { Text(stringResource(R.string.mine_qr_refresh)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
fun PurchasesScreen(
    onBack: () -> Unit,
    onOpenWork: (String) -> Unit,
    viewModel: PurchasesViewModel = viewModel(),
) {
    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            title = stringResource(R.string.mine_purchases_title),
            onBack = onBack,
            actions = {
                IconButton(
                    onClick = viewModel::reload,
                    enabled = !viewModel.loading && viewModel.hasDlsiteSession,
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.a11y_refresh))
                }
            },
        )
        when {
            viewModel.loading && viewModel.purchases.isEmpty() -> LoadingState(
                stringResource(R.string.mine_purchases_loading),
                Modifier.weight(1f).fillMaxWidth(),
            )
            viewModel.error != null && viewModel.purchases.isEmpty() -> ErrorState(
                viewModel.error!!,
                onRetry = viewModel::reload,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            viewModel.purchases.isEmpty() -> EmptyState(
                title = stringResource(R.string.online_empty_purchases_title),
                message = stringResource(
                    if (viewModel.hasDlsiteSession) R.string.online_empty_purchases_message
                    else R.string.online_purchases_login,
                ),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            else -> LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                if (viewModel.loading) {
                    item(key = "loading") { LoadingState(stringResource(R.string.mine_purchases_loading)) }
                } else if (viewModel.error != null) {
                    item(key = "error") { ErrorState(viewModel.error!!, viewModel::reload) }
                }
                items(viewModel.purchases, key = { it.workno }) { item ->
                    WorkCard(
                        facts = WorkFacts(
                            title = item.title,
                            circle = item.circle,
                            vas = item.vas,
                            tags = emptyList(),
                            release = item.release,
                            sourceId = item.workno,
                        ),
                        coverUrl = item.coverUrl,
                        onClick = { onOpenWork(item.workno) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

private fun Context.onMeteredNetwork(): Boolean {
    val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = manager.activeNetwork ?: return true
    val caps = manager.getNetworkCapabilities(network) ?: return true
    return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}

private fun AsmrCatalogItem.isStub(): Boolean {
    val id = com.nanahoshi.audioplayer.dlsite.DlsiteClient.canonicalWorkno(sourceId)
    val titleIsId = title.isBlank() || com.nanahoshi.audioplayer.dlsite.DlsiteClient.canonicalWorkno(title) == id
    return titleIsId && circle.isBlank() && vas.isEmpty() && tags.isEmpty() && release.isBlank()
}

private fun com.nanahoshi.audioplayer.dlsite.DlsiteHit.toHit() = OnlineHit(
    sourceId = workno,
    title = title,
    circle = circle,
    vas = vas,
    tags = tags,
    release = release,
    coverUrl = coverUrl,
    fromAsmr = false,
)

private fun AsmrCatalogItem.toHit() = OnlineHit(
    sourceId = sourceId,
    title = title,
    circle = circle,
    vas = vas,
    tags = tags,
    release = release,
    coverUrl = coverUrl,
)
