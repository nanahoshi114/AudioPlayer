package com.nanahoshi.audioplayer.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket

/** 仪器测试共用的本地 HTTP fixture 服务。 */
internal class LocalProgressHttp(scope: CoroutineScope, private val handler: suspend (Input) -> Reply) {
    data class Input(val path: String, val headers: Map<String, String>, val body: ByteArray)
    data class Reply(val code: Int = 200, val bytes: ByteArray = ByteArray(0), val headers: Map<String, String> = emptyMap())
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val listener = scope.launch(Dispatchers.IO) {
        try {
            while (isActive) {
                val socket = server.accept()
                launch {
                    socket.use {
                        socket.soTimeout = 5000
                        val input = socket.getInputStream().buffered()
                        fun line(): String {
                            val out = java.io.ByteArrayOutputStream()
                            while (true) { val byte = input.read(); if (byte < 0 || byte == 10) break; if (byte != 13) out.write(byte) }
                            return out.toString("US-ASCII")
                        }
                        val path = line().split(' ')[1]
                        val headers = linkedMapOf<String, String>()
                        while (true) { val header = line(); if (header.isEmpty()) break; headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim() }
                        val body = ByteArray(headers["content-length"]?.toInt() ?: 0)
                        var offset = 0
                        while (offset < body.size) { val count = input.read(body, offset, body.size - offset); if (count < 0) throw IOException("short fixture body"); offset += count }
                        val reply = handler(Input(path, headers, body))
                        val extra = reply.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
                        val head = "HTTP/1.1 ${reply.code} Fixture\r\nContent-Length: ${reply.bytes.size}\r\nConnection: close\r\n$extra\r\n"
                        socket.getOutputStream().apply { write(head.toByteArray(Charsets.US_ASCII)); write(reply.bytes); flush() }
                    }
                }
            }
        } catch (error: IOException) { if (isActive) throw error }
    }
    fun url(path: String) = "http://127.0.0.1:${server.localPort}$path"
    suspend fun close() { listener.cancel(); server.close(); listener.join() }
}