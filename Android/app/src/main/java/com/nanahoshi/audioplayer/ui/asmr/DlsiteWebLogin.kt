package com.nanahoshi.audioplayer.ui.asmr

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nanahoshi.audioplayer.dlsite.DlsiteClient
import com.nanahoshi.audioplayer.dlsite.mergeDlsiteCookies
import com.nanahoshi.audioplayer.ui.components.AppTopBar

private const val LOGIN_URL = "https://login.dlsite.com/login?user=self"

private val COOKIE_URLS = listOf(
    "https://login.dlsite.com/",
    "https://www.dlsite.com/",
    "https://www.dlsite.com/maniax/",
    "https://play.dlsite.com/",
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun DlsiteWebLoginDialog(
    loading: Boolean,
    error: String?,
    onComplete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var pageLoading by remember { mutableStateOf(true) }
    var localError by remember { mutableStateOf<String?>(null) }
    val webView = remember(context) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = DlsiteClient.USER_AGENT
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportMultipleWindows(false)
            settings.javaScriptCanOpenWindowsAutomatically = false
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    pageLoading = true
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    pageLoading = false
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        pageLoading = false
                        localError = "页面加载失败，请检查网络后重试"
                    }
                }
            }
            webChromeClient = WebChromeClient()
            loadUrl(LOGIN_URL)
        }
    }
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                AppTopBar(
                    title = "登录 DLsite",
                    onBack = onDismiss,
                    actions = {
                        TextButton(
                            enabled = !loading,
                            onClick = {
                                localError = null
                                val cookie = readDlsiteWebCookies()
                                if (cookie.isBlank()) {
                                    localError = "还没有读取到登录信息，请先在页面里完成登录"
                                } else {
                                    onComplete(cookie)
                                }
                            },
                        ) { Text(if (loading) "校验中…" else "完成") }
                    },
                )
                Text(
                    "登录完成后点右上角「完成」。保存时会去掉 Cloudflare 的验证 Cookie。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                if (pageLoading || loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                val message = error ?: localError
                if (message != null) {
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                AndroidView(
                    factory = { webView },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
}

private fun readDlsiteWebCookies(): String {
    val manager = CookieManager.getInstance()
    return mergeDlsiteCookies(*COOKIE_URLS.map { manager.getCookie(it) }.toTypedArray())
}

internal fun clearDlsiteWebCookies() {
    val manager = CookieManager.getInstance()
    manager.removeAllCookies(null)
    manager.flush()
}
