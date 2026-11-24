package com.nanahoshi.audioplayer.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.asmr.AsmrClient
import com.nanahoshi.audioplayer.bilibili.BilibiliClient
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.DlsiteOriginalEntryEntity
import com.nanahoshi.audioplayer.data.db.FileKind
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.LibraryFileEntity
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.data.db.TrackSubtitleBindingEntity
import com.nanahoshi.audioplayer.data.db.TrackSubtitleKind
import com.nanahoshi.audioplayer.data.db.TranslationJobEntity
import com.nanahoshi.audioplayer.data.db.WhisperJobEntity
import com.nanahoshi.audioplayer.dlsite.DlsiteClient
import com.nanahoshi.audioplayer.japaneseasmr.JaArticle
import com.nanahoshi.audioplayer.japaneseasmr.JaCandidate
import com.nanahoshi.audioplayer.japaneseasmr.JaMediaRef
import com.nanahoshi.audioplayer.japaneseasmr.JaPostRef
import com.nanahoshi.audioplayer.japaneseasmr.JapaneseAsmrClient
import com.nanahoshi.audioplayer.japaneseasmr.jaIdentity
import com.nanahoshi.audioplayer.playback.LoadedSubtitles
import com.nanahoshi.audioplayer.playback.SubtitleCue
import com.nanahoshi.audioplayer.playback.SubtitleOrigin
import com.nanahoshi.audioplayer.playback.SubtitleSource
import com.nanahoshi.audioplayer.playback.SubtitleSourceKind
import com.nanahoshi.audioplayer.playback.SubtitleTranslationTarget
import com.nanahoshi.audioplayer.playback.encodeGeneratedCues
import com.nanahoshi.audioplayer.playback.overlayFileName
import com.nanahoshi.audioplayer.playback.parseGeneratedSubtitles
import com.nanahoshi.audioplayer.playback.storedQueueRow
import com.nanahoshi.audioplayer.playback.subtitleSourceFingerprint
import com.nanahoshi.audioplayer.youtube.YouTubeClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@RunWith(AndroidJUnit4::class)
class SubtitleBindingAndroidTest {
    @Test
    fun manualBindingsTakePrecedenceAndUnavailableSourcesReportNotices() = runBlocking {
        withFixture {
            val folder = work()
            val track = track("01.wav", TrackSource.ASMR, folder)
            val row = queue(track)
            libraryFile(folder, "01.srt", "自動字幕")
            val manual = libraryFile(folder, "manual.srt", "手動字幕")
            val generated = generated(track)
            assertSelection(row, SubtitleSourceKind.GENERATED, "古い聞き取り", manual = false)

            bindings.bindNone(track.id)
            assertSelection(row, SubtitleSourceKind.NONE, null, manual = true)
            bindings.bindGenerated(track.id)
            assertSelection(row, SubtitleSourceKind.GENERATED, "古い聞き取り", manual = true)
            bindings.bindLibraryFile(track.id, manual)
            assertSelection(row, SubtitleSourceKind.LIBRARY_FILE, "手動字幕", manual = true)

            val input = srt("import.srt", "コピー字幕")
            assertTrue(bindings.importAndBindCopy(track.id, providerUri(input), input.name))
            assertSelection(row, SubtitleSourceKind.APP_FILE, "コピー字幕", manual = true)

            bindings.clearBinding(track.id)
            db.trackSubtitles().upsert(TrackSubtitleBindingEntity(
                trackId = track.id, kind = TrackSubtitleKind.EXTERNAL_URI, uri = providerUri(input).toString(),
                displayName = input.name, updatedAt = 1,
            ))
            assertSelection(
                row, SubtitleSourceKind.EXTERNAL_URI, null, manual = true,
                notice = context.getString(R.string.player_error_bound_subtitle_uri),
            )
            bindings.clearBinding(track.id)
            assertSelection(row, SubtitleSourceKind.GENERATED, "古い聞き取り", manual = false)

            assertTrue(generated.delete())
            bindings.bindGenerated(track.id)
            assertSelection(
                row, SubtitleSourceKind.LIBRARY_FILE, "自動字幕", manual = true,
                notice = context.getString(R.string.player_error_bound_generated_missing),
            )
            bindings.clearBinding(track.id)
            assertSelection(row, SubtitleSourceKind.LIBRARY_FILE, "自動字幕", manual = false)
        }
    }

    @Test
    fun loadsDlsiteAndJapaneseAsmrLibrarySubtitlesFromLocalFiles() = runBlocking {
        withFixture {
            val folder = work()
            val scripts = db.folders().insert(FolderEntity(parentId = folder, name = "字幕", position = 0))
            val dlsite = track("01.wav", TrackSource.DLSITE, folder)
            val japaneseAsmr = track("01.wav", TrackSource.JAPANESE_ASMR, folder)
            libraryFile(scripts, "01.srt", "DLsite 字幕", hash = "dlsite:original:01.srt")
            libraryFile(
                scripts, "01.srt", "Japanese ASMR 字幕", hash = "legacy-ja-file",
                remote = "japaneseasmr://download/01.srt",
            )

            assertSelection(queue(dlsite), SubtitleSourceKind.LIBRARY_FILE, "DLsite 字幕", manual = false)
            assertSelection(queue(japaneseAsmr), SubtitleSourceKind.LIBRARY_FILE, "Japanese ASMR 字幕", manual = false)
        }
    }

