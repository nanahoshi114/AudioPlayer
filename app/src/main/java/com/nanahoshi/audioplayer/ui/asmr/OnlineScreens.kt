package com.nanahoshi.audioplayer.ui.asmr

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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import com.nanahoshi.audioplayer.ui.CoverImage
import com.nanahoshi.audioplayer.ui.EmptyHint
import com.nanahoshi.audioplayer.ui.components.AppTopBar
import com.nanahoshi.audioplayer.ui.components.EmptyState
import com.nanahoshi.audioplayer.ui.components.LabelBadge
import com.nanahoshi.audioplayer.ui.components.LoadingState
import com.nanahoshi.audioplayer.ui.components.SectionHeader
import com.nanahoshi.audioplayer.ui.components.WorkHeader
import com.nanahoshi.audioplayer.ui.components.SkeletonWorkList
import com.nanahoshi.audioplayer.ui.formatDuration
import com.nanahoshi.audioplayer.ui.theme.LocalReduceMotion
import com.nanahoshi.audioplayer.ui.theme.LocalSourceColors
import com.nanahoshi.audioplayer.ui.theme.Motion
import com.nanahoshi.audioplayer.ui.library.LibraryEntry
import com.nanahoshi.audioplayer.ui.library.mixedEntries
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

enum class OnlineSort(val label: String, val order: String, val direction: String) {
    RELEASE_DESC("发售日", "release", "desc"),
    NEWEST("最新收录", "create_date", "desc"),
    RELEASE_ASC("发售日-正序", "release", "asc"),
    SALES("销量", "dl_count", "desc"),
    PRICE_ASC("价格-正序", "price", "asc"),
    PRICE_DESC("价格", "price", "desc"),
    RATING("评分", "rate_average_2dp", "desc"),
    REVIEWS("评价数", "review_count", "desc"),
    RJ_DESC("RJ号", "id", "desc"),
    RJ_ASC("RJ号-正序", "id", "asc"),
    RANDOM("随机", "random", "desc"),
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

enum class PieceKind(val label: String, val key: String) {
    TAG("标签", "tag"),
    VA("声优", "va"),
    CIRCLE("社团", "circle"),
}

data class SearchPiece(val kind: PieceKind, val name: String, val genreId: String? = null) {
    fun token(): String = "$${kind.key}:$name$"
    fun caption(): String = "${kind.label}:$name"
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
    private var page = 1
    private var asmrHasMore = true
    private var dlsiteHasMore = true
    private var job: Job? = null

    init {
        reload()
    }

    fun usePopular(): Boolean = text.isBlank() && pieces.isEmpty() && !allAges && sort == null

    fun sortLabel(): String = when {
        usePopular() -> "热门"
        sort != null -> sort!!.label
        else -> OnlineSort.SALES.label
    }

    fun summary(): String {
        val parts = buildList {
            if (text.isNotBlank()) add(text.trim())
            pieces.forEach { add(it.caption()) }
        }
        return parts.joinToString(" ")
    }

