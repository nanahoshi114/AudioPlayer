package com.nanahoshi.audioplayer.japaneseasmr

import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

class JaNetworkPolicyTest {
    @Test fun checksLiteralAddressesAndSiteScope() {
        for (url in listOf(
            "https://127.0.0.1", "https://10.0.0.2", "https://100.64.0.1", "https://169.254.1.1",
            "https://192.0.2.1", "https://[::1]", "https://[::ffff:192.168.0.1]",
            "https://[64:ff9b::a00:1]", "https://[fd00::1]", "https://[ff02::1]",
            "http://audio.example", "https://audio.example:444", "https://user@audio.example",
        )) assertEquals(url, JaFailure.UNSAFE, runCatching {
            JaNetworkPolicy.checkUrl(url.toHttpUrl())
        }.exceptionOrNull().let { (it as? JapaneseAsmrException)?.failure })
        JaNetworkPolicy.checkUrl("https://www.japaneseasmr.com".toHttpUrl(), siteOnly = true)
        JaNetworkPolicy.checkUrl("https://[2606:4700:4700::1111]".toHttpUrl())
        assertThrows(JapaneseAsmrException::class.java) {
            JaNetworkPolicy.checkUrl("https://audio.example".toHttpUrl(), siteOnly = true)
        }
    }

    @Test fun resolverReturnsOnlyPublicAddresses() {
        val allowed = InetAddress.getByName("93.184.216.34")
        val blocked = InetAddress.getByName("192.168.1.1")
        val upstream = object : Dns {
            override fun lookup(hostname: String) = if (hostname == "mixed.example") listOf(blocked, allowed) else listOf(blocked)
        }
        val resolver = JaNetworkPolicy.publicDns(upstream)
        assertEquals(listOf(allowed), resolver.lookup("mixed.example"))
        assertEquals(JaFailure.UNSAFE, assertThrows(JapaneseAsmrException::class.java) {
            resolver.lookup("private.example")
        }.failure)
    }

    @Test fun redirectIsCheckedBeforeTheNextConnection() {
        val requests = mutableListOf<String>()
        val connections = mutableListOf<InetSocketAddress>()
        val client = JaNetworkPolicy.install(OkHttpClient.Builder(), siteOnly = false, followMediaRedirects = true)
            .eventListener(object : EventListener() {
                override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                    connections += inetSocketAddress
                }
            })
            .addInterceptor { chain ->
                val request = chain.request()
                requests += request.url.encodedPath
                if (request.url.host != "audio.example") return@addInterceptor chain.proceed(request)
                val next = if (request.url.encodedPath == "/first") "/second" else "https://127.0.0.1/"
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(302).message("fixture").header("Location", next).body("".toResponseBody()).build()
            }.build()
        val error = assertThrows(JapaneseAsmrException::class.java) {
            client.newCall(Request.Builder().url("https://audio.example/first").build()).execute().close()
        }
        assertEquals(JaFailure.UNSAFE, error.failure)
        assertEquals(listOf("/first", "/second"), requests)
        assertTrue(connections.isEmpty())
        assertEquals(Proxy.NO_PROXY, client.proxy)
    }
}