    @Test
    fun translationAndPolishKeepIndependentFingerprintOverlaysAlongsideGeneratedSubtitles() = runBlocking {
        withFixture {
            val folder = work()
            val track = track("01.wav", TrackSource.ASMR, folder)
            val row = queue(track)
            val first = libraryFile(folder, "first.srt", "最初の台詞")
            val second = libraryFile(folder, "second.srt", "別の台詞")
            val generated = generated(track)
            val generatedBefore = generated.readText()
            val starts = Channel<Long>(Channel.UNLIMITED)
            val requests = SubtitleTaskRequests { starts.trySend(it).isSuccess }
            val translator = TranslateCenter(context, db, credentials, subtitles, requests, completedRecovery())
            try {
                bindings.bindLibraryFile(track.id, first)
                val untranslated = subtitles.load(row)
                val translated = listOf(untranslated.cues.single().copy(translation = "第一句"))
                val firstOutput = publishOverlay(translator, requests, starts, SubtitleTask.TRANSLATE, row, untranslated, translated)

                val beforePolish = subtitles.load(row)
                assertEquals(translated, beforePolish.cues)
                val polished = listOf(translated.single().copy(text = "最初の台詞。", translation = "第一句话。"))
                val polishOutput = publishOverlay(translator, requests, starts, SubtitleTask.POLISH, row, beforePolish, polished)
                assertEquals(firstOutput, polishOutput)
                assertEquals(polished, subtitles.load(row).cues)

                bindings.bindLibraryFile(track.id, second)
                val secondSource = subtitles.load(row)
                assertNull(secondSource.overlayOutputName)
                val secondTranslation = listOf(secondSource.cues.single().copy(translation = "另一句"))
                val secondOutput = publishOverlay(
                    translator, requests, starts, SubtitleTask.TRANSLATE, row, secondSource, secondTranslation,
                )
                assertNotEquals(firstOutput.name, secondOutput.name)
                assertEquals(secondTranslation, subtitles.load(row).cues)

                bindings.bindLibraryFile(track.id, first)
                val loaded = subtitles.load(row)
                assertEquals(polished, loaded.cues)
                assertEquals(firstOutput.name, loaded.overlayOutputName)
                assertEquals(untranslated.sourceCues, loaded.sourceCues)
                assertEquals(generatedBefore, generated.readText())

                // Keep the filename and its short fingerprint valid while changing the stored full fingerprint.
                val validOverlay = firstOutput.readText()
                val fingerprint = subtitleSourceFingerprint(untranslated.sourceCues, includeTranslation = true)
                val wrongFingerprint = fingerprint.dropLast(1) + if (fingerprint.last() == '0') '1' else '0'
                firstOutput.writeText(encodeGeneratedCues(polished, null, sourceFingerprint = wrongFingerprint))
                val rejected = subtitles.load(row)
                assertEquals(untranslated.sourceCues, rejected.cues)
                assertNull(rejected.overlayOutputName)
                firstOutput.writeText(validOverlay)

                bindings.bindGenerated(track.id)
                assertSelection(row, SubtitleSourceKind.GENERATED, "古い聞き取り", manual = true)
                assertEquals("旧译文", subtitles.load(row).cues.single().translation)
                assertEquals(secondTranslation, parseGeneratedSubtitles(secondOutput.readText())?.displayCues)
            } finally {
                translator.close()
                starts.close()
            }
        }
    }

    @Test
    fun legacyUnfingerprintedOverlayAppliesOnlyToTheHistoricalAutomaticSource() = runBlocking {
        withFixture {
            val folder = work()
            val original = track("01.wav", TrackSource.ASMR, folder)
            val borrowing = track("01.wav", TrackSource.DLSITE, folder)
            val historicalFile = libraryFile(folder, "01.srt", "以前の台詞")
            val originalRow = queue(original)
            val borrowedRow = queue(borrowing)
            for (row in listOf(originalRow, borrowedRow)) {
                val name = checkNotNull(overlayFileName(row.trackId, row.remoteUrl, row.localUri, row.queueItemId))
                val overlay = File(subtitleDir, name).apply {
                    writeText(encodeGeneratedCues(listOf(SubtitleCue(1000, 2000, "以前の台詞", "旧译文"))))
                }
                assertNull(parseGeneratedSubtitles(overlay.readText())?.sourceFingerprint)
            }

            assertEquals("旧译文", subtitles.load(originalRow).cues.single().translation)
            bindings.bindLibraryFile(original.id, historicalFile)
            val manual = subtitles.load(originalRow)
            assertTrue(manual.hasManualBinding)
            assertEquals("以前の台詞", manual.cues.single().text)
            assertNull(manual.cues.single().translation)
            assertNull(manual.overlayOutputName)
            bindings.clearBinding(original.id)
            assertEquals("旧译文", subtitles.load(originalRow).cues.single().translation)

            val borrowed = subtitles.load(borrowedRow)
            assertEquals(SubtitleSourceKind.LIBRARY_FILE, borrowed.sourceKind)
            assertEquals("以前の台詞", borrowed.cues.single().text)
            assertNull(borrowed.cues.single().translation)
            assertNull(borrowed.overlayOutputName)
        }
    }