    fun applyQuery(nextText: String, nextPieces: List<SearchPiece>) {
        text = nextText
        pieces = nextPieces
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
        val more = if (usePopular()) asmrHasMore else asmrHasMore || dlsiteHasMore
        if (!more) return
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
        val next = viewModelScope.launch {
            loading = true
            if (reset) {
                error = null
                asmrHasMore = true
                dlsiteHasMore = true
            }
            val nextPage = if (reset) 1 else page + 1
            val popular = usePopular()
            try {
                val (asmrResult, dlsiteResult) = coroutineScope {
                    val asmrCall = async {
                        if (!asmrHasMore && !reset) {
                            null
                        } else {
                            catchPage {
                                if (popular) {
                                    graph.asmr.popular(nextPage, subtitleOnly)
                                } else {
                                    val chosen = sort ?: OnlineSort.SALES
                                    graph.asmr.search(keyword(), chosen.order, chosen.direction, nextPage, subtitleOnly)
                                }
                            }
                        }
                    }
                    val dlsiteCall = async {
                        if (popular || (!dlsiteHasMore && !reset)) {
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
                                    page = nextPage,
                                )
                            }
                        }
                    }
                    asmrCall.await() to dlsiteCall.await()
                }
                val asmrPage = asmrResult?.getOrNull()
                val dlsitePage = dlsiteResult?.getOrNull()
                if (asmrPage != null) {
                    asmrHasMore = asmrPage.works.size >= 20 && (asmrPage.page * 20) < asmrPage.total
                } else if (asmrResult != null) {
                    asmrHasMore = false
                }
                if (dlsitePage != null) {
                    dlsiteHasMore = dlsitePage.fetched >= 20 && (nextPage * 20) < dlsitePage.total
                } else if (dlsiteResult != null) {
                    dlsiteHasMore = false
                }
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
                works = markListed(merged.values.toList())
                page = nextPage
                if (works.isEmpty()) {
                    error = asmrResult?.exceptionOrNull()?.message
                        ?: dlsiteResult?.exceptionOrNull()?.message
                        ?: "没有作品。"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (reset) works = emptyList()
                error = failure.message ?: "暂时无法连接"
            } finally {
                if (job === coroutineContext[Job]) loading = false
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
                        is CatalogLookup.Found -> if (lookup.item.isStub()) hit else lookup.item.toHit()
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

    private fun dedupeKey(sourceId: String): String {
        val canonical = com.nanahoshi.audioplayer.dlsite.DlsiteClient.canonicalWorkno(sourceId)
        val digits = canonical.filter { it.isDigit() }
        return if (digits.isNotEmpty() && canonical.all { it.isDigit() }) "RJ$digits" else canonical
    }
}

enum class WorkFileSource {
    PLAY,
    ASMR,
}

@OptIn(ExperimentalCoroutinesApi::class)
class OnlineWorkViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
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
    var saved by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var working by mutableStateOf(false)
        private set
    var atWorkRoot by mutableStateOf(true)
        private set
    var folderTitle by mutableStateOf("")
        private set
    var usingLibrary by mutableStateOf(false)
        private set
    var filesFromPlay by mutableStateOf(false)
        private set
    var libraryFolders by mutableStateOf<List<FolderEntity>>(emptyList())
        private set
    var libraryEntries by mutableStateOf<List<LibraryEntry>>(emptyList())
        private set
    var nodeFolders by mutableStateOf<List<AsmrNode>>(emptyList())
        private set
    var nodeFiles by mutableStateOf<List<AsmrNode>>(emptyList())
        private set

    private var sourceKey = ""
    private var rootFolderId: Long? = null
    private val libraryStack = mutableListOf<Long>()
    private val nodeStack = mutableListOf<AsmrNode>()
    private val playStack = mutableListOf<com.nanahoshi.audioplayer.dlsite.PlayEntry>()
    private var playRoot = emptyList<com.nanahoshi.audioplayer.dlsite.PlayEntry>()
    private val browseId = MutableStateFlow<Long?>(null)
    private var sortMode = LibrarySort.ADDED
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            graph.librarySort.sort.collect { mode ->
                sortMode = mode
                if (showPlay) publishPlay() else if (!usingLibrary) publishNodes()
            }
        }
        viewModelScope.launch {
            browseId.flatMapLatest { id ->
                if (id == null) {
                    kotlinx.coroutines.flow.flowOf(null)
                } else {
                    combine(
                        graph.library.observeChildFolders(id),
                        graph.library.observeTracksIn(id),
                        graph.library.observeFilesIn(id),
                        graph.library.observeFolder(id),
                        graph.librarySort.sort,
                    ) { folders, tracks, files, folder, sort ->
                        LibraryBrowse(
                            id = id,
                            title = folder?.name.orEmpty(),
                            folders = folders.sortedForLibrary(sort),
                            entries = mixedEntries(tracks, files, sort),
                        )
                    }
                }
            }.collect { browse ->
                if (browse == null || !usingLibrary) return@collect
                libraryFolders = browse.folders
                libraryEntries = browse.entries
                if (browse.entries.any { it is LibraryEntry.Audio && it.track.source == TrackSource.DLSITE }) {
                    filesFromPlay = true
                }
                folderTitle = browse.title
                atWorkRoot = browse.id == rootFolderId
            }
        }
    }

