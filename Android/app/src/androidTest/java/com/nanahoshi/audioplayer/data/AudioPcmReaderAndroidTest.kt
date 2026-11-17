package com.nanahoshi.audioplayer.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class AudioPcmReaderAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun constructorFailureReleasesResourcesAndIsRepeatable() {
        val notAudio = File.createTempFile("not-audio", ".txt", context.cacheDir).apply { writeText("这不是音频") }
        try {
            // 构造失败必须自清理原生句柄：反复失败不累积泄漏，异常统一为 WhisperException。
            repeat(5) {
                val failure = runCatching { AudioPcmReader(notAudio) }.exceptionOrNull()
                assertEquals(WhisperException::class.java, failure?.javaClass)
            }
        } finally {
            notAudio.delete()
        }
    }

    @Test fun wavDecodesToPcmAndCloseIsIdempotent() {
        val wav = File.createTempFile("tone", ".wav", context.cacheDir)
        try {
            wav.writeBytes(wavBytes(sampleCount = 16_000, sampleRate = 16_000))
            val reader = AudioPcmReader(wav)
            val chunk = reader.readChunk(16_000)
            assertNotNull(chunk)
            assertTrue(chunk!!.isNotEmpty())
            reader.close()
            reader.close()
        } finally {
            wav.delete()
        }
    }

    /** 最小 16-bit PCM 单声道 WAV：440Hz 正弦。 */
    private fun wavBytes(sampleCount: Int, sampleRate: Int): ByteArray {
        val dataSize = sampleCount * 2
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(1)
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * 2)
        buffer.putShort(2)
        buffer.putShort(16)
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize)
        for (index in 0 until sampleCount) {
            buffer.putShort((8000 * sin(2 * Math.PI * 440 * index / sampleRate)).toInt().toShort())
        }
        return buffer.array()
    }
}
