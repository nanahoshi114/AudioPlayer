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

    val hasMore: Boolean
        get() = if (usePopular() || shouldSkipDlsiteSearch(text, pieces)) {
            asmrPaging.hasMore
        } else {
            asmrPaging.hasMore || dlsitePaging.hasMore
        }

    fun loadMore() {
        if (loading) return
        // 有错误时即使两来源都读完也允许重试（例如合并阶段的异常），成功后错误自动清除。
        if (!hasMore && error == null) return
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
            val skipDlsite = popular || shouldSkipDlsiteSearch(text, pieces)
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
                        if (skipDlsite || (!dlsitePaging.hasMore && !reset)) {
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
