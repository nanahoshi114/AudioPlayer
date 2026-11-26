package com.nanahoshi.audioplayer.ui.asmr

import android.content.Context
import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.japaneseasmr.JaArticle
import com.nanahoshi.audioplayer.japaneseasmr.JaVisibleDownload
import com.nanahoshi.audioplayer.japaneseasmr.JapaneseAsmrClient
import com.nanahoshi.audioplayer.japaneseasmr.buzzCopyDownloadLink
import com.nanahoshi.audioplayer.japaneseasmr.buzzDownloadEndpoint
import com.nanahoshi.audioplayer.japaneseasmr.hasBuzzDownloadFile
import com.nanahoshi.audioplayer.japaneseasmr.hasJaDownloadLinks
import com.nanahoshi.audioplayer.japaneseasmr.isBuzzBrowserPage
import com.nanahoshi.audioplayer.japaneseasmr.isBuzzDownloadEndpoint
import com.nanahoshi.audioplayer.japaneseasmr.isBuzzHost
import com.nanahoshi.audioplayer.japaneseasmr.isJaBrowserPage
import com.nanahoshi.audioplayer.japaneseasmr.isJaDownloadRedirect
import com.nanahoshi.audioplayer.japaneseasmr.jaPostId
import com.nanahoshi.audioplayer.text.resolveUserError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class VisibleArticle(val pageUrl: String, val html: String)

/** [JapaneseAsmrWebScripts.BUZZ_PROBE] 的返回内容；字段为空表示脚本尚未捕获到对应地址。 */
@Serializable
internal data class BuzzProbe(
    val direct: String? = null,
    val file: String? = null,
    val next: String? = null,
)

private val probeJson = Json { ignoreUnknownKeys = true }
private const val MAX_VISIBLE_HTML_BYTES = 512_000

/**
 * 解码 `evaluateJavascript` 回调参数：脚本返回字符串时，回调收到的是带转义的 JSON 字符串字面量。
 * 返回 null 或非字符串结果时得到 null；不能用 trim('"') 剥引号，否则转义字符会残留。
 */
internal fun decodeJsString(result: String?): String? {
    val raw = result?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
    return runCatching { probeJson.decodeFromString<String>(raw) }.getOrNull()
}

internal fun decodeJsBoolean(result: String?): Boolean = result?.trim() == "true"

internal fun decodeVisibleArticle(result: String?): VisibleArticle? {
    val encoded = decodeJsString(result) ?: return null
    val article = runCatching { probeJson.decodeFromString<VisibleArticle>(encoded) }.getOrNull() ?: return null
    return article.takeIf { it.html.toByteArray(Charsets.UTF_8).size <= MAX_VISIBLE_HTML_BYTES }
}

internal fun decodeBuzzProbe(result: String?): BuzzProbe? {
    val encoded = decodeJsString(result) ?: return null
    val probe = runCatching { probeJson.decodeFromString<BuzzProbe>(encoded) }.getOrNull() ?: return null
    return BuzzProbe(
        direct = probe.direct?.takeIf { it.isNotEmpty() },
        file = probe.file?.takeIf { it.isNotEmpty() },
        next = probe.next?.takeIf { it.isNotEmpty() },
    )
}

internal fun isChallengeAddress(address: String): Boolean =
    address.contains("/cdn-cgi/", ignoreCase = true) || address.contains("challenge-platform", ignoreCase = true)

internal fun canLoadVerificationAddress(address: String, buzzheavier: Boolean): Boolean =
    if (buzzheavier) isBuzzBrowserPage(address) else isJaBrowserPage(address)

/**
 * 验证页的状态与逻辑：持有 WebView 引用、网页状态、Buzzheavier 地址捕获与读取流程。
 * 界面只读取这里的状态并转发点击，站点验证和会话始终由可见网页处理。
 */
