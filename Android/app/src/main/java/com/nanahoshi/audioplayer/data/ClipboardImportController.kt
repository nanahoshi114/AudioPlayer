package com.nanahoshi.audioplayer.data

import androidx.compose.runtime.*
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.asmr.CatalogLookup
import com.nanahoshi.audioplayer.bilibili.BiliImportRequest
import com.nanahoshi.audioplayer.bilibili.BiliPart
import com.nanahoshi.audioplayer.bilibili.BiliVideoHit
import com.nanahoshi.audioplayer.dlsite.DlsiteException
import com.nanahoshi.audioplayer.dlsite.DlsiteShowcase
import com.nanahoshi.audioplayer.youtube.YouTubeVideo
import com.nanahoshi.audioplayer.japaneseasmr.JaArticle
import com.nanahoshi.audioplayer.japaneseasmr.JapaneseAsmrException
import com.nanahoshi.audioplayer.text.TextRef
import kotlinx.coroutines.*
import java.io.IOException

internal data class ClipboardDlsiteWork(val product: DlsiteShowcase, val purchased: Boolean)

internal data class ClipboardImportPreview(
    val target: ClipboardTarget, val title: String, val cover: String?, val alreadySaved: Boolean = false,
    val parts: List<BiliPart> = emptyList(), val youtubeVideo: YouTubeVideo? = null,
    val biliVideo: BiliVideoHit? = null,
    val dlsiteWork: ClipboardDlsiteWork? = null,
    val jaArticle: JaArticle? = null,
    val jaExternalUrl: String? = null,
    val jaDetailsOnly: Boolean = false,
    val jaVerificationPage: String? = null,
) {
    val jaBrowserUrl get() = jaVerificationPage ?: jaExternalUrl
    val detailsOnly get() = dlsiteWork?.purchased == false || jaDetailsOnly || jaBrowserUrl != null
    val multiPartBili get() = target.source == ClipboardSource.BILIBILI && parts.size > 1
    val alreadyInLibrary get() = alreadySaved && !multiPartBili
}

internal enum class ClipboardImportStage { PREVIEW, CHOOSING_BILI_PARTS, SAVING }

internal data class ClipboardImportState(
    val preview: ClipboardImportPreview,
    val stage: ClipboardImportStage = ClipboardImportStage.PREVIEW,
    val error: TextRef? = null,
) {
    val busy get() = stage != ClipboardImportStage.PREVIEW
    val canImport: Boolean
        get() = !busy && !preview.detailsOnly && !preview.alreadyInLibrary &&
            (preview.target.source != ClipboardSource.BILIBILI || preview.parts.isNotEmpty())
}
internal interface ClipboardImportBackend {
    suspend fun preview(target: ClipboardTarget): ClipboardImportPreview?
    suspend fun chooseBiliParts(preview: ClipboardImportPreview): List<BiliImportRequest>?
    suspend fun save(preview: ClipboardImportPreview, biliRequests: List<BiliImportRequest>): String
}

internal suspend fun clipboardWorkPreview(
    target: ClipboardTarget,
    lookupAsmr: suspend (String) -> CatalogLookup,
    lookupDlsite: suspend (String) -> DlsiteShowcase?,
    isPurchased: suspend (String) -> Boolean,
): ClipboardImportPreview? {
    when (val result = lookupAsmr(target.id)) {
        is CatalogLookup.Found -> return ClipboardImportPreview(target, result.item.title, result.item.coverUrl)
        CatalogLookup.Unknown -> throw IOException()
        CatalogLookup.Missing -> Unit
    }
    val product = lookupDlsite(target.id) ?: return null
    return ClipboardImportPreview(
        target, product.title, product.coverUrl,
        dlsiteWork = ClipboardDlsiteWork(product, isPurchased(target.id)),
    )
}

