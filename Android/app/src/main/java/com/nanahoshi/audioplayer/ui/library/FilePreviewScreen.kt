package com.nanahoshi.audioplayer.ui.library

import com.nanahoshi.audioplayer.text.resolveNoUrl
import com.nanahoshi.audioplayer.text.resolveUserError
import com.nanahoshi.audioplayer.data.ExternalFileDownloader
import com.nanahoshi.audioplayer.data.LocalAudio
import com.nanahoshi.audioplayer.data.RemoteTextPreview
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.libraryFilePreview
import com.nanahoshi.audioplayer.data.readLocalTextPreview
import com.nanahoshi.audioplayer.data.resolveLibraryFileSource
import com.nanahoshi.audioplayer.R
import androidx.compose.ui.res.stringResource
import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.nanahoshi.audioplayer.ui.components.AppTopBar
import com.nanahoshi.audioplayer.ui.components.LoadingState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.nanahoshi.audioplayer.asmr.AsmrClient
import com.nanahoshi.audioplayer.data.db.FileKind
import com.nanahoshi.audioplayer.dlsite.DlsiteClient
import com.nanahoshi.audioplayer.dlsite.RemotePreview
import com.nanahoshi.audioplayer.data.db.LibraryFileEntity
import com.nanahoshi.audioplayer.data.toHttps
import com.nanahoshi.audioplayer.graph
import java.io.File
import java.util.UUID

class FilePreviewViewModel(app: Application) : AndroidViewModel(app) {
    private val appContext = app.applicationContext
    private val graph = app.graph()
    private val imageCacheDir = File(appContext.cacheDir, "external-open/img-${UUID.randomUUID()}")
    private val imageDownloader by lazy { ExternalFileDownloader(sign = graph.dlsite::signedPlayFile) }

    internal suspend fun fetchAuthedImage(request: RemotePreview): File = withContext(Dispatchers.IO) {
        require(request.authed)
        graph.cache.protect(imageCacheDir)
        val identity = UUID.nameUUIDFromBytes("${request.workno}\n${request.fileKey}\n${request.url}".toByteArray())
        val target = File(imageCacheDir, identity.toString())
        imageDownloader.downloadFile(request, target)
        target
    }

    override fun onCleared() {
        graph.cache.release(imageCacheDir)
        super.onCleared()
    }

    suspend fun file(id: Long): LibraryFileEntity? = graph.library.libraryFile(id)

    fun request(): RemotePreview? = graph.remotePreview.value

    fun externalSession() = com.nanahoshi.audioplayer.data.ExternalFileSession(getApplication(), graph.cache, graph.dlsite)

    suspend fun hasLocalCopy(location: String?): Boolean = withContext(Dispatchers.IO) {
        if (location.isNullOrBlank()) return@withContext false
        when {
            location.startsWith("/") -> java.io.File(location).isFile
            location.startsWith("file:") -> Uri.parse(location).path?.let { java.io.File(it).isFile } == true
            location.startsWith("content:") -> LocalAudio.contentUriReadable(appContext, Uri.parse(location))
            else -> false
        }
    }

    suspend fun localImageModel(location: String?): Any? = withContext(Dispatchers.IO) {
        if (location.isNullOrBlank()) return@withContext null
        when {
            location.startsWith("/") -> java.io.File(location).takeIf { it.isFile }
            location.startsWith("file:") -> Uri.parse(location).path?.let { java.io.File(it).takeIf { file -> file.isFile } }
            location.startsWith("content:") -> location.takeIf { LocalAudio.contentUriReadable(appContext, Uri.parse(it)) }
            else -> null
        }
    }

    suspend fun sourceFor(file: LibraryFileEntity): TrackSource? = withContext(Dispatchers.IO) {
        val jaFileIds = graph.db.jaDownloads().fileIds().toSet()
        resolveLibraryFileSource(file, jaFileIds)
    }

