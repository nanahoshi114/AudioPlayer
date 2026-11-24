package com.nanahoshi.audioplayer.playback

import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.FileKind
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.LibraryFileEntity
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.data.resolveLibraryFileSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkSubtitleMatcherTest {
    private val folders = listOf(
        FolderEntity(id = 1, name = "Work", position = 0, asmrSourceId = "RJ123456"),
        folder(2, 1, "WAV"), folder(3, 1, "LRC"), folder(4, 1, "字幕"),
        folder(5, 2, "Disc1"), folder(6, 3, "Disc1"), folder(7, 3, "Disc2"),
        FolderEntity(id = 8, name = "Other work", position = 1, asmrSourceId = "RJ654321"),
    ).associateBy { it.id }

    @Test
    fun sameFolderRanksExactBeforeNormalizedAndLrcBeforeVttBeforeSrt() {
        val track = track("01.wav")
        val lrc = file(1, "01.lrc")
        val vtt = file(2, "01.vtt")
        val srt = file(3, "01.srt")
        val normalized = file(4, "01.mp3.lrc")

        val result = match(track, normalized, srt, vtt, lrc)

        assertEquals(lrc, result.best)
        assertEquals(listOf(lrc, vtt, srt, normalized), result.candidates.map { it.file })
        assertEquals(
            listOf(SubtitleMatchTier.EXACT, SubtitleMatchTier.EXACT, SubtitleMatchTier.EXACT, SubtitleMatchTier.NORMALIZED),
            result.candidates.map { it.tier },
        )
        assertTrue(result.candidates.all { it.scope == WorkSubtitleScope.SAME_FOLDER })
        assertEquals(normalized, match(track, normalized).best)
    }

    @Test
    fun sameWorkAllowsSeparateSubtitleFoldersButExcludesAnotherWork() {
        val track = track("01.wav")
        val exact = file(1, "01.lrc", 3)
        val tagged = file(2, "01 [中文].srt", 4)
        val unrelated = file(3, "01.lrc", 8)

        val result = match(track, unrelated, tagged, exact)

        assertEquals(exact, result.best)
        assertEquals(listOf("LRC/01.lrc", "字幕/01 [中文].srt"), result.candidates.map { it.relativePath })
        assertTrue(result.candidates.all { it.scope == WorkSubtitleScope.SAME_SOURCE })
        assertEquals(tagged, match(track, tagged).best)
    }

    @Test
    fun normalizesFullWidthCharactersSeparatorsAndLanguageTags() {
        val track = track("０１＿Ｔａｌｋ.wav")
        val subtitle = file(1, "01-Talk.mp3［ＺＨ－ＨＡＮＳ］.srt")

        val result = match(track, subtitle)

        assertEquals(subtitle, result.best)
        assertEquals(SubtitleMatchTier.NORMALIZED, result.candidates.single().tier)
    }

    @Test
    fun matchingDiscDisambiguatesOtherwiseIdenticalSubtitles() {
        val track = track("01.wav", folderId = 5)
        val discOne = file(1, "01.lrc", 6)
        val discTwo = file(2, "01.lrc", 7)

        val result = match(track, discTwo, discOne)

        assertEquals(discOne, result.best)
        assertEquals(listOf("LRC/Disc1/01.lrc", "LRC/Disc2/01.lrc"), result.candidates.map { it.relativePath })
    }

    @Test
    fun trackNumbersRequireACompleteOneToOneMapping() {
        val first = track("01 Opening.wav")
        val second = track("02 Ending.wav", id = 2)
        val firstSubtitle = file(1, "1 Prologue.lrc", 3)
        val secondSubtitle = file(2, "2 Epilogue.lrc", 3)
        val subtitles = listOf(firstSubtitle, secondSubtitle)
        val tracks = listOf(first, second)
        val otherSource = first.copy(id = 3, source = TrackSource.ASMR)

        for ((audio, subtitle) in tracks.zip(subtitles)) {
            val result = WorkSubtitleMatcher.match(audio, folders, subtitles, folderTracks = tracks + otherSource)
            assertEquals(subtitle, result.best)
            assertEquals(SubtitleMatchTier.TRACK_NUMBER, result.candidates.first().tier)
        }

        val duplicateAudio = listOf(first, second.copy(title = "01 Outro.wav"))
        val duplicateSubtitles = listOf(firstSubtitle, secondSubtitle.copy(name = "1 Epilogue.lrc"))
        for ((audio, files) in listOf(
            duplicateAudio to subtitles,
            tracks to duplicateSubtitles,
            tracks to listOf(firstSubtitle),
        )) {
            val result = WorkSubtitleMatcher.match(first, folders, files, folderTracks = audio)
            assertNull(result.best)
            assertEquals(files.toSet(), result.candidates.map { it.file }.toSet())
            assertTrue(result.candidates.all { it.tier == null })
        }
    }

    @Test
    fun equalConfidenceCandidatesStayVisibleWhileAutomaticSelectionSkipsTheTie() {
        val track = track("01.wav")
        val tied = listOf(file(1, "01.lrc", 3), file(2, "01.lrc", 4))
        val lowerConfidence = file(3, "01 [中文].srt", 4)
        val result = match(track, *(tied + lowerConfidence).toTypedArray())

        assertNull(result.best)
        assertEquals((tied + lowerConfidence).toSet(), result.candidates.map { it.file }.toSet())
        assertEquals(
            listOf(lowerConfidence),
            WorkSubtitleMatcher.automaticCandidates(result, track, folders).map { it.file },
        )
    }

    @Test
    fun dlsiteBorrowsAsmrSubtitlesOnlyWithCrossSourceFallback() {
        val track = track("01.wav")
        val borrowed = file(1, "01.lrc", 3, hash = "asmr:01")

        val enabled = WorkSubtitleMatcher.match(track, folders, listOf(borrowed), crossSourceFallback = true)
        assertEquals(borrowed, enabled.best)
        assertEquals(WorkSubtitleScope.CROSS_SOURCE, enabled.candidates.single().scope)
        assertEquals(TrackSource.ASMR, enabled.candidates.single().source)

        val disabled = WorkSubtitleMatcher.match(track, folders, listOf(borrowed), crossSourceFallback = false)
        assertNull(disabled.best)
        assertTrue(disabled.candidates.isEmpty())
    }

    @Test
    fun inactiveDlsiteEntriesAreFilteredBeforeRankingAndNumericUniquenessChecks() {
        val track = track("01 Voice.wav")
        val exact = file(1, "01 Voice.lrc")
        val normalized = file(2, "01 Voice.mp3.lrc")
        val inactiveNumber = file(3, "01 Old script.lrc", 3)
        val activeNumber = file(4, "01 Current script.srt", 3)
        val files = listOf(exact, normalized, inactiveNumber, activeNumber)
        val unfiltered = WorkSubtitleMatcher.match(track, folders, files)
        assertEquals(exact, unfiltered.best)
        assertEquals(SubtitleMatchTier.NORMALIZED, unfiltered.candidates.first { it.file == normalized }.tier)

        val result = WorkSubtitleMatcher.match(track, folders, files, activeDlsiteFileIds = setOf(activeNumber.id))
        assertEquals(activeNumber, result.best)
        assertEquals(activeNumber, result.candidates.single().file)
        assertEquals(SubtitleMatchTier.TRACK_NUMBER, result.candidates.single().tier)
    }

    @Test
    fun legacyJapaneseAsmrFileSourceUsesManifestIdsOrStableUris() {
        val old = file(1, "01.lrc", hash = "unscoped")
        val stableUri = old.copy(id = 2, remoteUrl = "japaneseasmr://download/01.lrc")

        assertEquals(TrackSource.ASMR, resolveLibraryFileSource(old))
        assertEquals(TrackSource.JAPANESE_ASMR, resolveLibraryFileSource(old, jaFileIds = setOf(old.id)))
        assertEquals(TrackSource.JAPANESE_ASMR, resolveLibraryFileSource(stableUri))
        assertEquals(TrackSource.DLSITE, resolveLibraryFileSource(file(3, "01.lrc")))
    }

    @Test
    fun batchByNameRanksMatchesAndDropsTiesAndSharedSuggestions() {
        val first = track("01.wav")
        val second = track("02.wav", id = 2)
        val duplicate = track("02.mp3", id = 3)
        val exact = SubtitleCandidateEntry("01.srt", fileId = 1)
        val normalized = SubtitleCandidateEntry("01.mp3.lrc", fileId = 2)
        val shared = SubtitleCandidateEntry("02.lrc", fileId = 3)
        val candidates = listOf(shared, normalized, exact)

        assertEquals(listOf(first to exact), pairs(listOf(first, second, duplicate), candidates, BatchPairMode.BY_NAME))
        val tied = exact.copy(fileId = 4)
        assertTrue(pairs(listOf(first), candidates + tied, BatchPairMode.BY_NAME).isEmpty())
    }

    @Test
    fun batchByOrderPairsSuppliedOrderUpToTheShorterList() {
        val first = track("01.wav")
        val second = track("02.wav", id = 2)
        val subtitle = SubtitleCandidateEntry("02.srt", fileId = 1)
        assertEquals(listOf(first to subtitle), pairs(listOf(first, second), listOf(subtitle), BatchPairMode.BY_ORDER))
    }

    @Test
    fun trackNumbersStayScopedToEachSourceInsideTheSameFolder() {
        val dlsite = track("01 Voice.wav")
        val asmrIntro = track("01 Extra.wav", id = 3).copy(source = TrackSource.ASMR)
        val asmrAfter = track("02 After.wav", id = 4).copy(source = TrackSource.ASMR)
        val dlsiteSubtitle = file(1, "1 Prologue.lrc", 3, hash = "dlsite:1")
        val asmrOne = file(2, "1 Intro.lrc", 3, hash = "asmr:2")
        val asmrTwo = file(3, "2 Epilogue.lrc", 3, hash = "asmr:3")
        val folderTracks = listOf(dlsite, asmrIntro, asmrAfter)

        val result = WorkSubtitleMatcher.match(
            dlsite, folders, listOf(dlsiteSubtitle, asmrOne, asmrTwo), folderTracks = folderTracks,
        )
        assertEquals(dlsiteSubtitle, result.best)
        assertEquals(SubtitleMatchTier.TRACK_NUMBER, result.candidates.first { it.file == dlsiteSubtitle }.tier)
        // ASMR 的序号集合与 DLSite 不同，不应把 DLSite 的匹配误判成歧义。
        assertNull(result.candidates.first { it.file == asmrOne }.tier)
        assertNull(result.candidates.first { it.file == asmrTwo }.tier)

        val asmrResult = WorkSubtitleMatcher.match(
            asmrAfter, folders, listOf(dlsiteSubtitle, asmrOne, asmrTwo), folderTracks = folderTracks,
        )
        assertEquals(asmrTwo, asmrResult.best)
        assertEquals(SubtitleMatchTier.TRACK_NUMBER, asmrResult.candidates.first { it.file == asmrTwo }.tier)
        assertNull(asmrResult.candidates.first { it.file == asmrOne }.tier)
    }

    @Test
    fun batchByIdentifierUsesPreIndexedNormalizationAndKeepsUniqueMatches() {
        val first = track("０１＿Talk.wav")
        val second = track("02.wav", id = 2)
        val third = track("03.wav", id = 3)
        val exactNormalized = SubtitleCandidateEntry("01-Talk.srt", fileId = 1)
        val normalized = SubtitleCandidateEntry("02.mp3.lrc", fileId = 2)
        val lowerRank = SubtitleCandidateEntry("02 [中文].srt", fileId = 3)

        val result = pairs(
            listOf(first, second, third),
            listOf(lowerRank, normalized, exactNormalized),
            BatchPairMode.BY_NAME,
        )
        assertEquals(listOf(first to exactNormalized, second to normalized), result)
        // 按顺序模式保持两端原有顺序，不做名称匹配。
        assertEquals(
            listOf(first to lowerRank, second to normalized),
            pairs(listOf(first, second), listOf(lowerRank, normalized), BatchPairMode.BY_ORDER),
        )
    }

    private fun match(track: TrackEntity, vararg files: LibraryFileEntity) =
        WorkSubtitleMatcher.match(track, folders, files.toList())

    private fun pairs(tracks: List<TrackEntity>, candidates: List<SubtitleCandidateEntry>, mode: BatchPairMode) =
        WorkSubtitleMatcher.suggestBatchPairs(tracks, candidates, mode)

    private fun folder(id: Long, parentId: Long, name: String) =
        FolderEntity(id = id, parentId = parentId, name = name, position = 0)

    private fun track(title: String, folderId: Long = 2, id: Long = 1) = TrackEntity(
        id = id, source = TrackSource.DLSITE, title = title, artist = null, durationMs = 0,
        coverUri = null, localUri = null, bvid = null, cid = null, page = null, addedAt = 0, folderId = folderId,
    )

    private fun file(id: Long, name: String, folderId: Long = 2, hash: String = "dlsite:$id") = LibraryFileEntity(
        id = id, folderId = folderId, name = name, kind = FileKind.TEXT,
        remoteUrl = "https://example.invalid/$id", addedAt = 0, asmrWorkId = 123456, fileHash = hash,
    )
}
