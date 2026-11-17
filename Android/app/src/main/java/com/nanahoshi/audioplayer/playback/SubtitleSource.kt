package com.nanahoshi.audioplayer.playback

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import com.nanahoshi.audioplayer.asmr.AsmrClient
import com.nanahoshi.audioplayer.bilibili.BiliSubtitle
import com.nanahoshi.audioplayer.bilibili.BilibiliClient
import com.nanahoshi.audioplayer.data.LocalAudio
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.readBiliSubtitle
import com.nanahoshi.audioplayer.data.biliSubtitleLanguage
import com.nanahoshi.audioplayer.data.writeBiliSubtitle
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import com.nanahoshi.audioplayer.data.db.SubtitlePublicationEntity
import com.nanahoshi.audioplayer.data.db.TranslateMark
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.data.db.WhisperJobEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

data class LoadedSubtitles(
    val cues: List<SubtitleCue>,
    val generatedEmpty: Boolean,
    val generated: Boolean,
    val notice: String? = null,
    val language: String? = null,
    val origin: SubtitleOrigin? = null,
    val sourceCues: List<SubtitleCue> = cues,
    val generatedFingerprint: String? = null,
) {
    val canTranslate: Boolean = canGenerateTranslation(cues, language)
}

/** 一首曲目的字幕，以及取不到时需要告诉用户的原因。 */
data class TrackSubtitles(
    val cues: List<SubtitleCue>,
    val notice: String? = null,
    val language: String? = null,
)