    @Test
    fun discardGeneratedOnlyProtectsSharedFingerprintAndPreservesManualOverlay() = runBlocking {
        withFixture {
            val folder = work()
            val track = track("01.wav", TrackSource.ASMR, folder)
            val other = track("02.wav", TrackSource.ASMR, folder)
            val row = queue(track)
            val otherRow = queue(other)
            val sharedFingerprint = "a".repeat(64)
            val generated = generated(track, sharedFingerprint)
            generated(other, sharedFingerprint)
            val original = generated.readText()
            bindings.bindLibraryFile(track.id, libraryFile(folder, "manual.srt", "手動字幕"))
            val binding = db.trackSubtitles().getForTrack(track.id)
            val source = subtitles.load(row)
            val translated = listOf(source.cues.single().copy(translation = "手动字幕"))
            assertTrue(subtitles.saveTranslations(row, translated, source))
            val overlay = subtitles.expectedOverlayFile(row, source.sourceCues)
            val overlayBefore = overlay.readText()

            subtitles.discardGeneratedOnly(row)
            assertEquals(original, generated.readText())
            assertEquals(2, db.whisper().forFingerprint(sharedFingerprint).size)
            assertSelection(otherRow, SubtitleSourceKind.GENERATED, "古い聞き取り", manual = false)

            db.tracks().delete(other.id)
            subtitles.discardGeneratedOnly(row)
            assertFalse(generated.exists())
            assertTrue(db.whisper().forFingerprint(sharedFingerprint).isEmpty())
            assertEquals(binding, db.trackSubtitles().getForTrack(track.id))
            assertEquals(overlayBefore, overlay.readText())
            assertEquals(translated, subtitles.load(row).cues)
        }
    }

    @Test
    fun forceRegenerateKeepsReadableWhisperFileUntilNewResultIsPublished() = runBlocking {
        withFixture {
            withTimeout(20_000) {
                val transcriptionRequested = CompletableDeferred<Unit>()
                val releaseResponse = CompletableDeferred<Unit>()
                val server = LocalProgressHttp(this) { input ->
                    when (input.path) {
                        "/v1/models" -> LocalProgressHttp.Reply(bytes = """{"data":[{"id":"qwen3-asr-fixture"}]}""".toByteArray())
                        "/v1/audio/transcriptions" -> {
                            transcriptionRequested.complete(Unit)
                            releaseResponse.await()
                            LocalProgressHttp.Reply(bytes = """{"segments":[{"start":0,"end":1,"text":"新しい聞き取り"}]}""".toByteArray())
                        }
                        else -> LocalProgressHttp.Reply(code = 404)
                    }
                }
                val starts = Channel<Long>(Channel.UNLIMITED)
                val requests = SubtitleTaskRequests { starts.trySend(it).isSuccess }
                val recovery = completedRecovery()
                val translator = TranslateCenter(context, db, credentials, subtitles, requests, recovery)
                val downloads = downloads()
                var worker: Job? = null
                try {
                    val endpoint = server.url("")
                    credentials.saveWhisper(endpoint, "fixture-token", WHISPER_MODEL_QWEN3)
                    val audio = File(root, "01.wav").apply { writeText("fixture audio") }
                    val track = track(audio.name, local = audio)
                    val row = queue(track)
                    val fingerprint = MessageDigest.getInstance("SHA-256").digest(audio.readBytes())
                        .joinToString("") { "%02x".format(it) }
                    val old = generated(track, fingerprint, SubtitleOrigin(WHISPER_MODEL_QWEN3, endpoint))
                    val oldBytes = old.readText()
                    val whisper = WhisperCenter(context, db, credentials, downloads, translator, subtitles, emptyMap(), requests, recovery)
                    bindings.bindGenerated(track.id)

                    val reuse = async { whisper.startIfLocal(row, translate = false, model = WHISPER_MODEL_QWEN3) }
                    val reuseRequest = checkNotNull(requests.take(starts.receive()))
                    worker = reuseRequest.launchIn(this).also { it.start() }
                    assertEquals(WhisperStart.AlreadyDone, reuse.await())
                    reuseRequest.result.await()

                    val preparing = async { whisper.startIfLocal(row, translate = false, model = WHISPER_MODEL_QWEN3, forceRegenerate = true) }
                    val request = checkNotNull(requests.take(starts.receive()))
                    worker = request.launchIn(this).also { it.start() }
                    assertEquals(WhisperStart.Started, preparing.await())
                    transcriptionRequested.await()
                    val running = db.whisper().forFingerprint(fingerprint)
                    assertEquals(listOf(DownloadStatus.DONE, DownloadStatus.RUNNING), running.map { it.status })
                    assertEquals(true, running.last().forceRegenerate)
                    assertEquals(oldBytes, old.readText())
                    assertSelection(row, SubtitleSourceKind.GENERATED, "古い聞き取り", manual = true)

                    releaseResponse.complete(Unit)
                    request.result.await()
                    assertEquals(DownloadStatus.DONE, db.whisper().get(running.last().id)?.status)
                    assertSelection(row, SubtitleSourceKind.GENERATED, "新しい聞き取り", manual = true)
                    assertEquals(fingerprint, subtitles.load(row).generatedFingerprint)
                } finally {
                    withContext(NonCancellable) {
                        server.close()
                        worker?.cancelAndJoin()
                        downloads.close()
                        translator.close()
                        starts.close()
                    }
                }
            }
        }
    }

