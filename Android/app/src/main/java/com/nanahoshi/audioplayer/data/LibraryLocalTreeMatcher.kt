package com.nanahoshi.audioplayer.data

import android.net.Uri
import androidx.room.withTransaction
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.asmr.AsmrWork
import com.nanahoshi.audioplayer.asmr.WorkFacts
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.dlsite.DlsiteClient
import com.nanahoshi.audioplayer.dlsite.PlayEntry
import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.UserFacingException
import com.nanahoshi.audioplayer.text.resolve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 本地文件夹导入与作品目录识别。 */
internal class LibraryLocalTreeMatcher(private val repo: LibraryRepository) {
    private val db get() = repo.db
    private val context get() = repo.context
    private val asmr get() = repo.asmr
    private val dlsite get() = repo.dlsite

    suspend fun importFile(uri: Uri, folderId: Long? = null): TrackEntity = withContext(Dispatchers.IO) {
        upsertLocal(uri, folderId)
    }

    suspend fun importTree(uri: Uri, folderId: Long?, keepStructure: Boolean): Int = withContext(Dispatchers.IO) {
        val root = LocalAudio.readTree(context, uri) ?: return@withContext 0
        val preparedByKey = LinkedHashMap<String, PreparedLocalAudio>()
        if (keepStructure) {
            val prepared = prepareLocalNode(root, preparedByKey)
            db.withTransaction {
                val seen = HashSet<String>()
                val filtered = filterNewLocalNode(prepared, seen) ?: return@withTransaction 0
                insertPreparedNode(filtered, folderId)
            }
        } else {
            val preparedFiles = root.allFiles().mapNotNull { fileUri ->
                prepareLocalAudio(fileUri, preparedByKey)
            }
            if (preparedFiles.isEmpty()) return@withContext 0
            db.withTransaction {
                val seen = HashSet<String>()
                var inserted = 0
                for (item in preparedFiles) {
                    if (!seen.add(item.key)) continue
                    if (db.tracks().findByLocalUri(item.key) != null) continue
                    insertPreparedLocal(item, folderId)
                    inserted++
                }
                inserted
            }
        }
    }

