package com.nanahoshi.audioplayer

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 供 OkHttp 拦截器测试使用的固定响应。 */
data class FakeHttpReply(
    val code: Int = 200,
    val body: ByteArray = ByteArray(0),
    val headers: Map<String, String> = emptyMap(),
    val contentType: String = "application/octet-stream",
)

fun fakeHttpReply(
    body: ByteArray,
    code: Int = 200,
    contentType: String = "application/octet-stream",
    headers: Map<String, String> = emptyMap(),
) = FakeHttpReply(code, body, headers, contentType)

fun textHttpReply(
    body: String,
    code: Int = 200,
    contentType: String = "application/json; charset=utf-8",
    headers: Map<String, String> = emptyMap(),
) = fakeHttpReply(body.toByteArray(), code, contentType, headers)

/** 构造始终由 [handler] 应答的客户端，便于测试分页、重试与重定向。 */
fun fakeHttpClient(handler: (Request) -> FakeHttpReply): OkHttpClient =
    OkHttpClient.Builder().addInterceptor(fakeHttpInterceptor(handler)).build()

fun fakeHttpInterceptor(handler: (Request) -> FakeHttpReply): Interceptor = Interceptor { chain ->
    val request = chain.request()
    val reply = handler(request)
    Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(reply.code)
        .message("fixture")
        .body(reply.body.toResponseBody(reply.contentType.toMediaType()))
        .apply { reply.headers.forEach { (name, value) -> header(name, value) } }
        .build()
}

fun zipBytes(vararg entries: Pair<String, ByteArray>): ByteArray =
    ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }.toByteArray()

fun zipText(vararg entries: Pair<String, String>): ByteArray =
    zipBytes(*entries.map { (name, text) -> name to text.toByteArray() }.toTypedArray())

/** 生成指定时长的有效单/双声道 16-bit PCM WAV。 */
fun wavBytes(
    durationMs: Long = 1_000,
    sampleRate: Int = 16_000,
    channels: Int = 1,
    bitsPerSample: Int = 16,
    toneHz: Double = 440.0,
    amplitude: Short = 8_000,
): ByteArray {
    val frames = (sampleRate * durationMs / 1_000).toInt().coerceAtLeast(1)
    val blockAlign = channels * bitsPerSample / 8
    val dataSize = frames * blockAlign
    val samples = ShortArray(frames * channels) { index ->
        val frame = index / channels
        (kotlin.math.sin(2.0 * Math.PI * toneHz * frame / sampleRate) * amplitude).toInt().toShort()
    }
    val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("RIFF".toByteArray())
    buffer.putInt(36 + dataSize)
    buffer.put("WAVE".toByteArray())
    buffer.put("fmt ".toByteArray())
    buffer.putInt(16)
    buffer.putShort(1.toShort())
    buffer.putShort(channels.toShort())
    buffer.putInt(sampleRate)
    buffer.putInt(sampleRate * blockAlign)
    buffer.putShort(blockAlign.toShort())
    buffer.putShort(bitsPerSample.toShort())
    buffer.put("data".toByteArray())
    buffer.putInt(dataSize)
    for (sample in samples) buffer.putShort(sample)
    return buffer.array()
}