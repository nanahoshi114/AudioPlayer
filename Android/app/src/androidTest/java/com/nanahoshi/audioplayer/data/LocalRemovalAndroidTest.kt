package com.nanahoshi.audioplayer.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nanahoshi.audioplayer.asmr.AsmrClient
import com.nanahoshi.audioplayer.bilibili.BilibiliClient
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.data.db.DlsiteOriginalEntryEntity
import com.nanahoshi.audioplayer.data.db.DlsiteOriginalWorkEntity
import com.nanahoshi.audioplayer.data.db.DownloadEntity
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.JaDownloadEntryEntity
import com.nanahoshi.audioplayer.data.db.LibraryFileEntity
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.data.db.TrackSubtitleBindingEntity
import com.nanahoshi.audioplayer.data.db.TrackSubtitleKind
import com.nanahoshi.audioplayer.data.db.TranslateMark
import com.nanahoshi.audioplayer.data.db.WhisperJobEntity
import com.nanahoshi.audioplayer.dlsite.DlsiteClient
import com.nanahoshi.audioplayer.japaneseasmr.JA_WORK_DOWNLOAD
import com.nanahoshi.audioplayer.japaneseasmr.JaFileTransfer
import com.nanahoshi.audioplayer.japaneseasmr.JaPostRef
import com.nanahoshi.audioplayer.japaneseasmr.JaRemoteFile
import com.nanahoshi.audioplayer.japaneseasmr.JaWorkClient
import com.nanahoshi.audioplayer.japaneseasmr.JaWorkManifest
import com.nanahoshi.audioplayer.japaneseasmr.JapaneseAsmrClient
import com.nanahoshi.audioplayer.japaneseasmr.jaDownloadEntry
import com.nanahoshi.audioplayer.japaneseasmr.jaHash
import com.nanahoshi.audioplayer.playback.SubtitleSource
import com.nanahoshi.audioplayer.text.UserFacingException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LocalRemovalAndroidTest {
    @Test
    fun unlinksUserFilesAndOnlyDeletesSubtitlesWhenRequestedForDownloads(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val directory = File(context.filesDir, "local-removal-test/${UUID.randomUUID()}").apply { mkdirs() }
        val cues = mutableListOf<File>()
        try {
            val subtitles = SubtitleSource(context, db, BilibiliClient(CredentialStore(context)), AsmrClient())
            suspend fun fixture(
                name: String,
                path: String,
                source: TrackSource = TrackSource.ASMR,
                bvid: String? = null,
                cid: Long? = null,
            ): Pair<TrackEntity, File> {
                val track = TrackEntity(source = source, title = name, artist = null,
                    durationMs = 1000, coverUri = null, localUri = path, bvid = bvid, cid = cid,
                    page = null, addedAt = 0)
                val saved = track.copy(id = db.tracks().insert(track))
                val fingerprint = UUID.randomUUID().toString().replace("-", "")
                val cue = File(context.filesDir, "subtitles/$fingerprint.json")
                cues += cue
                cue.parentFile?.mkdirs()
                cue.writeText("[]")
                db.whisper().insert(WhisperJobEntity(title = name, fingerprint = fingerprint,
                    trackId = saved.id, localPath = path, remoteJobId = null, status = DownloadStatus.DONE,
                    progress = 1f, error = null, awaitRemoteUrl = null))
                return saved to cue
            }

            val userAudio = File(directory, "user.wav").apply { writeText("user") }
            val (linked, linkedCue) = fixture("linked", userAudio.toURI().toString())
            val (mixedLink, mixedLinkCue) = fixture("mixed link", "content://fixture/audio")
            val audio = File(directory, "download.wav").apply { writeText("download") }
            val (downloaded, downloadedCue) = fixture("downloaded", audio.path)
            val changed = File(directory, "changed.wav").apply { writeText("replacement") }
            val (old, oldCue) = fixture("changed", "content://fixture/old")
            db.tracks().updateLocalUri(old.id, changed.path)

            assertEquals(DeleteResult(0, 1), deleteLocalCopies(db, context.filesDir, subtitles, listOf(linked), true))
            assertTrue(userAudio.isFile)
            assertTrue(linkedCue.isFile)
            assertNull(db.tracks().getById(linked.id)?.localUri)
            assertNotNull(db.whisper().latestDoneForTrack(linked.id))

            assertEquals(DeleteResult(1, 1, 1),
                deleteLocalCopies(db, context.filesDir, subtitles, listOf(downloaded, mixedLink, old), false))
            assertFalse(audio.exists())
            assertTrue(downloadedCue.isFile)
            assertTrue(mixedLinkCue.isFile)
            assertNull(db.tracks().getById(mixedLink.id)?.localUri)
            assertTrue(oldCue.isFile)
            assertTrue(changed.isFile)
            assertEquals(changed.path, db.tracks().getById(old.id)?.localUri)

            val deleteAudio = File(directory, "delete.wav").apply { writeText("download") }
            val (deleteTrack, deleteCue) = fixture("delete subtitles", deleteAudio.path)
            assertEquals(DeleteResult(1, 0), deleteLocalCopies(db, context.filesDir, subtitles, listOf(deleteTrack), true))
            assertFalse(deleteAudio.exists())
            assertFalse(deleteCue.exists())
            assertNull(db.whisper().latestDoneForTrack(deleteTrack.id))

            // B 站关联与 ASMR/DLSITE 一致：只取消关联，用户文件、字幕、听写和视频字幕缓存都保留。
            val (biliLink, biliLinkCue) = fixture("bili link", "content://fixture/bili", TrackSource.BILIBILI, "BV1link00001", 1L)
            val biliCache = File(context.filesDir, "bilibili/BV1link00001/1.json").apply { parentFile?.mkdirs(); writeText("[]") }
            assertEquals(DeleteResult(0, 1), deleteLocalCopies(db, context.filesDir, subtitles, listOf(biliLink), true))
            assertNull(db.tracks().getById(biliLink.id)?.localUri)
            assertTrue(biliLinkCue.isFile)
            assertNotNull(db.whisper().latestDoneForTrack(biliLink.id))
            assertTrue(biliCache.isFile)
            biliCache.delete()
            biliCache.parentFile?.delete()
        } finally {
            db.close()
            cues.forEach { it.delete() }
            directory.deleteRecursively()
        }
    }

    @Test
    fun mixedBatchKeepsSubtitlesWhenRequested() = runBlocking { assertMixedBatch(false) }

    @Test
    fun mixedBatchDeletesSubtitlesWhenRequested() = runBlocking { assertMixedBatch(true) }

    private suspend fun assertMixedBatch(deleteSubtitles: Boolean) = withLibrary {
        val audio = write("bilibili/BV1234567890/1.m4s")
        val videoSubtitle = write("bilibili/BV1234567890/1.json")
        val track = track(audio.path, TrackSource.BILIBILI, bvid = "BV1234567890", cid = 1)
        val generated = subtitles.generatedFile("abc123").apply { parentFile?.mkdirs(); writeText("[]") }
        val whisperId = db.whisper().insert(WhisperJobEntity(
            title = track.title, fingerprint = "abc123", trackId = track.id, localPath = audio.path,
            remoteJobId = null, status = DownloadStatus.DONE, progress = 1f, error = null, awaitRemoteUrl = null,
        ))
        val external = write("external.wav")
        val associated = track(external.toURI().toString())
        val subtitle = write("library/subtitle.srt")
        val subtitleRow = file(subtitle.name, subtitle.path)
        val attachment = write("library/cover.jpg")
        val attachmentRow = file(attachment.name, attachment.path)
        val custom = write("custom-subtitles/renamed.srt")
        bindCopy(track.id, custom.path)
        val local = write("local.wav")
        val localTrack = track(local.path, TrackSource.LOCAL)

        val plan = library.observeLocalRemovalTargets(
            setOf(track.id, associated.id, localTrack.id), emptySet(), setOf(subtitleRow.id, attachmentRow.id), null,
        ).first()
        assertEquals(listOf(custom.canonicalPath), plan.customSubtitlePaths)
        assertEquals(DeleteResult(1, 1, deletedFiles = if (deleteSubtitles) 2 else 1),
            remove(plan.copy(audioDownloads = plan.audioDownloads + localTrack), deleteSubtitles))

        assertFalse(audio.exists())
        assertFalse(videoSubtitle.exists())
        assertFalse(attachment.exists())
        assertNull(db.files().get(attachmentRow.id)?.localUri)
        assertNull(db.tracks().getById(track.id)?.localUri)
        assertNull(db.tracks().getById(associated.id)?.localUri)
        assertTrue(external.isFile)
        assertTrue(local.isFile)
        assertEquals(local.path, db.tracks().getById(localTrack.id)?.localUri)
        assertEquals(!deleteSubtitles, subtitle.isFile)
        assertEquals(!deleteSubtitles, generated.isFile)
        assertEquals(!deleteSubtitles, custom.isFile)
        assertEquals(!deleteSubtitles, db.files().get(subtitleRow.id)?.localUri != null)
        assertEquals(!deleteSubtitles, db.whisper().get(whisperId) != null)
        assertEquals(!deleteSubtitles, db.trackSubtitles().getForTrack(track.id) != null)
    }

    @Test
    fun subtitleOnlyAndAttachmentOnlyBatchesClearTheirRows() = runBlocking {
        withLibrary {
            val subtitle = write("only.vtt")
            val subtitleRow = file(subtitle.name, subtitle.path)
            val attachment = write("only.jpg")
            val attachmentRow = file(attachment.name, attachment.path)

            val subtitleOnly = LocalRemovalPlan(subtitleFiles = listOf(subtitleRow))
            assertEquals(DeleteResult(0, 0), remove(subtitleOnly, false))
            assertTrue(subtitle.isFile)
            assertEquals(DeleteResult(0, 0, deletedFiles = 1), remove(subtitleOnly, true))
            assertFalse(subtitle.exists())
            assertNull(db.files().get(subtitleRow.id)?.localUri)

            assertEquals(DeleteResult(0, 0, deletedFiles = 1),
                remove(LocalRemovalPlan(otherFiles = listOf(attachmentRow)), false))
            assertFalse(attachment.exists())
            assertNull(db.files().get(attachmentRow.id)?.localUri)
        }
    }

    @Test
    fun folderSelectionIncludesHiddenSubtitlesFromTheActiveDlsiteManifestOnly() = runBlocking {
        withLibrary {
            val work = db.folders().insert(FolderEntity(name = "work", position = 0, asmrSourceId = RJ, asmrWorkId = 1))
            val child = db.folders().insert(FolderEntity(parentId = work, name = "child", position = 0,
                asmrWorkId = 1, contentSource = TrackSource.DLSITE.name))
            val hidden = file("hidden.srt", write("hidden.srt").path, child)
            val borrowed = file("borrowed.srt", write("borrowed.srt").path, child, "asmr")
            val oldDlsite = file("inactive.srt", write("inactive.srt").path, child)
            val ja = file("ja.srt", write("ja.srt").path, child)
            db.jaDownloads().saveEntries(listOf(JaDownloadEntryEntity(RJ, "51", ja.name, "FILE", 1,
                null, ja.localUri, null, fileId = ja.id)))
            db.dlsiteOriginals().upsertWork(DlsiteOriginalWorkEntity(RJ, work, root.path, "active"))
            db.dlsiteOriginals().upsertEntries(listOf(
                DlsiteOriginalEntryEntity(RJ, "child", "FOLDER", 0, root.path, folderId = child),
                DlsiteOriginalEntryEntity(RJ, "child/hidden.srt", "TEXT", 1, hidden.localUri!!, fileId = hidden.id),
                DlsiteOriginalEntryEntity(RJ, "child/ja.srt", "TEXT", 1, ja.localUri!!, fileId = ja.id),
            ))

            val plan = library.observeLocalRemovalTargets(
                emptySet(), setOf(work), setOf(borrowed.id, oldDlsite.id), TrackSource.DLSITE,
            ).first()
            assertEquals(listOf(hidden), plan.subtitleFiles)
            assertEquals(1, remove(plan, true).deletedFiles)
            assertNull(db.files().get(hidden.id)?.localUri)
            for (unaffected in listOf(borrowed, oldDlsite, ja)) {
                assertEquals(unaffected.localUri, db.files().get(unaffected.id)?.localUri)
                assertTrue(File(unaffected.localUri!!).isFile)
            }
        }
    }

    @Test
    fun removeItemsRejectsAnUncoveredWorkFileBeforeDeletingAnySelection() = runBlocking {
        withLibrary {
            val work = db.folders().insert(FolderEntity(name = "work", position = 0, asmrSourceId = RJ))
            val workFile = file("work.vtt", null, work)
            val loose = file("loose.vtt", null)
            val track = track(null)
            val result = runCatching { library.deleteItems(listOf(track.id), emptyList(), listOf(loose.id, workFile.id)) }
            assertTrue(result.exceptionOrNull() is LibraryRemovalRejected)
            assertNotNull(db.tracks().getById(track.id))
            assertNotNull(db.files().get(loose.id))
            assertNotNull(db.files().get(workFile.id))

            library.deleteItems(listOf(track.id), listOf(work), listOf(loose.id, workFile.id))
            assertNull(db.tracks().getById(track.id))
            assertNull(db.files().get(loose.id))
            assertNull(db.files().get(workFile.id))
        }
    }

    @Test
    fun sharedCopyAddedAfterConfirmationIsPreserved() = runBlocking {
        withLibrary {
            val first = track(write("first.wav").path)
            val second = track(write("second.wav").path)
            val copy = write("custom-subtitles/shared.srt")
            bindCopy(first.id, copy.path)
            val plan = library.observeLocalRemovalTargets(setOf(first.id), emptySet(), emptySet(), null).first()
            assertEquals(listOf(copy.canonicalPath), plan.customSubtitlePaths)
            bindCopy(second.id, alias(copy))
            val refreshed = library.observeLocalRemovalTargets(setOf(first.id), emptySet(), emptySet(), null).first()
            assertTrue(refreshed.customSubtitlePaths.isEmpty())

            assertEquals(DeleteResult(1, 0), remove(plan, true))
            assertTrue(copy.isFile)
            assertNotNull(db.trackSubtitles().getForTrack(first.id))
            assertNotNull(db.trackSubtitles().getForTrack(second.id))
        }
    }

    @Test
    fun aliasesAreCountedOnceAndMissingCopiesReconcileWithoutClearingChangedPaths() = runBlocking {
        withLibrary {
            val audio = write("audio.wav")
            val rawTrack = track(alias(audio))
            val canonicalTrack = track(audio.canonicalPath)
            val subtitle = write("aliases.srt")
            val raw = file("raw.srt", alias(subtitle))
            val canonical = file("canonical.srt", subtitle.canonicalPath)
            val missing = write("missing.srt")
            val missingRow = file(missing.name, missing.path)
            val blocked = File(root, "blocked.srt").apply { mkdirs() }
            val blockedRow = file(blocked.name, blocked.path)
            val replaced = write("replaced.srt")
            val stale = file("replaced.srt", missing.path)
            db.files().update(stale.copy(localUri = replaced.path))
            assertTrue(missing.delete())

            val result = remove(LocalRemovalPlan(audioDownloads = listOf(rawTrack, canonicalTrack),
                subtitleFiles = listOf(raw, canonical, missingRow, stale, blockedRow)), true)
            assertEquals(DeleteResult(1, 0, changed = 2, deletedFiles = 2), result)
            assertFalse(audio.exists())
            assertFalse(subtitle.exists())
            for (id in listOf(raw.id, canonical.id, missingRow.id)) assertNull(db.files().get(id)?.localUri)
            for (id in listOf(rawTrack.id, canonicalTrack.id)) assertNull(db.tracks().getById(id)?.localUri)
            assertEquals(replaced.path, db.files().get(stale.id)?.localUri)
            assertEquals(blocked.path, db.files().get(blockedRow.id)?.localUri)
            assertTrue(replaced.isFile)
            assertTrue(blocked.isDirectory)
        }
    }

    @Test
    fun storedCatalogClearsRawAndCanonicalOriginalSubtitlePathsAndCustomBindings() = runBlocking {
        withLibrary {
            val subtitle = write("dlsite/$RJ/original/test/voice.srt")
            val raw = file(subtitle.name, alias(subtitle))
            val canonical = file("alias.srt", subtitle.canonicalPath)
            db.dlsiteOriginals().upsertEntries(listOf(
                DlsiteOriginalEntryEntity(RJ, subtitle.name, "TEXT", subtitle.length(), raw.localUri!!, fileId = raw.id),
            ))
            val work = db.folders().insert(FolderEntity(name = "work title", position = 0, asmrSourceId = RJ))
            val track = track(null, folderId = work)
            val custom = write("custom-subtitles/import.srt")
            bindCopy(track.id, custom.path)
            write("custom-subtitles/.staging/partial.srt")
            val catalog = StoredCatalog(context, db, subtitles, bindings)
            val listed = catalog.list()
            val originalItem = listed.single { filesTouch(it.path, subtitle) }
            val customItem = listed.single { filesTouch(it.path, custom) }
            assertEquals(StoredKind.SUBTITLE, originalItem.kind)
            assertEquals(StoredKind.SUBTITLE, customItem.kind)
            assertEquals("work title", customItem.workTitle)
            assertEquals("imported.srt", customItem.name)
            assertFalse(listed.any { it.path.contains("/.staging/") })

            catalog.delete(listOf(originalItem, customItem), deleteSubtitles = false)
            assertFalse(subtitle.exists())
            assertFalse(custom.exists())
            assertNull(db.files().get(raw.id)?.localUri)
            assertNull(db.files().get(canonical.id)?.localUri)
            assertNull(db.trackSubtitles().getForTrack(track.id))
        }
    }

    @Test
    fun japaneseAsmrReuseReadsCurrentPathsAndRechecksDiskBeforePublication() = runBlocking {
        withLibrary {
            val work = db.folders().insert(FolderEntity(name = "work", position = 0, asmrSourceId = RJ))
            val reused = write("japaneseasmr-downloads/$RJ/files/voice.wav")
            val retained = write("japaneseasmr-downloads/$RJ/files/old.srt")
            val disappeared = write("japaneseasmr-downloads/$RJ/files/gone.srt")
            val track = track(reused.path, TrackSource.JAPANESE_ASMR, folderId = work).copy(
                remoteUrl = jaWorkFileIdentity(RJ, reused.name),
            ).also { db.tracks().update(it) }
            suspend fun attachment(disk: File) = file(disk.name, disk.path, work).copy(
                remoteUrl = jaWorkFileIdentity(RJ, disk.name),
            ).also { db.files().update(it) }
            val old = attachment(retained)
            val gone = attachment(disappeared)
            val post = JaPostRef("51", RJ, "fixture", null, JapaneseAsmrClient.postUrl("51"), null)
            val entry = jaDownloadEntry(post, null).single()
            db.jaDownloads().saveEntries(listOf(
                JaDownloadEntryEntity(RJ, post.postId, reused.name, "AUDIO", reused.length(), null,
                    reused.path, null, trackId = track.id),
                JaDownloadEntryEntity(RJ, post.postId, retained.name, "FILE", retained.length(), null,
                    retained.path, null, fileId = old.id),
                JaDownloadEntryEntity(RJ, post.postId, disappeared.name, "FILE", disappeared.length(), null,
                    disappeared.path, null, fileId = gone.id),
            ))
            val manifest = JaWorkManifest(entry, listOf(reused, disappeared).map {
                JaRemoteFile(it.name, it.length(), null, JaFileTransfer.Buzz("https://buzzheavier.com/fixture".toHttpUrl(), null))
            }, 0)
            val job = DownloadEntity(trackId = 0, title = RJ, bytesDone = 0, bytesTotal = 0,
                status = DownloadStatus.RUNNING, error = null, source = TrackSource.JAPANESE_ASMR.name,
                workKey = RJ, fileKey = post.postId, kind = JA_WORK_DOWNLOAD, workFolderId = work,
                jaEntryKey = jaHash(entry.address))
            val id = db.downloads().insert(job)
            val report = TaskProgressTracker().begin(TaskKey(TaskKind.DOWNLOAD, id), ProgressPart.DOWNLOAD)
            val client = JaWorkClient(OkHttpClient.Builder().addInterceptor { error("The manifest is reusable") }.build())

            withTimeout(5_000) {
                coroutineScope {
                    val importing = CompletableDeferred<Unit>()
                    val deleted = CompletableDeferred<Unit>()
                    val publish = async {
                        library.jaWorkFiles.execute(job.copy(id = id), manifest, client, report, phase = {
                            if (it == ProgressPhase.IMPORTING) {
                                importing.complete(Unit)
                                deleted.await()
                            }
                        }, requireTarget = {})
                    }
                    importing.await()
                    assertEquals(DeleteResult(1, 0, deletedFiles = 1), remove(LocalRemovalPlan(
                        audioDownloads = listOf(track), subtitleFiles = listOf(old),
                    ), true))
                    // Bytes at a formerly published path do not restore a cleared association.
                    reused.writeText("unassociated")
                    retained.writeText("unassociated")
                    assertTrue(disappeared.delete())
                    deleted.complete(Unit)
                    publish.await()
                }
            }

            assertNull(db.tracks().getById(track.id)?.localUri)
            assertNull(db.files().get(old.id)?.localUri)
            assertNull(db.files().get(gone.id)?.localUri)
            for (disk in listOf(reused, retained, disappeared)) {
                assertNull(db.jaDownloads().entry(RJ, disk.name)?.localPath)
            }
            assertEquals(DownloadStatus.DONE, db.downloads().get(id)?.status)
        }
    }

    @Test
    fun dlsitePreviouslyScannedDirectoryFailsCleanlyWhenAFileIsDeleted() = runBlocking {
        withLibrary {
            db.folders().insert(FolderEntity(name = "work", position = 0, asmrSourceId = RJ))
            val directory = File(root, "dlsite/$RJ/original/first")
            val audio = write("dlsite/$RJ/original/first/voice.wav")
            val subtitle = write("dlsite/$RJ/original/first/voice.srt")
            var beforePublication: suspend () -> Unit = {}
            val store = DlsiteOriginalStore(context, db, dlsite, library) { _, _, _ -> beforePublication(); emptyList() }
            store.importDirectory(RJ, directory, "first")
            val manifest = db.dlsiteOriginals().entries(RJ)
            val fileRow = db.files().get(manifest.single { it.relativePath == subtitle.name }.fileId!!)!!

            withTimeout(5_000) {
                coroutineScope {
                    val scanned = CompletableDeferred<Unit>()
                    val deleted = CompletableDeferred<Unit>()
                    beforePublication = { scanned.complete(Unit); deleted.await() }
                    val publication = async { runCatching { store.importDirectory(RJ, directory, "second") } }
                    scanned.await()
                    assertEquals(1, remove(LocalRemovalPlan(subtitleFiles = listOf(fileRow)), true).deletedFiles)
                    deleted.complete(Unit)
                    assertTrue(publication.await().exceptionOrNull() is UserFacingException)
                }
            }

            assertNull(db.files().get(fileRow.id)?.localUri)
            assertFalse(subtitle.exists())
            assertTrue(audio.isFile)
            assertEquals("first", db.dlsiteOriginals().work(RJ)?.generation)
            assertEquals(manifest, db.dlsiteOriginals().entries(RJ))
            assertTrue(runCatching { store.importDirectory(RJ, directory, "first") }.exceptionOrNull() is UserFacingException)
            assertEquals(manifest, db.dlsiteOriginals().entries(RJ))
        }
    }

    @Test
    fun sharedFingerprintCueSurvivesUntilTheLastLinkedTrackIsRemoved() = runBlocking {
        withLibrary {
            val audioA = write("asmr/RJ123456/a.wav")
            val audioB = write("asmr/RJ123456/b.wav")
            val first = track(audioA.path)
            val second = track(audioB.path)
            val fingerprint = "c".repeat(64)
            val cue = File(root, "subtitles/$fingerprint.json").apply {
                parentFile?.mkdirs()
                writeText("{\"cues\":[]}")
            }
            for ((saved, audio) in listOf(first to audioA, second to audioB)) {
                db.whisper().insert(WhisperJobEntity(
                    title = saved.title, fingerprint = fingerprint, trackId = saved.id, localPath = audio.path,
                    remoteJobId = null, status = DownloadStatus.DONE, progress = 1f, error = null, awaitRemoteUrl = null,
                ))
            }

            assertEquals(DeleteResult(1, 0), remove(LocalRemovalPlan(audioDownloads = listOf(first)), true))
            assertFalse(audioA.exists())
            assertTrue(cue.isFile)
            assertEquals(listOf(second.id), db.whisper().forFingerprint(fingerprint).map { it.trackId })

            assertEquals(DeleteResult(1, 0), remove(LocalRemovalPlan(audioDownloads = listOf(second)), true))
            assertFalse(audioB.exists())
            assertFalse(cue.exists())
            assertTrue(db.whisper().forFingerprint(fingerprint).isEmpty())
        }
    }

    @Test
    fun deletingASingleGeneratedSubtitleKeepsOverlayFiles() = runBlocking {
        withLibrary {
            val audio = write("asmr/RJ123456/song.mp3")
            val linked = track(audio.path)
            val fingerprint = "d".repeat(64)
            val cue = File(root, "subtitles/$fingerprint.json").apply {
                parentFile?.mkdirs()
                writeText("{\"cues\":[]}")
            }
            val overlay = File(root, "subtitles/extra-0123456789abcdef.json").apply { writeText("[]") }
            db.whisper().insert(WhisperJobEntity(
                title = linked.title, fingerprint = fingerprint, trackId = linked.id, localPath = audio.path,
                remoteJobId = null, status = DownloadStatus.DONE, progress = 1f, error = null, awaitRemoteUrl = null,
            ))

            val deleted = subtitles.generatedWriteLock.withLock { subtitles.discardGeneratedFileLocked(cue) }
            assertTrue(deleted)
            assertFalse(cue.exists())
            assertTrue(overlay.isFile)
            assertTrue(db.whisper().forFingerprint(fingerprint).isEmpty())
            // 覆盖文件不是生成字幕本体，绝不能被当作生成字幕删除。
            assertFalse(subtitles.generatedWriteLock.withLock { subtitles.discardGeneratedFileLocked(overlay) })
            assertTrue(overlay.isFile)
            assertNotNull(db.tracks().getById(linked.id))
        }
    }

    @Test
    fun activeAutoTranslationProtectsGeneratedCueAndWhisperRow() = runBlocking {
        withLibrary {
            val audio = write("asmr/RJ123456/active.mp3")
            val linked = track(audio.path)
            val fingerprint = "e".repeat(64)
            val cue = File(root, "subtitles/$fingerprint.json").apply {
                parentFile?.mkdirs()
                writeText("{\"cues\":[]}")
            }
            val jobId = db.whisper().insert(WhisperJobEntity(
                title = linked.title, fingerprint = fingerprint, trackId = linked.id, localPath = audio.path,
                remoteJobId = null, status = DownloadStatus.DONE, progress = 1f, error = null, awaitRemoteUrl = null,
                translateStatus = TranslateMark.QUEUED,
            ))

            assertEquals(DeleteResult(1, 0), remove(LocalRemovalPlan(audioDownloads = listOf(linked)), true))
            assertFalse(audio.exists())
            assertTrue(cue.isFile)
            assertEquals(TranslateMark.QUEUED, db.whisper().get(jobId)?.translateStatus)
        }
    }

    @Test
    fun catalogRemovesOrphanAudioAndItsGeneratedSubtitlesWithoutCrashing() = runBlocking {
        withLibrary {
            val audio = write("asmr/RJ123456/orphan.mp3")
            val fingerprint = "f".repeat(64)
            val cue = File(root, "subtitles/$fingerprint.json").apply {
                parentFile?.mkdirs()
                writeText("{\"cues\":[]}")
            }
            db.whisper().insert(WhisperJobEntity(
                title = "orphan", fingerprint = fingerprint, trackId = 999L, localPath = audio.path,
                remoteJobId = null, status = DownloadStatus.DONE, progress = 1f, error = null, awaitRemoteUrl = null,
            ))
            val catalog = StoredCatalog(context, db, subtitles, bindings)
            val item = StoredItem(
                path = audio.path, name = audio.name, kind = StoredKind.AUDIO, workTitle = null, workCode = null,
                sourceId = null, trackId = 999L, remoteUrl = null, audioPath = null,
            )

            catalog.delete(listOf(item), deleteSubtitles = true)
            assertFalse(audio.exists())
            assertFalse(cue.exists())
            assertTrue(db.whisper().doneForTrack(999L).isEmpty())
        }
    }

    private suspend fun withLibrary(test: suspend Fixture.() -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(app.cacheDir, "local-removal-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir(): File = root
            override fun getApplicationContext(): Context = this
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            Fixture(context, db, root).test()
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }

    private class Fixture(val context: Context, val db: AppDatabase, val root: File) {
        private val credentials = CredentialStore(context)
        private val bili = BilibiliClient(credentials)
        private val asmr = AsmrClient()
        val dlsite = DlsiteClient(credentials)
        val library = LibraryRepository(context, db, bili, asmr, dlsite)
        val subtitles = SubtitleSource(context, db, bili, asmr)
        val bindings = SubtitleBindingStore(context, db, subtitles)

        fun write(path: String): File = File(root, path).apply { parentFile?.mkdirs(); writeText("fixture") }

        fun alias(file: File): String {
            val sub = File(file.parentFile, "alias").apply { mkdirs() }
            return File(sub, "../${file.name}").absolutePath
        }

        suspend fun track(path: String?, source: TrackSource = TrackSource.ASMR, folderId: Long? = null,
                          bvid: String? = null, cid: Long? = null): TrackEntity {
            val row = TrackEntity(source = source, title = "track", artist = null, durationMs = 1000, coverUri = null,
                localUri = path, bvid = bvid, cid = cid, page = null, addedAt = 0, folderId = folderId)
            return row.copy(id = db.tracks().insert(row))
        }

        suspend fun file(name: String, path: String?, folderId: Long? = null, source: String = "dlsite"): LibraryFileEntity {
            val row = LibraryFileEntity(folderId = folderId, name = name, kind = previewKind(name), remoteUrl = "",
                addedAt = 0, asmrWorkId = 1, fileHash = "$source:$name", localUri = path)
            return row.copy(id = db.files().insert(row))
        }

        suspend fun bindCopy(trackId: Long, path: String) {
            db.trackSubtitles().upsert(TrackSubtitleBindingEntity(trackId = trackId, kind = TrackSubtitleKind.APP_FILE,
                uri = path, displayName = "imported.srt", updatedAt = 0))
        }

        suspend fun remove(plan: LocalRemovalPlan, subtitlesToo: Boolean): DeleteResult =
            deleteLocalTargets(db, root, subtitles, bindings, plan, subtitlesToo)
    }

    private companion object {
        const val RJ = "RJ123456"
    }
}
