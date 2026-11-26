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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.decodeBase64
import java.io.File
import java.nio.ByteBuffer
import com.nanahoshi.audioplayer.data.toHex
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class JaMegaFatalException(text: TextRef) : JaWorkException(text)
internal class JaMegaTransientException(text: TextRef) : JaWorkException(text)
internal class JaMegaManifestInvalidException(text: TextRef) : JaWorkException(text)

internal sealed interface JaMegaFileSource {
    data class PublicFile(val handle: String) : JaMegaFileSource
    data class FolderFile(val folderHandle: String, val nodeHandle: String) : JaMegaFileSource
}

internal data class JaMegaResolved(val files: List<JaRemoteFile>, val skipped: Int = 0)

private val apiBase = "https://g.api.mega.co.nz/cs".toHttpUrlOrNull()!!
private val handlePattern = "[A-Za-z0-9_-]{8}"
private val nodeHandle = Regex(handlePattern)
private val fileKeyPattern = "[A-Za-z0-9_-]{43}=?"
private val fileKeyRegex = Regex(fileKeyPattern)
private val folderKeyPattern = "[A-Za-z0-9_-]{22}(?:==)?"
private val legacySingle = Regex("!($handlePattern)!($fileKeyPattern)")
private val legacyFolder = Regex("F!($handlePattern)!($folderKeyPattern)(?:([!?])($handlePattern))?")
private val modernFolder = Regex("($folderKeyPattern)(?:/(folder|file)/($handlePattern))?")

private fun megaRequest(sequence: Long, folder: String?, command: String): Request {
    val url = apiBase.newBuilder().addQueryParameter("id", sequence.toString()).apply {
        if (folder != null) addQueryParameter("n", folder)
    }.build()
    return Request.Builder().url(url)
        .post("[$command]".toRequestBody("application/json".toMediaType())).build()
}

internal fun JaMegaFileSource.downloadRequest(sequence: Long): Request = when (this) {
    is JaMegaFileSource.PublicFile -> megaRequest(sequence, null, """{"a":"g","p":"$handle","g":1,"ssl":2}""")
    is JaMegaFileSource.FolderFile ->
        megaRequest(sequence, folderHandle, """{"a":"g","n":"$nodeHandle","g":1,"ssl":2}""")
}

internal fun parseMegaResponse(json: String): JsonObject {
    val parsed = try {
        Json.parseToJsonElement(json)
    } catch (_: IllegalArgumentException) {
        invalidShare()
    }
    val value = if (parsed is JsonArray) parsed.singleOrNull() else parsed
    val error = when (value) {
        is JsonObject -> value.field("e")?.intOrNull
        is JsonPrimitive -> value.intOrNull
        else -> null
    }
    when (error) {
        -3, -4, -18 -> throw JaMegaTransientException(TextRef.Res(R.string.ja_error_mega_busy))
        -17 -> throw JaMegaFatalException(TextRef.Res(R.string.ja_error_mega_quota))
    }
    if (error != null && error < 0) invalidShare()
    return value as? JsonObject ?: invalidShare()
}

internal fun resolveMegaAddress(data: JsonObject, expectedSize: Long): HttpUrl {
    if ("s" in data && data.field("s")?.longOrNull != expectedSize) {
        throw JaMegaManifestInvalidException(TextRef.Res(R.string.ja_error_mega_size_changed))
    }
    return data.string("g")?.toHttpUrlOrNull()?.takeIf {
        it.isHttps && it.port == 443 && it.username.isEmpty() && it.password.isEmpty() &&
            (it.host.endsWith(".mega.co.nz") || it.host.endsWith(".mega.nz"))
    } ?: invalidShare()
}

internal sealed class JaMegaShare {
    class SingleFile(val source: JaMegaFileSource.PublicFile, val crypto: JaMegaCrypto) : JaMegaShare() {
        fun resolve(data: JsonObject): JaRemoteFile {
            val size = data.field("s")?.longOrNull?.takeIf { it >= 0 } ?: invalidShare()
            val name = crypto.name(data.string("at") ?: invalidShare())
            val address = if (size == 0L) null else resolveMegaAddress(data, size)
            return JaRemoteFile(name, size, null, JaFileTransfer.Mega(source, crypto, address))
        }
    }

