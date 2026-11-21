package com.nanahoshi.audioplayer.ui.importing

import com.nanahoshi.audioplayer.text.resolve
import com.nanahoshi.audioplayer.R

import com.nanahoshi.audioplayer.asmr.WorkFacts
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nanahoshi.audioplayer.bilibili.BiliPart
import com.nanahoshi.audioplayer.bilibili.BilibiliException
import com.nanahoshi.audioplayer.data.*
import com.nanahoshi.audioplayer.graph
import com.nanahoshi.audioplayer.youtube.YouTubeException
import com.nanahoshi.audioplayer.youtube.YouTubeVideo
import com.nanahoshi.audioplayer.japaneseasmr.JaArticle
import com.nanahoshi.audioplayer.japaneseasmr.JaFailure
import com.nanahoshi.audioplayer.japaneseasmr.JapaneseAsmrClient
import com.nanahoshi.audioplayer.japaneseasmr.JapaneseAsmrException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun importCandidate(clip: ClipData): ClipboardImportCandidate? {
    val timestamp = if (Build.VERSION.SDK_INT >= 26) clip.description.timestamp else 0
    var bareWork: ClipboardImportCandidate? = null
    for (index in 0 until minOf(clip.itemCount, 10)) {
        val item = clip.getItemAt(index)
        // 不用 coerceToText，避免自动访问剪贴板中的 content: URI 或 Intent。
        val text = item.text?.take(MAX_IMPORT_CLIPBOARD_LENGTH)?.toString()
        val target = text?.let(::clipboardImportTarget)
            ?: item.uri?.takeIf { it.scheme == "https" || it.scheme == "http" }?.toString()
                ?.let(::clipboardImportTarget)
        if (target != null) return ClipboardImportCandidate(target, timestamp)
        if (bareWork == null && text != null) bareWork = clipboardBareWorkCandidate(text, timestamp)
    }
    return bareWork
}

class ClipboardImportViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph()
    private val preferences = app.getSharedPreferences("clipboard_import", Context.MODE_PRIVATE)
    private var inspectJob: Job? = null
    internal val controller = ClipboardImportController(viewModelScope, object : ClipboardImportBackend {
        override suspend fun preview(target: ClipboardTarget): ClipboardImportPreview? = withContext(Dispatchers.IO) {
            when (target.source) {
                ClipboardSource.BILIBILI -> {
                    val details = try {
                        graph.bilibili.videoDetails(target.id)
                    } catch (error: BilibiliException) {
                        if (error.code == -404 || error.code == 404) return@withContext null
                        throw error
                    }
                    val parts = details.parts.filter { it.cid > 0 }.distinctBy { it.cid }
                    check(parts.isNotEmpty())
                    val saved = parts.all { graph.db.tracks().findByBvid(it.bvid, it.cid) != null }
                    ClipboardImportPreview(target, details.video.title, details.video.coverUrl, saved, parts, biliVideo = details.video)
                }
                ClipboardSource.ASMR -> {
                    val saved = graph.library.folderBySource(target.id)
                    if (saved != null) ClipboardImportPreview(target, saved.name, saved.coverUri, alreadySaved = true)
                    else clipboardWorkPreview(target, graph.asmr::lookupCatalog, graph.dlsite::lookupProduct, graph.dlsite::isPurchased)
                }
                ClipboardSource.JAPANESE_ASMR -> {
                    val post = graph.db.japaneseAsmr().post(target.id)
                    val root = post?.rootFolderId?.let { graph.library.folder(it) }
                    if (post != null && root?.asmrSourceId?.equals(post.rj, ignoreCase = true) == true &&
                        graph.db.japaneseAsmr().mediaForPost(post.postId).any { row -> row.trackId?.let { graph.library.track(it) } != null }
                    ) {
                        return@withContext ClipboardImportPreview(target, post.title, post.coverUri, alreadySaved = true,
                            jaArticle = JaArticle(post.ref(), emptyList(), emptyList()))
                    }
                    val article = try {
                        graph.japaneseAsmr.article(target.id)
                    } catch (failure: JapaneseAsmrException) {
                        if (failure.verificationPage != null) {
                            return@withContext ClipboardImportPreview(target, getApplication<Application>().resolve(failure.userText), null,
                                jaVerificationPage = failure.verificationPage)
                        }
                        if (failure.failure != JaFailure.IDENTITY) throw failure
                        return@withContext ClipboardImportPreview(target, getApplication<Application>().resolve(failure.userText), null,
                            jaExternalUrl = JapaneseAsmrClient.postUrl(target.id))
                    }
                    ClipboardImportPreview(target, article.post.title, article.post.coverUri,
                        jaArticle = article, jaDetailsOnly = article.media.isEmpty())
                }
                ClipboardSource.YOUTUBE -> {
                    val saved = graph.db.tracks().findYouTube(target.id)
                    val video = saved?.let { YouTubeVideo(target.id, it.title, it.artist.orEmpty(),
                        it.coverUri ?: "https://i.ytimg.com/vi/${target.id}/hqdefault.jpg", it.durationMs) }
                        ?: try {
                            graph.youtube.videoInfo(target.id)
                        } catch (error: YouTubeException) {
                            if (error.statusCode == 404) return@withContext null
                            throw error
                        }
                    ClipboardImportPreview(target, video.title, video.coverUrl, saved != null, youtubeVideo = video)
                }
            }
        }
        override suspend fun save(preview: ClipboardImportPreview, selectedParts: List<BiliPart>): String? {
            when (preview.target.source) {
                ClipboardSource.BILIBILI -> {
                    val details = com.nanahoshi.audioplayer.bilibili.BiliVideoDetails(checkNotNull(preview.biliVideo), preview.parts)
                    val requests = graph.biliImportRequests.choose(listOf(details)) ?: return null
                    requests.forEach { graph.library.importBili(it) }
                    return getApplication<Application>().getString(R.string.online_added_to_library)
                }
                ClipboardSource.ASMR -> {
                    val work = preview.dlsiteWork
                    if (work == null) {
                        graph.library.ensureAsmrWork(graph.asmr.loadWork(preview.target.id))
                    } else {
                        check(work.purchased)
                        val id = preview.target.id
                        if (graph.library.folderBySource(id) != null) return getApplication<Application>().getString(R.string.online_in_library)
                        val product = work.product
                        val nodes = graph.dlsite.playTree(id, product.circle.ifBlank { null }, product.coverUrl)
                        graph.library.saveOnlineWork(product.facts().copy(sourceId = id), product.coverUrl,
                            null, nodes, emptyList(), TrackSource.DLSITE)
                    }
                }
                ClipboardSource.JAPANESE_ASMR -> {
                    val article = checkNotNull(preview.jaArticle)
                    val post = article.post
                    val asmr = try {
                        graph.asmr.loadWork(post.rj)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    val product = if (asmr != null) null else try {
                        graph.dlsite.showcase(post.rj)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    val facts = asmr?.facts() ?: product?.facts()
                        ?: WorkFacts(post.title, "", article.voiceActors, emptyList(), "", post.rj)
                    graph.library.saveOnlineWork(facts.copy(sourceId = post.rj),
                        asmr?.coverUrl ?: product?.coverUrl ?: post.coverUri,
                        asmr, emptyList(), emptyList(), TrackSource.JAPANESE_ASMR,
                        selectedArticle = article, playChecked = false)
                }
                ClipboardSource.YOUTUBE -> graph.library.saveYouTube(checkNotNull(preview.youtubeVideo))
            }
            return getApplication<Application>().getString(R.string.online_added_to_library)
        }
    }, lastHandled = { preferences.getString("last_handled", null) },
        rememberHandled = { preferences.edit().putString("last_handled", it).apply() }, message = { graph.show(it) })

    fun inspectClipboard() {
        if (controller.state?.saving == true) return
        inspectJob?.cancel()
        val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        // 某些系统会拒绝读取；保持当前页面，不记录剪贴板或异常原文。
        val clip = runCatching { clipboard.primaryClip }.getOrElse { return }
        inspectJob = viewModelScope.launch {
            val candidate = clip?.let {
                withContext(Dispatchers.Default) { runCatching { importCandidate(it) }.getOrNull() }
            }
            if (candidate != null) controller.offer(candidate)
            else controller.pendingLookup?.let { controller.resolveLookup(it, confirmed = false) }
        }
    }
}