/** 所有状态更新在主线程；测试注入后端和作用域，不读 Android 剪贴板或 org.json。 */
internal class ClipboardImportController(
    private val scope: CoroutineScope,
    private val backend: ClipboardImportBackend,
    private val lastHandled: () -> String?,
    private val rememberHandled: (String) -> Unit,
    private val message: (TextRef) -> Unit,
) {
    var state by mutableStateOf<ClipboardImportState?>(null); private set
    var pendingLookup by mutableStateOf<ClipboardImportCandidate?>(null); private set
    private var job: Job? = null
    private var generation = 0L
    private var pendingFingerprint: String? = null
    private var jaVerification: Pair<String, ClipboardImportPreview>? = null

    fun offer(candidate: ClipboardImportCandidate) {
        if (state?.busy == true) return
        val fingerprint = candidate.fingerprint
        if (fingerprint == pendingFingerprint || fingerprint == pendingLookup?.fingerprint) return
        val handled = lastHandled()
        val handledBeforeUpgrade = handled != null && candidate.requiresLookupConfirmation &&
            handled == candidate.copy(requiresLookupConfirmation = false).fingerprint
        if (fingerprint == handled || handledBeforeUpgrade) {
            if (pendingFingerprint != null || pendingLookup != null) dismiss()
            return
        }
        dismiss()
        if (candidate.requiresLookupConfirmation) pendingLookup = candidate
        else loadPreview(candidate, fingerprint)
    }

    fun resolveLookup(candidate: ClipboardImportCandidate, confirmed: Boolean) {
        if (pendingLookup != candidate) return
        pendingLookup = null
        val fingerprint = candidate.fingerprint
        if (confirmed) loadPreview(candidate, fingerprint)
        else rememberHandled(fingerprint)
    }

    private fun loadPreview(candidate: ClipboardImportCandidate, fingerprint: String) {
        val token = generation
        pendingFingerprint = fingerprint
        job = scope.launch {
            try {
                val preview = backend.preview(candidate.target)
                if (token != generation) return@launch
                rememberHandled(fingerprint)
                if (preview != null) {
                    state = ClipboardImportState(preview)
                } else if (candidate.requiresLookupConfirmation) {
                    message(TextRef.Res(R.string.clipboard_no_work))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (token == generation) {
                    val source = candidate.target.source
                    message(when {
                        error is DlsiteException && error.statusCode == 401 -> TextRef.Res(R.string.clipboard_dlsite_login_expired)
                        error is JapaneseAsmrException -> TextRef.Res(R.string.clipboard_ja_prefix, listOf(error.userText))
                        source == ClipboardSource.JAPANESE_ASMR -> TextRef.Res(R.string.clipboard_ja_read_failed)
                        source == ClipboardSource.ASMR -> TextRef.Res(R.string.clipboard_asmr_connection_failed)
                        source == ClipboardSource.BILIBILI -> TextRef.Res(R.string.clipboard_bilibili_read_failed)
                        else -> TextRef.Res(
                            R.string.clipboard_source_read_failed,
                            listOf(TextRef.Res(source.labelRes)),
                        )
                    })
                }
            } finally {
                if (token == generation) pendingFingerprint = null
            }
        }
    }
    fun dismiss() {
        if (state?.busy == true) return
        generation++
        job?.cancel()
        pendingFingerprint = null
        pendingLookup = null
        jaVerification = null
        state = null
    }

    fun beginJaVerification(preview: ClipboardImportPreview): String? {
        val current = state?.takeUnless { it.busy } ?: return null
        if (current.preview != preview || preview.jaVerificationPage == null) return null
        dismiss()
        val token = generation.toString()
        jaVerification = token to preview
        return token
    }

    fun finishJaVerification(token: String, article: JaArticle?) {
        val (pendingToken, preview) = jaVerification ?: return
        if (token != pendingToken || (article != null && article.post.postId != preview.target.id)) return
        jaVerification = null
        state = ClipboardImportState(
            if (article == null) preview else preview.copy(
                title = article.post.title,
                cover = article.post.coverUri,
                jaArticle = article,
                jaDetailsOnly = article.media.isEmpty(),
                jaVerificationPage = null,
            ),
        )
    }

    fun confirm() {
        val current = state?.takeIf { it.canImport } ?: return
        val preview = current.preview
        val token = generation
        val retry = current.copy(error = null)
        val stage = if (preview.multiPartBili) ClipboardImportStage.CHOOSING_BILI_PARTS else ClipboardImportStage.SAVING
        state = retry.copy(stage = stage)
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                ensureActive()
                val requests = if (preview.target.source == ClipboardSource.BILIBILI) {
                    backend.chooseBiliParts(preview) ?: return@launch
                } else emptyList()
                ensureActive()
                if (token != generation) return@launch
                state = retry.copy(stage = ClipboardImportStage.SAVING)
                val result = backend.save(preview, requests)
                ensureActive()
                if (token == generation) {
                    state = null
                    message(TextRef.Raw(result))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (token == generation) state = retry.copy(error = TextRef.Res(R.string.clipboard_add_failed))
            } finally {
                if (token == generation && state?.busy == true) state = retry
            }
        }
    }
}
