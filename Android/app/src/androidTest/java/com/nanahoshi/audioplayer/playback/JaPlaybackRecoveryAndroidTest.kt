package com.nanahoshi.audioplayer.playback

import android.content.ComponentName
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nanahoshi.audioplayer.AudioPlayerApplication
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.japaneseasmr.JapaneseAsmrClient
import com.nanahoshi.audioplayer.japaneseasmr.rewriteJaPlaylist
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

@UnstableApi
@RunWith(AndroidJUnit4::class)
class JaPlaybackRecoveryAndroidTest {
    private enum class Recovery { PAUSE, SWITCH, ONLINE_FAILURE }

    @Test fun externalDeletionReopensOnlineAndPreservesPauseRequestedDuringRecovery() = runBlocking {
        testRecovery(Recovery.PAUSE)
    }

    @Test fun switchingTracksDuringRecoveryDoesNotReplaceTheCurrentItem() = runBlocking {
        testRecovery(Recovery.SWITCH)
    }

    @Test fun failedOnlineRecoveryKeepsTheQueueAndTheRemainingItemCanPlay() = runBlocking {
        testRecovery(Recovery.ONLINE_FAILURE)
    }

    private suspend fun testRecovery(recovery: Recovery) = kotlinx.coroutines.coroutineScope {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val graph = (context.applicationContext as AudioPlayerApplication).graph
        val post = "99876543"
        val rj = "RJ01987654"
        val base = "https://audio.invalid/recovery"
        val manifest = "$base/index.m3u8"
        val source = graph.japaneseAsmr.rememberVerifiedArticle(JapaneseAsmrClient.postUrl(post),
            """<h1 class="page-title">$rj</h1><div class="cleanPlayer"><audio src="$manifest"></audio></div>""", rj)
        val root = graph.library.importJapaneseAsmrPost(source).rootId
        val track = graph.db.japaneseAsmr().track(post, source.media.single().key)!!
        val file = File.createTempFile("ja-recovery-", ".wav", context.cacheDir)
        val otherFile = File.createTempFile("ja-next-", ".wav", context.cacheDir)
        val next = track.copy(id = 0, source = TrackSource.LOCAL, title = "Next fixture", folderId = null,
            localUri = otherFile.absolutePath, remoteUrl = null, asmrWorkId = null, fileHash = null)
        val nextTrack = next.copy(id = graph.db.tracks().insert(next))
        val controllerFuture = withContext(Dispatchers.Main) {
            MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        }
        val controller = withContext(Dispatchers.IO) { controllerFuture.get(10, java.util.concurrent.TimeUnit.SECONDS) }
        val unlock = CompletableDeferred<Unit>()
        val playbackErrors = AtomicInteger()
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { playbackErrors.incrementAndGet() }
        }
        try {
            val bytes = ByteArray(32_000 * 5)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36 + bytes.size).put("WAVEfmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(bytes.size)
            file.writeBytes(header.array() + bytes)
            file.copyTo(otherFile, overwrite = true)
            graph.db.tracks().updateLocalUri(track.id, file.absolutePath)
            val playlist = rewriteJaPlaylist("""
                #EXTM3U
                #EXT-X-VERSION:3
                #EXT-X-TARGETDURATION:1
                #EXTINF:0.512,
                0.ts
                #EXTINF:0.512,
                1.ts
                #EXT-X-ENDLIST
            """.trimIndent(), manifest)
            graph.japaneseAsmr.rememberPlaylist(manifest,
                if (recovery == Recovery.ONLINE_FAILURE) "invalid manifest".toByteArray() else playlist)
            repeat(2) { index ->
                val segment = instrumentation.context.assets.open("japaneseasmr/$index.ts").use { it.readBytes() }
                graph.japaneseAsmr.rememberPlaylist("$base/$index.ts", segment)
            }
            withTimeout(15_000) {
                graph.player.replaceAndPlay(listOf(track.copy(localUri = file.absolutePath), nextTrack)).join()
                await {
                    controller.isPlaying && controller.currentMediaItem?.localConfiguration?.uri?.scheme == "file"
                }
                val queueId = withContext(Dispatchers.Main) {
                    controller.addListener(listener)
                    controller.currentMediaItem!!.mediaId
                }
                val locked = CompletableDeferred<Unit>()
                val lock = launch {
                    graph.player.withQueueLock {
                        locked.complete(Unit)
                        unlock.await()
                    }
                }
                locked.await()
                assertTrue(file.delete())
                withContext(Dispatchers.Main) {
                    controller.stop()
                    controller.prepare()
                    controller.play()
                }
                await { controller.playerError != null }
                withContext(Dispatchers.Main) {
                    when (recovery) {
                        Recovery.PAUSE -> controller.pause()
                        Recovery.SWITCH -> {
                            controller.seekTo(1, 0L)
                            controller.prepare()
                            controller.play()
                        }
                        Recovery.ONLINE_FAILURE -> Unit
                    }
                }
                if (recovery == Recovery.SWITCH) await { controller.currentMediaItemIndex == 1 }
                unlock.complete(Unit)
                lock.join()

                when (recovery) {
                    Recovery.PAUSE -> {
                        await {
                            controller.currentMediaItem?.localConfiguration?.uri?.scheme == "japaneseasmr" &&
                                controller.playbackState == Player.STATE_READY
                        }
                        withContext(Dispatchers.Main) {
                            assertEquals(queueId, controller.currentMediaItem?.mediaId)
                            assertFalse(controller.playWhenReady)
                            assertNull(controller.playerError)
                            controller.play()
                        }
                        await { controller.isPlaying }
                    }
                    Recovery.SWITCH -> {
                        graph.player.withQueueLock {}
                        await { controller.currentMediaItemIndex == 1 && controller.isPlaying }
                        withContext(Dispatchers.Main) {
                            assertEquals("file", controller.getMediaItemAt(0).localConfiguration?.uri?.scheme)
                        }
                    }
                    Recovery.ONLINE_FAILURE -> {
                        await {
                            controller.currentMediaItem?.localConfiguration?.uri?.scheme == "japaneseasmr" &&
                                graph.player.jaPlaybackFailure.value?.queueId == queueId.toLong() &&
                                controller.playerError != null
                        }
                        assertEquals(2, playbackErrors.get())
                        assertEquals(2, graph.db.queue().items().size)
                        graph.player.remove(0, queueId.toLong()).join()
                        await { controller.mediaItemCount == 1 && controller.playbackState == Player.STATE_READY }
                        withContext(Dispatchers.Main) { controller.play() }
                        await { controller.isPlaying }
                    }
                }
                assertNotNull(graph.db.tracks().getById(track.id))
                val remaining = graph.db.queue().items()
                if (recovery == Recovery.ONLINE_FAILURE) {
                    assertEquals(nextTrack.id, remaining.single().trackId)
                } else {
                    assertEquals(queueId, remaining.first().id.toString())
                    assertEquals(nextTrack.id, remaining.last().trackId)
                }
            }
        } finally {
            unlock.complete(Unit)
            graph.player.clearQueue().join()
            graph.library.deleteItems(listOf(nextTrack.id), listOf(root))
            graph.japaneseAsmr.invalidate(post)
            file.delete()
            otherFile.delete()
            withContext(Dispatchers.Main) {
                controller.removeListener(listener)
                controller.release()
            }
        }
        Unit
    }

    private suspend fun await(check: () -> Boolean) {
        while (!withContext(Dispatchers.Main) { check() }) delay(20)
    }
}
