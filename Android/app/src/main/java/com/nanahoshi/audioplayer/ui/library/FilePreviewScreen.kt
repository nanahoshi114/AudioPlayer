package com.nanahoshi.audioplayer.ui.library

import android.app.Application
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import com.nanahoshi.audioplayer.ui.components.AppTopBar
import com.nanahoshi.audioplayer.ui.components.LoadingState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.nanahoshi.audioplayer.asmr.AsmrClient
import com.nanahoshi.audioplayer.asmr.AsmrException
import com.nanahoshi.audioplayer.data.db.FileKind
import com.nanahoshi.audioplayer.dlsite.DlsiteClient
import com.nanahoshi.audioplayer.dlsite.RemotePreview
import com.nanahoshi.audioplayer.data.db.LibraryFileEntity
import com.nanahoshi.audioplayer.data.toHttps
import com.nanahoshi.audioplayer.graph

class FilePreviewViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.
    graph()

    suspend fun file(id: Long): LibraryFileEntity? = graph.library.libraryFile(id)

    fun request(): RemotePreview? = graph.remotePreview.value

    fun externalSession() = com.nanahoshi.audioplayer.data.ExternalFileSession(getApplication(), graph.cache, graph.dlsite)

    suspend fun externalRequest(file: LibraryFileEntity): RemotePreview {
        val authed = com.nanahoshi.audioplayer.data.libraryFileSource(file.fileHash) == com.nanahoshi.audioplayer.data.TrackSource.DLSITE
        val key = file.remoteUrl.substringAfter("/optimized/", "").substringBefore('?').takeIf { it.isNotBlank() }
        val folders = if (authed) graph.db.folders().all().associateBy { it.id } else emptyMap()
        var folder = folders[file.folderId]
        val visited = mutableSetOf<Long>()
        while (folder != null && folder.asmrSourceId.isNullOrBlank() && visited.add(folder.id)) folder = folders[folder.parentId]
        return RemotePreview(file.name, file.remoteUrl, file.kind,
            if (authed) DlsiteClient.PLAY_REFERER else AsmrClient.REFERER, authed,
            workno = if (authed) folder?.asmrSourceId ?: "RJ${file.asmrWorkId}" else null, fileKey = key)
    }

    suspend fun text(url: String): String = try {
        if (url.startsWith("/")) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            java.io.File(url).inputStream().use { input ->
                val bytes = input.takeBytes(512 * 1024 + 1)
                bytes.copyOfRange(0, minOf(bytes.size, 512 * 1024)).toString(Charsets.UTF_8) +
                    if (bytes.size > 512 * 1024) "\n…（只显示前面一段）" else ""
            }
        } else graph.asmr.readText(url)
    } catch (error: AsmrException) {
        error.message ?: "无法打开这个文件"
    } catch (error: Exception) {
        error.message ?: "无法打开这个文件"
    }

    fun cookie(): String? = graph.dlsite.playCookie()

    suspend fun remoteText(request: RemotePreview): String = try {
        if (request.authed || request.referer.contains("dlsite")) {
            graph.dlsite.readText(request.url, request.referer.ifBlank { DlsiteClient.STORE_REFERER }, request.authed)
        } else {
            graph.asmr.readText(request.url)
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        error.message ?: "无法打开这个文件"
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
        AppTopBar(title = file?.name ?: "预览", onBack = onBack)
        val current = file
        when {
            missing -> Text("找不到这个文件", modifier = Modifier.padding(16.dp))
            current == null -> LoadingState("正在打开")
            current.kind == FileKind.OTHER -> {
                var request by remember(current.id) { mutableStateOf<RemotePreview?>(null) }
                LaunchedEffect(current.id) { request = viewModel.externalRequest(current) }
                request?.let { ExternalOpenContent(it, current.localUri, viewModel) } ?: LoadingState("正在打开")
            }
            current.kind == FileKind.IMAGE -> {
                if (current.localUri.isNullOrBlank() && current.remoteUrl.isBlank()) {
                    Text("无法打开这个文件", modifier = Modifier.padding(16.dp))
                } else {
                    ZoomImage(
                        contentDescription = current.name,
                        image = {
                            PreviewImage(
                                model = current.localUri?.let { java.io.File(it) }?.takeIf { it.isFile } ?: current.remoteUrl.toHttps(),
                                contentDescription = current.name,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit,
                            )
                        },
                    )
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
        val path = file.localUri?.takeIf { java.io.File(it).isFile } ?: file.remoteUrl
        body = if (path.isBlank()) "这个文件还没有下载到本机" else viewModel.text(path)
    }
    val text = body
    if (text == null) {
        LoadingState("正在打开")
    } else {
        Text(
            text,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        )
    }
}

@Composable
private fun ZoomImage(
    contentDescription: String,
    image: @Composable () -> Unit,
) {
    var scale by androidx.compose.runtime.remember { mutableFloatStateOf(1f) }
    var offset by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        offset = if (scale <= 1f) Offset.Zero else offset + panChange
    }
    androidx.compose.foundation.layout.Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
            .transformable(state),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        image()
    }
}

@Composable
fun RemotePreviewScreen(
    onBack: () -> Unit,
    viewModel: FilePreviewViewModel = viewModel(),
) {
    val request = remember { viewModel.request() }
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = request?.name ?: "预览", onBack = onBack)
        when {
            request == null -> Text("找不到这个文件", modifier = Modifier.padding(16.dp))
            request.kind == FileKind.OTHER -> ExternalOpenContent(request, null, viewModel)
            request.kind == FileKind.IMAGE -> {
                val context = androidx.compose.ui.platform.LocalContext.current
                ZoomImage(contentDescription = request.name) {
                    PreviewImage(
                        model = coil.request.ImageRequest.Builder(context)
                            .data(request.url.toHttps())
                            .addHeader("Referer", request.referer.ifBlank { DlsiteClient.STORE_REFERER })
                            .addHeader("User-Agent", if (request.authed) DlsiteClient.USER_AGENT else AsmrClient.USER_AGENT)
                            .apply {
                                if (request.authed) {
                                    viewModel.cookie()?.let { addHeader("Cookie", it) }
                                }
                            }
                            .build(),
                        contentDescription = request.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
            else -> RemoteTextPreview(request, viewModel)
        }
    }
}

@Composable
private fun RemoteTextPreview(request: RemotePreview, viewModel: FilePreviewViewModel) {
    var body by remember(request.url) { mutableStateOf<String?>(null) }
    LaunchedEffect(request.url) {
        body = viewModel.remoteText(request)
    }
    val text = body
    if (text == null) {
        LoadingState("正在打开")
    } else {
        Text(
            text,
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        )
    }
}


private fun java.io.InputStream.takeBytes(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (output.size() < limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit - output.size()))
        if (count < 0) break
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
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
        Text("此文件类型暂不支持应用内预览")
        if (loading) {
            com.nanahoshi.audioplayer.ui.components.RoundedProgress(
                progress = total?.takeIf { it > 0 }?.let { bytes.toFloat() / it }, modifier = Modifier.padding(top = 16.dp))
            Text("正在准备文件，已下载 ${com.nanahoshi.audioplayer.data.formatCacheBytes(bytes)}")
            androidx.compose.material3.TextButton(onClick = { job?.cancel() }) { Text("取消") }
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
                            context.startActivity(android.content.Intent.createChooser(intent, "使用其他应用打开")
                                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: android.content.ActivityNotFoundException) {
                        error = "没有可打开此文件的应用"
                    } catch (failure: Exception) {
                        error = if (failure is com.nanahoshi.audioplayer.data.ExternalFileException) failure.message else "无法用其他应用打开这个文件"
                    } finally { loading = false }
                }
            }, modifier = Modifier.padding(top = 12.dp)) { Text("使用其他应用打开") }
        }
        error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
    }
}

@Composable
private fun PreviewImage(model: Any?, contentDescription: String, modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit) {
    var retry by remember(model) { mutableStateOf(0) }
    androidx.compose.runtime.key(retry) {
        coil.compose.SubcomposeAsyncImage(model = model, contentDescription = contentDescription,
            modifier = modifier, contentScale = contentScale,
            loading = { LoadingState("正在读取图片…") },
            error = {
                Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    Text("图片读取失败")
                    androidx.compose.material3.TextButton(onClick = { retry++ }) { Text("重试") }
                }
            })
    }
}
