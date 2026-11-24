package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.data.db.TranslateMark
import com.nanahoshi.audioplayer.data.db.WhisperJobEntity
import com.nanahoshi.audioplayer.playback.LoadedSubtitles
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WhisperMatchTargetsTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun matchesOnlyPositiveTrackIdsAndKeepsFailedJobsOutOfTheActiveState() {
        val row = row().copy(trackId = 42L)
        val targets = targets(row)
        val queued = job(trackId = 42L)
        assertTrue(matchesActiveWhisper(queued, targets))
        assertTrue(matchesActiveWhisper(queued.copy(status = DownloadStatus.RUNNING), targets))
        assertTrue(matchesActiveWhisper(queued.copy(status = DownloadStatus.DONE, translateStatus = TranslateMark.QUEUED), targets))
        assertTrue(matchesActiveWhisper(queued.copy(status = DownloadStatus.DONE, translateStatus = TranslateMark.RUNNING), targets))
        assertFalse(matchesActiveWhisper(queued.copy(status = DownloadStatus.DONE), targets))
        val failedTranslation = queued.copy(translateStatus = TranslateMark.FAILED, status = DownloadStatus.DONE)
        assertFalse(matchesActiveWhisper(failedTranslation, targets))
        assertTrue(matchesFailedWhisper(failedTranslation, targets))
        assertFalse(matchesFailedWhisper(failedTranslation.copy(dismissedAt = 100L), targets))
        val failed = queued.copy(status = DownloadStatus.FAILED)
        assertTrue(matchesFailedWhisper(failed, targets))
        assertFalse(matchesActiveWhisper(failed, targets))
        assertFalse(matchesFailedWhisper(queued, targets))
        assertFalse(matchesActiveWhisper(job(trackId = 99L), targets))
        assertFalse(matchesActiveWhisper(job(trackId = 0L), targets(row.copy(trackId = 0L))))
        assertFalse(matchesActiveWhisper(job(), targets(row.copy(trackId = null))))
    }

    @Test
    fun resolvesRowLibraryAndSavedPathsWithoutMaterializingContent() {
        val contentUri = "content://audio/document/123"
        val row = row().copy(localUri = contentUri, remoteUrl = "/legacy/audio.m4a")
        val track = row.toTrack().copy(id = 42L, localUri = "/library/audio.m4a")
        val staging = File(files.root, "whisper-src")
        val staged = File(staging, "42771eceaae17d00621a8f9f464ae0c95fa2eaa63e58a1009ee362da07c2d723")
        val saved = "/downloads/audio.m4a"
        val targets = targets(row, track = track, savedPaths = listOf(saved))
        assertEquals(setOf(staged.absolutePath, "/legacy/audio.m4a", "/library/audio.m4a", saved), targets.localPaths)
        assertTrue(matchesActiveWhisper(job(localPath = staged.absolutePath), targets))
        assertTrue(matchesActiveWhisper(job(trackId = 42L), targets))
        assertFalse(staging.exists())

        staging.mkdirs()
        staged.writeText("previous materialization")
        assertEquals(targets, targets(row, track = track, savedPaths = listOf(saved)))
        assertEquals("previous materialization", staged.readText())
        assertEquals(listOf(staged.name), staging.list()?.toList())
    }

    @Test
    fun previouslyCollidingContentUrisGetDifferentStagedPaths() {
        val first = "content://audio/document/Aa"
        val second = "content://audio/document/BB"
        assertEquals(first.hashCode(), second.hashCode())
        val firstPath = stagedFilePath(files.root, first)
        val secondPath = stagedFilePath(files.root, second)
        assertNotEquals(firstPath, secondPath)
        // 文件名使用完整 SHA-256 十六进制摘要，长内容 URI 也不会因截断碰撞。
        assertTrue(File(firstPath).name.matches(Regex("[0-9a-f]{64}")))
        assertEquals("whisper-src", File(firstPath).parentFile?.name)
    }

    @Test
    fun distinguishesBiliPartsAndMatchesLegacyLocalPlayback() {
        val row = row(TrackSource.BILIBILI).copy(
            workno = "BV1234567890",
            fileKey = "17",
            remoteUrl = "https://www.bilibili.com/video/BV1234567890",
        )
        val videoFolder = files.newFolder("bilibili", "BV1234567890")
        val file = File(videoFolder, "17.m4s")
        file.writeText("audio")
        val targets = targets(row)
        assertTrue(matchesActiveWhisper(job(localPath = file.absolutePath), targets))
        assertFalse(matchesActiveWhisper(job(localPath = File(videoFolder, "18.m4s").absolutePath), targets))
        assertFalse(matchesActiveWhisper(job(remoteUrl = row.remoteUrl), targets))

        val fromDownloads = targets(row.copy(workno = null, fileKey = null, remoteUrl = file.absolutePath))
        assertTrue(matchesActiveWhisper(job(remoteUrl = file.absolutePath), fromDownloads))
        assertTrue(matchesActiveWhisper(job(localPath = file.absolutePath), fromDownloads))
    }

    @Test
    fun matchesReadyYouTubeAudioAndExactJapaneseAsmrIdentities() {
        val videoId = "abcDEF01234"
        val file = File(files.newFolder("youtube", videoId), "audio.m4a")
        file.writeText("audio")
        val youtube = row(TrackSource.YOUTUBE).copy(youtubeVideoId = videoId)
        assertTrue(matchesActiveWhisper(job(localPath = file.absolutePath), targets(youtube)))
        assertFalse(matchesActiveWhisper(job(localPath = file.absolutePath), targets(youtube.copy(youtubeVideoId = "xyzDEF01234"))))

        val ja = row(TrackSource.JAPANESE_ASMR).copy(workno = "1234", fileKey = "track-key", remoteUrl = "https://example.org/audio")
        val targets = targets(ja)
        assertTrue(matchesActiveWhisper(job(remoteUrl = "japaneseasmr://audio?post=1234&media=track-key"), targets))
        assertFalse(matchesActiveWhisper(job(remoteUrl = "japaneseasmr://audio?post=5678&media=track-key"), targets))
        assertFalse(matchesActiveWhisper(job(remoteUrl = "japaneseasmr://audio?post=1234&media=another-key"), targets))
        assertFalse(matchesActiveWhisper(job(remoteUrl = ja.remoteUrl), targets))

        val workFile = "japaneseasmr://file?rj=RJ01234567&path=track"
        assertTrue(matchesActiveWhisper(job(remoteUrl = workFile), targets(ja.copy(remoteUrl = workFile))))
    }

    @Test
    fun acceptsGeneratedFingerprintOnlyFromTheCurrentQueueItem() {
        val row = row()
        val loaded = LoadedSubtitles(emptyList(), generatedEmpty = false, generated = true, generatedFingerprint = "fingerprint")
        val job = job().copy(fingerprint = "fingerprint")
        assertTrue(matchesActiveWhisper(job, targets(row, loaded, row.queueItemId)))
        assertFalse(matchesActiveWhisper(job, targets(row, loaded, row.queueItemId + 1L)))
        assertFalse(matchesActiveWhisper(job, targets(row, loaded, null)))
        assertFalse(matchesActiveWhisper(job.copy(fingerprint = ""), targets(row, loaded.copy(generatedFingerprint = ""), row.queueItemId)))
    }

    private fun targets(
        row: QueuedTrack,
        loaded: LoadedSubtitles? = null,
        loadedQueueItemId: Long? = null,
        track: TrackEntity? = null,
        savedPaths: List<String> = emptyList(),
    ) = resolveReadOnlyMatchTargets(row, loaded, loadedQueueItemId, files.root, track, savedPaths)

    private fun row(source: TrackSource = TrackSource.LOCAL) = QueuedTrack(
        queueItemId = 1L, position = 0, trackId = null, source = source, title = "Track",
        artist = null, durationMs = 0L, coverUri = null, localUri = null, bvid = null, cid = null,
        page = null, remoteUrl = null, referer = null, workno = null, fileKey = null,
    )

    private fun job(trackId: Long? = null, localPath: String? = null, remoteUrl: String? = null) = WhisperJobEntity(
        id = 1L, title = "Job", fingerprint = "", trackId = trackId, localPath = localPath, remoteJobId = null,
        status = DownloadStatus.QUEUED, progress = 0f, error = null, awaitRemoteUrl = remoteUrl,
    )
}
