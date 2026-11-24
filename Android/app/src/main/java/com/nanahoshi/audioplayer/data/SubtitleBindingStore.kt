package com.nanahoshi.audioplayer.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.LibraryFileEntity
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.data.db.TrackSubtitleBindingEntity
import com.nanahoshi.audioplayer.data.db.TrackSubtitleKind
import com.nanahoshi.audioplayer.dlsite.DlsiteClient
import com.nanahoshi.audioplayer.japaneseasmr.jaIdentity
import com.nanahoshi.audioplayer.playback.SubtitleSource
import com.nanahoshi.audioplayer.playback.WorkSubtitleCandidate
import com.nanahoshi.audioplayer.playback.WorkSubtitleMatcher
import com.nanahoshi.audioplayer.playback.nearestSubtitleWorkRoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed interface BindExternalResult {
    data object Bound : BindExternalResult
    data object NeedsAppCopy : BindExternalResult
}

data class BatchExternalPair(val trackId: Long, val uri: Uri, val displayName: String)

class SubtitleBindingStore(
    private val context: Context,
    private val db: AppDatabase,
    private val subtitles: SubtitleSource,
) {
    val customSubtitleLock = Mutex()
    private val directory = File(context.filesDir, "custom-subtitles")
    internal val activeStagingNames = ConcurrentHashMap.newKeySet<String>()
    private val dao get() = db.trackSubtitles()
    private val resolver get() = context.contentResolver

    suspend fun bindLibraryFile(trackId: Long, fileId: Long, displayName: String? = null) =
        replace(binding(trackId, TrackSubtitleKind.LIBRARY_FILE, fileId = fileId, displayName = displayName))

    suspend fun bindExternalUri(
        trackId: Long, uri: Uri, displayName: String, treeUri: Uri? = null,
    ): BindExternalResult = bindExternal(listOf(BatchExternalPair(trackId, uri, displayName)), treeUri, treeUri ?: uri)

    suspend fun batchBindExternalTree(pairs: List<BatchExternalPair>, treeUri: Uri): BindExternalResult =
        bindExternal(pairs, treeUri, treeUri)

    private suspend fun bindExternal(
        pairs: List<BatchExternalPair>, treeUri: Uri?, grant: Uri,
    ): BindExternalResult = withContext(Dispatchers.IO) {
        if (pairs.isEmpty()) return@withContext BindExternalResult.Bound
        val rows = pairs.map { pair ->
            val document = if (treeUri != null) documentInTree(treeUri, pair.uri) else pair.uri
            if (document?.scheme != "content") return@withContext BindExternalResult.NeedsAppCopy
            binding(
                pair.trackId, TrackSubtitleKind.EXTERNAL_URI, uri = document.toString(),
                treeUri = treeUri?.toString(), displayName = pair.displayName,
            )
        }
        customSubtitleLock.withLock {
            val wasPersisted = hasGrant(grant)
            if (!wasPersisted) {
                try {
                    resolver.takePersistableUriPermission(grant, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) {
                    return@withLock BindExternalResult.NeedsAppCopy
                } catch (_: IllegalArgumentException) {
                    return@withLock BindExternalResult.NeedsAppCopy
                }
            }
            if (!hasGrant(grant)) return@withLock BindExternalResult.NeedsAppCopy
            withContext(NonCancellable) {
                try {
                    commit(rows)
                } catch (error: Exception) {
                    if (!wasPersisted) releaseIfUnused(grant.toString())
                    throw error
                }
            }
            BindExternalResult.Bound
        }
    }

    suspend fun importAndBindCopy(trackId: Long, uri: Uri, displayName: String): Boolean = withContext(Dispatchers.IO) {
        if (!isSubtitleFile(displayName)) return@withContext false
        val staging = File(directory, ".staging")
        val filename = "${UUID.randomUUID()}.${fileExtension(displayName)}"
        val partial = File(staging, filename)
        val target = File(directory, filename)
        activeStagingNames.add(filename)
        try {
            if (!staging.mkdirs() && !staging.isDirectory) return@withContext false
            val input = resolver.openInputStream(uri) ?: return@withContext false
            input.use { source ->
                partial.outputStream().use { output -> copyExternalBytes(source, output, null) { _, _ -> } }
            }
            currentCoroutineContext().ensureActive()
            // 空文件无法作为字幕读取，按单个失败处理，不中断批量复制。
            if (partial.length() == 0L) return@withContext false
            customSubtitleLock.withLock {
                withContext(NonCancellable) {
                    if (!partial.renameTo(target)) return@withContext false
                    try {
                        commit(listOf(binding(trackId, TrackSubtitleKind.APP_FILE, uri = target.absolutePath, displayName = displayName)))
                        true
                    } catch (error: Exception) {
                        if (dao.countByAppPath(target.absolutePath) == 0) target.delete()
                        throw error
                    }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        } finally {
            partial.delete()
            activeStagingNames.remove(filename)
        }
    }

    suspend fun bindGenerated(trackId: Long) = replace(binding(trackId, TrackSubtitleKind.GENERATED))

    internal suspend fun bindGeneratedIfOlder(trackId: Long, notNewerThan: Long?): Boolean = mutate {
        val existing = dao.getForTrack(trackId) ?: return@mutate false
        if (existing.kind == TrackSubtitleKind.GENERATED ||
            (notNewerThan != null && existing.updatedAt > notNewerThan)
        ) return@mutate false
        commit(listOf(binding(trackId, TrackSubtitleKind.GENERATED)))
        true
    }

    suspend fun bindNone(trackId: Long) = replace(binding(trackId, TrackSubtitleKind.NONE))

    suspend fun clearBinding(trackId: Long) = mutate {
        val previous = db.withTransaction {
            dao.getForTrack(trackId)?.also { dao.deleteForTrack(trackId) }
        }
        if (previous != null) {
            subtitles.invalidate()
            cleanupReplaced(previous)
        }
    }

    suspend fun setOffsetMs(trackId: Long, offsetMs: Long?) = mutate {
        val changed = db.withTransaction {
            val row = dao.getForTrack(trackId) ?: return@withTransaction false
            dao.upsert(row.copy(offsetMs = offsetMs, updatedAt = System.currentTimeMillis()))
            true
        }
        if (changed) subtitles.invalidate()
    }

    suspend fun batchBindLibraryFiles(pairs: List<Pair<Long, Long>>) = mutate {
        commit(pairs.map { (trackId, fileId) -> binding(trackId, TrackSubtitleKind.LIBRARY_FILE, fileId = fileId) })
    }

    suspend fun cleanupOrphans() = mutate {
        val canonicalPaths = dao.allAppPaths().map(::canonicalPath).toSet()
        directory.listFiles().orEmpty().filter { it.isFile && canonicalPath(it.path) !in canonicalPaths }
            .forEach { it.delete() }
        File(directory, ".staging").listFiles().orEmpty().filter { it.isFile && it.name !in activeStagingNames }
            .forEach { it.delete() }
    }

    internal suspend fun cleanupDetached(rows: List<TrackSubtitleBindingEntity>) = mutate {
        rows.distinctBy { row ->
            row.kind to if (row.kind == TrackSubtitleKind.APP_FILE) row.uri?.let(::canonicalPath) else row.treeUri ?: row.uri
        }.forEach { cleanupReplaced(it) }
        subtitles.invalidate()
    }

    suspend fun listWorkSubtitleCandidates(track: TrackEntity): List<WorkSubtitleCandidate> = withContext(Dispatchers.IO) {
        val catalog = loadWorkSubtitleCatalog(db, track.folderId) ?: return@withContext emptyList()
        catalog.match(track, crossSourceFallback = true).candidates
    }

    suspend fun resolveWorkSubtitleTrackIds(rootFolderId: Long, source: TrackSource?): Set<Long> = withContext(Dispatchers.IO) {
        val catalog = loadWorkSubtitleCatalog(db, rootFolderId) ?: return@withContext emptySet()
        val tracks = catalog.tracks.filter { source == null || it.source == source }
        val bindings = tracks.map { it.id }.chunked(900).flatMap { dao.getForTracks(it) }.associateBy { it.trackId }
        val manualFiles = bindings.values.mapNotNull { it.fileId }.distinct().chunked(900)
            .flatMap { db.files().getByIds(it) }.associateBy { it.id }
        // 与 SubtitleSource 一致：trackId 为空的历史任务按本地路径、远端地址或 JA 音轨身份归属。
        val generatedJobs = db.whisper().done().filter {
            it.fingerprint.isNotBlank() && subtitles.hasGeneratedFile(it.fingerprint)
        }
        val generatedTrackIds = generatedJobs.mapNotNull { it.trackId }.toSet()
        val unattached = generatedJobs.filter { it.trackId == null }
        val generatedPaths = unattached.mapNotNull { it.localPath?.takeIf { path -> path.isNotBlank() } }.toSet()
        val generatedUrls = unattached.mapNotNull { it.awaitRemoteUrl?.takeIf { url -> url.isNotBlank() } }.toSet()
        val jaIdentities = tracks.filter { it.source == TrackSource.JAPANESE_ASMR }
            .mapNotNull { track -> db.japaneseAsmr().mediaForTrack(track.id)?.let { track.id to jaIdentity(it.postId, it.mediaKey) } }
            .toMap()
        val grants = resolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
        tracks.filterTo(mutableListOf()) { track ->
            val row = bindings[track.id]
            when (row?.kind) {
                TrackSubtitleKind.NONE -> false
                TrackSubtitleKind.APP_FILE -> row.uri?.let { File(it).isFile } == true
                TrackSubtitleKind.EXTERNAL_URI -> (row.treeUri ?: row.uri) in grants
                TrackSubtitleKind.LIBRARY_FILE -> row.fileId?.let { manualFiles[it] }?.let { librarySubtitleReadable(it, catalog.jaFileIds) } == true
                else -> track.id in generatedTrackIds ||
                    (track.localUri?.let { it in generatedPaths } == true) ||
                    (track.remoteUrl?.let { it in generatedUrls } == true) ||
                    (jaIdentities[track.id]?.let { it in generatedUrls } == true) ||
                    WorkSubtitleMatcher.automaticCandidates(
                        catalog.match(track, subtitles.allowsCrossSourceFallback()), track, catalog.folders,
                    ).isNotEmpty()
            }
        }.mapTo(mutableSetOf()) { it.id }
    }

    /** 字幕文件必须仍能读取：本地文件存在，或至少有可下载的远端来源；不再把失效的本地关联算作可用。 */
    private fun librarySubtitleReadable(file: LibraryFileEntity, jaFileIds: Set<Long>): Boolean {
        val local = file.localUri
        if (!local.isNullOrBlank()) {
            val readable = when {
                local.startsWith("/") -> File(local).isFile
                local.startsWith("file:") -> Uri.parse(local).path?.let { File(it).isFile } == true
                local.startsWith("content:") -> runCatching {
                    resolver.openInputStream(Uri.parse(local))?.use { true } == true
                }.getOrDefault(false)
                else -> false
            }
            if (readable) return true
        }
        if (file.remoteUrl.isBlank()) return false
        return resolveLibraryFileSource(file, jaFileIds).let { it == TrackSource.ASMR || it == TrackSource.DLSITE }
    }

    private suspend fun replace(row: TrackSubtitleBindingEntity) = mutate { commit(listOf(row)) }

    private suspend fun <T> mutate(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        customSubtitleLock.withLock { withContext(NonCancellable) { block() } }
    }

    /** Caller holds customSubtitleLock. */
    internal suspend fun commit(rows: List<TrackSubtitleBindingEntity>) {
        if (rows.isEmpty()) return
        val previous = db.withTransaction {
            rows.distinctBy { it.trackId }.also { require(it.size == rows.size) }.mapNotNull { row ->
                val old = dao.getForTrack(row.trackId)
                dao.upsert(row.copy(
                    id = old?.id ?: 0L,
                    // 重新绑定同一字幕时保留用户微调的时间轴；换目标则照旧清零。
                    offsetMs = row.offsetMs ?: old?.takeIf { sameTarget(it, row) }?.offsetMs,
                ))
                old
            }
        }
        subtitles.invalidate()
        previous.forEach { cleanupReplaced(it) }
    }

    private fun sameTarget(old: TrackSubtitleBindingEntity, row: TrackSubtitleBindingEntity): Boolean =
        old.kind == row.kind && when (row.kind) {
            TrackSubtitleKind.LIBRARY_FILE -> old.fileId == row.fileId
            TrackSubtitleKind.EXTERNAL_URI -> old.uri == row.uri && old.treeUri == row.treeUri
            TrackSubtitleKind.GENERATED -> true
            else -> false
        }

    private suspend fun cleanupReplaced(row: TrackSubtitleBindingEntity) {
        when (row.kind) {
            TrackSubtitleKind.APP_FILE -> row.uri?.let { path ->
                val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return
                if (file.parentFile != directory.canonicalFile || dao.countByAppPath(path) != 0) return
                if (path != file.path && dao.countByAppPath(file.path) != 0) return
                if (dao.allAppPaths().none { canonicalPath(it) == file.path }) file.delete()
            }
            TrackSubtitleKind.EXTERNAL_URI -> (row.treeUri ?: row.uri)?.let { releaseIfUnused(it) }
        }
    }

    private suspend fun releaseIfUnused(uri: String) {
        if (dao.countByUri(uri) != 0 || db.tracks().usesLocalGrant(uri) || db.files().usesLocalGrant(uri)) return
        try {
            resolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // The provider can revoke a grant before the user clears the binding.
        } catch (_: IllegalArgumentException) {
            // 非法或已失效的 URI 不应阻止解绑。
        }
    }

    private fun hasGrant(uri: Uri) =
        resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    private fun documentInTree(tree: Uri, document: Uri): Uri? {
        if (tree.authority != document.authority || !DocumentsContract.isTreeUri(tree)) return null
        return try {
            if (DocumentsContract.isTreeUri(document) &&
                DocumentsContract.getTreeDocumentId(document) != DocumentsContract.getTreeDocumentId(tree)
            ) return null
            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getDocumentId(document))
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun binding(
        trackId: Long, kind: String, fileId: Long? = null, uri: String? = null,
        treeUri: String? = null, displayName: String? = null,
    ) = TrackSubtitleBindingEntity(
        trackId = trackId, kind = kind, fileId = fileId, uri = uri,
        treeUri = treeUri, displayName = displayName, updatedAt = System.currentTimeMillis(),
    )
}

internal class WorkSubtitleCatalog(
    val folders: Map<Long, FolderEntity>,
    val tracks: List<TrackEntity>,
    val files: List<LibraryFileEntity>,
    val jaFileIds: Set<Long>,
    private val activeDlsiteFileIds: Set<Long>?,
) {
    private val tracksByFolder = tracks.groupBy { it.folderId }

    fun match(track: TrackEntity, crossSourceFallback: Boolean) = WorkSubtitleMatcher.match(
        track, folders, files, jaFileIds, activeDlsiteFileIds, crossSourceFallback,
        tracksByFolder[track.folderId].orEmpty(),
    )
}

internal suspend fun loadWorkSubtitleCatalog(db: AppDatabase, folderId: Long?): WorkSubtitleCatalog? {
    folderId ?: return null
    val folders = db.folders().all().associateBy { it.id }
    val root = nearestSubtitleWorkRoot(folderId, folders)
    val ids = if (root == null) listOf(folderId) else folders.keys.filter {
        nearestSubtitleWorkRoot(it, folders)?.id == root.id
    }
    val rj = root?.asmrSourceId?.takeIf { it.isNotBlank() }?.let(DlsiteClient::canonicalWorkno)
    val entries = rj?.let { db.dlsiteOriginals().entries(it) }.orEmpty()
    val activeEntries = entries.takeIf { it.isNotEmpty() }
    val activeDlsiteTrackIds = activeEntries?.mapNotNull { it.trackId }?.toSet()
    val activeDlsiteFileIds = activeEntries?.mapNotNull { it.fileId }?.toSet()
    val jaFileIds = rj?.let { db.jaDownloads().entries(it).mapNotNull { entry -> entry.fileId }.toSet() }.orEmpty()
    return WorkSubtitleCatalog(
        folders,
        ids.chunked(900).flatMap { db.tracks().inFolders(it) }.filter {
            it.source != TrackSource.DLSITE || activeDlsiteTrackIds?.contains(it.id) != false
        },
        ids.chunked(900).flatMap { db.files().inFolders(it) }.filter {
            isSubtitleFile(it.name) && (resolveLibraryFileSource(it, jaFileIds) != TrackSource.DLSITE ||
                activeDlsiteFileIds?.contains(it.id) != false)
        },
        jaFileIds,
        activeDlsiteFileIds,
    )
}
