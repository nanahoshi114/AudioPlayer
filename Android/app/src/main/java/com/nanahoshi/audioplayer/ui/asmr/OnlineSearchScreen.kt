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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun WorkSearchScreen(
    onOpenWork: (String) -> Unit,
    pendingSearch: PendingOnlineSearch? = null,
    onConsumeSearch: (Long) -> Unit = {},
    viewModel: OnlineSearchViewModel = viewModel(),
) {
    val listState = rememberLazyListState()
    com.nanahoshi.audioplayer.ui.components.ResetOnNewSearch(listState, viewModel.searchRevision)
    var composing by rememberSaveable { mutableStateOf(false) }
    val pendingWork = pendingSearch as? PendingOnlineSearch.WorkPiece
    LaunchedEffect(pendingWork?.id) {
        pendingWork?.let { request ->
            composing = false
            viewModel.applyQuery("", listOf(request.piece))
            onConsumeSearch(request.id)
        }
    }
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
    LoadMoreOnScrollEnd(
        state = listState,
        itemCount = works.size,
        canLoadMore = viewModel.hasMore,
        loading = viewModel.loading,
        hasError = viewModel.error != null,
        onLoadMore = viewModel::loadMore,
    )
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
                        LoadingState()
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
    pendingSearch: PendingOnlineSearch? = null,
    onConsumeSearch: (Long) -> Unit = {},
) {
    val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    Column(Modifier.fillMaxSize()) {
        val sources = OnlineSearchSource.entries
        val worksLabel = stringResource(R.string.online_source_works)
        val biliLabel = stringResource(R.string.library_badge_bilibili)
        SegmentedTabBar(
            options = sources,
            selected = source,
            label = { item ->
                when (item) {
                    OnlineSearchSource.WORKS -> worksLabel
                    OnlineSearchSource.BILIBILI -> biliLabel
                    OnlineSearchSource.YOUTUBE -> "YouTube"
                }
            },
            onSelect = onSourceChange,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Box(Modifier.weight(1f)) {
            holder.SaveableStateProvider(source.saveableKey) {
                when (source) {
                    OnlineSearchSource.BILIBILI -> com.nanahoshi.audioplayer.ui.bilibili.BiliBrowser(
                        topBarOwnerKey = source.topBarOwnerKey(entryId),
                        onOpenCollection = onOpenBiliCollection,
                        pendingUploaderSearch = pendingSearch as? PendingOnlineSearch.BiliUploader,
                        onConsumeSearch = onConsumeSearch,
                    )
                    OnlineSearchSource.YOUTUBE -> com.nanahoshi.audioplayer.ui.youtube.YouTubeBrowser(
                        topBarOwnerKey = source.topBarOwnerKey(entryId),
                    )
                    OnlineSearchSource.WORKS -> WorkSearchScreen(onOpenWork, pendingSearch, onConsumeSearch)
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
    val options = remember(showPopular) {
        buildList<OnlineSort?> {
            if (showPopular) add(null)
            addAll(OnlineSort.entries)
        }
    }
    val current = if (showPopular) selected else selected ?: OnlineSort.SALES
    DropdownFilterButton(
        options = options,
        selected = current,
        buttonLabel = label,
        optionLabel = { item ->
            if (item == null) stringResource(R.string.online_popular) else stringResource(item.labelRes)
        },
        onSelect = { item ->
            if (item == null) onPopular() else onSort(item)
        },
    )
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
