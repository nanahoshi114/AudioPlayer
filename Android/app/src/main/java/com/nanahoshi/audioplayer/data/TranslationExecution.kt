package com.nanahoshi.audioplayer.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 自动翻译、独立翻译及润色共享；等候期间仍可单独取消。 */
internal class TranslationExecution {
    private val lock = Mutex()
    suspend fun <T> run(block: suspend () -> T): T = lock.withLock { block() }
}