    @Test
    fun nonPersistableProviderRequiresCopyAndOrphanCleanupKeepsReferencedFiles() = runBlocking {
        withFixture {
            val track = track("01.wav")
            val row = queue(track)
            val input = srt("provider.srt", "取り込み字幕")
            val uri = providerUri(input)
            bindings.bindNone(track.id)
            val previous = db.trackSubtitles().getForTrack(track.id)

            assertEquals(BindExternalResult.NeedsAppCopy, bindings.bindExternalUri(track.id, uri, input.name))
            assertEquals(previous, db.trackSubtitles().getForTrack(track.id))
            assertTrue(bindings.importAndBindCopy(track.id, uri, input.name))
            val binding = checkNotNull(db.trackSubtitles().getForTrack(track.id))
            assertEquals(TrackSubtitleKind.APP_FILE, binding.kind)
            assertEquals(input.name, binding.displayName)
            val copy = File(checkNotNull(binding.uri))
            assertEquals(input.readText(), copy.readText())
            assertSelection(row, SubtitleSourceKind.APP_FILE, "取り込み字幕", manual = true)

            val orphan = File(copy.parentFile, "orphan.srt").apply { writeText("orphan fixture") }
            bindings.cleanupOrphans()
            assertFalse(orphan.exists())
            assertTrue(copy.isFile)
            bindings.clearBinding(track.id)
            assertNull(db.trackSubtitles().getForTrack(track.id))
            assertFalse(copy.exists())
            assertTrue(input.isFile)
            assertSelection(row, SubtitleSourceKind.NONE, null, manual = false)
        }
    }

    @Test
    fun deleteItemsCleansOnlyDetachedAppFileCopiesAndInvalidatesSubtitles() = runBlocking {
        withFixture {
            val removed = track("removed.wav")
            val sharedRemoved = track("shared-removed.wav")
            val survivor = track("survivor.wav")
            val exclusive = File(root, "custom-subtitles/exclusive.srt").apply {
                parentFile?.mkdirs()
                writeText("exclusive")
            }
            val shared = File(root, "custom-subtitles/shared.srt").apply { writeText("shared") }
            for (row in listOf(
                TrackSubtitleBindingEntity(
                    trackId = removed.id, kind = TrackSubtitleKind.APP_FILE, uri = exclusive.path,
                    displayName = "exclusive.srt", updatedAt = 0,
                ),
                TrackSubtitleBindingEntity(
                    trackId = sharedRemoved.id, kind = TrackSubtitleKind.APP_FILE, uri = shared.path,
                    displayName = "shared.srt", updatedAt = 0,
                ),
                TrackSubtitleBindingEntity(
                    trackId = survivor.id, kind = TrackSubtitleKind.APP_FILE, uri = shared.path,
                    displayName = "shared.srt", updatedAt = 0,
                ),
            )) db.trackSubtitles().upsert(row)
            val revision = subtitles.revision.value

            library().deleteItems(listOf(removed.id, sharedRemoved.id), emptyList(), emptyList())

            assertNull(db.tracks().getById(removed.id))
            assertNull(db.tracks().getById(sharedRemoved.id))
            assertNotNull(db.tracks().getById(survivor.id))
            assertNull(db.trackSubtitles().getForTrack(removed.id))
            assertNull(db.trackSubtitles().getForTrack(sharedRemoved.id))
            assertFalse(exclusive.exists())
            assertTrue(shared.isFile)
            assertNotNull(db.trackSubtitles().getForTrack(survivor.id))
            assertTrue(subtitles.revision.value > revision)
        }
    }

