package com.nanahoshi.audioplayer.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

internal const val MAX_SUBTITLE_TEXT_BYTES = 16 * 1024 * 1024

internal fun InputStream.readFullText(maxBytes: Int = MAX_SUBTITLE_TEXT_BYTES): String {
    require(maxBytes > 0)
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (count > maxBytes - total) {
            throw IOException("Text exceeds $maxBytes bytes")
        }
        output.write(buffer, 0, count)
        total += count
    }
    return decodeTextBytes(output.toByteArray(), clipped = false)
}

data class BoundedTextBytes(val bytes: ByteArray, val clipped: Boolean)

fun readBoundedBytes(input: InputStream, limit: Int = 512 * 1024): BoundedTextBytes {
    require(limit in 0 until Int.MAX_VALUE)
    val buffer = ByteArray(limit + 1)
    var size = 0
    while (size < buffer.size) {
        val count = input.read(buffer, size, buffer.size - size)
        if (count < 0) break
        if (count == 0) {
            val byte = input.read()
            if (byte < 0) break
            buffer[size++] = byte.toByte()
        } else {
            size += count
        }
    }
    return BoundedTextBytes(buffer.copyOf(minOf(size, limit)), size > limit)
}

fun decodeTextBytes(bytes: ByteArray, clipped: Boolean = false): String {
    fun startsWith(vararg prefix: Int): Boolean =
        bytes.size >= prefix.size && prefix.indices.all { bytes[it].toInt() and 0xff == prefix[it] }

    fun decode(charset: Charset, skip: Int = 0, strict: Boolean = true): String? {
        val action = if (strict) CodingErrorAction.REPORT else CodingErrorAction.REPLACE
        val decoder = charset.newDecoder().onMalformedInput(action).onUnmappableCharacter(action)
        val input = ByteBuffer.wrap(bytes, skip, bytes.size - skip)
        val output = CharBuffer.allocate((input.remaining() * decoder.maxCharsPerByte()).toInt() + 1)
        // With more bytes expected, the decoder leaves a trailing partial character in input.
        val result = decoder.decode(input, output, !clipped)
        if (result.isError) return null
        if (!clipped) decoder.flush(output)
        output.flip()
        return output.toString()
    }

    when {
        startsWith(0xef, 0xbb, 0xbf) -> return decode(Charsets.UTF_8, 3, strict = false).orEmpty()
        startsWith(0xff, 0xfe) -> return decode(Charsets.UTF_16LE, 2, strict = false).orEmpty()
        startsWith(0xfe, 0xff) -> return decode(Charsets.UTF_16BE, 2, strict = false).orEmpty()
    }
    decode(Charsets.UTF_8)?.let { return it }
    val japanese = decode(shiftJis)
    val chinese = decode(gbk)
    return when {
        japanese != null && (chinese == null || looksLikeJapanese(japanese, chinese)) -> japanese
        chinese != null -> chinese
        else -> decode(Charsets.UTF_8, strict = false).orEmpty()
    }
}

private fun looksLikeJapanese(japanese: String, chinese: String): Boolean {
    val fullWidthKana = japanese.count {
        it in '\u3041'..'\u3096' || it in '\u309d'..'\u309f' ||
            (it in '\u30a1'..'\u30fe' && it != '\u30fb')
    }
    if (fullWidthKana > 0) return true

    // GBK characters can decode as pairs of half-width kana or punctuation.
    if (chinese.any { it in "·，。！？、；：《》“”‘’（）" } ||
        japanese.any { it in '\ue000'..'\uf8ff' }
    ) return false
    return (1 until japanese.length).any { i ->
        japanese[i - 1] in '\uff66'..'\uff9d' && japanese[i] in '\uff66'..'\uff9d'
    }
}

private val shiftJis = if (Charset.isSupported("MS932")) Charset.forName("MS932") else Charset.forName("Shift_JIS")
private val gbk = Charset.forName("GBK")
