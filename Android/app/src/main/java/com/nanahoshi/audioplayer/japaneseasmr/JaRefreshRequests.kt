package com.nanahoshi.audioplayer.japaneseasmr

import kotlinx.coroutines.CompletableDeferred
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class JaRefreshRequests {
    internal class Pending(val post: JaPostRef) {
        val result = CompletableDeferred<JaArticle?>()
    }

    private val requests = ConcurrentHashMap<String, Pending>()

    suspend fun verify(post: JaPostRef, open: suspend (String) -> Boolean): JaArticle? {
        val token = UUID.randomUUID().toString()
        val request = Pending(post)
        requests[token] = request
        try {
            return if (open(token)) request.result.await() else null
        } finally {
            requests.remove(token, request)
        }
    }

    fun get(token: String) = requests[token]

    fun finish(token: String, article: JaArticle?) {
        val pending = requests.remove(token) ?: return
        pending.result.complete(article?.takeIf {
            it.post.postId == pending.post.postId && it.post.rj.equals(pending.post.rj, ignoreCase = true)
        })
    }
}