class SubtitleSource(
    private val context: Context,
    private val db: AppDatabase,
    private val bilibili: BilibiliClient,
    private val asmr: AsmrClient,
) {
    private val _revision = MutableStateFlow(0L)
    internal val revision = _revision.asStateFlow()
    internal val generatedWriteLock = Mutex()

    suspend fun load(row: QueuedTrack): LoadedSubtitles = withContext(Dispatchers.IO) {
        try {
            when (val state = generatedState(row)) {
                is GeneratedState.Ready -> return@withContext loadedGenerated(state.fingerprint, state.document)
                GeneratedState.Corrupt -> return@withContext LoadedSubtitles(
                    emptyList(), false, false, "生成的字幕文件损坏，可在「更多」里删除后重新生成",
                )
                GeneratedState.Missing -> Unit
            }
            val track = row.trackId?.takeIf { it > 0L }?.let { db.tracks().getById(it) }
                ?: bilibiliRowTrack(row)
                ?: return@withContext LoadedSubtitles(emptyList(), false, false)
            val trackSubtitles = cuesFor(track)
            LoadedSubtitles(
                withOverlay(row, trackSubtitles.cues), false, false, trackSubtitles.notice, trackSubtitles.language,
                sourceCues = trackSubtitles.cues,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            LoadedSubtitles(emptyList(), false, false)
        }
    }

    suspend fun discard(row: QueuedTrack) = withContext(Dispatchers.IO) {
        generatedWriteLock.withLock { removeGenerated(row) }
        db.translations().deleteInactiveForQueue(row.queueItemId)
        invalidate()
    }

    suspend fun discardStored(trackId: Long?, localPath: String?, remoteUrl: String?) = withContext(Dispatchers.IO) {
        generatedWriteLock.withLock { discardStoredLocked(trackId, localPath, remoteUrl) }
    }

    internal suspend fun discardStoredLocked(trackId: Long?, localPath: String?, remoteUrl: String?) {
        removeGenerated(storedQueueRow(trackId, localPath, remoteUrl))
        invalidate()
    }

    /** 与翻译发布串行删除覆盖文件及其终态记录，保留进行中的翻译和听写。 */
    suspend fun discardTranslationOverlay(file: File) =
        withContext(Dispatchers.IO) {
            generatedWriteLock.withLock {
                if (file.isFile && !file.delete()) return@withLock
                db.withTransaction {
                    val candidates = db.translations().all().filter {
                        it.generatedFingerprint == null && overlayFileName(
                            it.trackId, it.remoteUrl, it.localUri, it.queueItemId, it.bvid, it.cid,
                        ) == file.name
                    }
                    candidates.forEach { db.translations().deleteInactive(it.id) }
                }
                invalidate()
            }
        }

    internal fun invalidate() {
        _revision.update { it + 1 }
    }

    /** 是否有可保留的生成字幕；删除音频前据此决定要不要询问。 */
    suspend fun hasGenerated(trackId: Long?, localPath: String?, remoteUrl: String?): Boolean = withContext(Dispatchers.IO) {
        val row = storedQueueRow(trackId, localPath, remoteUrl)
        val identities = listOf(row) + recoveredOverlayIdentities(row, relatedDone(row))
        val biliOverlay = biliIdentityFromPath(row.localUri)?.let { (bvid, cid) ->
            overlayFileName(null, null, null, null, bvid, cid)?.let { File(File(context.filesDir, "subtitles"), it) }
        }
        identities.any { overlayFile(it).isFile } || biliOverlay?.isFile == true || generatedState(row) != GeneratedState.Missing
    }

    suspend fun cuesFor(track: TrackEntity): TrackSubtitles = withContext(Dispatchers.IO) {
        try {
            when (track.source) {
                TrackSource.BILIBILI -> bilibiliCues(track)
                TrackSource.ASMR -> TrackSubtitles(parseSubtitleDocument(asmrText(track).orEmpty()))
                TrackSource.DLSITE, TrackSource.YOUTUBE -> TrackSubtitles(emptyList())
                TrackSource.LOCAL -> TrackSubtitles(parseSubtitleDocument(localText(track).orEmpty()))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            TrackSubtitles(emptyList())
        }
    }

    internal fun loadGenerated(fingerprint: String): LoadedSubtitles? =
        generatedFile(fingerprint).takeIf { it.isFile }?.let { file ->
            parseGeneratedSubtitles(file.readText())?.let { loadedGenerated(fingerprint, it) }
        }

    private fun loadedGenerated(fingerprint: String, document: GeneratedSubtitles) =
        LoadedSubtitles(
            document.displayCues, document.cues.isEmpty(), true, origin = document.origin,
            sourceCues = document.cues, generatedFingerprint = fingerprint,
        )

    internal suspend fun generatedFingerprint(row: QueuedTrack): String? = findGeneratedJob(row)?.fingerprint

    suspend fun saveTranslations(
        row: QueuedTrack,
        cues: List<SubtitleCue>,
        expected: LoadedSubtitles? = null,
        translationJobId: Long? = null,
        generatedFingerprint: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        // 非生成路径：核对移到写锁外，避免联网重读阻塞取消/删除，也避免网络抖动丢弃已完成的翻译。
        // 只有读到非空源且内容指纹变化才拒绝；核对读到空结果（加载失败或源真空）时放行——
        // overlay 内嵌源指纹，显示侧仅在精确匹配时套用，不会错误覆盖已变化的源。
        if (expected != null && !expected.generated && generatedFingerprint == null) {
            val verified = load(row)
            if (verified.sourceCues.isNotEmpty() &&
                subtitleSourceFingerprint(verified.sourceCues) != subtitleSourceFingerprint(expected.sourceCues)
            ) return@withContext false
        }
        generatedWriteLock.withLock {
            val currentJob = findGeneratedJob(row)
            if (generatedFingerprint != null && currentJob != null && currentJob.fingerprint != generatedFingerprint) {
                return@withLock false
            }
            val fingerprint = generatedFingerprint ?: currentJob?.fingerprint
            val file = fingerprint?.let(::generatedFile)
            val document = file?.takeIf { it.isFile }?.let { parseGeneratedSubtitles(it.readText()) }
            if (generatedFingerprint != null && document == null) return@withLock false
            if (document != null) {
                // 生成路径：本地文件比较，保留严格守卫。
                val current = loadedGenerated(checkNotNull(fingerprint), document)
                if (expected != null && current != expected) return@withLock false
            } else if (expected != null && expected.generated) {
                // 生成字幕在保存前被删除：拒绝，不降级写 overlay。
                return@withLock false
            }
            coroutineContext.ensureActive()
            val target = if (document != null) checkNotNull(file) else overlayFile(row)
            val payload = if (document != null) {
                encodeGeneratedCues(document.cues, document.origin, revisedCues = cues)
            } else {
                encodeGeneratedCues(cues, null, sourceFingerprint = expected?.sourceCues?.let(::subtitleSourceFingerprint))
            }
            val whisperJobId = if (document != null) {
                currentJob?.takeIf { it.fingerprint == fingerprint }?.id
                    ?: fingerprint?.let { db.whisper().forFingerprint(it).lastOrNull { job -> job.status == DownloadStatus.DONE }?.id }
            } else null
            if (translationJobId == null) {
                withContext(NonCancellable) {
                    target.writeTextAtomically(payload)
                    whisperJobId?.let { db.whisper().markTranslated(it, System.currentTimeMillis()) }
                }
                true
            } else publishTranslation(target, payload, whisperJobId, translationJobId)
        }
    }

    /** 调用者持有 generatedWriteLock。写入发布记录后，中断由启动恢复接手。 */
    internal suspend fun publishTranslation(
        target: File,
        payload: String,
        whisperJobId: Long?,
        translationJobId: Long?,
    ): Boolean {
        coroutineContext.ensureActive()
        return withContext(NonCancellable) {
            val now = System.currentTimeMillis()
            val publication = SubtitlePublicationEntity(target.name, payload, whisperJobId, translationJobId, now)
            val staged = db.withTransaction {
                if (translationJobId != null) {
                    if (db.translations().get(translationJobId)?.status != DownloadStatus.RUNNING) return@withTransaction false
                } else if (whisperJobId != null && db.whisper().get(whisperJobId)?.translateStatus != TranslateMark.RUNNING) {
                    return@withTransaction false
                }
                db.translations().stagePublication(publication)
                true
            }
            if (!staged) return@withContext false
            val published = finishPublication(publication)
            if (published) invalidate()
            published
        }
    }

    internal suspend fun recoverPublications() = generatedWriteLock.withLock {
        for (publication in db.translations().pendingPublications()) {
            try {
                finishPublication(publication)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 单条恢复失败不阻塞其余发布补完，记录留待下次启动。
            }
        }
    }

    private suspend fun finishPublication(publication: SubtitlePublicationEntity): Boolean {
        val target = File(File(context.filesDir, "subtitles"), publication.filename)
        var written = false
        // 非法文件名（如目录穿越）与写入失败同走失败分支，不抛出中断恢复。
        if (target.name == publication.filename) {
            try {
                target.writeTextAtomically(publication.payload)
                written = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 含非 IO 异常（如 SecurityException）：清发布记录并标失败，可重试。
            }
        }
        if (!written) {
            db.withTransaction {
                db.translations().clearPublication(publication.filename)
                publication.translationJobId?.let { db.translations().failIfActive(it, "无法保存译文，请重试") }
                    ?: publication.whisperJobId?.let { db.whisper().markTranslateFailed(it, "无法保存译文，请重试") }
            }
            return false
        }
        return try {
            db.withTransaction {
                publication.translationJobId?.let { db.translations().finishIfActive(it) }
                publication.whisperJobId?.let { db.whisper().markTranslated(it, publication.translatedAt) }
                db.translations().clearPublication(publication.filename)
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 文件已写成但 DB 提交失败：保留发布记录，下次启动幂等重写再提交。
            false
        }
    }

    private suspend fun generatedState(row: QueuedTrack): GeneratedState {
        val job = findGeneratedJob(row) ?: return GeneratedState.Missing
        val file = generatedFile(job.fingerprint)
        if (file.name == ".json" || !file.isFile) return GeneratedState.Missing
        val document = parseGeneratedSubtitles(file.readText()) ?: return GeneratedState.Corrupt
        return GeneratedState.Ready(document, job.fingerprint)
    }

    private sealed interface GeneratedState {
        data class Ready(val document: GeneratedSubtitles, val fingerprint: String) : GeneratedState
        data object Corrupt : GeneratedState
        data object Missing : GeneratedState
    }

    private suspend fun removeGenerated(row: QueuedTrack) {
        val jobs = relatedDone(row)
        jobs.map { it.fingerprint }.filter { it.isNotBlank() }.distinct().forEach { fingerprint ->
            val file = generatedFile(fingerprint)
            if (file.name != ".json") file.delete()
            db.whisper().deleteDoneForFingerprint(fingerprint)
            db.translations().deleteInactiveForFingerprint(fingerprint)
        }
        overlayFile(row).delete()
        overlayFile(row, useBiliIdentity = false).delete()
        val trackId = row.trackId?.takeIf { it > 0L }
        if (trackId != null) {
            db.whisper().deleteDoneForTrack(trackId)
            db.translations().deleteInactiveForTrack(trackId)
        }
        row.remoteUrl?.takeIf { it.isNotBlank() }?.let {
            db.whisper().deleteDoneForUrl(it)
            db.translations().deleteInactiveForUrl(it)
        }
        row.localUri?.takeIf { it.isNotBlank() }?.let { db.whisper().deleteDoneForPath(it) }
        val storedPath = storedAudioPath(row)
        storedPath?.let { db.whisper().deleteDoneForPath(it) }
        // 孤儿条目的翻译覆盖按旧 track/url 命名；按找回的身份补删文件和记录。
        for (identity in recoveredOverlayIdentities(row, jobs)) {
            overlayFile(identity).delete()
            identity.trackId?.let {
                db.whisper().deleteDoneForTrack(it)
                db.translations().deleteInactiveForTrack(it)
            }
            identity.remoteUrl?.let {
                db.whisper().deleteDoneForUrl(it)
                db.translations().deleteInactiveForUrl(it)
            }
        }
        // 未入库 B 站音频的翻译覆盖按 bili 身份命名；storedQueueRow 不带 bvid/cid，从存储路径还原后补删。
        val bili = biliIdentityFromPath(row.localUri) ?: biliIdentityFromPath(storedPath)
        if (bili != null) {
            val (bvid, cid) = bili
            overlayFileName(null, null, null, null, bvid, cid)?.let {
                File(File(context.filesDir, "subtitles"), it).delete()
            }
            db.translations().deleteInactiveForBili(bvid, cid)
        }
    }

    /** B 站音轨恒存于 filesDir/bilibili/{bvid}/{cid}.m4s（AGENTS §6.1），从路径确定性还原身份。 */
    private fun biliIdentityFromPath(path: String?): Pair<String, Long>? {
        if (path.isNullOrBlank() || !path.startsWith("/")) return null
        val file = File(path)
        val dir = file.parentFile ?: return null
        if (dir.parentFile != File(context.filesDir, "bilibili")) return null
        val bvid = dir.name.takeIf { BVID_ROW.matches(it) } ?: return null
        val cid = file.nameWithoutExtension.toLongOrNull()?.takeIf { it > 0L } ?: return null
        return bvid to cid
    }

    private suspend fun relatedDone(row: QueuedTrack): List<WhisperJobEntity> {
        val found = mutableListOf<WhisperJobEntity>()
        row.trackId?.takeIf { it > 0L }?.let { found += db.whisper().doneForTrack(it) }
        row.remoteUrl?.takeIf { it.isNotBlank() && (row.source != TrackSource.BILIBILI || it.startsWith("/")) }
            ?.let { found += db.whisper().doneForUrl(it) }
        storedAudioPath(row)?.let { found += db.whisper().doneForPath(it) }
        return found.distinctBy { it.id }
    }

    private suspend fun findGeneratedJob(row: QueuedTrack): WhisperJobEntity? {
        val jobs = buildList {
            row.trackId?.let { db.whisper().latestDoneForTrack(it) }?.let(::add)
            row.remoteUrl?.takeIf { it.isNotBlank() && (row.source != TrackSource.BILIBILI || it.startsWith("/")) }
                ?.let { db.whisper().latestDoneForUrl(it) }?.let(::add)
            storedAudioPath(row)?.let { db.whisper().latestDoneForPath(it) }?.let(::add)
        }
        return jobs.maxByOrNull { it.id }
    }

    private fun withOverlay(row: QueuedTrack, cues: List<SubtitleCue>): List<SubtitleCue> {
        if (cues.isEmpty() || cues.hasTranslation()) return cues
        val file = overlayFile(row).takeIf { it.isFile }
            ?: overlayFile(row, useBiliIdentity = false)
        if (!file.isFile) return cues
        val overlay = runCatching { parseGeneratedSubtitles(file.readText()) }.getOrNull() ?: return cues
        return applySubtitleOverlay(cues, overlay)
    }

    private fun overlayFile(row: QueuedTrack, useBiliIdentity: Boolean = true): File {
        val bili = if (useBiliIdentity) bilibiliRowTrack(row) else null
        return File(File(context.filesDir, "subtitles"),
            overlayFileName(row.trackId, row.remoteUrl, row.localUri, row.queueItemId, bili?.bvid, bili?.cid)!!)
    }

    private suspend fun storedAudioPath(row: QueuedTrack): String? {
        val track = row.trackId?.let { db.tracks().getById(it) }
        val bili = bilibiliRowTrack(row)
        if (bili != null) {
            com.nanahoshi.audioplayer.data.readyBiliAudio(context.filesDir, bili.bvid, bili.cid)?.let { return it.absolutePath }
        }
        listOf(track?.localUri, row.localUri, row.remoteUrl).firstOrNull { isFile(it) }?.let { return it }
        val url = row.remoteUrl
        if (!url.isNullOrBlank()) {
            val done = db.downloads().latestForUrl(url)
            if (done?.status == DownloadStatus.DONE && isFile(done.localPath)) return done.localPath
        }
        return null
    }

    private fun generatedFile(fingerprint: String): File {
        val safe = fingerprint.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }
        return File(File(context.filesDir, "subtitles"), "$safe.json")
    }

    private fun isFile(path: String?): Boolean =
        !path.isNullOrBlank() && path.startsWith("/") && File(path).isFile

    private suspend fun bilibiliCues(track: TrackEntity): TrackSubtitles {
        val bvid = track.bvid ?: return TrackSubtitles(emptyList())
        val cid = track.cid ?: return TrackSubtitles(emptyList())
        // 下载音轨时顺带存下的字幕，播放时优先读本地，不再依赖登录和网络。
        readBiliSubtitle(context.filesDir, bvid, cid)?.let { document ->
            val cues = parseSubtitleDocument(document)
            if (cues.isNotEmpty()) return TrackSubtitles(cues, language = biliSubtitleLanguage(document))
        }
        return when (val result = bilibili.subtitleDocument(bvid, cid)) {
            is BiliSubtitle.Ready -> {
                val cues = parseSubtitleDocument(result.document)
                // 音轨已经在本地时，顺手把这次拿到的字幕补存下来。
                if (cues.isNotEmpty() && LocalAudio.hasStoredCopy(track.localUri)) {
                    writeBiliSubtitle(context.filesDir, bvid, cid, result.document, result.language)
                }
                TrackSubtitles(cues, language = result.language)
            }
            BiliSubtitle.NeedsLogin -> TrackSubtitles(emptyList(), "B 站字幕需要登录后显示，可在「我的」重新登录")
            BiliSubtitle.Failed -> TrackSubtitles(emptyList(), "B 站字幕读取失败，可稍后再试")
            BiliSubtitle.Missing -> TrackSubtitles(emptyList())
        }
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

/** 下载管理等只有路径/曲目身份的入口，用同一构造方式合成队列行。 */
internal fun storedQueueRow(trackId: Long?, localPath: String?, remoteUrl: String?) = QueuedTrack(
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

/**
 * 曲目已移除的孤儿条目没有 trackId/remoteUrl，翻译覆盖却仍按旧 track/url 身份命名。
 * 借按路径（或地址）匹配的听写任务找回身份，询问和删除都不漏掉这些覆盖文件与记录。
 */
internal fun recoveredOverlayIdentities(row: QueuedTrack, jobs: List<WhisperJobEntity>): List<QueuedTrack> {
    val known = overlayFileName(row.trackId, row.remoteUrl, row.localUri, row.queueItemId)
    return jobs.mapNotNull { job ->
        val trackId = job.trackId?.takeIf { it > 0L }
        val url = job.awaitRemoteUrl?.takeIf { it.isNotBlank() }
        if (trackId == null && url == null) return@mapNotNull null
        val name = overlayFileName(trackId, url, null, null)
        if (name == null || name == known) null else storedQueueRow(trackId, null, url)
    }.distinctBy { overlayFileName(it.trackId, it.remoteUrl, it.localUri, it.queueItemId) }
}

/** 翻译覆盖文件名；身份优先级与队列行一致，按翻译任务匹配时 localUri 传 null。 */
internal fun overlayFileName(
    trackId: Long?, remoteUrl: String?, localUri: String?, queueItemId: Long?, bvid: String? = null, cid: Long? = null,
): String? {
    val raw = when {
        bvid != null && cid != null && (trackId == null || trackId <= 0L) -> "bili:$bvid:$cid"
        trackId != null && trackId > 0L -> "track:$trackId"
        !remoteUrl.isNullOrBlank() -> "url:$remoteUrl"
        !localUri.isNullOrBlank() -> "path:$localUri"
        queueItemId != null -> "queue:$queueItemId"
        else -> return null
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
    val key = digest.take(8).joinToString("") { "%02x".format(it) }
    return "extra-$key.json"
}

/**
 * 没入库的 B 站队列项（例如从收藏夹直接缓存的音轨）没有 trackId，
 * 用队列里保存的 workno / fileKey 当 bvid / cid，读同一份缓存字幕。
 */
internal fun bilibiliRowTrack(row: QueuedTrack): TrackEntity? {
    if (row.source != TrackSource.BILIBILI) return null
    val bvid = (row.bvid?.takeIf { BVID_ROW.matches(it) }
        ?: row.workno?.trim()?.takeIf { BVID_ROW.matches(it) })
        ?: return null
    val cid = row.cid?.takeIf { it > 0L }
        ?: row.fileKey?.toLongOrNull()?.takeIf { it > 0L }
        ?: return null
    val local = row.localUri?.takeIf { it.startsWith("/") }
        ?: row.remoteUrl?.takeIf { it.startsWith("/") }
    return TrackEntity(
        id = 0L,
        source = TrackSource.BILIBILI,
        title = row.title,
        artist = row.artist,
        durationMs = row.durationMs,
        coverUri = row.coverUri,
        localUri = local,
        bvid = bvid,
        cid = cid,
        page = row.page,
        addedAt = 0L,
    )
}

private val BVID_ROW = Regex("BV[0-9A-Za-z]{10}")
