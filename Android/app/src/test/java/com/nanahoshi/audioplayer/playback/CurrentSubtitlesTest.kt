package com.nanahoshi.audioplayer.playback

import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentSubtitlesTest {
    @Test
    fun slowOldReadCannotDelayOrReplaceNewItem() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val oldReadStarted = CompletableDeferred<Unit>()
        val releaseOldRead = CompletableDeferred<Unit>()
        val oldReadFinished = CompletableDeferred<Unit>()
        val current = CurrentSubtitles(scope, MutableStateFlow(listOf(row(1), row(2))), MutableStateFlow(Unit)) {
            if (it.queueItemId == 1L) {
                withContext(NonCancellable) {
                    oldReadStarted.complete(Unit)
                    releaseOldRead.await()
                    oldReadFinished.complete(Unit)
                }
            }
            loaded(it.title)
        }
        val reader = launch { current.state.collect {} }
        try {
            withTimeout(5_000) {
                current.select(1)
                oldReadStarted.await()
                current.showChinese(1, false)
                current.select(2)
                current.showChinese(1, false)
                val next = current.state.first { it.forItem(2) != null }
                assertEquals("曲目 2", next.forItem(2)?.cues?.textAt(0))
                assertNull(next.forItem(1))
                assertTrue(current.selection.value.chinese)
                releaseOldRead.complete(Unit)
                oldReadFinished.await()
                yield()
                assertEquals(next, current.state.value)
            }
        } finally {
            releaseOldRead.complete(Unit)
            reader.cancel()
            scope.cancel()
        }
    }

    @Test
    fun updatesAndDeletionReloadTheCurrentItem() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val revision = MutableStateFlow(0)
        var result = loaded("原文")
        val current = CurrentSubtitles(scope, MutableStateFlow(listOf(row(1))), revision.map { Unit }) { result }
        val reader = launch { current.state.collect {} }
        try {
            withTimeout(5_000) {
                current.select(1)
                assertEquals("原文", current.state.first { it.forItem(1) != null }.loaded?.cues?.textAt(0, chinese = true))
                result = loaded("原文", "译文")
                revision.value++
                val translated = current.state.first { it.forItem(1) == result }
                assertEquals("译文", translated.loaded?.cues?.textAt(0, chinese = true))
                assertEquals("", translated.loaded?.cues?.textAt(1_000, chinese = true))
                result = LoadedSubtitles(emptyList(), false, false)
                revision.value++
                assertTrue(current.state.first { it.forItem(1) == result }.loaded?.cues.orEmpty().isEmpty())
            }
        } finally {
            reader.cancel()
            scope.cancel()
        }
    }

    private fun loaded(text: String, translation: String? = null) =
        LoadedSubtitles(listOf(SubtitleCue(0, 1_000, text, translation)), false, false)

    private fun row(id: Long) = QueuedTrack(
        queueItemId = id, position = 0, trackId = id, source = TrackSource.LOCAL, title = "曲目 $id",
        artist = null, durationMs = 2_000, coverUri = null, localUri = null, bvid = null, cid = null,
        page = null, remoteUrl = null, referer = null, workno = null, fileKey = null,
    )
}
