package com.nanahoshi.audioplayer.japaneseasmr

import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.JapaneseAsmrDao
import com.nanahoshi.audioplayer.data.db.JapaneseAsmrMediaEntity
import com.nanahoshi.audioplayer.data.db.QueuedTrack

internal sealed interface JaMatchResult {
    val media: JaMediaRef? get() = null
    data class MatchedByKey(override val media: JaMediaRef) : JaMatchResult
    data class MatchedByPath(override val media: JaMediaRef) : JaMatchResult
    data object InsufficientIdentity : JaMatchResult
    data object Ambiguous : JaMatchResult
    data object Gone : JaMatchResult
}

internal suspend fun resolveStoredJaMatches(
    article: JaArticle,
    postId: String,
    dao: JapaneseAsmrDao,
): Map<String, JaMatchResult> = matchJaMedia(article.media, dao.mediaForPost(postId).associate {
    it.mediaKey to (it.pathHash ?: it.mediaKey.legacyPathHash())
}, ambiguous = article.duplicateKeys)

internal fun String.legacyPathHash(): String? =
    takeIf { startsWith("p:") }?.substring(2)?.takeIf { it.isNotEmpty() }

internal fun matchJaMedia(
    media: List<JaMediaRef>,
    stored: Map<String, String?>,
    occupied: Set<String> = emptySet(),
    ambiguous: Set<String> = emptySet(),
): Map<String, JaMatchResult> {
    val results = linkedMapOf<String, JaMatchResult>()
    val byKey = media.groupBy { it.key }
    val byPath = media.filter { it.pathHash != null }.groupBy { it.pathHash }
    val used = occupied.toMutableSet()
    for (key in stored.keys) {
        val exact = byKey[key].orEmpty()
        when {
            exact.isEmpty() -> Unit
            exact.size > 1 || key in used || key in ambiguous -> results[key] = JaMatchResult.Ambiguous
            else -> {
                results[key] = JaMatchResult.MatchedByKey(exact.single())
                used += key
            }
        }
    }
    val remaining = stored.filterKeys { it !in results }
    val paths = remaining.values.groupingBy { it }.eachCount()
    for ((key, path) in remaining) {
        val matches = byPath[path].orEmpty()
        results[key] = when {
            path == null -> JaMatchResult.InsufficientIdentity
            matches.isEmpty() -> JaMatchResult.Gone
            matches.size > 1 || paths[path] != 1 || matches.single().key in used || matches.single().key in ambiguous -> JaMatchResult.Ambiguous
            else -> JaMatchResult.MatchedByPath(matches.single())
        }
    }
    return results
}

internal fun resolveQueuedJaMatch(
    article: JaArticle,
    key: String,
    path: String?,
    stored: Map<String, JaMatchResult>,
    queue: List<QueuedTrack>,
): JaMatchResult {
    stored[key]?.let { return it }
    val occupied = stored.values.mapNotNull { it.media?.key }.toSet()
    val identities = queue.filter {
        it.source == TrackSource.JAPANESE_ASMR && it.trackId == null && it.workno == article.post.postId && it.fileKey != null && it.fileKey !in stored
    }.groupBy { requireNotNull(it.fileKey) }.mapValues { (queueKey, items) ->
        items.map { it.mediaPathHash ?: queueKey.legacyPathHash() }.distinct().singleOrNull()
    }
    return matchJaMedia(article.media, identities + (key to (path ?: key.legacyPathHash())), occupied, article.duplicateKeys).getValue(key)
}

internal data class OldJaQueueMatch(val mediaKeys: Set<String>, val ambiguous: Boolean = false)

internal fun matchOldJaQueue(
    media: List<JapaneseAsmrMediaEntity>,
    queue: List<QueuedTrack>,
): Map<Long, OldJaQueueMatch> {
    val exactKeys = media.map { it.mediaKey }.toSet()
    val mediaByPath = media.groupBy { it.pathHash ?: it.mediaKey.legacyPathHash() }
    fun path(item: QueuedTrack) = item.mediaPathHash ?: item.fileKey?.legacyPathHash()
    val queueByPath = queue.groupBy(::path)
    val pathsByKey = queue.groupBy { it.fileKey }.mapValues { (_, items) -> items.map(::path).distinct() }
    return queue.associate { item ->
        val key = requireNotNull(item.fileKey)
        val match = if (key in exactKeys) {
            OldJaQueueMatch(setOf(key))
        } else {
            val paths = pathsByKey.getValue(key)
            val candidates = paths.filterNotNull().flatMap { hash -> mediaByPath[hash].orEmpty() }
                .map { it.mediaKey }.toSet()
            val ambiguous = paths.size > 1 || candidates.size > 1 ||
                paths.filterNotNull().any { hash -> queueByPath[hash].orEmpty().any { it.fileKey != key } }
            OldJaQueueMatch(candidates, ambiguous)
        }
        item.queueItemId to match
    }
}
