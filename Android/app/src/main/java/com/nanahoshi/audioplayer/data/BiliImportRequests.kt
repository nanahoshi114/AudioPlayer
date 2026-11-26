package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.bilibili.*
import kotlinx.coroutines.flow.StateFlow

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
    busy: () -> Unit = {},
) {
    private val prompts = SinglePromptCoordinator<BiliImportPrompt, List<BiliImportRequest>>({ it.id }, busy)
    internal val prompt: StateFlow<BiliImportPrompt?> = prompts.prompt

    suspend fun choose(videos: List<BiliVideoDetails>, failed: List<String> = emptyList()): List<BiliImportRequest>? {
        val options = videos.distinctBy { it.video.bvid }.filter { it.parts.isNotEmpty() }.map { details ->
            val existing = existingMode(details.video.bvid)
            BiliImportOption(details, existing,
                if (details.parts.size == 1) existing ?: BiliImportMode.SPLIT else null,
                details.parts.map { it.cid }.toSet())
        }
        if (options.isEmpty()) return null
        if (failed.isEmpty() && options.all { it.mode != null && !it.converts }) return options.map { it.request().checked() }
        // 等待用户作答不能占着锁，否则第二个请求会静默排队；已有弹窗时直接告知用户。
        return prompts.choose { id -> BiliImportPrompt(id, options, failed = failed) }
    }

    internal fun edit(id: Long, index: Int, change: (BiliImportOption) -> BiliImportOption) {
        val current = prompts.prompt.value?.takeIf { it.id == id && !it.confirming } ?: return
        prompts.update(id) { prompt ->
            prompt.copy(options = current.options.mapIndexed { i, value -> if (i == index) change(value) else value })
        }
    }

    internal fun confirm(id: Long) {
        val current = prompts.prompt.value?.takeIf { it.id == id && it.ready } ?: return
        if (!current.confirming && current.options.any { it.converts }) {
            prompts.update(id) { it.copy(confirming = true) }
        } else {
            prompts.complete(id, current.options.map { it.request().checked() })
        }
    }

    internal fun cancel(id: Long) = prompts.cancel(id)
}
