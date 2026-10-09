package com.nanahoshi.audioplayer.data

import android.content.Context
import com.nanahoshi.audioplayer.asmr.AsmrClient
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.dlsite.DlsiteClient
import com.nanahoshi.audioplayer.dlsite.RemotePlayable
import com.nanahoshi.audioplayer.data.db.DownloadEntity
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.TrackEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

class DownloadCenter(
    context: Context,
    private val db: AppDatabase,
    private val asmr: AsmrClient,
    private val dlsite: DlsiteClient,
) {
    private val root = File(context.filesDir, "asmr")
    private val dlsiteRoot = File(context.filesDir, "dlsite")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val kick = Channel<Unit>(Channel.CONFLATED)

    fun observe(): Flow<List<DownloadEntity>> = db.downloads().observe()

    init {
        scope.launch {
            db.downloads().failActive("下载中断，请重试")
            for (ignored in kick) {
                while (true) {
                    val job = db.downloads().nextQueued() ?: break
                    downloadOne(job)
                }
            }
        }
    }

    suspend fun enqueueRemote(item: RemotePlayable, workKey: String): Boolean = withContext(Dispatchers.IO) {
        if (item.remoteUrl.isBlank()) return@withContext false
        val key = item.fileKey?.let(::rawFileKey)
        val storedKey = canonicalWorkKey(workKey)
        if (!key.isNullOrBlank() && db.downloads().activeForFile(item.source.name, storedKey, key) != null) {
            return@withContext false
        }
        if (localCopy(item.source.name, storedKey, key) != null) return@withContext false
        if (db.downloads().activeForUrl(item.remoteUrl) != null) return@withContext false
        db.downloads().insert(
            DownloadEntity(
                trackId = 0,
                title = item.title,
                bytesDone = 0,
                bytesTotal = 0,
                status = DownloadStatus.QUEUED,
                error = null,
                remoteUrl = item.remoteUrl,
                referer = item.referer,
                source = item.source.name,
                workKey = storedKey,
                fileKey = key ?: item.fileKey,
            ),
        )
        kick.trySend(Unit)
        true
    }

    suspend fun enqueue(tracks: List<TrackEntity>): Int = withContext(Dispatchers.IO) {
        var added = 0
        for (track in tracks) {
            if (track.source != TrackSource.ASMR && track.source != TrackSource.DLSITE) continue
            if (localFileReady(track.localUri)) continue
            if (db.downloads().activeForTrack(track.id) != null) continue
            db.downloads().insert(
                DownloadEntity(
                    trackId = track.id,
                    title = track.title,
                    bytesDone = 0,
                    bytesTotal = 0,
                    status = DownloadStatus.QUEUED,
                    error = null,
                ),
            )
            added += 1
        }
        if (added > 0) kick.trySend(Unit)
        added
    }

    fun retry(id: Long) {
        scope.launch {
            db.downloads().requeue(id)
            kick.trySend(Unit)
        }
    }

    private suspend fun downloadOne(job: DownloadEntity) {
        db.downloads().markRunning(job.id)
        try {
            if (!job.remoteUrl.isNullOrBlank() && job.trackId == 0L) {
                downloadRemote(job)
                return
            }
            val track = db.tracks().getById(job.trackId) ?: error("音频已经不在库里")
            if (localFileReady(track.localUri)) {
                val length = File(track.localUri!!).length()
                db.downloads().finish(job.id, length, length)
                return
            }
            var url = resolveUrl(track, refresh = false)
            var playCookie: String? = null
            val dest = destination(track)
            dest.parentFile?.mkdirs()
            val partial = File(dest.absolutePath + ".part")
            var refreshed = false
            val dlsiteTrack = track.source == TrackSource.DLSITE
            if (dlsiteTrack) {
                val signed = signedTrack(track)
                url = signed.url
                playCookie = signed.cookie
            }
            while (true) {
                if (partial.exists()) partial.delete()
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", if (dlsiteTrack) DlsiteClient.USER_AGENT else AsmrClient.USER_AGENT)
                    .header("Referer", if (dlsiteTrack) DlsiteClient.PLAY_REFERER else AsmrClient.REFERER)
                    .apply {
                        if (dlsiteTrack) {
                            playCookie?.let { header("Cookie", it) }
                        } else {
                            header("Origin", AsmrClient.ORIGIN)
                        }
                    }
                    .build()
                val expired = asmr.httpClient().newCall(request).execute().use { response ->
                    if (response.code == 401) error("登录已失效，请重新登录")
                    if ((response.code == 403 || response.code == 404) && !refreshed) return@use true
                    if (!response.isSuccessful) error("下载失败")
                    val body = response.body ?: error("下载失败")
                    val total = body.contentLength().coerceAtLeast(0)
                    body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            var reported = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                done += read
                                if (done - reported >= 256 * 1024) {
                                    db.downloads().progress(job.id, done, total)
                                    reported = done
                                }
                            }
                        }
                    }
                    false
                }
                if (!expired) break
                refreshed = true
                if (dlsiteTrack) {
                    val signed = signedTrack(track)
                    url = signed.url
                    playCookie = signed.cookie
                } else {
                    url = resolveUrl(track, refresh = true)
                }
            }
            if (dest.exists()) dest.delete()
            if (!partial.renameTo(dest)) error("无法保存文件")
            db.tracks().updateLocalUri(track.id, dest.absolutePath)
            val size = dest.length()
            db.downloads().finish(job.id, size, size)
        } catch (error: Exception) {
            db.downloads().fail(job.id, error.message ?: "下载失败")
        }
    }

    private suspend fun downloadRemote(job: DownloadEntity) {
        val workKey = job.workKey?.ifBlank { null } ?: "work"
        val rootDir = if (job.source == TrackSource.ASMR.name) root else dlsiteRoot
        val dest = File(rootDir, "$workKey/${fileName(job)}")
        dest.parentFile?.mkdirs()
        val partial = File(dest.absolutePath + ".part")
        var url = job.remoteUrl ?: error("没有可下载的地址")
        var refreshed = false
        val referer = job.referer?.ifBlank { null } ?: DlsiteClient.PLAY_REFERER
        val authed = job.source == TrackSource.DLSITE.name && !job.fileKey.isNullOrBlank()
        var playCookie = if (authed) dlsite.playCookie() else null
        if (authed) {
            val signed = dlsite.signedPlayFileBlocking(workKey, job.fileKey.orEmpty())
            url = signed.url
            playCookie = signed.cookie
        }
        while (true) {
            if (partial.exists()) partial.delete()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", if (authed) DlsiteClient.USER_AGENT else AsmrClient.USER_AGENT)
                .header("Referer", referer)
                .apply {
                    if (authed) playCookie?.let { header("Cookie", it) }
                    if (job.source == TrackSource.ASMR.name) header("Origin", AsmrClient.ORIGIN)
                }
                .build()
            val expired = asmr.httpClient().newCall(request).execute().use { response ->
                if (response.code == 401) error("登录已失效，请重新登录")
                if ((response.code == 403 || response.code == 404) && !refreshed && authed) return@use true
                if (!response.isSuccessful) error("下载失败")
                val body = response.body ?: error("下载失败")
                val total = body.contentLength().coerceAtLeast(0)
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        var reported = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            if (done - reported >= 256 * 1024) {
                                db.downloads().progress(job.id, done, total)
                                reported = done
                            }
                        }
                    }
                }
                false
            }
            if (!expired) break
            refreshed = true
            val workno = job.workKey ?: error("没有可下载的地址")
            val fileKey = job.fileKey ?: error("没有可下载的地址")
            val signed = dlsite.signedPlayFileBlocking(workno, fileKey)
            url = signed.url
            playCookie = signed.cookie
        }
        if (dest.exists()) dest.delete()
        if (!partial.renameTo(dest)) error("无法保存文件")
        db.downloads().setLocalPath(job.id, dest.absolutePath)
        val size = dest.length()
        db.downloads().finish(job.id, size, size)
    }

    private suspend fun worknoOf(track: TrackEntity): String? {
        var folderId = track.folderId
        while (folderId != null) {
            val folder = db.folders().get(folderId) ?: break
            if (!folder.asmrSourceId.isNullOrBlank()) return folder.asmrSourceId
            folderId = folder.parentId
        }
        return null
    }

    private fun fileName(job: DownloadEntity): String {
        val fromKey = job.fileKey?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        val base = sanitizeEntryName(job.title)
        return when {
            fileExtension(base).isNotEmpty() -> base
            !fromKey.isNullOrBlank() && fileExtension(fromKey).isNotEmpty() -> {
                val ext = fileExtension(fromKey)
                if (base.endsWith(".$ext")) base else "$base.$ext"
            }
            else -> base
        }
    }

    private suspend fun resolveUrl(track: TrackEntity, refresh: Boolean): String {
        val current = track.remoteUrl
        if (!refresh && !current.isNullOrBlank()) return current
        if (track.source == TrackSource.DLSITE) {
            val signed = signedTrack(track)
            db.tracks().updateRemoteUrl(track.id, signed.url)
            return signed.url
        }
        val workId = track.asmrWorkId ?: error("没有可下载的地址")
        val fresh = AsmrClient.findStream(asmr.trackTree(workId), rawFileKey(track.fileHash), track.title)
            ?: error("没有可下载的地址")
        db.tracks().updateRemoteUrl(track.id, fresh)
        return fresh
    }

    suspend fun downloadedKeys(source: TrackSource, workKey: String): Set<String> = withContext(Dispatchers.IO) {
        val keys = workKeys(workKey)
        buildSet {
            keys.forEach { key ->
                db.downloads().finishedForWork(source.name, key).forEach { row ->
                    val path = row.localPath
                    val fileKey = rawFileKey(row.fileKey) ?: return@forEach
                    if (!path.isNullOrBlank() && File(path).isFile) add(fileKey)
                }
            }
        }
    }

    private suspend fun signedTrack(track: TrackEntity): com.nanahoshi.audioplayer.dlsite.SignedPlayFile {
        val workno = worknoOf(track) ?: error("没有可下载的地址")
        val fileKey = rawFileKey(track.fileHash) ?: error("没有可下载的地址")
        return dlsite.signedPlayFileBlocking(workno, fileKey)
    }

    private suspend fun localCopy(source: String, workKey: String, fileKey: String?): String? {
        if (fileKey.isNullOrBlank()) return null
        return db.downloads().finishedForWork(source, workKey)
            .firstOrNull { rawFileKey(it.fileKey) == fileKey && !it.localPath.isNullOrBlank() && File(it.localPath).isFile }
            ?.localPath
    }

    private fun canonicalWorkKey(workKey: String): String = try {
        com.nanahoshi.audioplayer.dlsite.DlsiteClient.canonicalWorkno(workKey)
    } catch (_: Exception) {
        workKey
    }

    private fun workKeys(workKey: String): List<String> {
        val canonical = canonicalWorkKey(workKey)
        return listOf(workKey, canonical).distinct()
    }

    private suspend fun destination(track: TrackEntity): File {
        val workDir = File(root, (track.asmrWorkId ?: 0L).toString())
        val relative = relativeDirs(track.folderId)
        val fileName = withExtension(sanitizeEntryName(track.title), track.remoteUrl)
        return if (relative.isEmpty()) File(workDir, fileName) else File(workDir, "$relative/$fileName")
    }

    private suspend fun relativeDirs(folderId: Long?): String {
        if (folderId == null) return ""
        val names = ArrayDeque<String>()
        var current = db.folders().get(folderId)
        while (current != null && current.asmrSourceId == null && current.asmrWorkId != null) {
            names.addFirst(sanitizeEntryName(current.name))
            current = current.parentId?.let { db.folders().get(it) }
        }
        return names.joinToString("/")
    }

    private fun withExtension(name: String, url: String?): String {
        if (fileExtension(name).isNotEmpty()) return name
        val remoteExt = url?.substringBefore('?')?.substringAfterLast('.', "").orEmpty()
        if (remoteExt.isBlank() || remoteExt.length > 5 || remoteExt.contains('/')) return name
        return "$name.$remoteExt"
    }

    private fun localFileReady(path: String?): Boolean =
        !path.isNullOrBlank() && path.startsWith("/") && File(path).isFile
}
