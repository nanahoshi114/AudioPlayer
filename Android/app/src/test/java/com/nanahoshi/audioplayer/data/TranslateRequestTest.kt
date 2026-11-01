package com.nanahoshi.audioplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TranslateRequestTest {
    @Test
    fun qwenThinkingUsesEffortAndNotABudget() {
        val off = thinkingFields(TranslatePlatform.QWEN, enabled = false, effort = "low")
        assertEquals(false, off["enable_thinking"])
        assertFalse(off.containsKey("reasoning_effort"))
        assertFalse(off.containsKey("thinking_budget"))

        val on = thinkingFields(TranslatePlatform.QWEN, enabled = true, effort = "xhigh")
        assertEquals(true, on["enable_thinking"])
        assertEquals("xhigh", on["reasoning_effort"])
        assertFalse(on.containsKey("thinking_budget"))
        assertFalse(on.containsKey("thinking"))
    }

    @Test
    fun qwenFallsBackToMedium() {
        val fields = thinkingFields(TranslatePlatform.QWEN, enabled = true, effort = "max")
        assertEquals("medium", fields["reasoning_effort"])
    }

    @Test
    fun deepSeekKeepsEffortBesideTheThinkingSwitch() {
        val off = thinkingFields(TranslatePlatform.DEEPSEEK, enabled = false, effort = "high")
        assertEquals(mapOf("type" to "disabled"), off["thinking"])
        assertFalse(off.containsKey("reasoning_effort"))
        assertFalse(off.containsKey("enable_thinking"))
        assertFalse(off.containsKey("thinking_budget"))

        val on = thinkingFields(TranslatePlatform.DEEPSEEK, enabled = true, effort = "max")
        val thinking = on["thinking"] as Map<*, *>
        assertEquals("enabled", thinking["type"])
        assertFalse(thinking.containsKey("reasoning_effort"))
        assertEquals("max", on["reasoning_effort"])
        assertFalse(on.containsKey("thinking_budget"))
    }

    @Test
    fun deepSeekFallsBackToHigh() {
        val fields = thinkingFields(TranslatePlatform.DEEPSEEK, enabled = true, effort = "low")
        assertEquals("high", fields["reasoning_effort"])
    }

    @Test
    fun legacyAddressesPickAPlatformByHost() {
        assertEquals(TranslatePlatform.DEEPSEEK, platformForLegacyBase("https://api.deepseek.com"))
        assertEquals(TranslatePlatform.DEEPSEEK, platformForLegacyBase("https://API.DEEPSEEK.COM/v1"))
        assertEquals(
            TranslatePlatform.QWEN,
            platformForLegacyBase("https://maas.qianwenaiapi.com/compatible-mode/v1"),
        )
        assertEquals(
            TranslatePlatform.QWEN,
            platformForLegacyBase("https://dashscope.aliyuncs.com/compatible-mode/v1"),
        )
        assertNull(platformForLegacyBase("http://127.0.0.1:11434/v1"))
        assertNull(platformForLegacyBase(null))
        assertNull(platformForLegacyBase("不是地址"))
    }
}