@Stable
internal class JapaneseAsmrWebController(
    private val url: String,
    val buzzheavier: Boolean,
    private val context: Context,
    private val scope: CoroutineScope,
    private val client: JapaneseAsmrClient,
) {
    var onDownload: (suspend (JaVisibleDownload) -> Unit)? = null
    var onArticle: (JaArticle) -> Unit = {}
    var expectedRj: String? = null

    var webView by mutableStateOf<WebView?>(null)
    var openedPage by mutableStateOf<String?>(null)
    var loading by mutableStateOf(true)
    var reading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var hostingUnavailable by mutableStateOf(false)
    var downloadRedirect by mutableStateOf<String?>(null)
    var downloadEntryVisible by mutableStateOf(false)
    var buzzDirect by mutableStateOf<String?>(null)
    var buzzFile by mutableStateOf<String?>(null)
    var buzzLanding by mutableStateOf<JaVisibleDownload?>(null)

    val initialPostId: String? = jaPostId(url)

    private var navigation by mutableStateOf(0)
    private var awaitingBuzzDownload by mutableStateOf(false)
    private var buzzTopLevelTried by mutableStateOf(false)
    private var buzzCopyTried by mutableStateOf(false)
    private var buzzAutoTried by mutableStateOf(false)
    private var buzzNext by mutableStateOf<String?>(null)

    fun canLoad(address: String): Boolean = canLoadVerificationAddress(address, buzzheavier)

    /** 顶栏主按钮是否可点。 */
    val actionEnabled: Boolean
        get() = !loading && !reading &&
            (if (hostingUnavailable) true
            else if (onDownload == null) openedPage?.let(::jaPostId) != null
            else openedPage != null || downloadRedirect != null || buzzDirect != null || buzzFile != null)

    fun reloadPage(view: WebView) {
        navigation++
        buzzFile = null
        buzzDirect = null
        buzzAutoTried = false
        buzzLanding = null
        downloadRedirect = null
        hostingUnavailable = false
        loading = true
        error = null
        view.reload()
    }

    fun leave(onBack: () -> Unit) {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onBack()
    }

    private suspend fun readDownload(visible: JaVisibleDownload) {
        try {
            onDownload?.invoke(visible)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = context.resolveUserError(failure, R.string.ja_verify_read_failed)
        } finally {
            reading = false
        }
    }

    private fun captureBuzzDirect(address: String, view: WebView?) {
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

    private fun captureBuzzFile(address: String, view: WebView?) {
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

    private fun handleBuzzProbe(probe: BuzzProbe, view: WebView) {
        probe.direct?.let { captureBuzzDirect(it, view) }
        probe.file?.let { captureBuzzFile(it, view) }
        val next = probe.next
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

    /** 持续读取网页脚本捕获到的地址；手动点击网页按钮时也能生效。每轮只发一次合并探针。 */
    suspend fun pollBuzzCapture() {
        if (!buzzheavier) return
        while (buzzDirect == null) {
            delay(500)
            val view = webView ?: continue
            runCatching {
                view.evaluateJavascript(JapaneseAsmrWebScripts.BUZZ_PROBE) { result ->
                    decodeBuzzProbe(result)?.let { handleBuzzProbe(it, view) }
                }
            }
        }
    }

    /** 自动点 Download File 后若一直没拿到最终地址，允许用户重新操作。 */
    suspend fun watchCaptureTimeout() {
        if (!reading || !buzzheavier || buzzDirect != null) return
        delay(30_000)
        if (reading && buzzDirect == null) {
            reading = false
            awaitingBuzzDownload = false
            val view = webView
            if (view == null) {
                error = context.getString(R.string.ja_verify_capture_failed)
            } else {
                view.evaluateJavascript(JapaneseAsmrWebScripts.BUZZ_DIAGNOSTIC) { result ->
                    val info = decodeJsString(result)?.takeIf { it.isNotBlank() }
                    val action = context.getString(R.string.ja_verify_capture_failed)
                    error = if (info == null) action
                    else "$action\n\n${context.getString(R.string.ja_verify_capture_failed_info, info)}"
                }
            }
        }
    }

    /** Buzzheavier 页面加载完成后自动读取；用户只需处理真正出现的验证页。 */
    suspend fun autoStartBuzzRead(autoRead: Boolean) {
        if (!autoRead || !buzzheavier) return
        val landing = buzzLanding ?: return
        if (reading || buzzAutoTried || buzzDirect != null || buzzFile != null) return
        val view = webView ?: return
        delay(500)
        if (buzzLanding == landing && !reading && !buzzAutoTried &&
            buzzDirect == null && buzzFile == null
        ) startBuzzRead(view, manual = false)
    }

    private fun startBuzzRead(view: WebView, manual: Boolean) {
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
                view.evaluateJavascript(JapaneseAsmrWebScripts.BUZZ_CAPTURE, null)
                view.evaluateJavascript(JapaneseAsmrWebScripts.CLICK_BUZZ_DOWNLOAD) { result ->
                    scope.launch {
                        if (!decodeJsBoolean(result)) {
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

    /** 顶栏主按钮：读取下载入口或公开播放器曲目。 */
    fun onActionClick() {
        val view = webView ?: return
        if (hostingUnavailable) {
            reloadPage(view)
            return
        }
        if (onDownload != null) {
            reading = true
            error = null
            if (buzzheavier) {
                startBuzzRead(view, manual = true)
                return
            }
            val target = downloadRedirect
            if (target != null) {
                scope.launch { readDownload(JaVisibleDownload(target, "")) }
            } else {
                val revision = navigation
                view.evaluateJavascript(JapaneseAsmrWebScripts.READ_DOWNLOAD_PAGE) { result ->
                    scope.launch {
                        if (revision != navigation) return@launch
                        val visible = decodeVisibleArticle(result)
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
            return
        }
        val page = openedPage ?: return
        val postId = jaPostId(page) ?: return
        if (initialPostId != null && postId != initialPostId) {
            error = context.getString(R.string.ja_verify_return_original)
            return
        }
        val revision = navigation
        reading = true
        error = null
        view.evaluateJavascript(JapaneseAsmrWebScripts.READ_PUBLIC_PLAYER) { result ->
            scope.launch {
                try {
                    if (revision != navigation) return@launch
                    val visible = decodeVisibleArticle(result)
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
                } catch (failure: Exception) {
                    if (revision == navigation) error = context.resolveUserError(failure, R.string.ja_verify_read_track_failed)
                } finally {
                    if (revision == navigation) reading = false
                }
            }
        }
    }

    /** 初始化 WebView：关闭文件访问与多窗口，挂载客户端和下载监听后加载地址。 */
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    fun configure(view: WebView) {
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.allowFileAccess = false
        view.settings.allowContentAccess = false
        view.settings.setSupportMultipleWindows(false)
        view.settings.javaScriptCanOpenWindowsAutomatically = false
        view.webViewClient = VerificationWebViewClient()
        view.setDownloadListener { address, _, _, _, _ ->
            if (buzzheavier && isBuzzHost(address) && !isBuzzDownloadEndpoint(address)) {
                captureBuzzDirect(address, view)
            }
        }
        webView = view
        view.loadUrl(url)
    }

    private inner class VerificationWebViewClient : WebViewClient() {
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
            if (address != view.url) return
            loading = false
            if (buzzheavier) view.evaluateJavascript(JapaneseAsmrWebScripts.BUZZ_CAPTURE, null)
            if (hostingUnavailable) return
            openedPage = if (buzzheavier) address else address?.takeIf(::canLoad)
            if (buzzheavier && address != null && isBuzzBrowserPage(address)) {
                view.evaluateJavascript(JapaneseAsmrWebScripts.READ_DOWNLOAD_PAGE) { result ->
                    scope.launch {
                        val visible = decodeVisibleArticle(result) ?: return@launch
                        if (isBuzzBrowserPage(visible.pageUrl) &&
                            hasBuzzDownloadFile(visible.pageUrl, visible.html)
                        ) buzzLanding = JaVisibleDownload(
                            visible.pageUrl, visible.html,
                            userAgent = view.settings.userAgentString,
                            copyLink = buzzCopyDownloadLink(visible.pageUrl, visible.html),
                        )
                    }
                }
            } else if (onDownload != null && address != null && canLoad(address)) {
                // MEGA 等旧入口验证后会显示跳转提示，此时页面里已经有下载地址，
                // 不需要等文件清单出现。
                view.evaluateJavascript(JapaneseAsmrWebScripts.READ_DOWNLOAD_PAGE) { result ->
                    scope.launch {
                        val visible = decodeVisibleArticle(result) ?: return@launch
                        if (canLoad(visible.pageUrl)) {
                            downloadEntryVisible = hasJaDownloadLinks(visible.pageUrl, visible.html)
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
}