    suspend fun externalRequest(file: LibraryFileEntity, resolvedSource: TrackSource? = null): RemotePreview =
        withContext(Dispatchers.IO) {
            val source = resolvedSource ?: sourceFor(file)
            val folders = if (source == TrackSource.DLSITE) graph.db.folders().all().associateBy { it.id } else emptyMap()
            libraryFilePreview(file, folders, source)
        }

    suspend fun text(file: LibraryFileEntity): String = withContext(Dispatchers.IO) {
        val local = try {
            file.localUri?.let { readLocalTextPreview(appContext, it) }
        } catch (_: java.io.IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
        local?.displayText() ?: run {
            val source = sourceFor(file)
            val request = externalRequest(file, source)
            when {
                source == TrackSource.JAPANESE_ASMR -> appContext.getString(R.string.library_file_ja_not_downloaded)
                request.url.isBlank() -> appContext.getString(
                    if (source == TrackSource.DLSITE) R.string.library_file_not_downloaded
                    else R.string.library_file_no_local_copy,
                )
                else -> remoteText(request)
            }
        }
    }

    private fun RemoteTextPreview.displayText(): String =
        if (clipped) text + appContext.getString(R.string.library_file_truncated) else text

    suspend fun remoteText(request: RemotePreview): String = try {
        if (request.authed || request.referer.contains("dlsite")) {
            graph.dlsite.readText(request.copy(referer = request.referer.ifBlank { DlsiteClient.STORE_REFERER })).displayText()
        } else {
            graph.asmr.readText(request.url).displayText()
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        appContext.resolveUserError(error, R.string.library_file_open_failed)
    }
}

@Composable
fun FilePreviewScreen(
    fileId: Long,
    onBack: () -> Unit,
    viewModel: FilePreviewViewModel = viewModel(),
) {
    var file by remember { mutableStateOf<LibraryFileEntity?>(null) }
    var missing by remember { mutableStateOf(false) }
    LaunchedEffect(fileId) {
        val loaded = viewModel.file(fileId)
        file = loaded
        missing = loaded == null
    }
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = file?.name ?: stringResource(R.string.library_preview_title), onBack = onBack)
        val current = file
        when {
            missing -> Text(stringResource(R.string.library_file_missing), modifier = Modifier.padding(16.dp))
            current == null -> LoadingState(stringResource(R.string.library_file_loading), Modifier.weight(1f).fillMaxWidth())
            current.kind == FileKind.OTHER -> {
                var resolved by remember(current.id, current.localUri) {
                    mutableStateOf<Triple<Boolean, TrackSource?, RemotePreview>?>(null)
                }
                LaunchedEffect(current.id, current.localUri) {
                    val hasLocal = viewModel.hasLocalCopy(current.localUri)
                    if (hasLocal) {
                        resolved = Triple(true, null, RemotePreview(current.name, "", current.kind, "", authed = false))
                    } else {
                        val source = viewModel.sourceFor(current)
                        resolved = Triple(false, source, viewModel.externalRequest(current, source))
                    }
                }
                val state = resolved
                when {
                    state == null -> LoadingState(
                        stringResource(R.string.library_file_opening),
                        Modifier.weight(1f).fillMaxWidth(),
                    )
                    state.first -> ExternalOpenContent(state.third, current.localUri, viewModel)
                    state.second == TrackSource.JAPANESE_ASMR -> Text(
                        stringResource(R.string.library_file_ja_not_downloaded),
                        modifier = Modifier.padding(16.dp),
                    )
                    state.third.url.isBlank() -> Text(
                        stringResource(
                            if (state.second == TrackSource.DLSITE) R.string.library_file_not_downloaded
                            else R.string.library_file_no_local_copy,
                        ),
                        modifier = Modifier.padding(16.dp),
                    )
                    else -> ExternalOpenContent(state.third, null, viewModel)
                }
            }
            current.kind == FileKind.IMAGE -> {
                var resolved by remember(current.id, current.localUri) {
                    mutableStateOf<Triple<Any?, TrackSource?, RemotePreview?>?>(null)
                }
                LaunchedEffect(current.id, current.localUri) {
                    val localModel = viewModel.localImageModel(current.localUri)
                    if (localModel != null) {
                        resolved = Triple(localModel, null, null)
                    } else {
                        val source = viewModel.sourceFor(current)
                        resolved = Triple(null, source, viewModel.externalRequest(current, source))
                    }
                }
                val state = resolved
                val request = state?.third
                val hasRemote = request != null && (request.url.isNotBlank() ||
                    (request.authed && request.workno != null && request.fileKey != null))
                when {
                    state == null -> LoadingState(
                        stringResource(R.string.library_file_loading_image),
                        Modifier.weight(1f).fillMaxWidth(),
                    )
                    state.first != null -> ZoomImage(
                        model = state.first!!,
                        contentDescription = current.name,
                    )
                    state.second == TrackSource.JAPANESE_ASMR -> Text(
                        stringResource(R.string.library_file_ja_not_downloaded),
                        modifier = Modifier.padding(16.dp),
                    )
                    request == null || !hasRemote -> Text(
                        stringResource(
                            if (state.second == TrackSource.DLSITE) R.string.library_file_not_downloaded
                            else R.string.library_file_no_local_copy,
                        ),
                        modifier = Modifier.padding(16.dp),
                    )
                    else -> PreviewZoomImage(request, viewModel)
                }
            }
            else -> TextPreview(current, viewModel)
        }
    }
}

