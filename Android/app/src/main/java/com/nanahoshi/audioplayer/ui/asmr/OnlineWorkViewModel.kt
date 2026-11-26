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
                val localFacts = existing?.toWorkFacts()
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
                if (cleanup) graph.showRaw(result.message(getApplication()))
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
                    val track = savedJaTrack(article, media) ?: throw UserFacingException(TextRef.Res(R.string.online_saved_track_missing))
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
            val (play, asmrKeys) = withContext(Dispatchers.IO) {
                val playSet = graph.downloads.downloadedKeys(TrackSource.DLSITE, sourceId).toMutableSet()
                val asmrSet = graph.downloads.downloadedKeys(TrackSource.ASMR, sourceId).toMutableSet()
                if (workId != null) {
                    graph.db.tracks().forAsmrWork(workId).forEach { track ->
                        val path = track.localUri
                        if (path == null || !path.startsWith("/") || !java.io.File(path).isFile) return@forEach
                        val raw = rawFileKey(track.fileHash) ?: return@forEach
                        when (track.source) {
                            TrackSource.DLSITE -> playSet += raw
                            TrackSource.ASMR -> asmrSet += raw
                            else -> Unit
                        }
                    }
                }
                playSet to asmrSet
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
