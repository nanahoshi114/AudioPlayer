package com.nanahoshi.audioplayer.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.Call

class InflightCalls {
    class Ticket internal constructor(val key: String, val token: Int, val progress: TaskReporter? = null)

    private class Slot {
        var token: Int = 0
        var call: Call? = null
    }

    private val slots = HashMap<String, Slot>()
    private val shared = HashMap<String, CompletableDeferred<Any?>>()

    /**
     * 同一 [key] 的并发请求只执行一次，其余调用者等待同一结果。
     * 先到者持有取消票据；它被取消时所有等待者一同收到取消。
     */
    suspend fun <T : Any> execute(
        key: String,
        progress: TaskReporter? = null,
        block: suspend (Ticket) -> T,
    ): T {
        val deferred: CompletableDeferred<Any?>
        val owner: Boolean
        synchronized(slots) {
            val existing = shared[key]
            if (existing != null) {
                deferred = existing
                owner = false
            } else {
                deferred = CompletableDeferred()
                shared[key] = deferred
                owner = true
            }
        }
        if (!owner) {
            @Suppress("UNCHECKED_CAST")
            return deferred.await() as T
        }
        val ticket = prepare(key, progress)
        try {
            val value = block(ticket)
            deferred.complete(value)
            return value
        } catch (error: Throwable) {
            deferred.completeExceptionally(error)
            throw error
        } finally {
            synchronized(slots) { if (shared[key] === deferred) shared.remove(key) }
        }
    }

    fun prepare(key: String, progress: TaskReporter? = null): Ticket {
        val (ticket, previous) = synchronized(slots) {
            val slot = slots.getOrPut(key) { Slot() }
            slot.token += 1
            val current = slot.call
            slot.call = null
            Ticket(key, slot.token, progress) to current
        }
        previous?.cancel()
        return ticket
    }

    fun begin(ticket: Ticket, call: Call): Boolean {
        val accepted = synchronized(slots) {
            val slot = slots[ticket.key]
            if (slot == null || slot.token != ticket.token) {
                false
            } else {
                slot.call = call
                true
            }
        }
        if (!accepted) call.cancel()
        return accepted
    }

    fun end(ticket: Ticket, call: Call) {
        synchronized(slots) {
            val slot = slots[ticket.key] ?: return
            if (slot.token == ticket.token && slot.call === call) slot.call = null
        }
    }

    fun cancel(key: String) {
        val doomed = synchronized(slots) {
            val slot = slots.getOrPut(key) { Slot() }
            slot.token += 1
            val current = slot.call
            slot.call = null
            current
        }
        doomed?.cancel()
    }

    /** 阻塞的 HTTP 请求也要响应任务取消，且不能取消同一任务重试时换发的请求。 */
    suspend fun <T> cancelWithCoroutine(ticket: Ticket, block: suspend () -> T): T = coroutineScope {
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                val call = synchronized(slots) {
                    val slot = slots[ticket.key]?.takeIf { it.token == ticket.token }
                    slot?.call.also {
                        if (slot != null) {
                            slot.token += 1
                            slot.call = null
                        }
                    }
                }
                call?.cancel()
            }
        }
        try {
            block()
        } finally {
            watcher.cancel()
        }
    }
}