@Composable
private fun TextPreview(file: LibraryFileEntity, viewModel: FilePreviewViewModel) {
    var body by remember(file.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(file.id) {
        body = viewModel.text(file)
    }
    val text = body
    if (text == null) {
        LoadingState(stringResource(R.string.library_file_loading), Modifier.fillMaxSize())
    } else {
        SelectionContainer {
            Text(
                text,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            )
        }
    }
}

/** Coil 可直接接收 File 或 content:/file: 字符串/URI；这些都算本地图片，失败文案不应指向网络。 */
private fun Any.isLocalImageModel(): Boolean = when (this) {
    is File -> true
    is Uri -> this.scheme == "content" || this.scheme == "file"
    is String -> this.startsWith("content:", ignoreCase = true) || this.startsWith("file:", ignoreCase = true)
    else -> false
}

@Composable
private fun ZoomImage(
    model: Any,
    contentDescription: String,
    active: Boolean = true,
    @StringRes errorMessage: Int = if (model.isLocalImageModel()) R.string.library_file_local_image_failed else R.string.library_file_image_failed,
    onRetry: (() -> Unit)? = null,
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var imageSize by remember(model) { mutableStateOf(Size.Unspecified) }
    var scale by remember(model, active, containerSize) { mutableFloatStateOf(1f) }
    var offset by remember(model, active, containerSize) { mutableStateOf(Offset.Zero) }
    var retry by remember(model) { mutableStateOf(0) }

    fun clamp(position: Offset, zoom: Float): Offset {
        if (zoom <= 1f || !imageSize.isSpecified || imageSize.width <= 0f || imageSize.height <= 0f ||
            containerSize.width == 0 || containerSize.height == 0
        ) return Offset.Zero
        val viewport = Size(containerSize.width.toFloat(), containerSize.height.toFloat())
        val fit = ContentScale.Fit.computeScaleFactor(imageSize, viewport)
        val maxX = ((imageSize.width * fit.scaleX * zoom - viewport.width) / 2f).coerceAtLeast(0f)
        val maxY = ((imageSize.height * fit.scaleY * zoom - viewport.height) / 2f).coerceAtLeast(0f)
        return Offset(position.x.coerceIn(-maxX, maxX), position.y.coerceIn(-maxY, maxY))
    }

    fun transform(centroid: Offset, pan: Offset, zoom: Float) {
        if (!imageSize.isSpecified || !centroid.isSpecified) return
        val nextScale = (scale * zoom).coerceIn(1f, 5f)
        val focus = centroid - Offset(containerSize.width / 2f, containerSize.height / 2f)
        offset = clamp(focus + (offset - focus) * (nextScale / scale) + pan, nextScale)
        scale = nextScale
    }

    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { containerSize = it }
            .pointerInput(model, active, containerSize) {
                if (!active) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var drag = Offset.Zero
                    var transforming = false
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.any { it.isConsumed && it.position != it.previousPosition }) break
                        val pinch = event.changes.count { it.pressed } > 1
                        val pan = event.calculatePan()
                        drag += pan
                        // At the base scale, leave single-finger swipes for the pager.
                        if (pinch || (scale > 1.01f && (transforming || drag.getDistance() > viewConfiguration.touchSlop))) {
                            transforming = true
                            transform(event.calculateCentroid(useCurrent = false), pan, event.calculateZoom())
                            event.changes.forEach { if (it.pressed) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(model, active, containerSize) {
                if (!active) return@pointerInput
                detectTapGestures(onDoubleTap = { point ->
                    val target = if (scale <= 1.01f) 2.5f else 1f
                    transform(point, Offset.Zero, target / scale)
                })
            },
    ) {
        androidx.compose.runtime.key(retry) {
            SubcomposeAsyncImage(
                model = model,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
                contentScale = ContentScale.Fit,
                onSuccess = {
                    imageSize = it.painter.intrinsicSize
                    offset = clamp(offset, scale)
                },
                loading = { LoadingState(stringResource(R.string.library_file_loading_image), Modifier.fillMaxSize()) },
                error = {
                    ImagePreviewError(stringResource(errorMessage), onRetry ?: { retry += 1 })
                },
            )
        }
    }
}

@Composable
fun RemotePreviewScreen(
    onBack: () -> Unit,
    viewModel: FilePreviewViewModel = viewModel(),
) {
    val request = remember { viewModel.request() }
    if (request != null && !request.authed && request.galleryUrls.isNotEmpty()) {
        GalleryPreview(request, onBack)
        return
    }
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = request?.name ?: stringResource(R.string.library_preview_title), onBack = onBack)
        when {
            request == null -> Text(stringResource(R.string.library_file_missing), modifier = Modifier.padding(16.dp))
            request.kind == FileKind.OTHER -> ExternalOpenContent(request, null, viewModel)
            request.kind == FileKind.IMAGE -> PreviewZoomImage(request, viewModel)
            else -> RemoteTextPreview(request, viewModel)
        }
    }
}