    suspend fun recognizeLocalFolder(folderId: Long, input: String): String = withContext(Dispatchers.IO) {
        val code = input.trim()
        if (code.isEmpty()) throw UserFacingException(TextRef.Res(R.string.library_rj_required))
        val root = db.folders().get(folderId) ?: throw UserFacingException(TextRef.Res(R.string.library_folder_not_found))
        if (root.asmrSourceId != null || root.asmrWorkId != null) throw UserFacingException(TextRef.Res(R.string.library_folder_is_work))
        var parentId = root.parentId
        while (parentId != null) {
            val parent = db.folders().get(parentId) ?: break
            if (parent.asmrSourceId != null || parent.asmrWorkId != null) throw UserFacingException(TextRef.Res(R.string.library_folder_inside_work))
            parentId = parent.parentId
        }
        val local = localLevel(folderId)
        if (!local.hasMatchableFile()) throw UserFacingException(TextRef.Res(R.string.library_no_matchable_files))
        if (repo.folderBySource(code) != null) throw UserFacingException(TextRef.Res(R.string.library_work_already_in_library))
        val dlsiteCatalog = dlsiteCatalog(code)
        var extraPath: String? = null
        if (dlsiteCatalog is DlsiteCatalog.Ready) {
            when (val fit = fitLocalFolder(local, dlsiteMatchTree(dlsiteCatalog.nodes))) {
                is FolderFit.Ready -> {
                    adoptDlsite(root, dlsiteCatalog.workno, fit.assignment)
                    return@withContext context.resolve(recognizeNotice(CatalogSide.Matched, asmrMatched = false))
                }
                is FolderFit.Extra -> extraPath = fit.path
                FolderFit.Empty -> throw UserFacingException(TextRef.Res(R.string.library_no_matchable_files))
            }
        }
        val asmrWork = try {
            asmr.loadWork(code)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (asmrWork != null) {
            if (repo.folderBySource(asmrWork.sourceId) != null) throw UserFacingException(TextRef.Res(R.string.library_work_already_in_library))
            when (val fit = fitLocalFolder(local, asmrMatchTree(asmrWork.nodes))) {
                is FolderFit.Ready -> {
                    adoptAsmr(root, asmrWork, fit.assignment)
                    return@withContext context.resolve(recognizeNotice(CatalogSide.Missed, asmrMatched = true))
                }
                is FolderFit.Extra -> extraPath = fit.path
                FolderFit.Empty -> throw UserFacingException(TextRef.Res(R.string.library_no_matchable_files))
            }
        }
        val side = when (dlsiteCatalog) {
            is DlsiteCatalog.Ready -> CatalogSide.Missed
            DlsiteCatalog.Unreachable -> CatalogSide.Unreachable
            DlsiteCatalog.NotPurchased -> CatalogSide.NotPurchased
        }
        throw UserFacingException(recognizeNotice(side, asmrMatched = false, extraPath = extraPath))
    }

    /**
     * 作品已经在音频库里：把手机上的一个文件夹对照这部作品的文件，对上的曲目挂上本地文件。
     * 曲目 id 不变，磁盘上的文件不动；本地可以少，不能多出作品里没有的。
     */
    suspend fun recognizeLocalTree(workFolderId: Long, treeUri: Uri, preferred: TrackSource?): String =
        withContext(Dispatchers.IO) {
            val root = db.folders().get(workFolderId) ?: throw UserFacingException(TextRef.Res(R.string.library_work_not_found_short))
            if (root.asmrSourceId == null) throw UserFacingException(TextRef.Res(R.string.library_not_a_work))
            repo.backfillContentSources(workFolderId)
            val source = preferred?.takeIf { it in setOf(TrackSource.ASMR, TrackSource.DLSITE) &&
                repo.workHasSource(root.id, it) }
                ?: if (repo.workHasSource(root.id, TrackSource.DLSITE)) TrackSource.DLSITE else TrackSource.ASMR
            val tree = LocalAudio.readTree(context, treeUri)
            if (tree == null || tree.allFiles().isEmpty()) throw UserFacingException(TextRef.Res(R.string.library_no_matchable_files))

            val children = db.folders().all().groupBy { it.parentId }
            suspend fun workLevel(folderId: Long): WorkLevel {
                val files = db.tracks().inFolder(folderId)
                    .filter { it.source == source }
                    .map { WorkFile(it.title, it.id.toString(), null, true) }
                val folders = children[folderId].orEmpty()
                    .filter { it.contentSource == null || it.contentSource == source.name }
                    .map { WorkFolder(it.name, workLevel(it.id)) }
                return WorkLevel(files, folders)
            }
            val work = workLevel(workFolderId)
            if (work.files.isEmpty() && work.folders.isEmpty()) throw UserFacingException(TextRef.Res(R.string.library_work_no_files))

            val picked = HashMap<Long, Uri>()
            var counter = 0L
            fun localLevel(node: AudioNode): LocalLevel = LocalLevel(
                files = node.files.map { uri ->
                    val index = counter++
                    picked[index] = uri
                    LocalMatchFile(LocalAudio.displayName(context, uri), index, null)
                },
                folders = node.children.map { child -> LocalMatchFolder(counter++, child.name, localLevel(child)) },
            )
            when (val fit = fitLocalFolder(localLevel(tree), work)) {
                FolderFit.Empty -> throw UserFacingException(TextRef.Res(R.string.library_no_matchable_files))
                is FolderFit.Extra -> throw UserFacingException(
                    TextRef.Res(R.string.library_folder_extra_content, listOf(fit.path)),
                )
                is FolderFit.Ready -> {
                    var linked = 0
                    var busy = 0
                    try {
                        db.withTransaction {
                            for ((index, remote) in fit.assignment.tracks) {
                                val track = db.tracks().getById(remote.storageKey.toLong()) ?: continue
                                // 已经下载到 App 目录的曲目保留下载副本。
                                if (track.localUri?.startsWith("/") == true) continue
                                val uri = picked.getValue(index).toString()
                                // 同一个本地文件已经挂在别的曲目上时跳过，不覆盖，也不撞唯一索引。
                                val owner = db.tracks().findByLocalUri(uri)
                                if (owner != null && owner.id != track.id) {
                                    busy += 1
                                    continue
                                }
                                db.tracks().updateLocalUri(track.id, uri)
                                linked += 1
                            }
                        }
                    } catch (failure: Exception) {
                        if (failure.hasConstraint()) throw UserFacingException(TextRef.Res(R.string.library_folder_files_owned))
                        throw failure
                    }
                    val name = if (source == TrackSource.DLSITE) "DLsite" else "asmr.one"
                    when {
                        busy == 0 -> context.getString(R.string.library_linked_count, name, linked)
                        linked == 0 -> context.getString(R.string.library_folder_files_owned)
                        else -> context.getString(R.string.library_linked_with_busy, name, linked, busy)
                    }
                }
            }
        }

    private sealed class DlsiteCatalog {
        data class Ready(val workno: String, val nodes: List<PlayEntry>) : DlsiteCatalog()
        data object NotPurchased : DlsiteCatalog()
        data object Unreachable : DlsiteCatalog()
    }

    private suspend fun dlsiteCatalog(code: String): DlsiteCatalog {
        val purchased = try {
            dlsite.isPurchased(code)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error is com.nanahoshi.audioplayer.dlsite.DlsiteException && error.statusCode == 401) throw error
            return if (cannotReachDlsite(error)) DlsiteCatalog.Unreachable else DlsiteCatalog.NotPurchased
        }
        if (!purchased) return DlsiteCatalog.NotPurchased
        val workno = try {
            DlsiteClient.worknoOf(code)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DlsiteCatalog.NotPurchased
        }
        val nodes = try {
            dlsite.playTree(workno, artist = null, coverUrl = null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error is com.nanahoshi.audioplayer.dlsite.DlsiteException && error.statusCode == 401) throw error
            return if (cannotReachDlsite(error)) DlsiteCatalog.Unreachable else DlsiteCatalog.NotPurchased
        }
        return DlsiteCatalog.Ready(workno, nodes)
    }

