package com.nanahoshi.audioplayer.bilibili

import java.net.URLDecoder
import java.security.KeyFactory
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

internal val BILI_SESSION_COOKIE_NAMES = listOf(
    "SESSDATA",
    "bili_jct",
    "DedeUserID",
    "DedeUserID__ckMd5",
    "sid",
)

internal fun sessionCookies(pairs: Map<String, String>): String {
    val parts = BILI_SESSION_COOKIE_NAMES.mapNotNull { name ->
        val value = pairs[name]?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val stored = if (name == "SESSDATA") value.replace(",", "%2C") else value
        "$name=$stored"
    }
    if (parts.none { it.startsWith("SESSDATA=") }) return ""
    return parts.joinToString("; ")
}

internal fun sessionCookiesFromUrl(url: String): String {
    val query = url.substringAfter('?', "").substringBefore('#')
    if (query.isBlank()) return ""
    val pairs = linkedMapOf<String, String>()
    for (part in query.split('&')) {
        if (!part.contains('=')) continue
        val name = URLDecoder.decode(part.substringBefore('='), "UTF-8")
        if (name !in BILI_SESSION_COOKIE_NAMES) continue
        val decoded = URLDecoder.decode(part.substringAfter('='), "UTF-8")
        if (decoded.isNotEmpty()) pairs[name] = decoded
    }
    return sessionCookies(pairs)
}

internal fun sessionCookiesFromSetCookie(headers: List<String>): String {
    val pairs = linkedMapOf<String, String>()
    for (header in headers) {
        val first = header.substringBefore(';').trim()
        if (!first.contains('=')) continue
        val name = first.substringBefore('=').trim()
        if (name !in BILI_SESSION_COOKIE_NAMES) continue
        var value = first.substringAfter('=').trim()
        if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length - 1)
        }
        if (value.isNotEmpty()) pairs[name] = value
    }
    return sessionCookies(pairs)
}

internal fun combineSessionCookies(primary: String, fallback: String): String {
    val pairs = linkedMapOf<String, String>()
    fun absorb(header: String, overwrite: Boolean) {
        for (part in header.split(';')) {
            val trimmed = part.trim()
            if (!trimmed.contains('=')) continue
            val name = trimmed.substringBefore('=').trim()
            if (name !in BILI_SESSION_COOKIE_NAMES) continue
            val value = trimmed.substringAfter('=')
            if (value.isEmpty()) continue
            if (overwrite || name !in pairs) pairs[name] = value
        }
    }
    absorb(fallback, overwrite = false)
    absorb(primary, overwrite = true)
    return sessionCookies(pairs)
}

internal fun cookieField(header: String, name: String): String? =
    header.split(';')
        .map { it.trim() }
        .firstOrNull { it.substringBefore('=').trim() == name }
        ?.substringAfter('=', "")
        ?.takeIf { it.isNotEmpty() }

internal fun refreshCsrf(html: String): String? =
    Regex("""id\s*=\s*["']1-name["']\s*>([^<]+)<""")
        .find(html)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

internal fun correspondPath(timestampMs: Long): String {
    val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
    cipher.init(
        Cipher.ENCRYPT_MODE,
        correspondKey,
        OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT),
    )
    val encrypted = cipher.doFinal("refresh_$timestampMs".toByteArray(Charsets.UTF_8))
    return encrypted.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private val correspondKey by lazy {
    val encoded = decodeBase64(
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDLgd2OAkcGVtoE3ThUREbio0Eg" +
            "Uc/prcajMKXvkCKFCWhJYJcLkcM2DKKcSeFpD/j6Boy538YXnR6VhcuUJOhH2x71" +
            "nzPjfdTcqMz7djHum0qSZA0AyCBDABUqCrfNgCiJ00Ra7GmRj+YCK1NJEuewlb40" +
            "JNrRuoEUXpabUzGB8QIDAQAB",
    )
    KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(encoded))
}

private fun decodeBase64(value: String): ByteArray {
    val table = IntArray(256) { -1 }
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    alphabet.forEachIndexed { index, char -> table[char.code] = index }
    val out = ArrayList<Byte>(value.length)
    var buffer = 0
    var bits = 0
    for (char in value) {
        if (char.isWhitespace() || char == '=') continue
        val six = table[char.code]
        if (six < 0) continue
        buffer = (buffer shl 6) or six
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out += ((buffer shr bits) and 0xff).toByte()
        }
    }
    return out.toByteArray()
}