    fun load(sourceId: String, metered: Boolean) {
        if (sourceKey == sourceId && facts != null) {
            refreshDownloads(sourceId)
            return
        }
        sourceKey = sourceId
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            busy = true
            error = null
            nodeStack.clear()
            playStack.clear()
            filesFromPlay = false
            fileSource = WorkFileSource.ASMR
            downloadedPlay = emptySet()
            downloadedAsmr = emptySet()
            allowGallery = !metered
            try {
                var missing = false
                val loaded = try {
                    graph.asmr.loadWork(sourceId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    missing = failure.message == "未找到该作品"
                    if (!missing) graph.messages.value = failure.message ?: "暂时无法连接 asmr.one"
                    null
                }
                val page = try {
                    graph.dlsite.showcase(sourceId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                work = loaded
                val pageFacts = page?.facts()
                val nextFacts = loaded?.facts() ?: pageFacts
                if (nextFacts == null) {
                    facts = null
                    error = "没有读到作品。"
                    return@launch
                }
                facts = nextFacts
                dlsiteSite = page?.siteId?.filter { it.isLetter() }?.ifBlank { null } ?: "maniax"
                coverUrl = loaded?.coverUrl ?: page?.coverUrl
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
                val existing = graph.library.folderBySource(nextFacts.sourceId)
                saved = existing != null
                if (purchased) {
                    fileSource = WorkFileSource.PLAY
                    showPlay = true
                    usingLibrary = false
                    browseId.value = null
                    playRoot = try {
                        graph.dlsite.playTree(nextFacts.sourceId, nextFacts.circle, coverUrl)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        graph.messages.value = failure.message ?: "无法读取已购买文件"
                        emptyList()
                    }
                    atWorkRoot = true
                    folderTitle = ""
                    publishPlay()
                } else if (loaded != null && existing != null) {
                    showPlay = false
                    val workId = existing.asmrWorkId
                    if (workId != null) {
                        filesFromPlay = graph.library.observeTracks().first()
                            .any { it.asmrWorkId == workId && it.source == TrackSource.DLSITE }
                    }
                    showLibrary(existing.id)
                } else if (loaded != null) {
                    showPlay = false
                    usingLibrary = false
                    rootFolderId = null
                    libraryStack.clear()
                    browseId.value = null
                    atWorkRoot = true
                    folderTitle = ""
                    publishNodes()
                } else {
                    showPlay = false
                    usingLibrary = false
                }
                refreshDownloads(nextFacts.sourceId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "暂时无法连接"
            } finally {
                busy = false
            }
        }
    }

    fun useFileSource(source: WorkFileSource) {
        if (source == WorkFileSource.PLAY && !purchased) return
        fileSource = source
        playStack.clear()
        nodeStack.clear()
        atWorkRoot = true
        folderTitle = ""
        if (source == WorkFileSource.PLAY) {
            showPlay = true
            usingLibrary = false
            browseId.value = null
            publishPlay()
        } else {
            showPlay = false
            usingLibrary = false
            browseId.value = null
            publishNodes()
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

    fun canGoUp(): Boolean = when {
        showPlay -> playStack.isNotEmpty()
        usingLibrary -> libraryStack.size > 1
        else -> nodeStack.isNotEmpty()
    }

    fun up() {
        if (!canGoUp()) return
        when {
            showPlay -> {
                playStack.removeAt(playStack.lastIndex)
                atWorkRoot = playStack.isEmpty()
                folderTitle = playStack.lastOrNull()?.title.orEmpty()
                publishPlay()
            }
            usingLibrary -> {
                libraryStack.removeAt(libraryStack.lastIndex)
                browseId.value = libraryStack.last()
            }
            else -> {
                nodeStack.removeAt(nodeStack.lastIndex)
                atWorkRoot = nodeStack.isEmpty()
                folderTitle = nodeStack.lastOrNull()?.title.orEmpty()
                publishNodes()
            }
        }
    }

    fun openLibraryFolder(folder: FolderEntity) {
        libraryStack.add(folder.id)
        browseId.value = folder.id
    }

    fun openNode(node: AsmrNode) {
        nodeStack.add(node)
        atWorkRoot = false
        folderTitle = node.title
        publishNodes()
    }

    fun playLibrary(track: TrackEntity) {
        viewModelScope.launch {
            val folderId = browseId.value ?: return@launch
            val tracks = graph.library.tracksIn(folderId).sortedForLibrary(graph.librarySort.sort.value)
            val index = tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            graph.player.replaceAndPlay(tracks.ifEmpty { listOf(track) }, index)
        }
    }

    fun playNode(node: AsmrNode) {
        val current = work ?: return
        val siblings = (if (nodeStack.isEmpty()) current.nodes else nodeStack.last().children)
            .filter { isAsmrAudio(it.type, it.title) && !it.streamUrl.isNullOrBlank() }
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
                fileKey = sibling.hash,
            )
        }
        val index = siblings.indexOfFirst { it.hash == node.hash && it.title == node.title }.coerceAtLeast(0)
        if (items.isEmpty()) {
            graph.messages.value = "没有可播放的音频"
        } else {
            graph.player.replaceAndPlayRemote(items, index)
        }
    }

    fun openNodeFile(node: AsmrNode): Boolean {
        val url = node.streamUrl
        if (url.isNullOrBlank()) {
            graph.messages.value = "无法打开这个文件"
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
        playStack.add(entry)
        atWorkRoot = false
        folderTitle = entry.title
        publishPlay()
    }

    fun playPlay(entry: com.nanahoshi.audioplayer.dlsite.PlayEntry) {
        val siblings = (if (playStack.isEmpty()) playRoot else playStack.last().children).mapNotNull { it.audio }
        val index = siblings.indexOfFirst { it.remoteUrl == entry.audio?.remoteUrl }.coerceAtLeast(0)
        if (siblings.isEmpty()) {
            graph.messages.value = "没有可播放的音频"
        } else {
            graph.player.replaceAndPlayRemote(siblings, index)
        }
    }

    fun downloadPlay(entry: com.nanahoshi.audioplayer.dlsite.PlayEntry) {
        val audio = entry.audio ?: return
        if (playDownloaded(audio.fileKey)) {
            graph.messages.value = "已经下载"
            return
        }
        viewModelScope.launch {
            val added = graph.downloads.enqueueRemote(audio, audio.workno ?: facts?.sourceId.orEmpty())
            graph.messages.value = if (added) "已加入下载" else "已经在下载"
        }
    }

    fun downloadNode(node: AsmrNode) {
        val current = work ?: return
        if (asmrDownloaded(node)) {
            graph.messages.value = "已经下载"
            return
        }
        val url = node.streamUrl
        if (url.isNullOrBlank()) {
            graph.messages.value = "没有可下载的地址"
            return
        }
        val item = com.nanahoshi.audioplayer.dlsite.RemotePlayable(
            title = node.title,
            artist = current.circle.ifBlank { current.sourceId },
            coverUri = current.coverUrl,
            remoteUrl = url,
            source = TrackSource.ASMR,
            referer = com.nanahoshi.audioplayer.asmr.AsmrClient.REFERER,
            durationMs = node.durationMs,
            workno = current.sourceId,
            fileKey = node.hash.ifBlank { node.title },
        )
        viewModelScope.launch {
            val added = graph.downloads.enqueueRemote(item, current.sourceId)
            graph.messages.value = if (added) "已加入下载" else "已经在下载"
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
        if (saved) return
        val currentFacts = facts ?: return
        val current = work
        viewModelScope.launch {
            working = true
            try {
                if (purchased) {
                    graph.library.importPlayWork(currentFacts, playRoot, coverUrl)
                    saved = true
                    graph.messages.value = "已保存"
                } else if (current != null) {
                    graph.library.ensureAsmrWork(current)
                    val root = graph.library.folderBySource(current.sourceId) ?: return@launch
                    saved = true
                    showPlay = false
                    showLibrary(root.id)
                    graph.messages.value = "已保存"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (failure.message == "已经在音频库") {
                    saved = true
                    if (!purchased) {
                        graph.library.folderBySource(currentFacts.sourceId)?.let {
                            showPlay = false
                            showLibrary(it.id)
                        }
                    }
                }
                graph.messages.value = failure.message ?: "保存失败"
            } finally {
                working = false
            }
        }
    }

    private fun showLibrary(rootId: Long) {
        bindChain(listOf(rootId))
    }

    private fun bindChain(chain: List<Long>) {
        if (chain.isEmpty()) return
        rootFolderId = chain.first()
        usingLibrary = true
        libraryStack.clear()
        libraryStack.addAll(chain)
        browseId.value = chain.last()
        atWorkRoot = chain.size == 1
    }

    private fun publishPlay() {
        val nodes = if (playStack.isEmpty()) playRoot else playStack.last().children
        val folders = nodes.filter { it.children.isNotEmpty() }
        val files = nodes.filter { it.children.isEmpty() }
        when (sortMode) {
            LibrarySort.NAME -> {
                playFolders = folders.sortedBy { it.title.lowercase() }
                playFiles = files.sortedBy { it.title.lowercase() }
            }
            LibrarySort.ADDED -> {
                playFolders = folders.asReversed()
                playFiles = files.asReversed()
            }
        }
    }

    private fun publishNodes() {
        val nodes = if (nodeStack.isEmpty()) work?.nodes.orEmpty() else nodeStack.last().children
        val folders = nodes.filter { it.isContainer() }
        val files = nodes.filter { !it.isContainer() && !isSubtitleFile(it.title) }
        when (sortMode) {
            LibrarySort.NAME -> {
                nodeFolders = folders.sortedBy { it.title.lowercase() }
                nodeFiles = files.sortedBy { it.title.lowercase() }
            }
            LibrarySort.ADDED -> {
                nodeFolders = folders.asReversed()
                nodeFiles = files.asReversed()
            }
        }
    }
}

private data class LibraryBrowse(
    val id: Long,
    val title: String,
    val folders: List<FolderEntity>,
    val entries: List<LibraryEntry>,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OnlineSearchScreen(
    onOpenWork: (String) -> Unit,
    viewModel: OnlineSearchViewModel = viewModel(),
) {
    var composing by remember { mutableStateOf(false) }
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
            initialText = viewModel.text,
            initialPieces = viewModel.pieces,
            onBack = { composing = false },
            onSearch = { text, pieces ->
                viewModel.applyQuery(text, pieces)
                composing = false
            },
        )
    } else {
    val works = viewModel.works
    val listState = rememberLazyListState()
    LaunchedEffect(listState, works.size, viewModel.loading) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { index ->
                if (index >= works.lastIndex - 2) viewModel.loadMore()
            }
    }
    CollapsingTop(
        header = {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                shape = MaterialTheme.shapes.small,
                tonalElevation = 1.dp,
                onClick = { composing = true },
            ) {
                Text(
                    viewModel.summary().ifBlank { "搜索" },
                    modifier = Modifier.padding(16.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (viewModel.summary().isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = viewModel.subtitleOnly,
                    onClick = { viewModel.changeSubtitle(!viewModel.subtitleOnly) },
                    label = { Text("有字幕") },
                )
                FilterChip(
                    selected = viewModel.allAges,
                    onClick = { viewModel.changeAllAges(!viewModel.allAges) },
                    label = { Text("全年龄") },
                )
                SortMenu(
                    label = viewModel.sortLabel(),
                    showPopular = viewModel.text.isBlank() && viewModel.pieces.isEmpty() && !viewModel.allAges,
                    onPopular = { viewModel.chooseSort(null) },
                    onSort = viewModel::chooseSort,
                )
            }
        },
    ) { topInset ->
        when {
            works.isEmpty() && viewModel.loading -> SkeletonWorkList(modifier = topInset)
            works.isEmpty() -> Box(topInset.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                EmptyState(title = viewModel.error ?: "没有作品")
            }
            else -> LazyColumn(state = listState, modifier = topInset.fillMaxSize()) {
                items(works, key = { it.sourceId }) { item ->
                    OnlineResultCard(item) {
                        if (item.sourceId.isNotBlank()) onOpenWork(item.sourceId)
                    }
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

@Composable
private fun OnlineResultCard(item: OnlineHit, onClick: () -> Unit) {
    val facts = item.facts()
    val sources = LocalSourceColors.current
    Surface(
        onClick = onClick,
        enabled = item.sourceId.isNotBlank(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            CoverImage(item.coverUrl, size = 84.dp, shape = MaterialTheme.shapes.medium)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    facts.title.ifBlank { facts.sourceId },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(facts.circle, facts.vas.joinToString("、")).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { facts.sourceId },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (item.fromAsmr) {
                        LabelBadge("asmr.one", sources.asmr)
                    } else {
                        LabelBadge("DLsite", sources.dlsite)
                    }
                    if (item.asmrMissing) {
                        LabelBadge("asmr.one未收录", MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun SortMenu(
    label: String,
    showPopular: Boolean,
    onPopular: () -> Unit,
    onSort: (OnlineSort) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (showPopular) {
                DropdownMenuItem(text = { Text("热门") }, onClick = { open = false; onPopular() })
            }
            OnlineSort.entries.forEach { item ->
                DropdownMenuItem(text = { Text(item.label) }, onClick = { open = false; onSort(item) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchComposer(
    initialText: String,
    initialPieces: List<SearchPiece>,
    onBack: () -> Unit,
    onSearch: (String, List<SearchPiece>) -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }
    var pieces by remember { mutableStateOf(initialPieces) }
    var pendingKind by remember { mutableStateOf<PieceKind?>(null) }
    var pendingName by remember { mutableStateOf("") }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("搜索", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { onSearch(text.trim(), pieces) }) { Text("搜索") }
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
                            Text(piece.caption(), modifier = Modifier.padding(start = 10.dp))
                            IconButton(onClick = { pieces = pieces.filterNot { it == piece } }) {
                                Icon(Icons.Default.Close, contentDescription = "删除")
                            }
                        }
                    }
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it.replace("$", "") },
                    modifier = Modifier.widthIn(min = 120.dp).padding(8.dp).onPreviewKeyEvent { event ->
                        val deleting = event.type == KeyEventType.KeyDown && event.key == Key.Backspace
                        if (deleting && text.isEmpty() && pieces.isNotEmpty()) {
                            pieces = pieces.dropLast(1)
                            true
                        } else {
                            false
                        }
                    },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    decorationBox = { inner ->
                        if (text.isEmpty() && pieces.isEmpty()) {
                            Text("输入文字", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                }) { Text(kind.label) }
            }
        }
    }
    val kind = pendingKind
    if (kind == PieceKind.TAG) {
        TagPicker(
            taken = pieces.mapNotNull { it.genreId }.toSet(),
            onPick = { genre ->
                pieces = pieces + SearchPiece(PieceKind.TAG, genre.name, genre.id)
                pendingKind = null
            },
            onDismiss = { pendingKind = null },
        )
    } else if (kind != null) {
        AlertDialog(
            onDismissRequest = { pendingKind = null },
            title = { Text("加入${kind.label}") },
            text = {
                OutlinedTextField(
                    value = pendingName,
                    onValueChange = { pendingName = it.replace("$", "") },
                    singleLine = true,
                    label = { Text(kind.label) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = pendingName.trim()
                    if (name.isNotEmpty()) pieces = pieces + SearchPiece(kind, name)
                    pendingKind = null
                }) { Text("加入") }
            },
            dismissButton = { TextButton(onClick = { pendingKind = null }) { Text("取消") } },
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
        title = { Text("加入标签") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("筛选") },
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
                        item { Text("没有匹配的标签", modifier = Modifier.padding(12.dp)) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
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
        (applicationContext as Application).graph().messages.value = "没有作品编号"
        return
    }
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        (applicationContext as Application).graph().messages.value = "没有可用的浏览器"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineWorkScreen(
    sourceId: String,
    onBack: () -> Unit,
    onOpenPreview: (Long) -> Unit,
    onOpenRemotePreview: () -> Unit,
    viewModel: OnlineWorkViewModel = viewModel(),
) {
    val context = LocalContext.current
    var browserChoice by remember { mutableStateOf(false) }
    var browserMenu by remember { mutableStateOf(false) }
    LaunchedEffect(sourceId) { viewModel.load(sourceId, context.onMeteredNetwork()) }
    BackHandler(enabled = viewModel.canGoUp()) { viewModel.up() }
    val facts = viewModel.facts
    val canSave = facts != null && !viewModel.asmrMissing || viewModel.purchased
    if (browserChoice && facts != null) {
        AlertDialog(
            onDismissRequest = { browserChoice = false },
            title = { Text("打开外部浏览器") },
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
                }
            },
            confirmButton = {
                TextButton(onClick = { browserChoice = false }) { Text("取消") }
            },
        )
    }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            title = "作品",
            onBack = onBack,
            actions = {
                if (viewModel.working || viewModel.busy) {
                    CircularProgressIndicator(Modifier.padding(end = 8.dp).size(20.dp))
                }
                if (facts != null) {
                    IconButton(onClick = { browserMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = browserMenu, onDismissRequest = { browserMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("打开外部浏览器") },
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
            facts == null -> Box(Modifier.fillMaxSize().padding(24.dp)) {
                EmptyHint(viewModel.error ?: "没有读到作品。")
            }
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
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = if (canSave) 96.dp else 16.dp),
                ) {
                item(key = "facts") {
                    WorkHeader(facts, viewModel.coverUrl, purchased = viewModel.purchased)
                }
                if (viewModel.gallery.isNotEmpty()) {
                    item(key = "gallery") {
                        GalleryBlock(
                            urls = viewModel.gallery,
                            revealed = viewModel.allowGallery,
                            onReveal = viewModel::revealGallery,
                        )
                    }
                }
                item(key = "trial") {
                    TrialBlock(viewModel.trials, onPlay = viewModel::playTrial)
                }
                item(key = "files-title") {
                    SectionHeader(
                        if (viewModel.purchased) {
                            if (viewModel.fileSource == WorkFileSource.PLAY) {
                                "文件（来自DLSite Play）"
                            } else {
                                "文件（来自asmr.one）"
                            }
                        } else if (viewModel.showPlay || viewModel.filesFromPlay) {
                            "文件（来自DLSite Play）"
                        } else {
                            "文件（来自asmr.one）"
                        },
                    )
                }
                if (viewModel.purchased) {
                    item(key = "file-source") {
                        SingleChoiceSegmentedButtonRow(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        ) {
                            SegmentedButton(
                                selected = viewModel.fileSource == WorkFileSource.PLAY,
                                onClick = { viewModel.useFileSource(WorkFileSource.PLAY) },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            ) { Text("DLSite Play") }
                            SegmentedButton(
                                selected = viewModel.fileSource == WorkFileSource.ASMR,
                                onClick = { viewModel.useFileSource(WorkFileSource.ASMR) },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            ) { Text("asmr.one") }
                        }
                    }
                }
                if (viewModel.asmrMissing && viewModel.fileSource == WorkFileSource.ASMR && !viewModel.showPlay) {
                    item(key = "missing") {
                        Box(Modifier.fillMaxWidth().padding(24.dp)) {
                            EmptyHint("asmr.one未收录")
                        }
                    }
                } else {
                    if (!viewModel.atWorkRoot) {
                        item(key = "up") {
                            ListItem(
                                headlineContent = { Text(viewModel.folderTitle.ifBlank { "返回" }) },
                                leadingContent = {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回上一级")
                                },
                                modifier = Modifier.clickable(onClick = viewModel::up),
                            )
                        }
                    }
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
                                        artist = facts.circle.ifBlank { facts.sourceId },
                                        durationMs = audio.durationMs,
                                        downloaded = viewModel.playDownloaded(audio.fileKey),
                                        hasSubtitle = false,
                                        source = "DLsite",
                                    ),
                                    onClick = { viewModel.playPlay(entry) },
                                    trailing = {
                                        val savedFile = viewModel.playDownloaded(audio.fileKey)
                                        IconButton(
                                            onClick = { viewModel.downloadPlay(entry) },
                                            enabled = !savedFile,
                                        ) {
                                            Icon(
                                                if (savedFile) Icons.Default.Check else Icons.Default.Download,
                                                contentDescription = if (savedFile) "已下载" else "下载",
                                            )
                                        }
                                    },
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
                                is LibraryEntry.Audio -> OnlineTrackRow(
                                    title = entry.track.title,
                                    coverUrl = entry.track.coverUri,
                                    supporting = trackSupporting(entry.track, entry.hasSubtitle),
                                    onClick = { viewModel.playLibrary(entry.track) },
                                )
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
                                val savedFile = viewModel.purchased && viewModel.asmrDownloaded(node)
                                OnlineTrackRow(
                                    title = node.title,
                                    coverUrl = viewModel.coverUrl,
                                    supporting = trackSupporting(
                                        artist = facts.circle.ifBlank { facts.sourceId },
                                        durationMs = node.durationMs,
                                        downloaded = savedFile,
                                        hasSubtitle = viewModel.nodeHasSubtitle(node),
                                    ),
                                    onClick = { viewModel.playNode(node) },
                                    trailing = if (viewModel.purchased) {
                                        {
                                            IconButton(
                                                onClick = { viewModel.downloadNode(node) },
                                                enabled = !savedFile,
                                            ) {
                                                Icon(
                                                    if (savedFile) Icons.Default.Check else Icons.Default.Download,
                                                    contentDescription = if (savedFile) "已下载" else "下载",
                                                )
                                            }
                                        }
                                    } else {
                                        null
                                    },
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
                                EmptyHint("这个文件夹是空的。")
                            }
                        }
                    }
                }
                }
            }
        }
    }
    if (facts != null && canSave) {
        if (viewModel.saved) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Text("已在音频库", modifier = Modifier.padding(start = 12.dp))
                }
            }
        } else {
            ExtendedFloatingActionButton(
                onClick = { if (!viewModel.working) viewModel.save() },
                expanded = true,
                icon = { Icon(Icons.Default.Download, contentDescription = null) },
                text = { Text("保存") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }
    }
}

@Composable
private fun OnlineFolderRow(name: String, coverUrl: String?, work: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(name) },
        supportingContent = { Text(if (work) "作品" else "文件夹") },
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
        FileKind.IMAGE -> "图片"
        FileKind.TEXT -> "文本"
        FileKind.OTHER -> "文件"
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
        FileKind.IMAGE -> "图片"
        FileKind.TEXT -> "文本"
        FileKind.OTHER -> "文件"
    }
    ListItem(
        headlineContent = { Text(name) },
        supportingContent = { Text(label) },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

private fun trackSupporting(track: TrackEntity, hasSubtitle: Boolean): String {
    val artist = track.artist?.takeIf { it.isNotBlank() } ?: when (track.source) {
        TrackSource.BILIBILI -> "B站"
        TrackSource.ASMR -> "asmr.one"
        TrackSource.DLSITE -> "DLsite"
        TrackSource.LOCAL -> "本地"
    }
    val source = when (track.source) {
        TrackSource.BILIBILI -> "B站"
        TrackSource.ASMR -> "asmr.one"
        TrackSource.DLSITE -> "DLsite"
        TrackSource.LOCAL -> "本地"
    }
    return trackSupporting(
        artist = artist,
        durationMs = track.durationMs,
        downloaded = (track.source == TrackSource.ASMR || track.source == TrackSource.DLSITE) &&
            com.nanahoshi.audioplayer.data.LocalAudio.hasStoredCopy(track.localUri),
        hasSubtitle = hasSubtitle,
        source = source,
    )
}

private fun trackSupporting(
    artist: String,
    durationMs: Long,
    downloaded: Boolean,
    hasSubtitle: Boolean,
    source: String = "asmr.one",
): String {
    val mark = if (downloaded) " · 已下载" else ""
    val subtitle = if (hasSubtitle) " · 有字幕" else ""
    return "$artist · $source · ${formatDuration(durationMs)}$mark$subtitle"
}

@Composable
private fun GalleryBlock(urls: List<String>, revealed: Boolean, onReveal: () -> Unit) {
    if (urls.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        SectionHeader("图库")
        if (!revealed) {
            TextButton(onClick = onReveal, modifier = Modifier.padding(horizontal = 8.dp)) { Text("预览") }
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
                        contentDescription = "样图",
                        modifier = Modifier.height(140.dp).width(186.dp).clip(RoundedCornerShape(12.dp)),
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
        SectionHeader("内容试听")
        if (trials.isEmpty()) {
            Text(
                "没有试听",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            trials.forEach { clip ->
                ListItem(
                    headlineContent = { Text(clip.title) },
                    supportingContent = { Text("试听") },
                    modifier = Modifier.clickable { onPlay(clip) },
                )
            }
        }
    }
}

class MineViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
    var loginId by mutableStateOf("")
    var password by mutableStateOf("")
    var cookie by mutableStateOf("")
    var loggedIn by mutableStateOf(graph.dlsite.hasSession())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var biliReady by mutableStateOf(graph.credentials.hasCookie())
        private set
    var biliCanRefresh by mutableStateOf(graph.credentials.hasBiliRefresh())
        private set
    var qrVisible by mutableStateOf(false)
        private set
    var qrImage by mutableStateOf<Bitmap?>(null)
        private set
    var qrMessage by mutableStateOf("")
        private set
    var qrExpired by mutableStateOf(false)
        private set
    private var qrJob: Job? = null
    private var qrGeneration = 0

    fun login() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                graph.dlsite.login(loginId, password)
                password = ""
                loggedIn = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "登录失败"
            } finally {
                loading = false
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
                error = failure.message ?: "登录失败"
            } finally {
                loading = false
            }
        }
    }

    fun logout() {
        graph.dlsite.logout()
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
        qrMessage = "正在获取二维码"
        qrJob = viewModelScope.launch {
            try {
                val login = graph.bilibili.requestQrLogin()
                if (generation != qrGeneration) return@launch
                qrImage = qrBitmap(login.url)
                qrMessage = "请用 B 站 App 扫码"
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
                        QrPoll.Scanned -> qrMessage = "已扫码，请在 B 站 App 里确认"
                        QrPoll.Expired -> {
                            qrExpired = true
                            qrMessage = "二维码已过期"
                            return@launch
                        }
                        QrPoll.Confirmed -> Unit
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation != qrGeneration) return@launch
                qrMessage = if (failure is BilibiliException) {
                    failure.message ?: "扫码登录失败"
                } else {
                    "无法连接 B 站"
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

    init {
        viewModelScope.launch {
            try {
                purchases = graph.dlsite.purchases()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "无法读取已购买作品"
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
    viewModel: MineViewModel = viewModel(),
) {
    var biliHelp by remember { mutableStateOf(false) }
    var sessdataLogin by remember { mutableStateOf(false) }
    if (viewModel.qrVisible) {
        BiliQrDialog(
            image = viewModel.qrImage,
            message = viewModel.qrMessage,
            expired = viewModel.qrExpired,
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
    var cookieOpen by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item(key = "dlsite") {
            AccountCard(title = "DLsite", loggedIn = viewModel.loggedIn) {
                if (!viewModel.loggedIn) {
                    OutlinedTextField(
                        value = viewModel.loginId,
                        onValueChange = { viewModel.loginId = it },
                        label = { Text("账号") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                    OutlinedTextField(
                        value = viewModel.password,
                        onValueChange = { viewModel.password = it },
                        label = { Text("密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Button(
                        onClick = viewModel::login,
                        enabled = !viewModel.loading,
                        modifier = Modifier.padding(top = 12.dp),
                    ) { Text("登录") }
                    TextButton(onClick = { cookieOpen = !cookieOpen }) {
                        Text(if (cookieOpen) "收起 Cookie" else "使用 Cookie")
                    }
                    AnimatedVisibility(visible = cookieOpen) {
                        Column {
                            OutlinedTextField(
                                value = viewModel.cookie,
                                onValueChange = { viewModel.cookie = it },
                                label = { Text("Cookie") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            TextButton(
                                onClick = viewModel::loginCookie,
                                enabled = !viewModel.loading,
                            ) { Text("使用 Cookie 登录") }
                        }
                    }
                } else {
                    Button(
                        onClick = onOpenPurchases,
                        enabled = !viewModel.loading,
                        modifier = Modifier.padding(top = 12.dp),
                    ) { Text("查看已购买作品") }
                    TextButton(onClick = viewModel::logout) { Text("退出") }
                }
            }
        }
        viewModel.error?.let { message ->
            item(key = "dlsite-error") {
                Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
        }
        item(key = "bilibili") {
            AccountCard(title = "B站", loggedIn = viewModel.biliReady) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            !viewModel.biliReady -> "还没有登录"
                            viewModel.biliCanRefresh -> "已扫码登录，会在需要时自动更新"
                            else -> "已保存登录信息"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { biliHelp = true }) {
                        Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = "B站登录说明")
                    }
                }
                if (viewModel.biliReady) {
                    Button(onClick = onOpenFavorites, modifier = Modifier.padding(top = 8.dp)) { Text("查看收藏夹") }
                    TextButton(onClick = viewModel::startBiliQr) { Text("扫码登录") }
                } else {
                    Button(onClick = viewModel::startBiliQr, modifier = Modifier.padding(top = 8.dp)) { Text("扫码登录") }
                    TextButton(onClick = onOpenFavorites) { Text("查看收藏夹") }
                }
                TextButton(onClick = { sessdataLogin = true }) { Text("SESSDATA登录") }
                TextButton(onClick = viewModel::clearBili) { Text("清除") }
            }
        }
    }
}

@Composable
private fun AccountCard(
    title: String,
    loggedIn: Boolean,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                LabelBadge(
                    if (loggedIn) "已登录" else "未登录",
                    if (loggedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}

@Composable
private fun BiliLoginHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("B站登录") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("只保存在这台手机里，用来播放你的账号能听的声音，包括需要登录或大会员的视频。不会上传到别处。")
                Text(
                    "扫码登录后，登录信息会在需要时自动更新。也可以在「SESSDATA登录」里粘贴 SESSDATA，或整段 Cookie；粘贴的内容不会自动更新。",
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
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
        title = { Text("SESSDATA登录") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("SESSDATA 或 Cookie") },
                visualTransformation = PasswordVisualTransformation(),
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = ::submit, enabled = text.isNotBlank()) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("改用文字框里的登录？") },
            text = {
                Text("当前已扫码登录，登录信息会在需要时自动更新。保存后将改用文字框里的内容，之后不会自动更新。")
            },
            confirmButton = {
                TextButton(onClick = { onSave(text.trim()) }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun BiliQrDialog(
    image: Bitmap?,
    message: String,
    expired: Boolean,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("扫码登录") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (image != null) {
                    Surface(color = Color.White) {
                        Image(
                            bitmap = image.asImageBitmap(),
                            contentDescription = "B站登录二维码",
                            modifier = Modifier.size(220.dp),
                        )
                    }
                } else if (!expired && message == "正在获取二维码") {
                    CircularProgressIndicator(modifier = Modifier.padding(vertical = 24.dp))
                }
                if (message.isNotBlank()) {
                    Text(message, modifier = Modifier.padding(top = 12.dp))
                }
            }
        },
        confirmButton = {
            if (expired) {
                TextButton(onClick = onRefresh) { Text("刷新二维码") }
            }
            TextButton(onClick = onDismiss) { Text("取消") }
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
        AppTopBar(title = "已购买作品", onBack = onBack)
        when {
            viewModel.loading -> LoadingState("正在读取已购买作品", Modifier.fillMaxSize())
            viewModel.purchases.isEmpty() -> EmptyState(
                title = viewModel.error ?: "没有已购买作品",
                modifier = Modifier.fillMaxSize(),
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(viewModel.purchases, key = { it.workno }) { item ->
                    Surface(
                        onClick = { onOpenWork(item.workno) },
                        color = Color.Transparent,
                    ) {
                        WorkFactsBlock(
                            facts = WorkFacts(
                                title = item.title,
                                circle = item.circle,
                                vas = item.vas,
                                tags = emptyList(),
                                release = item.release,
                                sourceId = item.workno,
                            ),
                            coverUrl = item.coverUrl,
                            singleLine = true,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
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
