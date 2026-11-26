package com.nanahoshi.audioplayer.playback

import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.UserFacingIOException
import com.nanahoshi.audioplayer.R
import org.junit.Assert.assertTrue
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleCuesTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun generatedDocumentPreservesSourceAndDisplaysRevisedBilingualCues() {
        val original = listOf(SubtitleCue(0, 900, "今日は"), SubtitleCue(1000, 1900, "いい天気"))
        val revised = listOf(SubtitleCue(0, 1900, "今日はいい天気。", "今天天气不错。", sourceCount = 2))
        val origin = SubtitleOrigin("parakeet-tdt-ctc-0.6b-ja", null)
        val raw = encodeGeneratedCues(original, origin, revisedCues = revised)
        val loaded = checkNotNull(parseGeneratedSubtitles(raw))
        assertEquals(original, loaded.cues)
        assertEquals(origin, loaded.origin)
        assertEquals(revised, parseGeneratedCues(raw))
        assertEquals("今日はいい天気。", loaded.displayCues.textAt(1100, chinese = false))
        assertEquals("今天天气不错。", loaded.displayCues.textAt(1100, chinese = true))

        val polished = revised.map { it.copy(translation = "今天天气真好。") }
        val afterPolish = checkNotNull(parseGeneratedSubtitles(encodeGeneratedCues(loaded.cues, loaded.origin, polished)))
        assertEquals(original, afterPolish.cues)
        assertEquals(polished, afterPolish.displayCues)
    }

    @Test
    fun translationOverlayChecksSourceTextAndTimingWhileReadingLegacyFiles() {
        val original = listOf(SubtitleCue(0, 900, "はい"), SubtitleCue(1000, 1900, "そうです"))
        val revised = listOf(SubtitleCue(0, 1900, "はい、そうです。", "嗯，是的。", sourceCount = 2))
        val overlay = checkNotNull(parseGeneratedSubtitles(encodeGeneratedCues(
            revised, null, sourceFingerprint = subtitleSourceFingerprint(original),
        )))
        assertEquals(revised, applySubtitleOverlay(original, overlay))
        val changedText = listOf(original[0].copy(text = "いいえ"), original[1])
        val changedTime = listOf(original[0].copy(endMs = 800), original[1])
        assertEquals(changedText, applySubtitleOverlay(changedText, overlay))
        assertEquals(changedTime, applySubtitleOverlay(changedTime, overlay))

        val legacyCue = original[0].copy(translation = "好的。")
        val legacy = checkNotNull(parseGeneratedSubtitles(encodeGeneratedCues(listOf(legacyCue))))
        assertEquals(listOf(legacyCue, original[1]), applySubtitleOverlay(original, legacy))
    }

    @Test
    fun sourceFingerprintIgnoresTranslationButTracksContentChanges() {
        val original = listOf(SubtitleCue(0, 900, "はい"), SubtitleCue(1000, 1900, "そうです"))
        val translated = original.map { it.copy(translation = "嗯，是的。") }
        assertEquals(subtitleSourceFingerprint(original), subtitleSourceFingerprint(translated))
        val changed = original.map { it.copy(text = it.text + "。") }
        assertEquals(false, subtitleSourceFingerprint(original) == subtitleSourceFingerprint(changed))
    }

    @Test
    fun atomicSubtitleWriteKeepsExistingContentsWhenReplacementFails() {
        val directory = temporaryFolder.newFolder("subtitles")
        val target = File(directory, "subtitle.json").apply { mkdir() }
        val original = File(target, "original").apply { writeText("旧字幕") }
        val failure = runCatching { target.writeTextAtomically("新字幕") }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(TextRef.Res(R.string.player_error_subtitle_save), (failure as UserFacingIOException).userText)
        assertEquals("旧字幕", original.readText())
        assertEquals(listOf("subtitle.json"), directory.list()?.toList())
    }

    @Test
    fun cancelledSubtitleCommitKeepsThePublishedFileAndDiscardsStaging() {
        val directory = temporaryFolder.newFolder("cancelled")
        val target = File(directory, "subtitle.json").apply { writeText("原文と旧訳文") }
        val failure = runCatching {
            target.withStagedSubtitle("新しい訳文") {
                assertEquals("原文と旧訳文", target.readText())
                throw CancellationException("已中止")
            }
        }.exceptionOrNull()
        assertEquals(CancellationException::class.java, failure?.javaClass)
        assertEquals("原文と旧訳文", target.readText())
        assertEquals(listOf("subtitle.json"), directory.list()?.toList())
    }

    @Test
    fun keepsOriginalExtensionBeforeStrippingIt() {
        val names = listOf("01.lrc", "01.mp3.srt", "01.mp3.lrc", "note.txt")
        assertEquals("01.mp3.lrc", pickSubtitleName("01.mp3", names))
        assertEquals("01.lrc", pickSubtitleName("01.mp3", listOf("01.vtt", "01.lrc")))
        assertEquals("01.vtt", pickSubtitleName("01.flac", listOf("01.srt", "01.vtt")))
    }

    @Test
    fun parsesBilibiliBody() {
        val cues = parseSubtitleDocument(
            """{"body":[{"from":1.5,"to":3,"content":"你好"}]}""",
        )
        assertEquals(1, cues.size)
        assertEquals(1500L, cues[0].startMs)
        assertEquals(3000L, cues[0].endMs)
        assertEquals("你好", cues[0].text)
        assertEquals("你好", cues.textAt(2000))
        assertEquals(0, cues.indexAt(2000))
        assertEquals("", cues.textAt(3000))
        assertEquals(-1, cues.indexAt(3000))
    }

    @Test
    fun parsesSrtAndLrc() {
        val srt = parseSubtitleDocument(
            """
            1
            00:00:01,000 --> 00:00:02,500
            第一句

            2
            00:00:03.000 --> 00:00:04.000
            第二句
            """.trimIndent(),
        )
        assertEquals(listOf(1000L, 3000L), srt.map { it.startMs })
        assertEquals("第一句", srt.textAt(1000))

        val lrc = parseSubtitleDocument(
            """
            [00:01.00]开头
            [00:02.50]后面
            """.trimIndent(),
        )
        assertEquals(1000L, lrc[0].startMs)
        assertEquals(2500L, lrc[0].endMs)
        assertEquals(Long.MAX_VALUE, lrc[1].endMs)
        assertEquals("后面", lrc.textAt(9000))
    }

    @Test
    fun parsesGeneratedCues() {
        val cues = parseGeneratedCues(
            """{"language":"ja","cues":[{"startMs":1200,"endMs":3400,"text":"こんにちは"}]}""",
        )
        assertEquals(1, cues.size)
        assertEquals(1200L, cues[0].startMs)
        assertEquals(3400L, cues[0].endMs)
        assertEquals("こんにちは", cues[0].text)
        assertEquals(emptyList<SubtitleCue>(), parseGeneratedCues("""{"cues":[]}"""))
    }

    @Test
    fun distinguishesCorruptGeneratedFileFromEmptyResult() {
        assertNull(parseGeneratedCuesOrNull("""{"cues":["""))
        assertNull(parseGeneratedCuesOrNull(""))
        assertNull(parseGeneratedCuesOrNull("<html>"))
        assertEquals(emptyList<SubtitleCue>(), parseGeneratedCuesOrNull("""{"cues":[]}"""))
    }

    @Test
    fun keepsTranslationBesideJapanese() {
        val encoded = encodeGeneratedCues(
            listOf(SubtitleCue(1200, 3400, "こんにちは", "你好")),
        )
        val cues = parseGeneratedCues(encoded)
        assertEquals("こんにちは", cues[0].text)
        assertEquals("你好", cues[0].translation)
        assertEquals("你好", cues.textAt(1200, chinese = true))
        assertEquals("こんにちは", cues.textAt(1200, chinese = false))
        assertEquals("只今日文", SubtitleCue(1, 2, "只今日文").line(chinese = true))
    }

    @Test
    fun languageMetadataAndSingleLanguageRemovalHelpersWork() {
        val original = listOf(
            SubtitleCue(0, 900, "今日は"),
            SubtitleCue(1000, 1900, "いい天気"),
        )
        val revised = listOf(
            SubtitleCue(0, 1900, "今日はいい天気。", "今天天气不错。", sourceCount = 2),
        )
        val origin = SubtitleOrigin("parakeet-tdt-ctc-0.6b-ja", null)
        val doc = checkNotNull(parseGeneratedSubtitles(encodeGeneratedCues(original, origin, revisedCues = revised)))
        assertNull(doc.language)

        val noChinese = checkNotNull(doc.withoutChinese())
        assertEquals(original, noChinese.cues)
        assertNull(noChinese.revisedCues)
        assertNull(noChinese.language)
        assertTrue(canGenerateTranslation(noChinese.displayCues, noChinese.language))
        assertNull(noChinese.withoutChinese())
        assertNull(noChinese.withoutOriginal())

        val noOriginal = checkNotNull(doc.withoutOriginal())
        assertEquals(listOf(SubtitleCue(0, 1900, "今天天气不错。")), noOriginal.cues)
        assertEquals(origin, noOriginal.origin)
        assertEquals("zh-Hans", noOriginal.language)
        assertEquals(false, canGenerateTranslation(noOriginal.displayCues, noOriginal.language))

        val shortDoc = GeneratedSubtitles(
            listOf(SubtitleCue(0, 1000, "はい", "好")),
            origin,
        )
        val shortChineseOnly = checkNotNull(shortDoc.withoutOriginal())
        assertEquals("zh-Hans", shortChineseOnly.language)
        assertEquals(false, canGenerateTranslation(shortChineseOnly.displayCues, shortChineseOnly.language))
        val roundTripped = checkNotNull(parseGeneratedSubtitles(
            encodeGeneratedCues(shortChineseOnly.cues, shortChineseOnly.origin, language = shortChineseOnly.language),
        ))
        assertEquals("zh-Hans", roundTripped.language)
        assertEquals(shortChineseOnly.cues, roundTripped.cues)
    }

    @Test
    fun parsesLrcContainingTimedBlockArrowInLyrics() {
        val lrc = parseSubtitleDocument(
            """
            [00:01.00]A --> B
            [00:02.50]次の行
            """.trimIndent(),
        )
        assertEquals(listOf(1000L, 2500L), lrc.map { it.startMs })
        assertEquals(listOf("A --> B", "次の行"), lrc.map { it.text })
    }
}
