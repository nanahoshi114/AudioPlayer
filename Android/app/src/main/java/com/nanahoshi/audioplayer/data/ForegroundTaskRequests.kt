package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.text.PersistedErrors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 将本进程的任务交给前台服务。收到 Intent 并成功进入前台后才开始执行，
 * 服务启动失败时立即取消并返回可读错误。
 */
internal class ForegroundTaskRequests<K>(
    private val startService: (Long) -> Boolean,
    private val startFailure: () -> kotlinx.coroutines.CancellationException,
) {
    private val pending = mutableMapOf<Long, Request<K, *>>()
    private var nextId = 0L

    fun <T> submit(task: K, block: suspend () -> T): Request<K, T> {
        val request = Request(task, block)
        synchronized(pending) {
            val id = ++nextId
            pending[id] = request
            request.result.invokeOnCompletion { synchronized(pending) { pending.remove(id) } }
            if (!startService(id)) {
                pending.remove(id)
                request.cancel(startFailure())
            }
        }
        return request
    }

    fun take(id: Long): Request<K, *>? = synchronized(pending) { pending.remove(id) }

    fun stopPending(reason: kotlinx.coroutines.CancellationException, stopService: () -> Unit) {
        val stopped = synchronized(pending) {
            stopService()
            pending.values.toList().also { pending.clear() }
        }
        stopped.forEach { it.cancel(reason) }
    }

    class Request<K, T>(val task: K, private val block: suspend () -> T) {
        val result = CompletableDeferred<T>()
        private var worker: Job? = null
        private var cancellation: kotlinx.coroutines.CancellationException? = null

        fun cancel(reason: kotlinx.coroutines.CancellationException = kotlinx.coroutines.CancellationException(PersistedErrors.CANCELLED)) {
            val current = synchronized(this) {
                if (cancellation != null) return
                cancellation = reason
                worker
            }
            if (current == null) result.cancel(reason) else current.cancel(reason)
        }

        fun launchIn(scope: CoroutineScope): Job {
            val worker = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    result.complete(withContext(Dispatchers.IO) { block() })
                } catch (error: Exception) {
                    result.completeExceptionally(error)
                }
            }
            val stopped = synchronized(this) {
                this.worker = worker
                cancellation
            }
            worker.invokeOnCompletion { cause ->
                if (cause != null) result.completeExceptionally(cause)
            }
            if (stopped != null) worker.cancel(stopped)
            return worker
        }
    }
}