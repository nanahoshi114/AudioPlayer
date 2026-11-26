package com.nanahoshi.audioplayer.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 单弹窗协调器：同一时间只允许一个等待用户作答的提示。
 *
 * 传入 [busy] 时采用非阻塞语义（已有提示则立即告知调用方）；不传时后来的请求
 * 在锁上排队，等前一个提示结束后再展示。
 */
internal class SinglePromptCoordinator<P : Any, R>(
    private val idOf: (P) -> Long,
    private val busy: (() -> Unit)? = null,
) {
    private val gate = Mutex()
    private var sequence = 0L
    private val mutable = MutableStateFlow<P?>(null)
    val prompt: StateFlow<P?> = mutable.asStateFlow()
    private var answer: CompletableDeferred<R?>? = null

    suspend fun choose(build: (Long) -> P): R? {
        val reject = busy
        return if (reject == null) {
            gate.withLock { awaitAnswer(build) }
        } else {
            awaitNonBlocking(build, reject)
        }
    }

    private suspend fun awaitNonBlocking(build: (Long) -> P, reject: () -> Unit): R? {
        val deferred = CompletableDeferred<R?>()
        val id = gate.withLock {
            if (answer != null) {
                reject()
                return null
            }
            val next = ++sequence
            answer = deferred
            mutable.value = build(next)
            next
        }
        return try {
            deferred.await()
        } finally {
            withContext(NonCancellable) { clear(id, deferred) }
        }
    }

    private suspend fun awaitAnswer(build: (Long) -> P): R? {
        val deferred = CompletableDeferred<R?>()
        val id = ++sequence
        answer = deferred
        mutable.value = build(id)
        return try {
            deferred.await()
        } finally {
            // 仍持有 gate：与下一个排队请求的展示保持原子。
            if (answer === deferred) {
                if (mutable.value?.let(idOf) == id) mutable.value = null
                answer = null
            }
        }
    }

    private suspend fun clear(id: Long, deferred: CompletableDeferred<R?>) {
        gate.withLock {
            if (answer === deferred) {
                if (mutable.value?.let(idOf) == id) mutable.value = null
                answer = null
            }
        }
    }

    fun update(id: Long, transform: (P) -> P) {
        val current = mutable.value?.takeIf { idOf(it) == id } ?: return
        mutable.value = transform(current)
    }

    /** 按当前提示的编号作答；编号已过期或提示已关闭时忽略。 */
    fun complete(id: Long, value: R?) {
        val current = mutable.value ?: return
        if (idOf(current) != id) return
        answer?.complete(value)
    }

    fun cancel(id: Long) = complete(id, null)
}