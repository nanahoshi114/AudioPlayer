package com.nanahoshi.audioplayer.data

import androidx.compose.runtime.*
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.asmr.CatalogLookup
import com.nanahoshi.audioplayer.bilibili.BiliPart
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
    val biliVideo: com.nanahoshi.audioplayer.bilibili.BiliVideoHit? = null,
    val dlsiteWork: ClipboardDlsiteWork? = null,
    val jaArticle: JaArticle? = null,
    val jaExternalUrl: String? = null,
    val jaDetailsOnly: Boolean = false,
    val jaVerificationPage: String? = null,
) {
    val jaBrowserUrl get() = jaVerificationPage ?: jaExternalUrl
    val detailsOnly get() = dlsiteWork?.purchased == false || jaDetailsOnly || jaBrowserUrl != null
}
internal data class ClipboardImportState(
    val preview: ClipboardImportPreview,
    val selected: Set<Long> = emptySet(), val saving: Boolean = false, val error: TextRef? = null,
) {
    val canImport: Boolean
        get() {
            if (saving || preview.detailsOnly) return false
            return if (preview.target.source == ClipboardSource.BILIBILI) preview.parts.any { it.cid in selected }
            else !preview.alreadySaved
        }
}
internal interface ClipboardImportBackend {
    suspend fun preview(target: ClipboardTarget): ClipboardImportPreview?
    suspend fun save(preview: ClipboardImportPreview, selectedParts: List<BiliPart>): String?
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

    fun offer(candidate: ClipboardImportCandidate) {
        if (state?.saving == true) return
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
                    state = ClipboardImportState(preview, selected = preview.parts.map { it.cid }.toSet())
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
        if (state?.saving == true) return
        generation++
        job?.cancel()
        pendingFingerprint = null
        pendingLookup = null
        state = null
    }
    fun togglePart(cid: Long) {
        val current = state ?: return
        if (current.saving) return
        state = current.copy(selected = if (cid in current.selected) current.selected - cid else current.selected + cid)
    }
    fun toggleAllParts() {
        val current = state ?: return
        if (current.saving) return
        val all = current.preview.parts.map { it.cid }.toSet()
        state = current.copy(selected = if (current.selected == all) emptySet() else all)
    }
    fun confirm() {
        val current = state?.takeIf { it.canImport } ?: return
        val preview = current.preview
        val token = generation
        state = current.copy(saving = true, error = null)
        job = scope.launch {
            try {
                val result = backend.save(preview, preview.parts.filter { it.cid in current.selected })
                if (token == generation) {
                    if (result == null) state = current.copy(saving = false, error = null)
                    else { state = null; message(TextRef.Raw(result)) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (token == generation) state = current.copy(error = TextRef.Res(R.string.clipboard_add_failed))
            }
        }
    }
}
