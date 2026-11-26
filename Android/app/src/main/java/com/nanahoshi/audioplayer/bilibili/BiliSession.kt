package com.nanahoshi.audioplayer.bilibili

import com.nanahoshi.audioplayer.data.CookieHeaders
import com.nanahoshi.audioplayer.data.toHex
import okio.ByteString.Companion.decodeBase64
import org.jsoup.Jsoup
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
        "$name=${CookieHeaders.sanitize(stored)}"
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
        val name = URLDecoder.decode(part.substringBefore('='), Charsets.UTF_8.name())
        if (name !in BILI_SESSION_COOKIE_NAMES) continue
        val decoded = URLDecoder.decode(part.substringAfter('='), Charsets.UTF_8.name())
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
        for ((name, value) in CookieHeaders.parse(header)) {
            if (name !in BILI_SESSION_COOKIE_NAMES) continue
            if (value.isEmpty()) continue
            if (overwrite || name !in pairs) pairs[name] = value
        }
    }
    absorb(fallback, overwrite = false)
    absorb(primary, overwrite = true)
    return sessionCookies(pairs)
}

internal fun cookieField(header: String, name: String): String? = CookieHeaders.field(header, name)

internal fun refreshCsrf(html: String): String? =
    Jsoup.parse(html).getElementById("1-name")
        ?.text()
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
    return encrypted.toHex()
}

private val correspondKey by lazy {
    val encoded = requireNotNull(
        (
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDLgd2OAkcGVtoE3ThUREbio0Eg" +
                "Uc/prcajMKXvkCKFCWhJYJcLkcM2DKKcSeFpD/j6Boy538YXnR6VhcuUJOhH2x71" +
                "nzPjfdTcqMz7djHum0qSZA0AyCBDABUqCrfNgCiJ00Ra7GmRj+YCK1NJEuewlb40" +
                "JNrRuoEUXpabUzGB8QIDAQAB"
            ).decodeBase64(),
    ) { "invalid base64 key" }.toByteArray()
    KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(encoded))
}
