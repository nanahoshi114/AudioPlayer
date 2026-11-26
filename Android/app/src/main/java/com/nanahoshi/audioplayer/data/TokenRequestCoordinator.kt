package com.nanahoshi.audioplayer.data

import kotlinx.coroutines.CompletableDeferred
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 以一次性令牌挂起的请求（网页验证、授权回跳等）。调用方拿到令牌后打开网页，
 * 网页完成时再按令牌交付结果；关闭网页或离开页面时令牌随协程结束失效。
 */
internal class TokenRequestCoordinator<P : Any, R : Any>(
    private val accept: (P, R) -> Boolean = { _, _ -> true },
) {
    class Pending<P, R> internal constructor(val payload: P) {
        internal val result = CompletableDeferred<R?>()
    }

    private val requests = ConcurrentHashMap<String, Pending<P, R>>()

    suspend fun await(
        payload: P,
        open: suspend (String) -> Boolean,
        token: () -> String = { UUID.randomUUID().toString() },
    ): R? {
        val id = token()
        val pending = Pending<P, R>(payload)
        requests[id] = pending
        try {
            return if (open(id)) pending.result.await() else null
        } finally {
            requests.remove(id, pending)
        }
    }

    fun get(token: String): Pending<P, R>? = requests[token]

    fun finish(token: String, value: R?) {
        val pending = requests.remove(token) ?: return
        pending.result.complete(value?.takeIf { accept(pending.payload, it) })
    }
}