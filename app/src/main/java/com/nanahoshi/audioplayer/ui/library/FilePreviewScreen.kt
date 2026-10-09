package com.nanahoshi.audioplayer.ui.library

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
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

    suspend fun text(url: String): String = try {
        graph.asmr.readText(url)
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
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        val current = file
        when {
            missing -> Text("找不到这个文件", modifier = Modifier.padding(16.dp))
            current == null -> CircularProgressIndicator(Modifier.padding(16.dp))
            current.kind == FileKind.OTHER -> Text(
                "未知文件类型，不支持预览",
                modifier = Modifier.padding(16.dp),
            )
            current.kind == FileKind.IMAGE -> {
                if (current.remoteUrl.isBlank()) {
                    Text("无法打开这个文件", modifier = Modifier.padding(16.dp))
                } else {
                    AsyncImage(
                        model = current.remoteUrl.toHttps(),
                        contentDescription = current.name,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        contentScale = ContentScale.Fit,
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
        body = if (file.remoteUrl.isBlank()) "无法打开这个文件" else viewModel.text(file.remoteUrl)
    }
    val text = body
    if (text == null) {
        CircularProgressIndicator(Modifier.padding(16.dp))
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
fun RemotePreviewScreen(
    onBack: () -> Unit,
    viewModel: FilePreviewViewModel = viewModel(),
) {
    val request = remember { viewModel.request() }
    Column(Modifier.fillMaxSize()) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        when {
            request == null -> Text("找不到这个文件", modifier = Modifier.padding(16.dp))
            request.kind == FileKind.OTHER -> Text("未知文件类型，不支持预览", modifier = Modifier.padding(16.dp))
            request.kind == FileKind.IMAGE -> {
                val context = androidx.compose.ui.platform.LocalContext.current
                AsyncImage(
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
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentScale = ContentScale.Fit,
                )
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
        CircularProgressIndicator(Modifier.padding(16.dp))
    } else {
        Text(
            text,
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        )
    }
}
