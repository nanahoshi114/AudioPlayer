package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.bilibili.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class BiliImportOption(
    val details: BiliVideoDetails,
    val existing: BiliImportMode?,
    val mode: BiliImportMode?,
    val selected: Set<Long>,
) {
    val converts get() = existing != null && mode != null && existing != mode
    val ready get() = mode != null && (mode == BiliImportMode.GROUPED || selected.isNotEmpty())
    fun request() = BiliImportRequest(details.video,
        details.parts.filter { mode == BiliImportMode.GROUPED || it.cid in selected }, checkNotNull(mode), converts)
}

internal data class BiliImportPrompt(val id: Long, val options: List<BiliImportOption>, val confirming: Boolean = false, val failed: List<String> = emptyList()) {
    val ready get() = options.isNotEmpty() && options.all { it.ready }
}

class BiliImportRequests(
    private val existingMode: suspend (String) -> BiliImportMode?,
    private val busy: () -> Unit = {},
) {
    private val gate = Mutex()
    private var sequence = 0L
    private val mutable = MutableStateFlow<BiliImportPrompt?>(null)
    internal val prompt = mutable.asStateFlow()
    private var answer: CompletableDeferred<List<BiliImportRequest>?>? = null

    suspend fun choose(videos: List<BiliVideoDetails>, failed: List<String> = emptyList()): List<BiliImportRequest>? {
        val options = videos.distinctBy { it.video.bvid }.filter { it.parts.isNotEmpty() }.map { details ->
            val existing = existingMode(details.video.bvid)
            BiliImportOption(details, existing,
                if (details.parts.size == 1 && existing != BiliImportMode.GROUPED) BiliImportMode.SPLIT else null,
                details.parts.map { it.cid }.toSet())
        }
        if (options.isEmpty()) return null
        if (failed.isEmpty() && options.all { it.mode != null && !it.converts }) return options.map { it.request().checked() }
        val deferred = CompletableDeferred<List<BiliImportRequest>?>()
        // 等待用户作答不能占着锁，否则第二个请求会静默排队；已有弹窗时直接告知用户。
        val id = gate.withLock {
            if (answer != null) {
                busy()
                return@withLock null
            }
            val next = ++sequence
            answer = deferred
            mutable.value = BiliImportPrompt(next, options, failed = failed)
            next
        } ?: return null
        return try {
            deferred.await()
        } finally {
            withContext(NonCancellable) {
                gate.withLock {
                    if (answer === deferred) {
                        if (mutable.value?.id == id) mutable.value = null
                        answer = null
                    }
                }
            }
        }
    }

    internal fun edit(id: Long, index: Int, change: (BiliImportOption) -> BiliImportOption) {
        val current = mutable.value?.takeIf { it.id == id && !it.confirming } ?: return
        mutable.value = current.copy(options = current.options.mapIndexed { i, value -> if (i == index) change(value) else value })
    }

    internal fun confirm(id: Long) {
        val current = mutable.value?.takeIf { it.id == id && it.ready } ?: return
        if (!current.confirming && current.options.any { it.converts }) mutable.value = current.copy(confirming = true)
        else answer?.complete(current.options.map { it.request().checked() })
    }

    internal fun cancel(id: Long) { if (mutable.value?.id == id) answer?.complete(null) }
}
