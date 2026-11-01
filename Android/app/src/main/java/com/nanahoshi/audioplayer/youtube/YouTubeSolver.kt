package com.nanahoshi.audioplayer.youtube

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.RenderProcessGoneDetail
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun interface YouTubeSolver {
    suspend fun solve(player: String, sigs: List<String>, ns: List<String>): String
}

/** 只执行打包组件，不打开 YouTube 页面、不暴露 Java 对象或登录凭证。 */
class WebViewYouTubeSolver(context: Context) : YouTubeSolver {
    private val app = context.applicationContext
    private val lock = Mutex()
    private val component by lazy { app.assets.open("youtube/yt-solver.cjs").bufferedReader().use { it.readText() } }

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun solve(player: String, sigs: List<String>, ns: List<String>): String = lock.withLock {
        withContext(Dispatchers.Main) {
            var view: WebView? = null
            try {
                withTimeout(30_000) {
                    suspendCancellableCoroutine { continuation ->
                        val input = buildJsonObject {
                            put("player", player)
                            putJsonArray("requests") {
                                if (sigs.isNotEmpty()) add(buildJsonObject { put("type", "sig"); put("challenges", JsonArray(sigs.map(::JsonPrimitive))) })
                                if (ns.isNotEmpty()) add(buildJsonObject { put("type", "n"); put("challenges", JsonArray(ns.map(::JsonPrimitive))) })
                            }
                        }.toString()
                        val runtime = WebView(app)
                        view = runtime
                        runtime.settings.apply {
                            javaScriptEnabled = true
                            blockNetworkLoads = true
                            allowFileAccess = false
                            allowContentAccess = false
                            domStorageEnabled = false
                            javaScriptCanOpenWindowsAutomatically = false
                        }
                        runtime.webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(v: WebView?, request: WebResourceRequest?): WebResourceResponse =
                                WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                            override fun onRenderProcessGone(v: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                                if (continuation.isActive) continuation.resumeWithException(YouTubeException("YouTube 脚本运行环境已停止"))
                                return true
                            }
                            override fun onPageFinished(v: WebView?, url: String?) {
                                if (!continuation.isActive) return
                                // 保留桌面打包组件，仅适配 node:fs 输入和 process.stdout 输出。
                                val code = """
                                    (function() {
                                      try {
                                        var output = '';
                                        var input = ${JsonPrimitive(input)};
                                        var require = function(name) {
                                          if (name !== 'node:fs') throw new Error('unsupported module');
                                          return { readFileSync: function() { return input; } };
                                        };
                                        var process = {stdout: {write: function(text) {output += text;}}};
                                        var NativeFunction = Function;
                                        var isolated = {
                                          location: new URL('https://www.youtube.com/watch?v=yt-dlp-wins'),
                                          document: Object.create(null), navigator: Object.create(null),
                                          XMLHttpRequest: {prototype: {}}
                                        };
                                        isolated.window = isolated; isolated.self = isolated;
                                        // 桌面脚本会赋值 globalThis.location。用隔离对象接收，避免在 WebView 内触发导航。
                                        function ScriptFunction() {
                                          var args = Array.prototype.slice.call(arguments);
                                          var body = args.pop();
                                          var names = ['globalThis','window','self','location','document','navigator','XMLHttpRequest'];
                                          var values = [isolated,isolated,isolated,isolated.location,isolated.document,isolated.navigator,isolated.XMLHttpRequest];
                                          var fn = NativeFunction.apply(null, names.concat(args, [body]));
                                          return fn.bind.apply(fn, [isolated].concat(values));
                                        }
                                        (new NativeFunction('require', 'process', 'Function', ${JsonPrimitive(component)}))(require, process, ScriptFunction);
                                        return output;
                                      } catch(e) { return JSON.stringify({error: true}); }
                                    })();
                                """.trimIndent()
                                runtime.evaluateJavascript(code) { result ->
                                    if (!continuation.isActive) return@evaluateJavascript
                                    val output = runCatching { (Json.parseToJsonElement(result) as? JsonPrimitive)?.contentOrNull }.getOrNull()
                                    if (output == null) continuation.resumeWithException(YouTubeException("YouTube 签名解析失败，请更新系统 WebView 后重试"))
                                    else continuation.resume(output)
                                }
                            }
                        }
                        runtime.loadUrl("about:blank")
                    }
                }
            } catch (_: TimeoutCancellationException) {
                throw YouTubeException("YouTube 签名解析超时")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: YouTubeException) {
                throw error
            } catch (_: Exception) {
                throw YouTubeException("YouTube 脚本运行失败，请更新系统 WebView 后重试")
            } finally {
                view?.stopLoading()
                view?.destroy()
            }
        }
    }
}