    @Test
    fun japaneseAsmrRefreshDetachesBindingsButKeepsGeneratedSubtitlesAndTasks() = runBlocking {
        withFixture {
            val repository = library()
            val complete = jaArticle("35", "p:keep", "p:gone", "p:gone2").copy(complete = true)
            val rootId = repository.importJapaneseAsmrPost(complete).rootId
            val keep = checkNotNull(db.japaneseAsmr().track("35", "p:keep"))
            val gone = checkNotNull(db.japaneseAsmr().track("35", "p:gone"))
            val gone2 = checkNotNull(db.japaneseAsmr().track("35", "p:gone2"))

            val fingerprint = "e".repeat(64)
            val cue = File(root, "subtitles/$fingerprint.json").apply {
                parentFile?.mkdirs()
                writeText("{\"cues\":[]}")
            }
            val whisperId = db.whisper().insert(WhisperJobEntity(
                title = "gone", fingerprint = fingerprint, trackId = gone.id, localPath = null,
                remoteJobId = null, status = DownloadStatus.DONE, progress = 1f, error = null,
                awaitRemoteUrl = jaIdentity("35", "p:gone"),
            ))
            val overlayName = checkNotNull(overlayFileName(gone.id, jaIdentity("35", "p:gone"), null, null))
            val overlay = File(root, "subtitles/$overlayName").apply { writeText("[]") }
            val translationId = db.translations().insert(TranslationJobEntity(
                title = "gone", trackId = gone.id, remoteUrl = null, queueItemId = null,
                status = DownloadStatus.DONE, error = null,
            ))
            val shared = File(root, "custom-subtitles/shared.srt").apply {
                parentFile?.mkdirs()
                writeText("shared")
            }
            val exclusive = File(root, "custom-subtitles/exclusive.srt").apply { writeText("exclusive") }
            db.trackSubtitles().upsert(TrackSubtitleBindingEntity(
                trackId = gone.id, kind = TrackSubtitleKind.APP_FILE, uri = shared.path, displayName = "shared.srt", updatedAt = 0,
            ))
            db.trackSubtitles().upsert(TrackSubtitleBindingEntity(
                trackId = keep.id, kind = TrackSubtitleKind.APP_FILE, uri = shared.path, displayName = "shared.srt", updatedAt = 0,
            ))
            db.trackSubtitles().upsert(TrackSubtitleBindingEntity(
                trackId = gone2.id, kind = TrackSubtitleKind.APP_FILE, uri = exclusive.path, displayName = "exclusive.srt", updatedAt = 0,
            ))

            val revision = subtitles.revision.value
            val result = repository.refreshJapaneseAsmrPost(
                "35", rootId, cleanup = true, article = complete.copy(media = complete.media.take(1)),
            )
            assertEquals(2, result.removed)

            assertNull(db.tracks().getById(gone.id))
            assertNull(db.tracks().getById(gone2.id))
            assertNotNull(db.tracks().getById(keep.id))
            assertTrue(cue.isFile)
            assertTrue(overlay.isFile)
            assertNotNull(db.whisper().get(whisperId))
            assertNotNull(db.translations().get(translationId))
            assertFalse(exclusive.exists())
            assertTrue(shared.isFile)
            assertNotNull(db.trackSubtitles().getForTrack(keep.id))
            assertTrue(subtitles.revision.value > revision)
        }
    }

    @Test
    fun orphanCleanupDropsStaleCopiesAndStagingFilesButKeepsActiveOnes() = runBlocking {
        withFixture {
            val bound = track("bound.wav")
            val kept = File(root, "custom-subtitles/kept.srt").apply {
                parentFile?.mkdirs()
                writeText("kept")
            }
            val orphan = File(root, "custom-subtitles/orphan.srt").apply { writeText("orphan") }
            db.trackSubtitles().upsert(TrackSubtitleBindingEntity(
                trackId = bound.id, kind = TrackSubtitleKind.APP_FILE, uri = kept.path, displayName = "kept.srt", updatedAt = 0,
            ))
            val staging = File(root, "custom-subtitles/.staging").apply { mkdirs() }
            val active = File(staging, "active.srt").apply { writeText("active") }
            val stale = File(staging, "stale.srt").apply { writeText("stale") }
            bindings.activeStagingNames.add(active.name)
            try {
                bindings.cleanupOrphans()

                assertTrue(kept.isFile)
                assertFalse(orphan.exists())
                assertTrue(active.isFile)
                assertFalse(stale.exists())
            } finally {
                bindings.activeStagingNames.remove(active.name)
            }
        }
    }

    @Test
    fun bindGeneratedIfOlderSwitchesOnlyOlderChoicesAndClearsOffset() = runBlocking {
        withFixture {
            val folder = work()
            val subtitleId = libraryFile(folder, "choice.srt", "台詞")
            val track = track("01.wav", folder = folder)
            db.trackSubtitles().upsert(TrackSubtitleBindingEntity(
                trackId = track.id, kind = TrackSubtitleKind.NONE, updatedAt = 100,
            ))
            assertTrue(bindings.bindGeneratedIfOlder(track.id, 100))
            assertEquals(TrackSubtitleKind.GENERATED, db.trackSubtitles().getForTrack(track.id)?.kind)
            assertNull(db.trackSubtitles().getForTrack(track.id)?.offsetMs)

            val generated = checkNotNull(db.trackSubtitles().getForTrack(track.id))
            db.trackSubtitles().upsert(generated.copy(
                kind = TrackSubtitleKind.LIBRARY_FILE, fileId = subtitleId, offsetMs = 500, updatedAt = 200,
            ))
            // 用户在这次生成之后改选的字幕优先保留。
            assertFalse(bindings.bindGeneratedIfOlder(track.id, 100))
            assertEquals(TrackSubtitleKind.LIBRARY_FILE, db.trackSubtitles().getForTrack(track.id)?.kind)
            assertEquals(500L, db.trackSubtitles().getForTrack(track.id)?.offsetMs)

            assertTrue(bindings.bindGeneratedIfOlder(track.id, 200))
            assertEquals(TrackSubtitleKind.GENERATED, db.trackSubtitles().getForTrack(track.id)?.kind)
            assertNull(db.trackSubtitles().getForTrack(track.id)?.offsetMs)

            assertFalse(bindings.bindGeneratedIfOlder(track.id, 300))
            db.trackSubtitles().deleteForTrack(track.id)
            assertFalse(bindings.bindGeneratedIfOlder(track.id, 300))
        }
    }

