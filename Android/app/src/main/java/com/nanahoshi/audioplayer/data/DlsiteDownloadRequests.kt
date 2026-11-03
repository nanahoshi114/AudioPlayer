package com.nanahoshi.audioplayer.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class DlsiteDownloadMode {
    ARCHIVE,
    PARTIAL,
}

data class DlsiteDownloadPrompt(
    val id: Long,
    val workNames: List<String>,
    val allowPartial: Boolean,
)

/** 请求留在调用者协程内，取消或离开页面后不会继续创建下载任务。 */
class DlsiteDownloadRequests {
    private val lock = Mutex()
    private val mutablePending = MutableStateFlow<DlsiteDownloadPrompt?>(null)
    val pending: StateFlow<DlsiteDownloadPrompt?> = mutablePending.asStateFlow()
    private var nextId = 0L
    private var answer: CompletableDeferred<DlsiteDownloadMode?>? = null

    suspend fun choose(workNames: List<String>, allowPartial: Boolean = true): DlsiteDownloadMode? = lock.withLock {
        val reply = CompletableDeferred<DlsiteDownloadMode?>()
        val prompt = DlsiteDownloadPrompt(++nextId, workNames.filter { it.isNotBlank() }.distinct(), allowPartial)
        answer = reply
        mutablePending.value = prompt
        try {
            reply.await()
        } finally {
            mutablePending.value = null
            answer = null
        }
    }

    fun answer(promptId: Long, mode: DlsiteDownloadMode?) {
        val prompt = mutablePending.value ?: return
        if (prompt.id != promptId || (mode == DlsiteDownloadMode.PARTIAL && !prompt.allowPartial)) return
        answer?.complete(mode)
    }
}
