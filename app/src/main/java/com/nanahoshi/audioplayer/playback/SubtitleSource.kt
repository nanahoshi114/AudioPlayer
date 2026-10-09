package com.nanahoshi.audioplayer.playback

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.nanahoshi.audioplayer.asmr.AsmrClient
import com.nanahoshi.audioplayer.bilibili.BilibiliClient
import com.nanahoshi.audioplayer.data.LocalAudio
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.TrackEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset

class SubtitleSource(
    private val context: Context,
    private val db: AppDatabase,
    private val bilibili: BilibiliClient,
    private val asmr: AsmrClient,
) {
    suspend fun cuesFor(track: TrackEntity): List<SubtitleCue> = withContext(Dispatchers.IO) {
        try {
            when (track.source) {
                TrackSource.BILIBILI -> bilibiliCues(track)
                TrackSource.ASMR -> parseSubtitleDocument(asmrText(track).orEmpty())
                TrackSource.DLSITE -> emptyList()
                TrackSource.LOCAL -> parseSubtitleDocument(localText(track).orEmpty())
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun bilibiliCues(track: TrackEntity): List<SubtitleCue> {
        val bvid = track.bvid ?: return emptyList()
        val cid = track.cid ?: return emptyList()
        val document = bilibili.subtitleDocument(bvid, cid) ?: return emptyList()
        return parseSubtitleDocument(document)
    }

    private suspend fun asmrText(track: TrackEntity): String? {
        val folderId = track.folderId ?: return null
        val files = db.files().listIn(folderId)
        val name = pickSubtitleName(track.title, files.map { it.name }) ?: return null
        val remote = files.firstOrNull { it.name == name }?.remoteUrl ?: return null
        return asmr.readText(remote)
    }

    private fun localText(track: TrackEntity): String? {
        val localUri = track.localUri ?: return null
        val siblings = siblingsOf(localUri)
        if (siblings.isEmpty()) return null
        val audioName = audioFileName(localUri).ifBlank { track.title }
        val name = pickSubtitleName(audioName, siblings.map { it.name }) ?: return null
        return siblings.firstOrNull { it.name == name }?.read()
    }

    private fun audioFileName(localUri: String): String {
        val uri = localUri.toUri()
        return if (uri.scheme == "content") {
            LocalAudio.displayName(context, uri)
        } else {
            File(uri.path ?: localUri).name
        }
    }

    private fun siblingsOf(localUri: String): List<NamedBytes> {
        val uri = localUri.toUri()
        if (uri.scheme == "content") return documentSiblings(uri)
        val file = File(if (uri.scheme == "file") uri.path.orEmpty() else localUri)
        val parent = file.parentFile ?: return emptyList()
        return parent.listFiles().orEmpty().filter { it.isFile }.map { child ->
            NamedBytes(child.name) { readLimited(child.inputStream()) }
        }
    }

    private fun documentSiblings(uri: Uri): List<NamedBytes> {
        return try {
            if (!DocumentsContract.isDocumentUri(context, uri)) return emptyList()
            val documentId = DocumentsContract.getDocumentId(uri)
            val slash = documentId.lastIndexOf('/')
            if (slash <= 0) return emptyList()
            val parentId = documentId.substring(0, slash)
            val authority = uri.authority ?: return emptyList()
            val children = DocumentsContract.buildChildDocumentsUri(authority, parentId)
            val rows = mutableListOf<NamedBytes>()
            context.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                while (cursor.moveToNext()) {
                    if (nameColumn < 0 || idColumn < 0) continue
                    val name = cursor.getString(nameColumn) ?: continue
                    val id = cursor.getString(idColumn) ?: continue
                    val child = DocumentsContract.buildDocumentUri(authority, id)
                    rows += NamedBytes(name) { readLimited(context.contentResolver.openInputStream(child)) }
                }
            }
            rows
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun readLimited(input: InputStream?): String? {
        input ?: return null
        return input.use { stream ->
            val buffer = ByteArray(MAX_BYTES)
            val count = stream.read(buffer)
            if (count <= 0) null else decode(buffer.copyOf(count))
        }
    }

    private fun decode(bytes: ByteArray): String {
        val utf8 = bytes.toString(Charsets.UTF_8)
        if ('\uFFFD' !in utf8) return utf8
        return try {
            bytes.toString(Charset.forName("GBK"))
        } catch (_: Exception) {
            utf8
        }
    }

    private fun String.toUri(): Uri = when {
        startsWith("content:") || startsWith("file:") -> Uri.parse(this)
        else -> Uri.EMPTY
    }

    private class NamedBytes(val name: String, val read: () -> String?)

    private companion object {
        const val MAX_BYTES = 512 * 1024
    }
}