    @Test
    fun subtitleCatalogHidesLegacyDlsitePlayEntriesWhenOriginalPackageIsActive() = runBlocking {
        withFixture {
            val folder = work()
            val activeTrack = track("01.wav", TrackSource.DLSITE, folder)
            val legacyTrack = track("02.wav", TrackSource.DLSITE, folder)
            val asmrTrack = track("03.wav", TrackSource.ASMR, folder)
            val jaTrack = track("04.wav", TrackSource.JAPANESE_ASMR, folder)
            val activeFile = libraryFile(folder, "01.srt", "活动字幕", hash = "dlsite:active")
            val legacyFile = libraryFile(folder, "02.srt", "旧字幕", hash = "dlsite:legacy")
            val asmrFile = libraryFile(folder, "03.srt", "来源字幕", hash = "asmr:3")
            val jaFile = libraryFile(folder, "04.srt", "篇目字幕", hash = "asmr:4", remote = "japaneseasmr://download/04.srt")
            db.dlsiteOriginals().upsertEntries(listOf(
                DlsiteOriginalEntryEntity(
                    workno = "RJ123456", relativePath = "01.wav", kind = "audio", size = 1,
                    localPath = "01.wav", trackId = activeTrack.id, fileId = activeFile,
                    folderId = folder,
                ),
            ))

            val catalog = checkNotNull(loadWorkSubtitleCatalog(db, folder))
            assertEquals(
                setOf(activeTrack.id, asmrTrack.id, jaTrack.id),
                catalog.tracks.map { it.id }.toSet(),
            )
            assertFalse(legacyTrack.id in catalog.tracks.map { it.id })
            assertEquals(setOf(activeFile, asmrFile, jaFile), catalog.files.map { it.id }.toSet())
            assertFalse(legacyFile in catalog.files.map { it.id })
        }
    }

    @Test
    fun rebindingTheSameSubtitlePreservesOffsetWhileSwitchingClearsIt() = runBlocking {
        withFixture {
            val folder = work()
            val target = track("01.wav", TrackSource.ASMR, folder)
            val first = libraryFile(folder, "01.srt", "字幕一")
            val second = libraryFile(folder, "02.srt", "字幕二")

            bindings.bindLibraryFile(target.id, first)
            bindings.setOffsetMs(target.id, 500)
            bindings.bindLibraryFile(target.id, first)
            assertEquals(500L, db.trackSubtitles().getForTrack(target.id)?.offsetMs)

            bindings.bindLibraryFile(target.id, second)
            assertNull(db.trackSubtitles().getForTrack(target.id)?.offsetMs)

            bindings.bindGenerated(target.id)
            bindings.setOffsetMs(target.id, -250)
            bindings.bindGenerated(target.id)
            assertEquals(-250L, db.trackSubtitles().getForTrack(target.id)?.offsetMs)
        }
    }

    @Test
    fun externalUriRebindingPreservesOffsetOnlyForTheSameDocument() = runBlocking {
        withFixture {
            val target = track("01.wav")
            val uri = "content://fixture/subtitles/01.srt"
            val tree = "content://fixture/tree"
            bindings.commit(listOf(TrackSubtitleBindingEntity(
                trackId = target.id, kind = TrackSubtitleKind.EXTERNAL_URI, uri = uri, treeUri = tree,
                displayName = "01.srt", offsetMs = 750, updatedAt = 1,
            )))
            bindings.commit(listOf(TrackSubtitleBindingEntity(
                trackId = target.id, kind = TrackSubtitleKind.EXTERNAL_URI, uri = uri, treeUri = tree,
                displayName = "01.srt", updatedAt = 2,
            )))
            assertEquals(750L, db.trackSubtitles().getForTrack(target.id)?.offsetMs)

            bindings.commit(listOf(TrackSubtitleBindingEntity(
                trackId = target.id, kind = TrackSubtitleKind.GENERATED, displayName = null, updatedAt = 3,
            )))
            assertNull(db.trackSubtitles().getForTrack(target.id)?.offsetMs)
        }
    }

    @Test
    fun zeroByteSubtitleCopiesFailCleanlyWithoutBinding() = runBlocking {
        withFixture {
            val target = track("01.wav")
            val empty = File(root, "empty.srt").apply { writeBytes(ByteArray(0)) }
            assertFalse(bindings.importAndBindCopy(target.id, providerUri(empty), empty.name))
            assertNull(db.trackSubtitles().getForTrack(target.id))
        }
    }

    @Test
    fun explicitNoneBindingLoadsWithDisabledFlag() = runBlocking {
        withFixture {
            val track = track("01.wav")
            val row = queue(track)
            bindings.bindNone(track.id)

            val loaded = subtitles.load(row)
            assertTrue(loaded.disabledByUser)
            assertTrue(loaded.hasManualBinding)
            assertTrue(loaded.cues.isEmpty())
            assertFalse(loaded.generated)

            bindings.clearBinding(track.id)
            assertFalse(subtitles.load(row).disabledByUser)
        }
    }

