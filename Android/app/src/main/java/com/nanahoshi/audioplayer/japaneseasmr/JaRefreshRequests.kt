package com.nanahoshi.audioplayer.japaneseasmr

import com.nanahoshi.audioplayer.data.TokenRequestCoordinator

internal class JaRefreshRequests {
    class Pending internal constructor(
        internal val shared: TokenRequestCoordinator.Pending<JaPostRef, JaArticle>,
    ) {
        val post: JaPostRef get() = shared.payload
    }

    private val requests = TokenRequestCoordinator<JaPostRef, JaArticle> { post, article ->
        article.post.postId == post.postId && article.post.rj.equals(post.rj, ignoreCase = true)
    }

    suspend fun verify(post: JaPostRef, open: suspend (String) -> Boolean): JaArticle? =
        requests.await(post, open)

    fun get(token: String): Pending? = requests.get(token)?.let(::Pending)

    fun finish(token: String, article: JaArticle?) = requests.finish(token, article)
}