package com.nanahoshi.audioplayer.data

import kotlinx.coroutines.flow.StateFlow

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
    private val prompts = SinglePromptCoordinator<DlsiteDownloadPrompt, DlsiteDownloadMode>({ it.id })
    val pending: StateFlow<DlsiteDownloadPrompt?> = prompts.prompt

    suspend fun choose(workNames: List<String>, allowPartial: Boolean = true): DlsiteDownloadMode? =
        prompts.choose { id ->
            DlsiteDownloadPrompt(id, workNames.filter { it.isNotBlank() }.distinct(), allowPartial)
        }

    fun answer(promptId: Long, mode: DlsiteDownloadMode?) {
        val prompt = prompts.prompt.value ?: return
        if (prompt.id != promptId || (mode == DlsiteDownloadMode.PARTIAL && !prompt.allowPartial)) return
        prompts.complete(promptId, mode)
    }
}