    @Test
    fun publishingNewCuesReopensAnExplicitlyDisabledTrack() = runBlocking {
        withFixture {
            withTimeout(20_000) {
                val transcriptionRequested = CompletableDeferred<Unit>()
                val releaseResponse = CompletableDeferred<Unit>()
                val server = LocalProgressHttp(this) { input ->
                    when (input.path) {
                        "/v1/models" -> LocalProgressHttp.Reply(
                            bytes = """{"data":[{"id":"qwen3-asr-fixture"}]}""".toByteArray(),
                        )
                        "/v1/audio/transcriptions" -> {
                            transcriptionRequested.complete(Unit)
                            releaseResponse.await()
                            LocalProgressHttp.Reply(
                                bytes = """{"segments":[{"start":0,"end":1,"text":"新しい聞き取り"}]}""".toByteArray(),
                            )
                        }
                        else -> LocalProgressHttp.Reply(code = 404)
                    }
                }
                val starts = Channel<Long>(Channel.UNLIMITED)
                val requests = SubtitleTaskRequests { starts.trySend(it).isSuccess }
                val recovery = completedRecovery()
                val translator = TranslateCenter(context, db, credentials, subtitles, requests, recovery)
                val downloads = downloads()
                var worker: Job? = null
                try {
                    val endpoint = server.url("")
                    credentials.saveWhisper(endpoint, "fixture-token", WHISPER_MODEL_QWEN3)
                    val audio = File(root, "01.wav").apply { writeText("fixture audio") }
                    val track = track(audio.name, local = audio)
                    val row = queue(track)
                    val fingerprint = MessageDigest.getInstance("SHA-256").digest(audio.readBytes())
                        .joinToString("") { "%02x".format(it) }
                    generated(track, fingerprint, SubtitleOrigin(WHISPER_MODEL_QWEN3, endpoint))
                    val whisper = WhisperCenter(
                        context, db, credentials, downloads, translator, subtitles, emptyMap(), requests, recovery,
                        subtitleBindings = bindings,
                    )
                    bindings.bindNone(track.id)
                    assertEquals(TrackSubtitleKind.NONE, db.trackSubtitles().getForTrack(track.id)?.kind)

                    val preparing = async {
                        whisper.startIfLocal(row, translate = false, model = WHISPER_MODEL_QWEN3, forceRegenerate = true)
                    }
                    val request = checkNotNull(requests.take(starts.receive()))
                    worker = request.launchIn(this).also { it.start() }
                    assertEquals(WhisperStart.Started, preparing.await())
                    transcriptionRequested.await()
                    // 生成进行中不提前改写用户关闭的选择。
                    assertEquals(TrackSubtitleKind.NONE, db.trackSubtitles().getForTrack(track.id)?.kind)

                    releaseResponse.complete(Unit)
                    request.result.await()
                    assertEquals(TrackSubtitleKind.GENERATED, db.trackSubtitles().getForTrack(track.id)?.kind)
                } finally {
                    withContext(NonCancellable) {
                        server.close()
                        worker?.cancelAndJoin()
                        downloads.close()
                        translator.close()
                        starts.close()
                    }
                }
            }
        }
    }

    private fun jaArticle(post: String, vararg keys: String) = JaArticle(
        JaPostRef(post, "RJ01258546", "篇 $post", null, JapaneseAsmrClient.postUrl(post), null),
        emptyList(),
        keys.map { key ->
            JaMediaRef(key, key, listOf(JaCandidate("https://audio.example/voice.mp3", null)), emptyList())
        },
    )

    private class Fixture {
        private val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(app.cacheDir, "external-open/subtitle-binding-${UUID.randomUUID()}").apply { mkdirs() }
        val subtitleDir = File(root, "subtitles").apply { mkdirs() }
        private val prefNames = ConcurrentHashMap.newKeySet<String>()
        val context: Context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolated = "${root.name}-$name"
                prefNames.add(isolated)
                return super.getSharedPreferences(isolated, mode)
            }
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val credentials = CredentialStore(context)
        private val asmr = AsmrClient()
        private val bili = BilibiliClient(credentials)
        private val dlsite = DlsiteClient(credentials)
        val subtitles = SubtitleSource(context, db, bili, asmr, dlsite)
        val bindings = SubtitleBindingStore(context, db, subtitles)

        fun close() {
            db.close()
            prefNames.forEach { app.deleteSharedPreferences(it) }
            root.deleteRecursively()
        }

        fun downloads(): DownloadCenter {
            val library = LibraryRepository(context, db, bili, asmr, dlsite)
            val youtube = YouTubeClient({ null }, { _, _, _ -> error("Unexpected YouTube request") })
            return DownloadCenter(context, db, asmr, dlsite, bili, youtube, library, subtitles, startService = { _, _ -> false })
        }

        /** 与 AppGraph 相同：删除曲目后清理被解绑的专属副本并刷新字幕修订号。 */
        fun library(): LibraryRepository {
            val repository = LibraryRepository(context, db, bili, asmr, dlsite)
            repository.onBindingsDetached = { detached, invalidate ->
                if (detached.isNotEmpty()) bindings.cleanupDetached(detached)
                else if (invalidate) subtitles.invalidate()
            }
            return repository
        }

