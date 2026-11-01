package com.nanahoshi.audioplayer.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress

fun isLocalNetworkAddress(raw: String?): Boolean {
    val host = raw?.toHttpUrlOrNull()?.host?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    if (!host.contains('.') && !host.contains(':')) return true
    if (host.endsWith(".local", ignoreCase = true)) return true
    if (!looksLikeIp(host)) return false
    val address = try {
        InetAddress.getByName(host)
    } catch (_: Exception) {
        return false
    }
    return address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isAnyLocalAddress
}

private fun looksLikeIp(host: String): Boolean {
    if (host.contains(':')) return true
    val parts = host.split('.')
    if (parts.size != 4) return false
    return parts.all { part ->
        val number = part.toIntOrNull() ?: return false
        number in 0..255 && part == number.toString()
    }
}