@Composable
private fun GalleryPreview(request: RemotePreview, onBack: () -> Unit) {
    val urls = request.galleryUrls
    val initial = request.initialIndex.coerceIn(0, (urls.size - 1).coerceAtLeast(0))
    val pager = rememberPagerState(initialPage = initial) { urls.size }
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = galleryPageTitle(request, pager.currentPage, urls.size), onBack = onBack)
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            RemoteZoomImage(
                request,
                urls[page],
                galleryPageTitle(request, page, urls.size),
                active = page == pager.currentPage,
            )
        }
    }
}

@Composable
private fun galleryPageTitle(request: RemotePreview, page: Int, total: Int): String {
    val pageTitle = stringResource(R.string.online_gallery_page_title, page + 1, total)
    val prefix = request.titlePrefix?.takeIf { it.isNotBlank() } ?: request.name
    return if (prefix.isBlank()) pageTitle else "$prefix · $pageTitle"
}

@Composable
private fun PreviewZoomImage(request: RemotePreview, viewModel: FilePreviewViewModel) {
    if (!request.authed) {
        RemoteZoomImage(request, request.url, request.name)
        return
    }

    val context = LocalContext.current
    var retryCount by remember(request) { mutableStateOf(0) }
    var file by remember(request, retryCount) { mutableStateOf<File?>(null) }
    var error by remember(request, retryCount) { mutableStateOf<String?>(null) }
    LaunchedEffect(viewModel, request, retryCount) {
        try {
            file = viewModel.fetchAuthedImage(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = context.resolveUserError(failure, R.string.library_file_image_failed)
        }
    }
    val model = file
    val message = error
    when {
        model != null -> ZoomImage(
            model = model, contentDescription = request.name,
            errorMessage = R.string.library_file_image_failed, onRetry = { retryCount++ },
        )
        message != null -> ImagePreviewError(message) { retryCount++ }
        else -> LoadingState(stringResource(R.string.library_file_loading_image), Modifier.fillMaxSize())
    }
}

@Composable
private fun ImagePreviewError(message: String, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message)
        androidx.compose.material3.TextButton(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
    }
}

