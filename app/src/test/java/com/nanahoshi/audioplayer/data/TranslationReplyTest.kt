package com.nanahoshi.audioplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslationReplyTest {
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
}
