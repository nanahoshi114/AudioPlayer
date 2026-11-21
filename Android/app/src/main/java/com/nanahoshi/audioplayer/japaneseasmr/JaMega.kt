package com.nanahoshi.audioplayer.japaneseasmr

import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.R
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.decodeBase64
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class JaMegaShare private constructor(private val handle: String, val crypto: JaMegaCrypto) {
    data class FileDetails(val address: HttpUrl, val name: String, val size: Long)

    fun request(api: HttpUrl): Request {
        val body = """[{"a":"g","p":"$handle","g":1,"ssl":2}]"""
        return Request.Builder().url(api).post(body.toRequestBody("application/json".toMediaType())).build()
    }

    fun resolve(json: String): FileDetails {
        val data = runCatching { (Json.parseToJsonElement(json) as? JsonArray)?.singleOrNull() as? JsonObject }.getOrNull()
            ?: invalidShare()
        val size = (data["s"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: invalidShare()
        val address = (data["g"] as? JsonPrimitive)?.contentOrNull?.toHttpUrlOrNull()?.takeIf {
            it.isHttps && it.port == 443 && it.username.isEmpty() && it.password.isEmpty() &&
                (it.host.endsWith(".mega.co.nz") || it.host.endsWith(".mega.nz"))
        } ?: invalidShare()
        val encrypted = (data["at"] as? JsonPrimitive)?.contentOrNull ?: invalidShare()
        return FileDetails(address, crypto.name(encrypted), size)
    }

    companion object {
        fun parse(url: HttpUrl): JaMegaShare {
            val parts = url.pathSegments
            val fragment = url.fragment.orEmpty()
            if (parts.firstOrNull() == "folder" || fragment.startsWith("F!")) {
                throw JaWorkException(TextRef.Res(R.string.ja_error_mega_folder))
            }
            val tokens = if (parts.size == 2 && parts.first() == "file") listOf(parts[1], fragment)
                else fragment.takeIf { it.startsWith('!') }?.drop(1)?.split('!').orEmpty()
            val (handle, encoded) = tokens.takeIf { it.size == 2 } ?: invalidShare()
            if (!Regex("[A-Za-z0-9_-]{8}").matches(handle) || !Regex("[A-Za-z0-9_-]{43}").matches(encoded)) invalidShare()
            val key = encoded.decodeBase64()?.toByteArray()?.takeIf { it.size == 32 } ?: invalidShare()
            return JaMegaShare(handle, JaMegaCrypto(key))
        }
    }
}

internal class JaMegaCrypto(shareKey: ByteArray) {
    private val key = SecretKeySpec(ByteArray(16) { i -> (shareKey[i].toInt() xor shareKey[i + 16].toInt()).toByte() }, "AES")
    private val nonce = shareKey.copyOfRange(16, 24)
    private val expectedMac = shareKey.copyOfRange(24, 32)
    val checksum = MessageDigest.getInstance("SHA-256").digest(expectedMac).joinToString("") { "%02x".format(it) }

    fun name(attributes: String): String {
        val value = runCatching {
            val ciphertext = requireNotNull(attributes.decodeBase64()).toByteArray()
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(ByteArray(16)))
            val decoded = cipher.doFinal(ciphertext).decodeToString().trimEnd('\u0000')
            require(decoded.startsWith("MEGA{"))
            val json = Json.parseToJsonElement(decoded.substring(4)) as JsonObject
            (json["n"] as? JsonPrimitive)?.contentOrNull
        }.getOrNull()
        return value?.takeIf(String::isNotBlank) ?: invalidShare()
    }

    fun cipherAt(offset: Long): Cipher {
        val iv = ByteBuffer.allocate(16).put(nonce).putLong(offset / 16).array()
        return Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
            val skip = (offset % 16).toInt()
            if (skip != 0) update(ByteArray(skip))
        }
    }

    // MEGA uses 128 KiB increments for the first eight chunks, then 1 MiB chunks.
    suspend fun verify(path: File): Boolean {
        val context = currentCoroutineContext()
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        val aggregateCipher = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        var aggregate = ByteArray(16)
        val chunks = ByteArray(1024 * 1024)
        val iv = IvParameterSpec(nonce + nonce)
        var offset = 0L
        var chunk = 1
        val size = path.length()
        java.io.DataInputStream(path.inputStream().buffered()).use { input ->
            while (offset < size) {
                context.ensureActive()
                val count = minOf(chunk * 128L * 1024, size - offset).toInt()
                val padded = (count + 15) and 15.inv()
                input.readFully(chunks, 0, count)
                if (padded > count) chunks.fill(0, count, padded)
                cipher.init(Cipher.ENCRYPT_MODE, key, iv)
                val encrypted = cipher.doFinal(chunks, 0, padded)
                for (i in aggregate.indices) aggregate[i] =
                    (aggregate[i].toInt() xor encrypted[encrypted.size - 16 + i].toInt()).toByte()
                aggregate = aggregateCipher.doFinal(aggregate)
                offset += count
                chunk = minOf(chunk + 1, 8)
            }
        }
        val mac = ByteArray(8) { i ->
            val start = if (i < 4) i else i + 4
            (aggregate[start].toInt() xor aggregate[start + 4].toInt()).toByte()
        }
        return MessageDigest.isEqual(expectedMac, mac)
    }
}

private fun invalidShare(): Nothing = throw JaWorkException(TextRef.Res(R.string.ja_error_mega_share_expired))
