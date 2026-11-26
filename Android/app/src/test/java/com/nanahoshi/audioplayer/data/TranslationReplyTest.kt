package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.playback.SubtitleCue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationReplyTest {
    @Test
    fun revisedReplyMergesConsecutiveSourcesAndUsesTheirTimes() {
        val source = listOf(
            SubtitleCue(100, 1000, "今日は"), SubtitleCue(1600, 2200, "いい天気"),
            SubtitleCue(3000, 18_000, "そうですね"),
        )
        val result = parseRevisedTranslation(
            """{"captions":[{"source":[1,2],"japanese":"今日はいい天気。","chinese":"今天天气不错。","startMs":0,"endMs":1},
            {"source":[3],"japanese":"そうですね。","chinese":"是啊。"}]}""", source,
        )
        assertEquals(listOf(
            SubtitleCue(100, 2200, "今日はいい天気。", "今天天气不错。", sourceCount = 2),
            SubtitleCue(3000, 18_000, "そうですね。", "是啊。"),
        ), result.cues)
        assertTrue(result.violations.isEmpty())
    }

    @Test
    fun revisedReplyRejectsMissingRepeatedReorderedAndOutOfBatchNumbers() {
        val source = listOf(SubtitleCue(0, 800, "はい"), SubtitleCue(900, 1700, "そうです"))
        for (ids in listOf("1", "1,1", "2,1", "1,3", "\"1\",2", "1,2,3")) {
            assertNull(parseRevisedTranslation(revisedCaption(ids), source).cues)
        }
        assertNull(parseRevisedTranslation(
            """{"captions":[{"source":[1],"japanese":"はい","chinese":"好"},
                {"source":[1,2],"japanese":"はい","chinese":"好"}]}""", source,
        ).cues)
        assertNull(parseRevisedTranslation(revisedCaption("1,2", japanese = " "), source).cues)
    }

    @Test
    fun revisedReplyCollectsSoftViolationsInsteadOfRejecting() {
        val source = listOf(SubtitleCue(0, 1000, "はい"), SubtitleCue(1600, 12_000, "そうです"))
        val ok = parseRevisedTranslation(revisedCaption("1,2", "あ".repeat(80)), source)
        assertEquals(1, ok.cues?.size)
        assertTrue(ok.violations.isEmpty())
        // 跨度超限：仍交付，但记录违规
        val span = parseRevisedTranslation(revisedCaption("1,2"), listOf(source[0], source[1].copy(endMs = 12_001)))
        assertEquals(1, span.cues?.size)
        assertTrue(span.violations.any { it.contains("跨度") })
        // 字数超限
        val jp = parseRevisedTranslation(revisedCaption("1,2", "あ".repeat(81)), source)
        assertTrue(jp.violations.any { it.contains("日文") })
        val zh = parseRevisedTranslation(revisedCaption("1,2", chinese = "好".repeat(81)), source)
        assertTrue(zh.violations.any { it.contains("中文") })
        // 条数超限
        val count = parseRevisedTranslation(revisedCaption("1,2,3,4,5"), List(5) { source[0] })
        assertEquals(1, count.cues?.size)
        assertTrue(count.violations.any { it.contains("超过最多") })
        // 单条不限字数、不限跨度
        val single = parseRevisedTranslation(revisedCaption("1", "あ".repeat(81)), listOf(source[0]))
        assertEquals(1, single.cues?.size)
        assertTrue(single.violations.isEmpty())
    }

    @Test
    fun revisedReplyFlagsLockedCueMerged() {
        val source = listOf(SubtitleCue(0, 20_000, "はい"), SubtitleCue(20_500, 21_000, "そうです"))
        val locked = lockedCues(source, MergeLimits.DEFAULT)
        assertTrue(locked[0])
        assertFalse(locked[1])
        val result = parseRevisedTranslation(revisedCaption("1,2"), source, MergeLimits.DEFAULT, locked)
        assertEquals(1, result.cues?.size)
        assertTrue(result.violations.any { it.contains("禁止合并") })
    }

    @Test
    fun qwenFileLimitsAreTwoLinesAndEighteenSeconds() {
        val limits = MergeLimits.forModel("qwen-audio-3.1-asr")
        assertEquals(2, limits.maxLines)
        assertEquals(18_000L, limits.maxSpanMs)
        assertEquals(MergeLimits.DEFAULT, MergeLimits.forModel("parakeet-tdt-ctc-0.6b-ja"))
        assertEquals(MergeLimits.DEFAULT, MergeLimits.forModel(null))
        val source = List(3) { SubtitleCue(it * 1000L, it * 1000L + 500, "はい") }
        assertTrue(parseRevisedTranslation(revisedCaption("1,2,3"), source, limits).violations.any { it.contains("超过最多 2") })
        val wide = listOf(SubtitleCue(0, 1000, "はい"), SubtitleCue(2000, 15_000, "そうです"))
        assertTrue(parseRevisedTranslation(revisedCaption("1,2"), wide, limits).violations.isEmpty())
    }

    @Test
    fun revisedReplyAcceptsSplittingWithinAMergeGroup() {
        val source = listOf(SubtitleCue(0, 1000, "はい"), SubtitleCue(1600, 2200, "そうです"))
        val result = parseRevisedTranslation(
            """{"captions":[{"source":[1],"japanese":"はい。","chinese":"好。"},
                {"source":[2],"japanese":"そうです。","chinese":"是的。"}]}""", source,
        )
        assertEquals(listOf(
            SubtitleCue(0, 1000, "はい。", "好。"),
            SubtitleCue(1600, 2200, "そうです。", "是的。"),
        ), result.cues)
    }

    @Test
    fun revisedReplyAcceptsLegalMergeAcrossGroupBoundary() {
        // 贪心分组 [1..4],[5,6]；[4,5,6] 跨组但满足条数/跨度/字数，须接受（真机第 10 批案例）。
        val source = List(6) { SubtitleCue(it * 200L, it * 200L + 100, "はい") }
        val result = parseRevisedTranslation(
            """{"captions":[{"source":[1,2,3],"japanese":"はい","chinese":"好"},
                {"source":[4,5,6],"japanese":"はい","chinese":"好"}]}""", source,
        )
        assertEquals(listOf(
            SubtitleCue(0, 500, "はい", "好", sourceCount = 3),
            SubtitleCue(600, 1100, "はい", "好", sourceCount = 3),
        ), result.cues)
        assertTrue(result.violations.isEmpty())
    }

    @Test
    fun revisedReplyExtractsJsonFromProseAndFences() {
        val source = listOf(SubtitleCue(0, 800, "はい"))
        val json = """{"captions":[{"source":[1],"japanese":"はい。","chinese":"好。"}]}"""
        val expected = listOf(SubtitleCue(0, 800, "はい。", "好。"))
        for (raw in listOf(
            json,
            "```json\n$json\n```",
            "好的，结果如下：\n```json\n$json\n```\n以上。",
            "$json\n补充说明",
        )) {
            assertEquals(expected, parseRevisedTranslation(raw, source).cues)
        }
    }

    @Test
    fun revisedReplyHandlesBracesAndEscapesInsideStrings() {
        val source = listOf(SubtitleCue(0, 800, "はい"))
        val raw = """{"captions":[{"source":[1],"japanese":"「{テスト}」","chinese":"引号\"和{花括号}"}]}"""
        assertEquals(listOf(SubtitleCue(0, 800, "「{テスト}」", "引号\"和{花括号}")), parseRevisedTranslation(raw, source).cues)
    }

    @Test
    fun revisedReplyRejectsTruncatedOrMissingJson() {
        val source = listOf(SubtitleCue(0, 800, "はい"))
        assertNull(parseRevisedTranslation("{\"captions\":[{\"source\":[1],\"japanese\":\"はい\",\"chinese\":\"好", source).cues)
        assertNull(parseRevisedTranslation("抱歉，我无法翻译。", source).cues)
    }

    @Test
    fun failureDetailScrubsUrlsAndClipsRaw() {
        val fallback = TextRef.Res(R.string.translate_error_format_retry)
        val network = TextRef.Res(R.string.translate_error_connection)
        assertEquals(network, translationFailureDetail(network, "raw", "hidden"))
        assertEquals(fallback, translationFailureDetail(null, "  ", "hidden"))

        val detail = translationFailureDetail(null, "看 https://example.com/a?b=1 谢谢", "hidden")
        assertEquals(TextRef.Joined(listOf(
            fallback, TextRef.Res(R.string.translate_error_model_response, listOf(TextRef.Raw("看 hidden 谢谢"))),
        ), separator = "\n"), detail)
        val long = translationFailureDetail(null, "x".repeat(2_000), "hidden") as TextRef.Joined
        assertEquals(listOf(TextRef.Raw("x".repeat(1_500))), (long.parts.last() as TextRef.Res).args)
    }

    @Test
    fun completeLinesAreDeliveredEvenIfServiceReportsTruncation() {
        val truncation = TextRef.Res(R.string.translate_error_truncated)
        val complete = parseLineCompletion("1|译文", truncation, 1)
        assertEquals(listOf("译文"), complete.lines)
        assertNull(complete.error)
        val incomplete = parseLineCompletion("1|译文", truncation, 2)
        assertNull(incomplete.lines)
        assertEquals(truncation, incomplete.error)
        assertEquals("1|译文", incomplete.raw)
        assertEquals(TextRef.Res(R.string.translate_detail_line_format), incomplete.diagnostic)
        assertNull(complete.diagnostic)
    }

    @Test
    fun failureDetailsIncludeStructuralReasonsOrHttpStatus() {
        val source = listOf(SubtitleCue(0, 800, "はい"))
        val parsed = parseRevisedTranslation(
            """{"captions":[{"source":[1],"japanese":"はい"}]}""", source,
        )
        val structure = TextRef.Res(R.string.translate_detail_missing_chinese)
        assertEquals(structure, parsed.structuralError)
        val format = TextRef.Res(R.string.translate_error_format_retry)
        assertEquals(
            TextRef.Joined(listOf(format, structure), separator = "\n"),
            translationFailureDetail(format, null, "hidden", parsed.structuralError),
        )

        val server = TextRef.Res(R.string.translate_error_server_unavailable)
        val status = TextRef.Res(R.string.translate_detail_http_status, listOf(503))
        val reply = parseLineCompletion(null, server, 1, status)
        assertNull(reply.raw)
        assertEquals(
            TextRef.Joined(listOf(server, status), separator = "\n"),
            translationFailureDetail(reply.error, reply.raw, "hidden", reply.diagnostic),
        )
    }

    @Test
    fun correctionInputCarriesIdGroupAndEscapedTextOnly() {
        val cues = listOf(
            SubtitleCue(0, 1000, "はい\"テスト\n次"),
            SubtitleCue(1600, 2200, "そうです"),
            SubtitleCue(5000, 6000, "別文"),
        )
        val groups = japaneseMergeGroups(cues)
        val array = Json.parseToJsonElement(japaneseCorrectionInput(cues, groups)).jsonArray
        assertEquals(3, array.size)
        array.forEachIndexed { index, element ->
            val obj = element.jsonObject
            assertEquals(index + 1, obj["id"]!!.jsonPrimitive.int)
            assertEquals(groups.indexOfFirst { index in it } + 1, obj["group"]!!.jsonPrimitive.int)
            assertEquals(cues[index].text, obj["text"]!!.jsonPrimitive.content)
            assertFalse("startMs" in obj)
            assertFalse("endMs" in obj)
            assertFalse("lock" in obj)
        }
    }

    @Test
    fun correctionInputMarksLockedCues() {
        val cues = listOf(SubtitleCue(0, 20_000, "長い"), SubtitleCue(20_500, 21_000, "短い"))
        val locked = lockedCues(cues, MergeLimits.DEFAULT)
        val groups = japaneseMergeGroups(cues, MergeLimits.DEFAULT, locked)
        val array = Json.parseToJsonElement(japaneseCorrectionInput(cues, groups, locked)).jsonArray
        assertTrue(array[0].jsonObject["lock"]!!.jsonPrimitive.boolean)
        assertFalse("lock" in array[1].jsonObject)
    }

    @Test
    fun correctionRulesContainNoMillisecondArithmetic() {
        assertFalse(japaneseCorrectionRules().contains("毫秒"))
        assertTrue(japaneseCorrectionRules(MergeLimits.forModel("qwen-audio-3.1-asr")).contains("最多 2 个"))
    }

    private fun revisedCaption(ids: String, japanese: String = "はい。", chinese: String = "是的。"): String =
        """{"captions":[{"source":[$ids],"japanese":"$japanese","chinese":"$chinese"}]}"""

    @Test
    fun keepsNumberedLines() {
        val lines = parseTranslationReply(
            """
            1|你好
            2|再见
            """.trimIndent(),
            2,
        )
        assertEquals(listOf("你好", "再见"), lines)
        assertEquals(listOf("甲"), parseTranslationReply("1｜甲", 1))
    }

    @Test
    fun rejectsThreeColumns() {
        assertNull(parseTranslationReply("1|こんにちは|你好", 1))
    }

    @Test
    fun rejectsAMissingNumber() {
        assertNull(
            parseTranslationReply(
                """
                ```text
                1|只有第一句
                ```
                """.trimIndent(),
                2,
            ),
        )
    }

    @Test
    fun joinMergedOriginalPreservesJapaneseAndSeparatesAsciiWords() {
        val mixed = listOf(
            SubtitleCue(0, 500, "  Hello  "),
            SubtitleCue(500, 1000, "world "),
            SubtitleCue(1000, 1500, "今日は"),
            SubtitleCue(1500, 2000, "いい天気"),
        )
        assertEquals("Hello world今日はいい天気", joinMergedOriginal(mixed))
    }

    @Test
    fun proofreadOnlyModeRejectsMergeAndOmitGroupingFromInput() {
        val options = JapaneseTranslateOptions(proofread = true, merge = false)
        assertTrue(options.usesStructuredProtocol)
        val source = listOf(SubtitleCue(0, 800, "今日は"), SubtitleCue(900, 1600, "晴れ"))

        val merged = parseRevisedTranslation(
            """{"captions":[{"source":[1,2],"japanese":"今日は晴れ。","chinese":"今天晴天。"}]}""",
            source,
            options = options,
        )
        assertNull(merged.cues)
        assertEquals(TextRef.Res(R.string.translate_detail_unexpected_merge), merged.structuralError)

        val single = parseRevisedTranslation(
            """{"captions":[{"source":[1],"japanese":"今日は。","chinese":"今天。"},
                {"source":[2],"japanese":"晴れ。","chinese":"晴天。"}]}""",
            source,
            options = options,
        )
        assertEquals(listOf(
            SubtitleCue(0, 800, "今日は。", "今天。"),
            SubtitleCue(900, 1600, "晴れ。", "晴天。"),
        ), single.cues)
        assertTrue(single.violations.isEmpty())

        val input = Json.parseToJsonElement(
            japaneseCorrectionInput(source, emptyList(), includeGrouping = false),
        ).jsonArray
        assertFalse("group" in input[0].jsonObject)
        assertFalse("lock" in input[0].jsonObject)
        assertTrue(japaneseCorrectionRules(options = options).contains("禁止合并"))
    }

    @Test
    fun mergeOnlyModeKeepsOriginalTextAndCollectsSoftViolations() {
        val options = JapaneseTranslateOptions(proofread = false, merge = true)
        assertTrue(options.usesStructuredProtocol)
        val source = listOf(
            SubtitleCue(0, 1000, "今日は"),
            SubtitleCue(1200, 2000, "いい天気"),
            SubtitleCue(3000, 4000, "ですね"),
        )
        val parsed = parseRevisedTranslation(
            """{"captions":[{"source":[1,2],"japanese":"MODEL_REWRITE_IGNORED","chinese":"今天天气不错"},
                {"source":[3],"chinese":"是呢"}]}""",
            source,
            options = options,
        )
        assertEquals(listOf(
            SubtitleCue(0, 2000, "今日はいい天気", "今天天气不错", sourceCount = 2),
            SubtitleCue(3000, 4000, "ですね", "是呢"),
        ), parsed.cues)
        assertTrue(parsed.violations.isEmpty())

        val tooLong = parseRevisedTranslation(
            """{"captions":[{"source":[1,2],"chinese":"${"好".repeat(81)}"}]}""",
            source.take(2),
            options = options,
        )
        assertEquals(1, tooLong.cues?.size)
        assertTrue(tooLong.violations.any { it.contains("中文") })
        assertTrue(japaneseCorrectionRules(options = options).contains("不要改写日文原文"))
    }
}
