package com.nanahoshi.audioplayer.bilibili

import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.userTextOr
import com.nanahoshi.audioplayer.R
import androidx.compose.runtime.*
import kotlinx.coroutines.*

/** 每次查询独立分页；旧请求即使没有响应取消，也不能覆盖新查询。 */
internal class BiliPagedState<T, K>(private val scope: CoroutineScope, private val key: (T) -> K) {
    var items by mutableStateOf<List<T>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<TextRef?>(null); private set
    var hasMore by mutableStateOf(false); private set
    var searched by mutableStateOf(false); private set
    var paused by mutableStateOf(false); private set
    var revision by mutableLongStateOf(0); private set
    private var nextPage = 1
    private var generation = 0L
    private var job: Job? = null
    private var loader: (suspend (Int) -> BiliPage<T>)? = null

    fun cancel() { generation++; job?.cancel(); job = null; loading = false; paused = true }
    fun start(load: suspend (Int) -> BiliPage<T>) {
        cancel(); loader = load; nextPage = 1; items = emptyList(); error = null; hasMore = true; searched = true
        revision++; paused = false
        loadMore()
    }
    fun loadMore() {
        val load = loader ?: return
        if (loading || !hasMore) return
        val token = generation
        val page = nextPage
        loading = true; error = null; paused = false
        job = scope.launch {
            try {
                val result = load(page)
                if (token != generation) return@launch
                val merged = mergeBiliPage(items, result, key)
                items = merged.items; hasMore = merged.hasMore; nextPage = page + 1
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (token == generation) error = failure.userTextOr(R.string.bilibili_error_page_network)
            } finally { if (token == generation) loading = false }
        }
    }
}
