package com.nanahoshi.audioplayer.japaneseasmr

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException

internal object JaNetworkPolicy {
    private val siteHosts = setOf("japaneseasmr.com", "www.japaneseasmr.com")
    private val redirectCodes = setOf(301, 302, 303, 307, 308)

    fun install(builder: OkHttpClient.Builder, siteOnly: Boolean, followMediaRedirects: Boolean): OkHttpClient.Builder =
        builder.followRedirects(false).followSslRedirects(false).proxy(Proxy.NO_PROXY)
            .dns(publicDns(Dns.SYSTEM))
            .addInterceptor(Interceptor { chain -> request(chain, siteOnly, followMediaRedirects) })

    internal fun publicDns(upstream: Dns): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val answers = upstream.lookup(hostname)
            if (answers.isEmpty()) throw UnknownHostException()
            return answers.filter { isPublic(it.address) }.ifEmpty { throw unsafe() }
        }
    }

    private fun request(chain: Interceptor.Chain, siteOnly: Boolean, follow: Boolean): Response {
        var request = chain.request()
        repeat(8) {
            checkUrl(request.url, siteOnly)
            val response = chain.proceed(request)
            val location = response.header("Location")
            if (!follow || response.code !in redirectCodes || location == null) return response

            response.close()
            val target = request.url.resolve(location) ?: throw unsafe()
            request = request.newBuilder().url(target).apply {
                if (target.host != request.url.host) {
                    removeHeader("Authorization")
                    removeHeader("Proxy-Authorization")
                    removeHeader("Cookie")
                }
            }.build()
        }
        throw JapaneseAsmrException(JaFailure.UNAVAILABLE)
    }

    internal fun checkUrl(url: HttpUrl, siteOnly: Boolean = false) {
        if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty()) throw unsafe()
        if (siteOnly && url.host !in siteHosts) throw unsafe()

        // OkHttp can bypass DNS for numeric hosts, so check those before handing off the request.
        val host = url.host
        val literal = when {
            ':' in host -> try {
                InetAddress.getByName(host).address
            } catch (_: UnknownHostException) {
                throw unsafe()
            }
            host.all { it == '.' || it in '0'..'9' } -> {
                val parts = host.split('.').map { it.toIntOrNull()?.takeIf { part -> part in 0..255 } ?: throw unsafe() }
                if (parts.size != 4 || parts.joinToString(".") != host) throw unsafe()
                parts.map { it.toByte() }.toByteArray()
            }
            else -> null
        }
        if (literal != null && !isPublic(literal)) throw unsafe()
    }

    private fun unsafe() = JapaneseAsmrException(JaFailure.UNSAFE)

    private fun isPublic(address: ByteArray): Boolean {
        if (address.size == 4) return publicV4(address, 0)
        if (address.size != 16) return false
        val a = address[0].toInt() and 255
        val b = address[1].toInt() and 255
        val c = address[2].toInt() and 255
        val d = address[3].toInt() and 255
        if (address.take(10).all { it == 0.toByte() } && address[10] == (-1).toByte() && address[11] == (-1).toByte()) {
            return publicV4(address, 12)
        }
        // The well-known IPv6 translation prefix is usable only for public IPv4 targets.
        if (a == 0 && b == 0x64 && c == 0xff && d == 0x9b && address.slice(4 until 12).all { it == 0.toByte() }) {
            return publicV4(address, 12)
        }
        return a in 0x20..0x3f &&
            !(a == 0x20 && b == 1 && (c <= 1 || c == 0x0d && d == 0xb8)) &&
            !(a == 0x20 && b == 2) &&
            !(a == 0x3f && b == 0xff && c < 0x10)
    }

    private fun publicV4(address: ByteArray, offset: Int): Boolean {
        val a = address[offset].toInt() and 255
        val b = address[offset + 1].toInt() and 255
        val c = address[offset + 2].toInt() and 255
        return a in 1..223 && a != 10 && a != 127 &&
            !(a == 100 && b in 64..127) &&
            !(a == 169 && b == 254) &&
            !(a == 172 && b in 16..31) &&
            !(a == 192 && (b == 168 || b == 0 && c in setOf(0, 2) || b == 88 && c == 99)) &&
            !(a == 198 && (b in 18..19 || b == 51 && c == 100)) &&
            !(a == 203 && b == 0 && c == 113)
    }
}