    class Folder(
        private val handle: String,
        private val folderKey: ByteArray,
        private val focusHandle: String?,
        private val focusKind: FocusKind?,
    ) : JaMegaShare() {
        enum class FocusKind { FOLDER, FILE }

        private class Node(val data: JsonObject, val handle: String) {
            val parent = data.string("p")
            val type = data.field("t")?.intOrNull
        }

        fun request(sequence: Long): Request = megaRequest(sequence, handle, """{"a":"f","c":1,"r":1,"ca":1}""")

        suspend fun resolveTree(data: JsonObject): JaMegaResolved {
            val context = currentCoroutineContext()
            val nodes = data["f"] as? JsonArray ?: invalidShare()
            val rootData = nodes.firstOrNull() as? JsonObject ?: invalidShare()
            val root = Node(rootData, rootData.string("h")?.takeIf(nodeHandle::matches) ?: invalidShare())
            if (root.type != 1 || directoryName(root, root.handle) == null) invalidShare()

            val byHandle = mutableMapOf<String, Node>()
            val children = mutableMapOf<String, MutableList<Node>>()
            for (item in nodes) {
                context.ensureActive()
                val obj = item as? JsonObject ?: continue
                val id = obj.string("h")?.takeIf(nodeHandle::matches) ?: continue
                val node = Node(obj, id)
                if (byHandle.put(id, node) != null) invalidShare()
                if (node.parent != null && id != root.handle) {
                    children.getOrPut(node.parent) { mutableListOf() }.add(node)
                }
            }
            val traversalRoot = if (focusHandle == null) root else {
                val focus = byHandle[focusHandle] ?: invalidShare()
                val expectedType = if (focusKind == FocusKind.FILE) 0 else 1
                if (focus.type != expectedType) invalidShare()
                val visited = mutableSetOf<String>()
                var node = if (focus.type == 1) focus else byHandle[focus.parent] ?: invalidShare()
                while (node.handle != root.handle) {
                    context.ensureActive()
                    if (!visited.add(node.handle) || node.type != 1 || directoryName(node, root.handle) == null) invalidShare()
                    node = byHandle[node.parent] ?: invalidShare()
                }
                if (focus.type == 0) {
                    val file = file(focus, root.handle, "")
                    return if (file == null) JaMegaResolved(emptyList(), 1) else JaMegaResolved(listOf(file))
                }
                focus
            }

            val files = mutableListOf<Pair<JaRemoteFile, String>>()
            val pending = ArrayDeque<Pair<Node, String?>>()
            children[traversalRoot.handle].orEmpty().forEach { pending.addLast(it to "") }
            val visited = mutableSetOf(traversalRoot.handle)
            var skipped = 0
            while (pending.isNotEmpty()) {
                context.ensureActive()
                val (node, parentPath) = pending.removeLast()
                if (!visited.add(node.handle)) continue
                if (node.type == 0) {
                    val file = parentPath?.let { file(node, root.handle, it) }
                    if (file == null) skipped++ else files.add(file to node.handle)
                }
                // Keep traversing broken branches so their file nodes are counted as skipped.
                val path = if (parentPath != null && node.type == 1) {
                    directoryName(node, root.handle)?.takeIf(::isSegment)
                        ?.let { "$parentPath$it/" }?.takeIf { it.length < 2048 }
                } else null
                children[node.handle].orEmpty().forEach { pending.addLast(it to path) }
            }
            if (files.isEmpty() && skipped == 0) {
                throw JaMegaFatalException(TextRef.Res(R.string.ja_error_mega_empty_folder))
            }
            files.sortWith(compareBy({ jaWorkPathKey(it.first.relativePath) }, { it.second }))
            return JaMegaResolved(files.map { it.first }, skipped)
        }

        private fun directoryName(node: Node, root: String): String? {
            val attributes = node.data.string("a") ?: return null
            return nodeKeys(node, root, 16).firstNotNullOfOrNull { key ->
                attributeName(SecretKeySpec(key, "AES"), attributes)
            }
        }

        private fun file(node: Node, root: String, parentPath: String): JaRemoteFile? {
            val size = node.data.field("s")?.longOrNull?.takeIf { it >= 0 } ?: return null
            val attributes = node.data.string("a") ?: return null
            return nodeKeys(node, root, 32).firstNotNullOfOrNull { key ->
                val crypto = JaMegaCrypto(key)
                val name = crypto.nameOrNull(attributes)?.takeIf(::isSegment) ?: return@firstNotNullOfOrNull null
                JaRemoteFile(parentPath + name, size, null,
                    JaFileTransfer.Mega(JaMegaFileSource.FolderFile(handle, node.handle), crypto))
            }
        }

        private fun nodeKeys(node: Node, root: String, length: Int): Sequence<ByteArray> {
            val candidates = node.data.string("k").orEmpty().split('/').sortedBy {
                val owner = it.substringBefore(':', "")
                owner != root && owner != handle
            }
            val cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(folderKey, "AES"))
            }
            return candidates.asSequence().mapNotNull { entry ->
                val encrypted = entry.substringAfter(':').decodeBase64()?.toByteArray()
                encrypted?.takeIf { it.size == length }?.let(cipher::doFinal)
            }
        }

        private fun isSegment(name: String): Boolean = '/' !in name && '\\' !in name
    }

    companion object {
        fun parse(url: HttpUrl): JaMegaShare {
            val parts = url.pathSegments
            val fragment = url.fragment.orEmpty()
            if (parts.size == 2 && parts[0] == "folder" && nodeHandle.matches(parts[1])) {
                val (key, selector, target) = modernFolder.matchEntire(fragment)?.destructured ?: invalidShare()
                return folder(parts[1], key, selector, target)
            }
            if (parts == listOf("")) {
                legacyFolder.matchEntire(fragment)?.destructured?.let { (handle, key, selector, target) ->
                    return folder(handle, key, selector, target)
                }
                legacySingle.matchEntire(fragment)?.destructured?.let { (handle, key) -> return single(handle, key) }
            }
            if (parts.size == 2 && parts[0] == "file" && nodeHandle.matches(parts[1]) &&
                fileKeyRegex.matches(fragment)) {
                return single(parts[1], fragment)
            }
            invalidShare()
        }

        private fun single(handle: String, encoded: String): SingleFile =
            SingleFile(JaMegaFileSource.PublicFile(handle), JaMegaCrypto(decodeKey(encoded, 32)))

        private fun folder(handle: String, encoded: String, selector: String, target: String): Folder {
            val kind = when (selector) {
                "folder", "!" -> Folder.FocusKind.FOLDER
                "file", "?" -> Folder.FocusKind.FILE
                else -> null
            }
            return Folder(handle, decodeKey(encoded, 16), target.ifEmpty { null }, kind)
        }
    }
}

