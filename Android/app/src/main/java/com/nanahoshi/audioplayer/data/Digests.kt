package com.nanahoshi.audioplayer.data

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** 全库共用的摘要与十六进制格式化，避免各文件各写一份 `MessageDigest` 样板。 */
internal const val SHA256 = "SHA-256"
internal const val SHA1 = "SHA-1"
internal const val MD5 = "MD5"

private const val HEX_DIGITS = "0123456789abcdef"

fun ByteArray.toHex(): String = buildString(size * 2) {
    for (byte in this@toHex) {
        val value = byte.toInt() and 0xff
        append(HEX_DIGITS[value ushr 4])
        append(HEX_DIGITS[value and 0x0f])
    }
}

fun digestHex(algorithm: String, bytes: ByteArray): String =
    MessageDigest.getInstance(algorithm).digest(bytes).toHex()

fun sha256Hex(bytes: ByteArray): String = digestHex(SHA256, bytes)

fun sha256Hex(value: String): String = sha256Hex(value.toByteArray(Charsets.UTF_8))

fun sha1Hex(value: String): String = digestHex(SHA1, value.toByteArray(Charsets.UTF_8))

fun md5Hex(value: String): String = digestHex(MD5, value.toByteArray(Charsets.UTF_8))

fun md5Hex(bytes: ByteArray): String = digestHex(MD5, bytes)

/** 读取流并计算 SHA-256；调用方负责在长任务里自行检查取消。 */
fun sha256Hex(input: InputStream, bufferSize: Int = DEFAULT_DIGEST_BUFFER): String {
    val digest = MessageDigest.getInstance(SHA256)
    val buffer = ByteArray(bufferSize)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    return digest.digest().toHex()
}

fun sha256Hex(file: File): String = file.inputStream().use { sha256Hex(it) }

private const val DEFAULT_DIGEST_BUFFER = 64 * 1024