    private fun cannotReachDlsite(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is java.io.IOException) return true
            if (current is com.nanahoshi.audioplayer.dlsite.DlsiteException && current.statusCode == null) return true
            current = current.cause
        }
        return false
    }

    private suspend fun adoptDlsite(root: FolderEntity, workno: String, assignment: FolderFitReady) {
        val shown = try {
            dlsite.showcase(workno)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val facts = shown?.facts() ?: WorkFacts(
            title = workno,
            circle = "",
            vas = emptyList(),
            tags = emptyList(),
            release = "",
            sourceId = workno,
        )
        val sourceId = facts.sourceId.ifBlank { workno }
        adoptLocalWork(
            root,
            AdoptedWork(
                source = TrackSource.DLSITE,
                sourceId = sourceId,
                title = facts.title.ifBlank { sourceId },
                artist = facts.circle.ifBlank { sourceId },
                coverUrl = shown?.coverUrl,
                workId = sourceId.filter { it.isDigit() }.toLongOrNull() ?: 0L,
                meta = facts.copy(sourceId = sourceId).toJson(),
            ),
            assignment,
        )
    }

    private suspend fun adoptAsmr(root: FolderEntity, work: AsmrWork, assignment: FolderFitReady) {
        adoptLocalWork(
            root,
            AdoptedWork(
                source = TrackSource.ASMR,
                sourceId = work.sourceId,
                title = work.title,
                artist = work.circle.ifBlank { work.sourceId },
                coverUrl = work.coverUrl,
                workId = work.id,
                meta = work.facts().toJson(),
            ),
            assignment,
        )
    }

    private data class AdoptedWork(
        val source: TrackSource,
        val sourceId: String,
        val title: String,
        val artist: String,
        val coverUrl: String?,
        val workId: Long,
        val meta: String,
    )

    private suspend fun adoptLocalWork(root: FolderEntity, work: AdoptedWork, assignment: FolderFitReady) {
        val tracks = if (assignment.tracks.isEmpty()) {
            emptyList()
        } else {
            assignment.tracks.keys.toList().chunked(500).flatMap { db.tracks().getByIds(it) }
        }
        val foreign = tracks.firstOrNull { it.source != TrackSource.LOCAL }
        if (foreign != null) throw UserFacingException(TextRef.Res(R.string.library_not_local_import, listOf(foreign.title)))
        try {
            db.withTransaction {
                db.folders().markAsWork(
                    root.id,
                    sanitizeEntryName("${work.sourceId} ${work.title}"),
                    work.workId,
                    work.sourceId,
                    work.coverUrl,
                    work.meta,
                )
                for (track in tracks) {
                    val remote = assignment.tracks.getValue(track.id)
                    db.tracks().attachWork(
                        track.id,
                        work.source,
                        work.artist,
                        track.coverUri ?: work.coverUrl,
                        remote.streamUrl,
                        work.workId,
                        scopedFileHash(work.source, remote.storageKey),
                    )
                }
                for (folderId in assignment.folderIds) {
                    db.folders().markWorkChild(folderId, work.workId, work.source.name)
                }
                for ((fileId, remote) in assignment.libraryFiles) {
                    val existing = db.files().get(fileId)
                    db.files().attachWork(
                        fileId,
                        work.workId,
                        scopedFileHash(work.source, remote.storageKey),
                        remote.streamUrl ?: existing?.remoteUrl.orEmpty(),
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (failure.hasConstraint()) throw UserFacingException(TextRef.Res(R.string.library_work_already_in_library))
            throw failure
        }
    }

    private suspend fun localLevel(folderId: Long): LocalLevel {
        val folders = db.folders().childrenOf(folderId).map { child ->
            LocalMatchFolder(child.id, child.name, localLevel(child.id))
        }
        val tracks = db.tracks().inFolder(folderId).map { track ->
            LocalMatchFile(localFileName(track), track.id, null)
        }
        val files = db.files().listIn(folderId).map { file ->
            LocalMatchFile(file.name, null, file.id)
        }
        return LocalLevel(tracks + files, folders)
    }

    private fun localFileName(track: TrackEntity): String {
        val raw = track.localUri?.trim().orEmpty()
        if (raw.isNotEmpty()) {
            val display = LocalAudio.displayName(context, Uri.parse(raw)).trim()
            if (display.isNotEmpty()) return display
        }
        return track.title
    }

    private data class PreparedLocalAudio(val key: String, val metadata: LocalMetadata)

    private data class PreparedLocalNode(
        val name: String,
        val files: List<PreparedLocalAudio>,
        val children: List<PreparedLocalNode>,
    )

    private suspend fun prepareLocalAudio(
        uri: Uri,
        cache: MutableMap<String, PreparedLocalAudio>,
    ): PreparedLocalAudio? {
        val key = uri.toString()
        cache[key]?.let { return it }
        if (db.tracks().findByLocalUri(key) != null) return null
        return PreparedLocalAudio(key, LocalAudio.readMetadata(context, uri)).also { cache[key] = it }
    }

    private suspend fun prepareLocalNode(
        node: AudioNode,
        cache: MutableMap<String, PreparedLocalAudio>,
    ): PreparedLocalNode = PreparedLocalNode(
        name = node.name,
        files = node.files.mapNotNull { prepareLocalAudio(it, cache) },
        children = node.children.map { prepareLocalNode(it, cache) },
    )

    private suspend fun filterNewLocalNode(
        node: PreparedLocalNode,
        seen: MutableSet<String>,
    ): PreparedLocalNode? {
        val newFiles = node.files.filter { item ->
            seen.add(item.key) && db.tracks().findByLocalUri(item.key) == null
        }
        val newChildren = node.children.mapNotNull { filterNewLocalNode(it, seen) }
        if (newFiles.isEmpty() && newChildren.isEmpty()) return null
        return PreparedLocalNode(node.name, newFiles, newChildren)
    }

    private suspend fun insertPreparedNode(node: PreparedLocalNode, parentId: Long?): Int {
        val position = db.folders().maxPosition(parentId) + 1
        val id = db.folders().insert(
            FolderEntity(
                parentId = parentId,
                name = node.name,
                position = position,
                addedAt = System.currentTimeMillis(),
            ),
        )
        var count = 0
        for (file in node.files) {
            insertPreparedLocal(file, id)
            count++
        }
        for (child in node.children) {
            count += insertPreparedNode(child, id)
        }
        return count
    }

    private suspend fun insertPreparedLocal(item: PreparedLocalAudio, folderId: Long?): TrackEntity {
        val id = db.tracks().insert(
            TrackEntity(
                source = TrackSource.LOCAL,
                title = item.metadata.title,
                artist = item.metadata.artist,
                durationMs = item.metadata.durationMs,
                coverUri = item.metadata.coverPath,
                localUri = item.key,
                bvid = null,
                cid = null,
                page = null,
                addedAt = System.currentTimeMillis(),
                folderId = folderId,
            ),
        )
        return db.tracks().getById(id) ?: throw UserFacingException(TextRef.Res(R.string.library_save_audio_failed))
    }

    private suspend fun upsertLocal(uri: Uri, folderId: Long?): TrackEntity {
        val key = uri.toString()
        db.tracks().findByLocalUri(key)?.let { return it }
        val metadata = LocalAudio.readMetadata(context, uri)
        return insertPreparedLocal(PreparedLocalAudio(key, metadata), folderId)
    }
}