package com.nanahoshi.audioplayer.ui.asmr

import com.nanahoshi.audioplayer.text.resolve
import com.nanahoshi.audioplayer.R
import androidx.compose.ui.res.stringResource
import android.annotation.SuppressLint
import android.app.Application
import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nanahoshi.audioplayer.graph
import com.nanahoshi.audioplayer.japaneseasmr.JaArticle
import com.nanahoshi.audioplayer.japaneseasmr.JaVisibleDownload
import com.nanahoshi.audioplayer.japaneseasmr.JaWorkException
import com.nanahoshi.audioplayer.japaneseasmr.isJaDownloadRedirect
import com.nanahoshi.audioplayer.japaneseasmr.JapaneseAsmrException
import com.nanahoshi.audioplayer.japaneseasmr.hasBuzzDownloadFile
import com.nanahoshi.audioplayer.japaneseasmr.hasJaDownloadLinks
import com.nanahoshi.audioplayer.japaneseasmr.buzzCopyDownloadLink
import com.nanahoshi.audioplayer.japaneseasmr.buzzDownloadEndpoint
import com.nanahoshi.audioplayer.japaneseasmr.isBuzzBrowserPage
import com.nanahoshi.audioplayer.japaneseasmr.isBuzzDownloadEndpoint
import com.nanahoshi.audioplayer.japaneseasmr.isBuzzHost
import com.nanahoshi.audioplayer.japaneseasmr.isJaBrowserPage
import com.nanahoshi.audioplayer.japaneseasmr.jaPostId
import com.nanahoshi.audioplayer.ui.components.AppTopBar
import com.nanahoshi.audioplayer.ui.components.EmptyState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class VisibleArticle(val pageUrl: String, val html: String)

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun JapaneseAsmrVerificationScreen(
    url: String,
    expectedRj: String?,
    onBack: () -> Unit,
    onArticle: (JaArticle) -> Unit = {},
    onDownload: (suspend (JaVisibleDownload) -> Unit)? = null,
    autoRead: Boolean = false,
) {
    val buzzheavier = onDownload != null && isBuzzBrowserPage(url)
    fun canLoad(address: String): Boolean =
        if (buzzheavier) isBuzzBrowserPage(address) else isJaBrowserPage(address)
    if (!canLoad(url)) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(title = "Japanese ASMR", onBack = onBack)
            EmptyState(
                title = stringResource(R.string.ja_verify_address_unavailable_title),
                message = stringResource(R.string.ja_verify_address_unavailable_message),
            )
        }
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = (context.applicationContext as Application).graph().japaneseAsmr
    var webView by remember { mutableStateOf<WebView?>(null) }
    var openedPage by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var reading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var hostingUnavailable by remember { mutableStateOf(false) }
    var navigation by remember { mutableStateOf(0) }
    val initialPostId = jaPostId(url)
    var downloadRedirect by remember { mutableStateOf<String?>(null) }
    var downloadEntryVisible by remember { mutableStateOf(false) }
    var buzzDirect by remember { mutableStateOf<String?>(null) }
    var buzzLanding by remember { mutableStateOf<JaVisibleDownload?>(null) }
    var awaitingBuzzDownload by remember { mutableStateOf(false) }
    var buzzTopLevelTried by remember { mutableStateOf(false) }
    var buzzCopyTried by remember { mutableStateOf(false) }
    var buzzAutoTried by remember { mutableStateOf(false) }
    var buzzNext by remember { mutableStateOf<String?>(null) }
    var buzzFile by remember { mutableStateOf<String?>(null) }

    suspend fun readDownload(visible: JaVisibleDownload) {
        try {
            onDownload?.invoke(visible)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: JaWorkException) {
            error = context.resolve(failure.userText)
        } catch (failure: JapaneseAsmrException) {
            error = context.resolve(failure.userText)
        } catch (_: Exception) {
            error = context.getString(R.string.ja_verify_read_failed)
        } finally {
            reading = false
        }
    }

    fun captureBuzzDirect(address: String, view: WebView?) {
        if (!buzzheavier || buzzDirect != null || !isBuzzHost(address) ||
            isBuzzDownloadEndpoint(address) || isChallengeAddress(address) ||
            address == url || address == buzzLanding?.url
        ) return
        buzzDirect = address
        awaitingBuzzDownload = false
        navigation++
        loading = false
        error = null
        view?.stopLoading()
        val landing = buzzLanding
        if (reading && landing != null) {
            scope.launch { readDownload(landing.copy(direct = address)) }
        } else {
            reading = false
        }
    }

    fun captureBuzzFile(address: String, view: WebView?) {
        if (!buzzheavier || buzzFile != null || !isBuzzHost(address)) return
        buzzFile = address
        awaitingBuzzDownload = false
        navigation++
        loading = false
        error = null
        view?.stopLoading()
        val landing = buzzLanding
        if (reading && landing != null) {
            scope.launch { readDownload(landing.copy(fileUrl = address)) }
        } else {
            reading = false
        }
    }

    // 持续读取网页脚本捕获到的地址；手动点击网页按钮时也能生效。
    LaunchedEffect(buzzheavier) {
        if (!buzzheavier) return@LaunchedEffect
        while (buzzDirect == null) {
            delay(500)
            val view = webView ?: continue
            runCatching {
                view.evaluateJavascript(BUZZ_READ_DIRECT) { result ->
                    val address = result?.trim('"')?.takeIf { it != "null" && it.isNotEmpty() }
                    if (address != null) captureBuzzDirect(address, view)
                }
                view.evaluateJavascript(BUZZ_READ_FILE) { result ->
                    val address = result?.trim('"')?.takeIf { it != "null" && it.isNotEmpty() }
                    if (address != null) captureBuzzFile(address, view)
                }
                view.evaluateJavascript(BUZZ_READ_NEXT) { result ->
                    val next = result?.trim('"')?.takeIf { it != "null" && it.isNotEmpty() }
                    if (next != null && next != buzzNext && isBuzzHost(next) && isBuzzDownloadEndpoint(next)) {
                        buzzNext = next
                        val landing = buzzLanding
                        if (landing != null && next != landing.url) {
                            val updated = landing.copy(copyLink = next)
                            buzzLanding = updated
                            if (reading && !buzzCopyTried) {
                                buzzCopyTried = true
                                scope.launch { readDownload(updated) }
                            }
                        }
                    }
                }
            }
        }
    }

    fun leave() {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onBack()
    }
    BackHandler { leave() }

    // 自动点 Download File 后若一直没拿到最终地址，允许用户重新操作。
    LaunchedEffect(reading, buzzDirect) {
        if (!reading || !buzzheavier || buzzDirect != null) return@LaunchedEffect
        delay(30_000)
        if (reading && buzzDirect == null) {
            reading = false
            awaitingBuzzDownload = false
            val view = webView
            if (view == null) {
                error = context.getString(R.string.ja_verify_capture_failed)
            } else {
                view.evaluateJavascript(BUZZ_DIAGNOSTIC) { result ->
                    val info = result?.trim('"')?.takeIf { it.isNotBlank() && it != "null" }
                    val action = context.getString(R.string.ja_verify_capture_failed)
                    error = if (info == null) action
                    else "$action\n\n${context.getString(R.string.ja_verify_capture_failed_info, info)}"
                }
            }
        }
    }

    fun startBuzzRead(view: WebView, manual: Boolean) {
        reading = true
        error = null
        val file = buzzFile
        val direct = buzzDirect
        val landing = buzzLanding
        when {
            file != null && landing != null -> scope.launch { readDownload(landing.copy(fileUrl = file)) }
            direct != null && landing != null -> scope.launch { readDownload(landing.copy(direct = direct)) }
            file != null || direct != null -> {
                if (manual) error = context.getString(R.string.ja_verify_wait_download)
                reading = false
            }
            landing == null -> {
                if (manual) error = context.getString(R.string.ja_verify_wait_download)
                reading = false
            }
            landing.copyLink != null && buzzAutoTried && !buzzCopyTried -> {
                // 浏览器跟随仍失败时，退回 Copy 链接的原生请求。
                buzzCopyTried = true
                scope.launch { readDownload(landing) }
            }
            else -> {
                buzzAutoTried = true
                // 网页按钮可能走 HTMX/XHR 或新窗口；先监听其请求，
                // 再点击可见的「Download File」，从响应头/响应地址取最终文件地址。
                awaitingBuzzDownload = true
                view.evaluateJavascript(BUZZ_CAPTURE, null)
                view.evaluateJavascript(CLICK_BUZZ_DOWNLOAD) { result ->
                    scope.launch {
                        if (result?.trim('"') != "true") {
                            if (manual) error = context.getString(R.string.ja_verify_manual_download)
                            reading = false
                            awaitingBuzzDownload = false
                        }
                    }
                }
                // 某些站点的按钮只接受 XHR；若 5 秒内没有结果，
                // 用主框架请求同一地址，让 302 或下载回调可见。
                if (!buzzTopLevelTried) {
                    scope.launch {
                        delay(5_000)
                        if (buzzDirect == null && buzzFile == null && !buzzTopLevelTried) {
                            val endpoint = buzzNext ?: buzzDownloadEndpoint(landing.url, landing.html)
                            if (endpoint != null) {
                                buzzTopLevelTried = true
                                awaitingBuzzDownload = true
                                view.loadUrl(endpoint, mapOf(
                                    "HX-Request" to "true",
                                    "HX-Current-URL" to landing.url,
                                    "Referer" to landing.url,
                                ))
                            }
                        }
                    }
                }
            }
        }
    }

    // Buzzheavier 页面加载完成后自动读取；用户只需处理真正出现的验证页。
    LaunchedEffect(buzzLanding, autoRead) {
        if (!autoRead || !buzzheavier) return@LaunchedEffect
        val landing = buzzLanding ?: return@LaunchedEffect
        if (reading || buzzAutoTried || buzzDirect != null || buzzFile != null) return@LaunchedEffect
        val view = webView ?: return@LaunchedEffect
        delay(500)
        if (buzzLanding == landing && !reading && !buzzAutoTried &&
            buzzDirect == null && buzzFile == null
        ) startBuzzRead(view, manual = false)
    }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            title = if (buzzheavier) stringResource(R.string.ja_verify_hosting_title) else stringResource(R.string.ja_verify_web_title),
            onBack = ::leave,
            actions = {
                TextButton(
                    enabled = !loading && !reading &&
                        (if (hostingUnavailable) true
                         else if (onDownload == null) openedPage?.let(::jaPostId) != null
                         else openedPage != null || downloadRedirect != null || buzzDirect != null || buzzFile != null),
                    onClick = {
                        val view = webView ?: return@TextButton
                        if (hostingUnavailable) {
                            loading = true
                            view.reload()
                            return@TextButton
                        }
                        if (onDownload != null) {
                            reading = true
                            error = null
                            if (buzzheavier) {
                                startBuzzRead(view, manual = true)
                                return@TextButton
                            }
                            val target = downloadRedirect
                            if (target != null) {
                                scope.launch { readDownload(JaVisibleDownload(target, "")) }
                            } else {
                                val revision = navigation
                                view.evaluateJavascript(READ_DOWNLOAD_PAGE) { result ->
                                    scope.launch {
                                        if (revision != navigation) return@launch
                                        val visible = decodeVisible(result)
                                        val ready = visible != null && canLoad(visible.pageUrl) && when {
                                            buzzheavier -> hasBuzzDownloadFile(visible.pageUrl, visible.html)
                                            initialPostId != null && jaPostId(visible.pageUrl) == initialPostId -> true
                                            else -> hasJaDownloadLinks(visible.pageUrl, visible.html)
                                        }
                                        if (!ready) {
                                            error = if (buzzheavier) context.getString(R.string.ja_verify_wait_download)
                                                else context.getString(R.string.ja_verify_wait_verify_download)
                                            reading = false
                                        } else {
                                            readDownload(JaVisibleDownload(visible.pageUrl, visible.html))
                                        }
                                    }
                                }
                            }
                            return@TextButton
                        }
                        val page = openedPage ?: return@TextButton
                        val postId = jaPostId(page) ?: return@TextButton
                        if (initialPostId != null && postId != initialPostId) {
                            error = context.getString(R.string.ja_verify_return_original)
                            return@TextButton
                        }
                        val revision = navigation
                        reading = true
                        error = null
                        view.evaluateJavascript(READ_PUBLIC_PLAYER) { result ->
                            scope.launch {
                                try {
                                    if (revision != navigation) return@launch
                                    val visible = decodeVisible(result)
                                    if (visible == null || !isJaBrowserPage(visible.pageUrl) ||
                                        jaPostId(visible.pageUrl) != postId
                                    ) {
                                        error = context.getString(R.string.ja_verify_wait_player_verify)
                                        return@launch
                                    }
                                    val article = client.rememberVerifiedArticle(visible.pageUrl, visible.html, expectedRj)
                                    if (revision == navigation) onArticle(article)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: JapaneseAsmrException) {
                                    if (revision == navigation) error = context.resolve(failure.userText)
                                } catch (_: IllegalArgumentException) {
                                    if (revision == navigation) error = context.getString(R.string.ja_verify_read_track_failed)
                                } finally {
                                    if (revision == navigation) reading = false
                                }
                            }
                        }
                    },
                ) {
                    Text(when {
                        hostingUnavailable -> stringResource(R.string.ja_verify_action_retry)
                        onDownload == null -> stringResource(R.string.ja_verify_action_read_tracks)
                        else -> stringResource(R.string.ja_verify_action_read_downloads)
                    })
                }
            },
        )
        if (!hostingUnavailable) Text(
            when {
                buzzheavier && (buzzFile != null || buzzDirect != null) ->
                    stringResource(R.string.ja_verify_buzz_captured)
                buzzheavier ->
                    stringResource(R.string.ja_verify_buzz_help)
                onDownload != null && initialPostId != null -> stringResource(R.string.ja_verify_post_help)
                onDownload != null && (downloadEntryVisible || downloadRedirect != null) ->
                    stringResource(R.string.ja_verify_entry_visible)
                onDownload != null -> stringResource(R.string.ja_verify_entry_help)
                initialPostId == null -> stringResource(R.string.ja_verify_open_post_help)
                else -> stringResource(R.string.ja_verify_wait_player)
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        error?.let {
            Text(it, modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
        }
        if (loading || reading) LinearProgressIndicator(Modifier.fillMaxWidth())
        AndroidView(
            factory = { host ->
                WebView(host).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.setSupportMultipleWindows(false)
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            if (!request.isForMainFrame) return false
                            val address = request.url.toString()
                            if (buzzheavier) {
                                if (!isBuzzHost(address)) return true
                                if (isChallengeAddress(address)) return false
                                if (isBuzzDownloadEndpoint(address)) {
                                    awaitingBuzzDownload = true
                                    return false
                                }
                                if (awaitingBuzzDownload) {
                                    if (address == url || address == buzzLanding?.url) return false
                                    captureBuzzDirect(address, view)
                                    return true
                                }
                                return false
                            }
                            if (canLoad(address)) return false
                            if (onDownload != null && isJaDownloadRedirect(address)) {
                                navigation++
                                downloadRedirect = address
                                reading = false
                                loading = false
                                error = null
                            }
                            return true
                        }

                        override fun onPageStarted(view: WebView, address: String?, icon: Bitmap?) {
                            navigation++
                            openedPage = null
                            downloadRedirect = null
                            downloadEntryVisible = false
                            if (!(buzzheavier && awaitingBuzzDownload)) reading = false
                            hostingUnavailable = false
                            if (address == null) {
                                view.stopLoading()
                                loading = false
                                error = context.getString(R.string.ja_verify_open_page_failed)
                                return
                            }
                            if (buzzheavier) {
                                when {
                                    !isBuzzHost(address) -> {
                                        view.stopLoading()
                                        loading = false
                                        error = context.getString(R.string.ja_verify_open_page_failed)
                                    }
                                    isChallengeAddress(address) -> {
                                        loading = true
                                        error = null
                                    }
                                    isBuzzDownloadEndpoint(address) -> {
                                        awaitingBuzzDownload = true
                                        loading = true
                                        error = null
                                    }
                                    awaitingBuzzDownload -> {
                                        captureBuzzDirect(address, view)
                                        if (buzzDirect == null) {
                                            loading = true
                                            error = null
                                        }
                                    }
                                    else -> {
                                        loading = true
                                        error = null
                                        if (isBuzzBrowserPage(address)) buzzLanding = null
                                    }
                                }
                                return
                            }
                            if (!canLoad(address)) {
                                view.stopLoading()
                                loading = false
                                if (onDownload != null && isJaDownloadRedirect(address)) {
                                    downloadRedirect = address
                                } else error = context.getString(R.string.ja_verify_open_page_failed)
                            } else {
                                loading = true
                                error = null
                            }
                        }

                        override fun onPageFinished(view: WebView, address: String?) {
                            if (address == view.url) {
                                loading = false
                                if (buzzheavier) view.evaluateJavascript(BUZZ_CAPTURE, null)
                                if (!hostingUnavailable) {
                                    openedPage = if (buzzheavier) address else address?.takeIf(::canLoad)
                                    if (buzzheavier && address != null && isBuzzBrowserPage(address)) {
                                        view.evaluateJavascript(READ_DOWNLOAD_PAGE) { result ->
                                            scope.launch {
                                                val visible = decodeVisible(result) ?: return@launch
                                                if (isBuzzBrowserPage(visible.pageUrl) &&
                                                    hasBuzzDownloadFile(visible.pageUrl, visible.html)
                                                ) buzzLanding = JaVisibleDownload(visible.pageUrl, visible.html,
                                                    userAgent = view.settings.userAgentString,
                                                    copyLink = buzzCopyDownloadLink(visible.pageUrl, visible.html))
                                            }
                                        }
                                    } else if (onDownload != null && address != null && canLoad(address)) {
                                        // MEGA 等旧入口验证后会显示跳转提示，此时页面里已经有下载地址，
                                        // 不需要等文件清单出现。
                                        view.evaluateJavascript(READ_DOWNLOAD_PAGE) { result ->
                                            scope.launch {
                                                val visible = decodeVisible(result) ?: return@launch
                                                if (canLoad(visible.pageUrl)) {
                                                    downloadEntryVisible = hasJaDownloadLinks(visible.pageUrl, visible.html)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                            if (!buzzheavier || !request.isForMainFrame || response.statusCode != 503) return
                            hostingUnavailable = true
                            loading = false
                            error = context.getString(R.string.ja_verify_buzz_unavailable)
                        }

                        override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                            if (!request.isForMainFrame) return
                            loading = false
                            error = context.getString(R.string.ja_verify_page_load_failed)
                        }
                    }
                    setDownloadListener { address, _, _, _, _ ->
                        if (buzzheavier && isBuzzHost(address) && !isBuzzDownloadEndpoint(address)) {
                            captureBuzzDirect(address, this)
                        }
                    }
                    webView = this
                    loadUrl(url)
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            onRelease = { view ->
                view.stopLoading()
                view.destroy()
            },
        )
    }
}

// 只读取主播放器、作品号、封面与章节。站点验证和会话始终由可见网页处理。
private val READ_PUBLIC_PLAYER = """
    (() => {
        const players = Array.from(document.querySelectorAll('.cleanPlayer video, .cleanPlayer audio'));
        const legacy = document.querySelector('#basic-playlist li a[data-url]');
        if (players.length === 0 && !legacy) return null;
        const output = document.createElement('template');
        const root = output.content;
        const inertDocument = root.ownerDocument;
        const copy = (parent, source, tag, attributes, text) => {
            if (!source) return null;
            const target = inertDocument.createElement(tag);
            for (const name of attributes) {
                if (source.hasAttribute(name)) target.setAttribute(name, source.getAttribute(name));
            }
            if (text) target.textContent = source.textContent;
            parent.appendChild(target);
            return target;
        };
        copy(root, document.querySelector('h1.page-title'), 'h1', ['class'], true);
        copy(root, document.querySelector('#voice_actors'), 'span', ['id'], true);
        copy(root, document.querySelector('time[datetime]'), 'time', ['datetime'], false);
        copy(root, document.querySelector('meta[property="og:image"]'), 'meta', ['property', 'content'], false);
        const entry = inertDocument.createElement('div');
        entry.className = 'entry-content';
        for (const line of document.querySelectorAll('.entry-content p')) {
            if (/^\s*RJ Code:/i.test(line.textContent)) copy(entry, line, 'p', [], true);
        }
        copy(entry, document.querySelector('.entry-content img[data-src]'), 'img', ['data-src'], false);
        root.appendChild(entry);
        const cleanPlayer = inertDocument.createElement('div');
        cleanPlayer.className = 'cleanPlayer';
        for (const player of players) {
            const node = copy(cleanPlayer, player, player.localName, ['title', 'descr', 'src', 'type'], false);
            for (const source of player.querySelectorAll('source[src]')) {
                copy(node, source, 'source', ['src', 'type'], false);
            }
        }
        root.appendChild(cleanPlayer);
        const chapters = document.querySelector('#plyr-chapter-playlist');
        if (chapters) root.appendChild(chapters.cloneNode(true));
        for (const link of document.querySelectorAll('a[href]')) {
            if (link.pathname === '/dlc.php' || /Download/i.test(link.textContent)) {
                const node = copy(entry, link, 'a', ['href'], true);
                node.setAttribute('href', link.href);
            }
        }
        if (legacy) {
            const playlist = inertDocument.createElement('ul');
            playlist.id = 'basic-playlist';
            const item = inertDocument.createElement('li');
            copy(item, legacy, 'a', ['data-url'], false);
            playlist.appendChild(item);
            root.appendChild(playlist);
        }
        const html = output.innerHTML;
        return html.length <= 512000 ? JSON.stringify({ pageUrl: location.href, html }) : null;
    })()
""".trimIndent()

private fun decodeVisible(result: String?): VisibleArticle? = runCatching {
    val encoded = result?.takeUnless { it == "null" } ?: return null
    Json.decodeFromString<VisibleArticle>(Json.decodeFromString<String>(encoded))
}.getOrNull()?.takeIf { it.html.toByteArray(Charsets.UTF_8).size <= 512_000 }

private fun isChallengeAddress(address: String): Boolean =
    address.contains("/cdn-cgi/", ignoreCase = true) || address.contains("challenge-platform", ignoreCase = true)

private const val BUZZ_READ_DIRECT = "window.__jaBuzzDirect || null"
private const val BUZZ_READ_NEXT = "window.__jaBuzzNext || null"
private const val BUZZ_READ_FILE = "window.__jaBuzzFile || null"

// 超时诊断只报告按钮类型、请求状态和响应类型，不包含 URL 或令牌。
private val BUZZ_DIAGNOSTIC = """
    (() => {
        const elements = Array.from(document.querySelectorAll('button, a, [role="button"]'));
        const el = elements.find((x) => /download\s*file/i.test((x.textContent || '').trim())) ||
            document.querySelector('[hx-get*="download"], a[href*="/download"], [data-url*="download"]');
        const last = window.__jaBuzzLast || {};
        return (el ? el.tagName : 'NONE') + (el && el.hasAttribute('hx-get') ? '|HX' : '') +
            (el && el.hasAttribute('href') ? '|HREF' : '') + '|XHR' + (window.__jaBuzzXhrCount || 0) +
            '|' + (last.status || 0) + '|' + (last.type || '') + '|' + (last.redirect ? 'R' : '-') +
            '|' + (window.__jaBuzzDirect ? 'D' : '-') + (window.__jaBuzzEndpointLink ? 'E' : '-') +
            (window.__jaBuzzFile ? 'V' : '-') +
            '|F' + (window.__jaBuzzFollowStatus || 0) + '|' + (window.__jaBuzzFollowType || '') +
            '|' + (window.__jaBuzzFollowHost || '') + (window.__jaBuzzFollowError ? 'X' : '');
    })()
""".trimIndent()

// 监听网页自己的 Download File 请求；HTMX 的 HX-Redirect、XHR/fetch 的最终地址以及
// 返回 HTML/JSON 中间的下载链接都不一定触发主框架导航。
private val BUZZ_CAPTURE = """
    (() => {
        if (window.__jaBuzzCapture) return;
        window.__jaBuzzCapture = true;
        window.__jaBuzzDirect = null;
        window.__jaBuzzNext = null;
        window.__jaBuzzFile = null;
        window.__jaBuzzLast = null;
        window.__jaBuzzFollowStatus = 0;
        window.__jaBuzzFollowType = '';
        window.__jaBuzzFollowHost = '';
        window.__jaBuzzFollowError = false;
        const absolute = (url) => {
            if (!url) return null;
            try { return new URL(url, location.href).href; } catch (e) { return null; }
        };
        const endpointLike = (url) => {
            try {
                const path = new URL(url, location.href).pathname;
                return /\/[A-Za-z0-9_-]+\/download$/.test(path) || /\/d\/[A-Za-z0-9_-]+$/.test(path);
            } catch (e) { return false; }
        };
        const useful = (url) => {
            const value = absolute(url);
            if (!value) return null;
            try {
                const host = new URL(value).hostname;
                if (!(/(^|\.)buzzheavier\.com$/.test(host) || host === 'mega.nz' || host === 'mega.co.nz')) return null;
            } catch (e) { return null; }
            return endpointLike(value) ? null : value;
        };
        const scan = (text) => {
            if (!text || text.length > 1000000) return null;
            const parsed = new DOMParser().parseFromString(text, 'text/html');
            const links = Array.from(parsed.querySelectorAll('a[href], [hx-get], [data-url], [data-clipboard-text]'))
                .map((el) => el.getAttribute('href') || el.getAttribute('hx-get') ||
                    el.getAttribute('data-url') || el.getAttribute('data-clipboard-text'));
            for (const link of links) {
                const value = absolute(link);
                if (!value) continue;
                if (endpointLike(value)) { window.__jaBuzzEndpointLink = true; continue; }
                const url = useful(value);
                if (url) return url;
            }
            const urls = text.match(/https?:\/\/[^"'\s<>]+/g) || [];
            for (const raw of urls) {
                const value = absolute(raw.replace(/&amp;/g, '&'));
                if (!value) continue;
                if (endpointLike(value)) { window.__jaBuzzEndpointLink = true; continue; }
                const url = useful(value);
                if (url) return url;
            }
            return null;
        };
        const report = (url) => {
            const value = useful(url);
            if (value) window.__jaBuzzDirect = value;
        };
        const record = (xhr) => {
            try {
                window.__jaBuzzLast = {
                    status: xhr.status,
                    type: (xhr.getResponseHeader('Content-Type') || '').split(';')[0].slice(0, 40),
                    redirect: !!(xhr.getResponseHeader('HX-Redirect') || xhr.getResponseHeader('Location')),
                };
            } catch (e) {}
        };
        // 浏览器会话内继续跟随 HX-Redirect/Location，直到拿到真正的文件地址。
        const follow = (start) => {
            if (!start || window.__jaBuzzFollowing) return;
            window.__jaBuzzFollowing = true;
            let url = absolute(start);
            let hops = 0;
            const step = () => {
                if (!url || hops++ > 6) { window.__jaBuzzFollowing = false; return; }
                fetch(url, {
                    // HX-Redirect 的目标是正常文档/文件地址；不要再带 HTMX 请求头，
                    // 否则服务端可能继续返回 204 + HX-Redirect，形成循环。
                    headers: { 'Referer': location.href },
                    credentials: 'include',
                    redirect: 'follow',
                }).then((response) => {
                    window.__jaBuzzFollowStatus = response.status;
                    window.__jaBuzzFollowType = (response.headers.get('Content-Type') || '').split(';')[0].slice(0, 30);
                    try {
                        const host = new URL(response.url).hostname;
                        window.__jaBuzzFollowHost = /(^|\.)buzzheavier\.com$/.test(host) ? 'B' : 'O';
                    } catch (e) { window.__jaBuzzFollowHost = '?'; }
                    const header = response.headers.get('HX-Redirect') || response.headers.get('Location');
                    if (header) { url = absolute(header); step(); return; }
                    const type = (response.headers.get('Content-Type') || '').toLowerCase();
                    const attachment = (response.headers.get('Content-Disposition') || '').toLowerCase().includes('attachment');
                    if (response.url && response.url !== location.href && (!type.includes('text/html') || attachment)) {
                        window.__jaBuzzFile = response.url;
                        window.__jaBuzzDirect = response.url;
                        window.__jaBuzzFollowing = false;
                        return;
                    }
                    if (type.includes('text/html') || type.includes('application/json')) {
                        response.clone().text().then((text) => {
                            const link = scan(text);
                            if (link) { url = link; step(); }
                            else { window.__jaBuzzNext = response.url || url; window.__jaBuzzFollowing = false; }
                        }).catch(() => { window.__jaBuzzFollowing = false; });
                    } else {
                        window.__jaBuzzNext = response.url || url;
                        window.__jaBuzzFollowing = false;
                    }
                }).catch(() => { window.__jaBuzzFollowError = true; window.__jaBuzzFollowing = false; });
            };
            step();
        };
        const pick = (xhr) => {
            if (!xhr) return null;
            try {
                const header = xhr.getResponseHeader('HX-Redirect') || xhr.getResponseHeader('Location');
                if (header) {
                    window.__jaBuzzNext = absolute(header);
                    report(header);
                    follow(header);
                } else if (xhr.responseURL && xhr.responseURL !== location.href) {
                    report(xhr.responseURL);
                }
                if (!window.__jaBuzzDirect) {
                    let text = null;
                    if (xhr.responseType === '' || xhr.responseType === 'text') text = xhr.responseText;
                    else if (xhr.responseType === 'json') text = JSON.stringify(xhr.response);
                    if (text) report(scan(text));
                }
            } catch (e) {}
        };
        document.addEventListener('htmx:afterRequest', (event) => {
            try { pick(event.detail && event.detail.xhr); } catch (e) {}
        });
        const open = XMLHttpRequest.prototype.open;
        XMLHttpRequest.prototype.open = function(method, url, ...rest) {
            window.__jaBuzzXhrCount = (window.__jaBuzzXhrCount || 0) + 1;
            this.addEventListener('loadend', () => { record(this); pick(this); });
            return open.call(this, method, url, ...rest);
        };
        const originalFetch = window.fetch;
        if (originalFetch) {
            window.fetch = function(...args) {
                window.__jaBuzzXhrCount = (window.__jaBuzzXhrCount || 0) + 1;
                return originalFetch.apply(this, args).then((response) => {
                    try {
                        const type = (response.headers.get('Content-Type') || '').toLowerCase();
                        window.__jaBuzzLast = {
                            status: response.status,
                            type: type.split(';')[0].slice(0, 40),
                            redirect: !!(response.headers.get('HX-Redirect') || response.headers.get('Location')),
                        };
                        const header = response.headers.get('HX-Redirect') || response.headers.get('Location');
                        if (header) {
                            window.__jaBuzzNext = absolute(header);
                            report(header);
                        } else if (response.url && response.url !== location.href) {
                            report(response.url);
                        }
                        if (!window.__jaBuzzDirect && (type.includes('text/html') || type.includes('application/json'))) {
                            response.clone().text().then((text) => report(scan(text))).catch(() => {});
                        }
                    } catch (e) {}
                    return response;
                });
            };
        }
        const openWindow = window.open;
        if (openWindow) {
            window.open = function(url, ...rest) {
                try { report(url); } catch (e) {}
                return openWindow.call(this, url, ...rest);
            };
        }
    })()
""".trimIndent()

private val CLICK_BUZZ_DOWNLOAD = """
    (() => {
        const elements = Array.from(document.querySelectorAll('button, a, [role="button"]'));
        const byText = elements.find((el) => /download\s*file/i.test((el.textContent || '').trim())) ||
            elements.find((el) => /^\s*download\s*$/i.test((el.textContent || '').trim()));
        const button = byText ||
            document.querySelector('[hx-get*="download"], a[href*="/download"], [data-url*="download"]');
        if (!button) return false;
        button.click();
        return true;
    })()
""".trimIndent()

private val READ_DOWNLOAD_PAGE = """
    (() => {
        const html = document.documentElement.outerHTML;
        return html.length <= 512000 ? JSON.stringify({ pageUrl: location.href, html }) : null;
    })()
""".trimIndent()