private fun decodeKey(encoded: String, size: Int): ByteArray =
    encoded.decodeBase64()?.toByteArray()?.takeIf { it.size == size } ?: invalidShare()

private fun JsonObject.field(name: String) = this[name] as? JsonPrimitive
private fun JsonObject.string(name: String) = field(name)?.takeIf { it.isString }?.contentOrNull

private fun attributeName(key: SecretKeySpec, attributes: String): String? = runCatching {
    val encrypted = requireNotNull(attributes.decodeBase64()).toByteArray()
    val cipher = Cipher.getInstance("AES/CBC/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(ByteArray(16)))
    val decoded = cipher.doFinal(encrypted).decodeToString().trimEnd('\u0000')
    require(decoded.startsWith("MEGA{"))
    val json = Json.parseToJsonElement(decoded.substring(4)) as JsonObject
    json.string("n")?.takeIf(String::isNotBlank)
}.getOrNull()

internal class JaMegaCrypto(shareKey: ByteArray) {
    private val key = SecretKeySpec(ByteArray(16) { i -> (shareKey[i].toInt() xor shareKey[i + 16].toInt()).toByte() }, "AES")
    private val nonce = shareKey.copyOfRange(16, 24)
    private val expectedMac = shareKey.copyOfRange(24, 32)
    val checksum = MessageDigest.getInstance("SHA-256").digest(expectedMac).toHex()

    fun name(attributes: String): String = nameOrNull(attributes) ?: invalidShare()

    fun nameOrNull(attributes: String): String? = attributeName(key, attributes)

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
        val aggregate = ByteArray(16)
        val aggregateScratch = ByteArray(16)
        val chunk = ByteArray(1024 * 1024)
        // CBC/NoPadding 的输出与输入等长；复用同一个缓冲区，避免每 1 MiB 分块
        // 只取末尾 16 字节却分配一整块临时数组。
        val encrypted = ByteArray(chunk.size)
        val iv = IvParameterSpec(nonce + nonce)
        var offset = 0L
        var chunkIndex = 1
        val size = path.length()
        java.io.DataInputStream(path.inputStream().buffered()).use { input ->
            while (offset < size) {
                context.ensureActive()
                val count = minOf(chunkIndex * 128L * 1024, size - offset).toInt()
                val padded = (count + 15) and 15.inv()
                input.readFully(chunk, 0, count)
                if (padded > count) chunk.fill(0, count, padded)
                cipher.init(Cipher.ENCRYPT_MODE, key, iv)
                cipher.doFinal(chunk, 0, padded, encrypted, 0)
                for (i in aggregate.indices) aggregate[i] =
                    (aggregate[i].toInt() xor encrypted[padded - 16 + i].toInt()).toByte()
                aggregateCipher.doFinal(aggregate, 0, aggregate.size, aggregateScratch, 0)
                System.arraycopy(aggregateScratch, 0, aggregate, 0, aggregate.size)
                offset += count
                chunkIndex = minOf(chunkIndex + 1, 8)
            }
        }
        val mac = ByteArray(8) { i ->
            val start = if (i < 4) i else i + 4
            (aggregate[start].toInt() xor aggregate[start + 4].toInt()).toByte()
        }
        return MessageDigest.isEqual(expectedMac, mac)
    }
}

private fun invalidShare(): Nothing = throw JaMegaFatalException(TextRef.Res(R.string.ja_error_mega_share_expired))
