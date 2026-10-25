package com.nanahoshi.audioplayer.playback

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.nanahoshi.audioplayer.asmr.AsmrClient
import com.nanahoshi.audioplayer.bilibili.BilibiliClient
import com.nanahoshi.audioplayer.data.LocalAudio
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.data.db.WhisperJobEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.security.MessageDigest

data class LoadedSubtitles(
    val cues: List<SubtitleCue>,
    val generatedEmpty: Boolean,
    val generated: Boolean,
)

class SubtitleSource(
    private val context: Context,
    private val db: AppDatabase,
    private val bilibili: BilibiliClient,
    private val asmr: AsmrClient,
) {
    suspend fun load(row: QueuedTrack): LoadedSubtitles = withContext(Dispatchers.IO) {
        try {
            val generated = generated(row)
            if (generated != null) return@withContext LoadedSubtitles(generated, generated.isEmpty(), true)
            val trackId = row.trackId ?: return@withContext LoadedSubtitles(emptyList(), false, false)
            val track = db.tracks().getById(trackId) ?: return@withContext LoadedSubtitles(emptyList(), false, false)
            LoadedSubtitles(withOverlay(row, cuesFor(track)), false, false)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            LoadedSubtitles(emptyList(), false, false)
        }
    }

    suspend fun discard(row: QueuedTrack) = withContext(Dispatchers.IO) {
        removeGenerated(row)
        db.translations().deleteForQueue(row.queueItemId)
    }

    suspend fun discardStored(trackId: Long?, localPath: String?, remoteUrl: String?) = withContext(Dispatchers.IO) {
        removeGenerated(storedRow(trackId, localPath, remoteUrl))
    }

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

    suspend fun saveTranslations(row: QueuedTrack, cues: List<SubtitleCue>) = withContext(Dispatchers.IO) {
        val job = findGeneratedJob(row)
        if (job != null) {
            val file = generatedFile(job.fingerprint)
            if (file.name == ".json") return@withContext
            file.parentFile?.mkdirs()
            file.writeText(encodeGeneratedCues(cues))
            db.whisper().markTranslated(job.id, System.currentTimeMillis())
        } else {
            val file = overlayFile(row)
            file.parentFile?.mkdirs()
            file.writeText(encodeGeneratedCues(cues))
        }
    }

    private suspend fun generated(row: QueuedTrack): List<SubtitleCue>? {
        val job = findGeneratedJob(row) ?: return null
        return readGenerated(job)
    }

    private fun storedRow(trackId: Long?, localPath: String?, remoteUrl: String?) = QueuedTrack(
        queueItemId = -1,
        position = 0,
        trackId = trackId?.takeIf { it > 0L },
        source = TrackSource.LOCAL,
        title = "",
        artist = null,
        durationMs = 0,
        coverUri = null,
        localUri = localPath,
        bvid = null,
        cid = null,
        page = null,
        remoteUrl = remoteUrl,
        referer = null,
        workno = null,
        fileKey = null,
    )

    private suspend fun removeGenerated(row: QueuedTrack) {
        val jobs = relatedDone(row)
        jobs.map { it.fingerprint }.filter { it.isNotBlank() }.distinct().forEach { fingerprint ->
            val file = generatedFile(fingerprint)
            if (file.name != ".json") file.delete()
            db.whisper().deleteDoneForFingerprint(fingerprint)
        }
        overlayFile(row).delete()
        val trackId = row.trackId?.takeIf { it > 0L }
        if (trackId != null) {
            db.whisper().deleteDoneForTrack(trackId)
            db.translations().deleteForTrack(trackId)
        }
        row.remoteUrl?.takeIf { it.isNotBlank() }?.let {
            db.whisper().deleteDoneForUrl(it)
            db.translations().deleteForUrl(it)
        }
        row.localUri?.takeIf { it.isNotBlank() }?.let { db.whisper().deleteDoneForPath(it) }
        storedAudioPath(row)?.let { db.whisper().deleteDoneForPath(it) }
    }

    private suspend fun relatedDone(row: QueuedTrack): List<WhisperJobEntity> {
        val found = mutableListOf<WhisperJobEntity>()
        row.trackId?.takeIf { it > 0L }?.let { found += db.whisper().doneForTrack(it) }
        row.remoteUrl?.takeIf { it.isNotBlank() }?.let { found += db.whisper().doneForUrl(it) }
        storedAudioPath(row)?.let { found += db.whisper().doneForPath(it) }
        return found.distinctBy { it.id }
    }

    private suspend fun findGeneratedJob(row: QueuedTrack): WhisperJobEntity? {
        val jobs = buildList {
            row.trackId?.let { db.whisper().latestDoneForTrack(it) }?.let(::add)
            row.remoteUrl?.takeIf { it.isNotBlank() }?.let { db.whisper().latestDoneForUrl(it) }?.let(::add)
            storedAudioPath(row)?.let { db.whisper().latestDoneForPath(it) }?.let(::add)
        }
        return jobs.maxByOrNull { it.id }
    }

    private fun withOverlay(row: QueuedTrack, cues: List<SubtitleCue>): List<SubtitleCue> {
        if (cues.isEmpty() || cues.hasTranslation()) return cues
        val file = overlayFile(row)
        if (!file.isFile) return cues
        val overlay = parseGeneratedCues(file.readText())
        if (overlay.isEmpty()) return cues
        val byStart = overlay.associateBy { it.startMs }
        return cues.map { cue ->
            val extra = byStart[cue.startMs]?.translation
            if (extra.isNullOrBlank()) cue else cue.copy(translation = extra)
        }
    }

    private fun overlayFile(row: QueuedTrack): File {
        val raw = when {
            row.trackId != null && row.trackId > 0L -> "track:${row.trackId}"
            !row.remoteUrl.isNullOrBlank() -> "url:${row.remoteUrl}"
            !row.localUri.isNullOrBlank() -> "path:${row.localUri}"
            else -> "queue:${row.queueItemId}"
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        val key = digest.take(8).joinToString("") { "%02x".format(it) }
        return File(File(context.filesDir, "subtitles"), "extra-$key.json")
    }

    private suspend fun storedAudioPath(row: QueuedTrack): String? {
        val track = row.trackId?.let { db.tracks().getById(it) }
        listOf(track?.localUri, row.localUri).firstOrNull { isFile(it) }?.let { return it }
        val url = row.remoteUrl
        if (!url.isNullOrBlank()) {
            val done = db.downloads().latestForUrl(url)
            if (done?.status == DownloadStatus.DONE && isFile(done.localPath)) return done.localPath
        }
        return null
    }

    private fun readGenerated(job: WhisperJobEntity): List<SubtitleCue>? {
        val file = generatedFile(job.fingerprint)
        if (file.name == ".json" || !file.isFile) return null
        return parseGeneratedCues(file.readText())
    }

    private fun generatedFile(fingerprint: String): File {
        val safe = fingerprint.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }
        return File(File(context.filesDir, "subtitles"), "$safe.json")
    }

    private fun isFile(path: String?): Boolean =
        !path.isNullOrBlank() && path.startsWith("/") && File(path).isFile

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