@Composable
private fun RemoteZoomImage(
    request: RemotePreview,
    url: String,
    contentDescription: String,
    active: Boolean = true,
) {
    val context = LocalContext.current
    val model = remember(context, url, request.referer) {
        ImageRequest.Builder(context)
            .data(url.toHttps())
            .addHeader("Referer", request.referer.ifBlank { DlsiteClient.STORE_REFERER })
            .addHeader("User-Agent", AsmrClient.USER_AGENT)
            .build()
    }
    ZoomImage(model, contentDescription, active)
}

@Composable
private fun RemoteTextPreview(request: RemotePreview, viewModel: FilePreviewViewModel) {
    var body by remember(request.url) { mutableStateOf<String?>(null) }
    LaunchedEffect(request.url) {
        body = viewModel.remoteText(request)
    }
    val text = body
    if (text == null) {
        LoadingState(stringResource(R.string.library_file_opening), Modifier.fillMaxSize())
    } else {
        SelectionContainer {
            Text(
                text,
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            )
        }
    }
}


@Composable
private fun ExternalOpenContent(request: RemotePreview, localUri: String?, viewModel: FilePreviewViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val session = remember(request, localUri) { viewModel.externalSession() }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var loading by remember { mutableStateOf(false) }
    var bytes by remember { mutableStateOf(0L) }
    var total by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.DisposableEffect(session) {
        onDispose {
            val pending = job
            pending?.cancel()
            if (pending != null) pending.invokeOnCompletion { session.close() } else session.close()
        }
    }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(stringResource(R.string.library_file_preview_unsupported))
        if (loading) {
            com.nanahoshi.audioplayer.ui.components.RoundedProgress(
                progress = total?.takeIf { it > 0 }?.let { bytes.toFloat() / it }, modifier = Modifier.padding(top = 16.dp))
            Text(stringResource(R.string.library_file_preparing, com.nanahoshi.audioplayer.data.formatCacheBytes(bytes)))
            androidx.compose.material3.TextButton(onClick = { job?.cancel() }) { Text(stringResource(R.string.common_cancel)) }
        } else {
            androidx.compose.material3.FilledTonalButton(onClick = {
                loading = true
                error = null
                job = scope.launch {
                    try {
                        val file = session.prepare(request, localUri) { done, length -> bytes = done; total = length }
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                setDataAndType(file.uri, file.mime)
                                clipData = android.content.ClipData.newRawUri("file", file.uri)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(android.content.Intent.createChooser(intent, context.getString(R.string.library_file_open_with_other))
                                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: android.content.ActivityNotFoundException) {
                        error = context.getString(R.string.library_file_no_app)
                    } catch (failure: Exception) {
                        error = if (failure is com.nanahoshi.audioplayer.data.ExternalFileException) context.resolveNoUrl(failure.userText, R.string.library_file_open_other_failed)
                        else context.getString(R.string.library_file_open_other_failed)
                    } finally { loading = false }
                }
            }, modifier = Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.library_file_open_with_other)) }
        }
        error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
    }
}
