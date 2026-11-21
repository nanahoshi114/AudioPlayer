package com.nanahoshi.audioplayer.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class SubtitlePipelineTest {
    @Test fun translationOfPreviousAudioOverlapsNextTranscription() = runBlocking {
        withTimeout(5_000) {
            data class Work(val id: Int, val translate: Boolean)
            val wake = Channel<Unit>(Channel.CONFLATED)
            val pending = mutableListOf(Work(1, false), Work(2, false))
            val started = Channel<Work>(Channel.UNLIMITED)
            val releaseTranscription = CompletableDeferred<Unit>()
            val releaseTranslation = CompletableDeferred<Unit>()
            val pump = async {
                pumpTasks(wake, { pending.toList() }, { it }, { work, _, running ->
                    running.none { it.translate == work.translate }
                }) { work ->
                    pending.remove(work)
                    started.send(work)
                    if (work.translate) releaseTranslation.await()
                    else if (work.id == 1) pending += Work(1, true)
                    else releaseTranscription.await()
                }
            }
            assertEquals(Work(1, false), started.receive())
            assertEquals(setOf(Work(1, true), Work(2, false)), setOf(started.receive(), started.receive()))
            assertFalse(pump.isCompleted)
            releaseTranscription.complete(Unit)
            releaseTranslation.complete(Unit)
            pump.await()
        }
    }

    @Test fun automaticManualAndPolishShareOnePositionAndWaitersCanCancel() = runBlocking {
        withTimeout(5_000) {
            val execution = TranslationExecution()
            val started = Channel<String>(Channel.UNLIMITED)
            val release = CompletableDeferred<Unit>()
            val auto = async { execution.run { started.send("auto"); release.await() } }
            assertEquals("auto", started.receive())
            val attempted = Channel<Unit>(Channel.UNLIMITED)
            val manual = async { attempted.send(Unit); execution.run { started.send("manual") } }
            attempted.receive()
            val polish = async { attempted.send(Unit); execution.run { started.send("polish") } }
            attempted.receive()
            assertTrue(started.tryReceive().isFailure)
            manual.cancelAndJoin()
            assertTrue(auto.isActive)
            release.complete(Unit)
            assertEquals("polish", started.receive())
            auto.await()
            polish.await()
            assertTrue(started.tryReceive().isFailure)
        }
    }

    @Test fun invalidDownloadPreferencesUseDefault() {
        for (count in 1..5) assertEquals(count, validDownloadConcurrency(count))
        for (count in listOf(-1, 0, 6, Int.MAX_VALUE)) assertEquals(3, validDownloadConcurrency(count))
    }
}
