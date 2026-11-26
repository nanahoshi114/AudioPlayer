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
    onSearchWorkPiece: (SearchPiece) -> Unit = {},
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
    val title = stringResource(R.string.online_work_title)
    val navigateBack = { if (viewModel.canGoUp()) viewModel.up() else onBack() }
    val retry = { viewModel.load(sourceId, context.onMeteredNetwork(), force = true, jaPost = jaPost) }
    if (facts == null) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(title, onBack = navigateBack) {
                if (viewModel.working || viewModel.busy) {
                    CircularProgressIndicator(Modifier.padding(end = 8.dp).size(20.dp))
                }
            }
            when {
                viewModel.busy -> LoadingState(modifier = Modifier.fillMaxSize())
                jaVerificationPage != null -> Box(
                    Modifier.fillMaxSize(), contentAlignment = Alignment.Center,
                ) {
                    JapaneseAsmrVerificationState(jaVerificationPage, verifyJapaneseAsmr)
                }
                else -> ErrorState(
                    viewModel.error ?: stringResource(R.string.online_work_not_found),
                    onRetry = retry,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        return
    }

    val pageState = rememberScrollState()
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
    ImmersiveWorkScaffold(
        listState = viewModel.browserListState(),
        pageState = pageState,
        bottomPadding = PaddingValues(bottom = if (targetFolderId != null || canSave) 96.dp else 24.dp),
        loading = when {
            viewModel.fileSource == WorkFileSource.JAPANESE_ASMR ->
                viewModel.jaSelected == null && viewModel.jaPosts.isEmpty() &&
                    (!viewModel.jaChecked || viewModel.jaLoading)
            else -> (viewModel.libraryLoading || viewModel.busy) && foldersEmpty && filesEmpty
        },
        topBar = { containerAlpha ->
            ImmersiveTopBar(
                title = title,
                containerAlpha = containerAlpha,
                onBack = navigateBack,
                actions = {
                    if (viewModel.working || viewModel.busy) {
                        CircularProgressIndicator(Modifier.padding(end = 8.dp).size(20.dp))
                    }
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
                },
            )
        },
        header = { topBarInset, heroMeasureModifier ->
            WorkHeader(
                facts = facts,
                coverUrl = viewModel.coverUrl,
                purchased = viewModel.purchased,
                topBarInset = topBarInset,
                onSearchCircle = { searchPieceForCircle(it)?.let(onSearchWorkPiece) },
                onSearchVa = { searchPieceForVa(it)?.let(onSearchWorkPiece) },
                onSearchTag = { searchPieceForTag(it)?.let(onSearchWorkPiece) },
                modifier = heroMeasureModifier,
            )
            if (!viewModel.busy) {
                viewModel.error?.let { ErrorState(it, onRetry = retry) }
            }
            GalleryBlock(viewModel.gallery, viewModel.allowGallery, viewModel::revealGallery) { index ->
                if (viewModel.openGallery(index)) onOpenRemotePreview()
            }
            TrialBlock(viewModel.trials, onPlay = viewModel::playTrial)
        },
        controls = {
            SectionHeader(stringResource(R.string.online_files))
            if (viewModel.availableSources.size > 1) {
                SegmentedTabBar(
                    options = viewModel.availableSources,
                    selected = viewModel.fileSource,
                    label = { it.trackSource().workLabel() },
                    onSelect = viewModel::useFileSource,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
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
                rootLabel = stringResource(R.string.online_root),
                onRoot = { viewModel.ancestor(0) },
                onCrumb = { viewModel.goToAncestor(trail[it].id) },
                revealCurrent = true,
            )
        },
        floatingActionButton = {
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
                    OnlineTrackRow(
                        title = media.title,
                        coverUrl = selected.coverUri,
                        artist = facts.circle.ifBlank { facts.sourceId },
                        durationMs = null,
                        source = TrackSource.JAPANESE_ASMR,
                        downloaded = false,
                        hasSubtitle = false,
                        onClick = { viewModel.playJa(media) },
                        trailing = if (viewModel.saved) {
                            {
                                TextButton(onClick = { viewModel.downloadJa(media) }) {
                                    Text(stringResource(R.string.online_download))
                                }
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        } else if (viewModel.asmrMissing && viewModel.fileSource == WorkFileSource.ASMR && !viewModel.showPlay) {
            item(key = "missing") {
                Box(Modifier.fillMaxWidth().padding(24.dp)) {
                    EmptyState(title = stringResource(R.string.online_not_listed))
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
                            artist = facts.circle.ifBlank { facts.sourceId },
                            durationMs = audio.durationMs,
                            source = TrackSource.DLSITE,
                            downloaded = viewModel.playDownloaded(audio.fileKey),
                            hasSubtitle = false,
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
                            val track = entry.track
                            OnlineTrackRow(
                                title = track.title,
                                coverUrl = track.coverUri,
                                artist = track.artist,
                                durationMs = track.durationMs,
                                source = track.source,
                                downloaded = track.source != TrackSource.LOCAL && LocalAudio.hasStoredCopy(track.localUri),
                                hasSubtitle = entry.hasSubtitle,
                                onClick = { viewModel.playLibrary(track) },
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
                            artist = facts.circle.ifBlank { facts.sourceId },
                            durationMs = node.durationMs,
                            source = TrackSource.ASMR,
                            downloaded = savedFile,
                            hasSubtitle = viewModel.nodeHasSubtitle(node),
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
    artist: String?,
    durationMs: Long?,
    source: TrackSource,
    downloaded: Boolean,
    hasSubtitle: Boolean,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { TrackMetaRow(artist, durationMs, source, downloaded, hasSubtitle) },
        leadingContent = { CoverImage(coverUrl) },
        trailingContent = trailing,
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
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
                itemsIndexed(urls, key = { index, url -> "$index-$url" }) { index, url ->
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(url.toHttps())
                            .addHeader("Referer", DlsiteClient.STORE_REFERER)
                            .addHeader("User-Agent", DlsiteClient.USER_AGENT)
                            .build(),
                        contentDescription = stringResource(R.string.a11y_sample_image, index + 1),
                        modifier = Modifier.height(140.dp).width(186.dp).clip(RoundedCornerShape(12.dp)).clickable { onOpen(index) },
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