        suspend fun work() = db.folders().insert(FolderEntity(
            name = "Work", position = 0, asmrWorkId = 123456, asmrSourceId = "RJ123456",
        ))

        suspend fun track(title: String, source: TrackSource = TrackSource.LOCAL, folder: Long? = null, local: File? = null): TrackEntity {
            val entity = TrackEntity(
                source = source, title = title, artist = null, durationMs = 2000, coverUri = null,
                localUri = local?.absolutePath, bvid = null, cid = null, page = null, addedAt = 0, folderId = folder,
            )
            return entity.copy(id = db.tracks().insert(entity))
        }

        suspend fun libraryFile(
            folder: Long, name: String, text: String, hash: String = "asmr:$name", remote: String = "",
        ): Long {
            val file = srt("library-${UUID.randomUUID()}.srt", text)
            return db.files().insert(LibraryFileEntity(
                folderId = folder, name = name, kind = FileKind.TEXT, remoteUrl = remote, addedAt = 0,
                asmrWorkId = 123456, fileHash = hash, localUri = file.absolutePath,
            ))
        }

        fun srt(name: String, text: String) = File(root, name).apply {
            writeText("1\n00:00:01,000 --> 00:00:02,000\n$text\n")
        }

        fun providerUri(file: File): Uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)

        fun queue(track: TrackEntity) = storedQueueRow(track.id, track.localUri, track.remoteUrl).copy(
            queueItemId = track.id, source = track.source, title = track.title, durationMs = track.durationMs,
        )

        suspend fun generated(
            track: TrackEntity, fingerprint: String = "b".repeat(64), origin: SubtitleOrigin = SubtitleOrigin(SENSE_VOICE_MODEL, null),
        ): File {
            db.whisper().insert(WhisperJobEntity(
                title = track.title, fingerprint = fingerprint, trackId = track.id, localPath = track.localUri,
                remoteJobId = null, status = DownloadStatus.DONE, progress = 1f, error = null,
                awaitRemoteUrl = null, endpoint = origin.endpoint, model = origin.model, skipTranslate = true,
            ))
            return subtitles.generatedFile(fingerprint).apply {
                val original = listOf(SubtitleCue(0, 1000, "古い聞き取り"))
                writeText(encodeGeneratedCues(original, origin, revisedCues = listOf(original.single().copy(translation = "旧译文"))))
            }
        }

        suspend fun assertSelection(
            row: QueuedTrack, kind: SubtitleSourceKind, text: String?, manual: Boolean, notice: String? = null,
        ) {
            val loaded = subtitles.load(row)
            assertEquals(kind, loaded.sourceKind)
            assertEquals(listOfNotNull(text), loaded.cues.map { it.text })
            assertEquals(manual, loaded.hasManualBinding)
            assertEquals(notice, loaded.notice)
        }

        suspend fun publishOverlay(
            translator: TranslateCenter,
            requests: ForegroundTaskRequests<SubtitleTask>,
            starts: Channel<Long>,
            task: SubtitleTask,
            row: QueuedTrack,
            source: LoadedSubtitles,
            result: List<SubtitleCue>,
        ): File = withTimeout(5_000) {
            val submission = async { translator.submit(task, row, source) }
            val request = checkNotNull(requests.take(starts.receive()))
            try {
                val job = db.translations().observe().first { rows ->
                    rows.any { it.status == DownloadStatus.QUEUED }
                }.single { it.status == DownloadStatus.QUEUED }
                val output = subtitles.expectedOverlayFile(row, source.sourceCues)
                val isPolish = task == SubtitleTask.POLISH
                val inputCues = if (isPolish) source.unshiftedCues else source.sourceCues
                assertEquals(task.name, job.kind)
                assertEquals(SubtitleTranslationTarget.OVERLAY, job.targetKind)
                assertNull(job.generatedFingerprint)
                assertEquals(subtitleSourceFingerprint(inputCues, includeTranslation = isPolish), job.sourceFingerprint)
                assertEquals(output.name, job.outputFilename)

                // Feed a deterministic result into the production save step without contacting a translation platform.
                assertEquals(1, db.translations().startQueued(job.id))
                assertTrue(subtitles.saveTranslations(
                    row, result, source, job.id, job.generatedFingerprint, job.targetKind, job.outputFilename,
                ))
                assertEquals(DownloadStatus.DONE, db.translations().get(job.id)?.status)
                val saved = checkNotNull(parseGeneratedSubtitles(output.readText()))
                val fullFingerprint = subtitleSourceFingerprint(source.sourceCues, includeTranslation = true)
                assertEquals(64, fullFingerprint.length)
                assertEquals(fullFingerprint, saved.sourceFingerprint)
                assertTrue(output.name.endsWith("-${fullFingerprint.take(8)}.json"))
                assertEquals(result, saved.displayCues)
                output
            } finally {
                request.cancel()
                submission.cancelAndJoin()
            }
        }
    }

    private suspend fun withFixture(block: suspend Fixture.() -> Unit) {
        val fixture = Fixture()
        try {
            fixture.block()
        } finally {
            fixture.close()
        }
    }

    private fun completedRecovery() = Job().apply { complete() }
}
