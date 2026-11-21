package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.UserFacingException
import com.nanahoshi.audioplayer.data.db.JapaneseAsmrMediaEntity
import com.nanahoshi.audioplayer.data.db.JapaneseAsmrPostEntity
import com.nanahoshi.audioplayer.japaneseasmr.JaArticle
import com.nanahoshi.audioplayer.japaneseasmr.JaPostRef
import com.nanahoshi.audioplayer.japaneseasmr.JapaneseAsmrClient
import com.nanahoshi.audioplayer.japaneseasmr.resolveStoredJaMatches
import com.nanahoshi.audioplayer.japaneseasmr.JaMatchResult
import com.nanahoshi.audioplayer.japaneseasmr.resolveQueuedJaMatch
import com.nanahoshi.audioplayer.japaneseasmr.matchOldJaQueue
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.playback.biliLocalCache
import com.nanahoshi.audioplayer.text.PersistedErrors
import com.nanahoshi.audioplayer.text.resolve
import kotlinx.serialization.json.Json
import com.nanahoshi.audioplayer.youtube.YouTubeVideo
import com.nanahoshi.audioplayer.youtube.readyYouTubeAudio

import android.content.Context
import android.net.Uri
import java.io.File
import com.nanahoshi.audioplayer.asmr.AsmrClient
import com.nanahoshi.audioplayer.asmr.AsmrNode
import com.nanahoshi.audioplayer.asmr.AsmrWork
import com.nanahoshi.audioplayer.asmr.WorkFacts
import com.nanahoshi.audioplayer.bilibili.BiliPart
import com.nanahoshi.audioplayer.dlsite.DlsiteClient
import com.nanahoshi.audioplayer.dlsite.PlayEntry
import com.nanahoshi.audioplayer.dlsite.RemotePlayable
import com.nanahoshi.audioplayer.bilibili.BilibiliClient
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.data.db.DlsiteOriginalEntryEntity
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.LibraryFileEntity
import com.nanahoshi.audioplayer.playback.queueRow
import com.nanahoshi.audioplayer.data.db.PlaylistEntity
import com.nanahoshi.audioplayer.data.db.PlaylistItemEntity
import com.nanahoshi.audioplayer.data.db.PlaylistTrack
import com.nanahoshi.audioplayer.data.db.QueueItemEntity
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import com.nanahoshi.audioplayer.data.db.TrackEntity
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class WorkFill {
    UNCHANGED,
    FILLED,
    EMPTY,
}

private fun FolderEntity.isVisibleWorkChild(source: TrackSource?, originalFolders: Set<Long>?): Boolean =
    (source == null || contentSource == source.name || asmrWorkId == null) &&
        (originalFolders == null || contentSource != TrackSource.DLSITE.name || id in originalFolders)

internal fun validWorkPath(
    ids: List<Long>,
    folders: Map<Long, FolderEntity>,
    source: TrackSource? = null,
    originalFolders: Map<Long, Set<Long>> = emptyMap(),
): List<FolderEntity> = buildList {
    for (id in ids) {
        val folder = folders[id] ?: break
        val parent = lastOrNull()
        if (parent != null && (folder.parentId != parent.id ||
                !folder.isVisibleWorkChild(source, originalFolders[parent.id]))) break
        add(folder)
    }
}

/** [purchased] 为 null 表示还没拿到 DLsite 已购列表。 */
data class WorkSources(
    val purchased: Boolean?,
    val hasPlay: Boolean,
    val hasAsmr: Boolean,
    val hasJapaneseAsmr: Boolean = false,
)

class LibraryRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val bilibili: BilibiliClient,
    private val asmr: AsmrClient,
    private val dlsite: DlsiteClient,
    internal val japaneseAsmr: JapaneseAsmrClient = JapaneseAsmrClient(),
) {
    internal val biliLibrary = BiliLibraryStore(context, db, ::upsertBili)

    suspend fun importBili(request: com.nanahoshi.audioplayer.bilibili.BiliImportRequest): List<TrackEntity> = biliLibrary.save(request)

    private val youtubeLock = kotlinx.coroutines.sync.Mutex()

    /** 作品导入串行化：前置检查必须在拿锁后重新生效，否则两个入口会同时填充同一作品。 */
    private val workImportLock = kotlinx.coroutines.sync.Mutex()

    suspend fun saveYouTube(video: YouTubeVideo): TrackEntity = withContext(Dispatchers.IO) {
        youtubeLock.withLock {
            db.withTransaction {
                db.tracks().findYouTube(video.id)?.let { return@withTransaction it }
                val track = TrackEntity(
                    source = TrackSource.YOUTUBE, title = video.title, artist = video.author,
                    durationMs = video.durationMs, coverUri = video.coverUrl,
                    localUri = readyYouTubeAudio(context.filesDir, video.id)?.absolutePath,
                    bvid = null, cid = null, page = null, addedAt = System.currentTimeMillis(),
                    remoteUrl = video.watchUrl, youtubeVideoId = video.id,
                )
                track.copy(id = db.tracks().insert(track))
            }
        }
    }

    fun observeTracks(): Flow<List<TrackEntity>> = db.tracks().observeAll()

    fun observeAllFolders(): Flow<List<FolderEntity>> = db.folders().observeAll()

    suspend fun allFolders(): List<FolderEntity> = withContext(Dispatchers.IO) {
        db.folders().all()
    }

    fun observeAllFiles(): Flow<List<LibraryFileEntity>> = db.files().observeAll()

    fun observeTracksIn(folderId: Long?): Flow<List<TrackEntity>> = combine(
        db.tracks().observeIn(folderId), db.dlsiteOriginals().observeEntries(),
    ) { tracks, _ -> filterOriginalTracks(folderId, tracks) }

    private suspend fun originalEntriesForFolder(folderId: Long?): List<DlsiteOriginalEntryEntity>? {
        if (folderId == null) return null
        val all = db.dlsiteOriginals().allEntries()
        val code = db.dlsiteOriginals().works().firstOrNull { it.rootFolderId == folderId }?.workno
            ?: all.firstOrNull { it.kind == "FOLDER" && it.folderId == folderId }?.workno ?: return null
        return all.filter { it.workno == code }
    }

    private suspend fun filterOriginalTracks(folderId: Long?, tracks: List<TrackEntity>): List<TrackEntity> {
        val entries = originalEntriesForFolder(folderId) ?: return tracks
        val ids = entries.mapNotNull { it.trackId }.toSet()
        return tracks.filter { it.source != TrackSource.DLSITE || it.id in ids }
    }

    suspend fun track(id: Long): TrackEntity? = withContext(Dispatchers.IO) {
        db.tracks().getById(id)
    }

    suspend fun tracksIn(folderId: Long?): List<TrackEntity> = withContext(Dispatchers.IO) {
        filterOriginalTracks(folderId, db.tracks().inFolder(folderId))
    }

    suspend fun ensureWorkFiles(folderId: Long): WorkFill = workImportLock.withLock { fillWorkFiles(folderId) }

    private suspend fun fillWorkFiles(folderId: Long): WorkFill = withContext(Dispatchers.IO) {
        val folder = db.folders().get(folderId) ?: return@withContext WorkFill.UNCHANGED
        val sourceId = folder.asmrSourceId ?: return@withContext WorkFill.UNCHANGED
        if (db.folders().childrenOf(folderId).isNotEmpty()) return@withContext WorkFill.UNCHANGED
        if (db.tracks().inFolder(folderId).isNotEmpty()) return@withContext WorkFill.UNCHANGED
        if (db.files().listIn(folderId).isNotEmpty()) return@withContext WorkFill.UNCHANGED
        val facts = WorkFacts.fromJson(folder.workMeta) ?: WorkFacts(
            title = folder.name,
            circle = "",
            vas = emptyList(),
            tags = emptyList(),
            release = "",
            sourceId = sourceId,
        )
        val workId = folder.asmrWorkId ?: sourceId.filter { it.isDigit() }.toLongOrNull() ?: 0L
        val purchased = try {
            dlsite.isPurchased(sourceId)
        } catch (_: Exception) {
            false
        }
        if (purchased) {
            val nodes = try {
                dlsite.playTree(sourceId, facts.circle.ifBlank { null }, folder.coverUri)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            if (nodes.isNotEmpty()) {
                db.withTransaction { importPlayNodes(nodes, folderId, workId, facts, folder.coverUri, "") }
                return@withContext WorkFill.FILLED
            }
        }
        val loaded = try {
            asmr.loadWork(sourceId)
        } catch (_: Exception) {
            null
        }
        if (loaded != null && loaded.nodes.isNotEmpty()) {
            db.withTransaction { importAsmrNodes(loaded.nodes, folderId, loaded, "") }
            return@withContext WorkFill.FILLED
        }
        WorkFill.EMPTY
    }

    suspend fun prepareWorkSources(folderId: Long): WorkSources? = withContext(Dispatchers.IO) {
        val folder = db.folders().get(folderId) ?: return@withContext null
        val sourceId = folder.asmrSourceId ?: return@withContext null
        backfillContentSources(folderId)
        val hasPlay = workHasSource(folderId, TrackSource.DLSITE)
        WorkSources(
            // 有 Play 文件就不必再问 DLsite；离线时那一步会一直等到超时。
            purchased = if (hasPlay) null else dlsite.cachedPurchased(sourceId),
            hasPlay = hasPlay,
            hasAsmr = workHasSource(folderId, TrackSource.ASMR) || WorkFacts.fromJson(folder.workMeta)?.asmrAvailable == true,
            hasJapaneseAsmr = db.japaneseAsmr().postsForRoot(folderId).isNotEmpty(),
        )
    }

    suspend fun checkPurchased(folderId: Long): Boolean = withContext(Dispatchers.IO) {
        val sourceId = db.folders().get(folderId)?.asmrSourceId ?: return@withContext false
        try {
            dlsite.isPurchased(sourceId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    suspend fun ensureWorkSource(folderId: Long, source: TrackSource): WorkFill =
        workImportLock.withLock { fillWorkSource(folderId, source) }

    private suspend fun fillWorkSource(folderId: Long, source: TrackSource): WorkFill = withContext(Dispatchers.IO) {
        val folder = db.folders().get(folderId) ?: return@withContext WorkFill.UNCHANGED
        val sourceId = folder.asmrSourceId ?: return@withContext WorkFill.UNCHANGED
        val workId = workIdOf(folder)
        if (source == TrackSource.JAPANESE_ASMR) {
            val posts = db.japaneseAsmr().postsForRoot(folderId)
            if (posts.any { post -> db.japaneseAsmr().mediaForPost(post.postId).any { it.trackId?.let { id -> db.tracks().getById(id) } != null } }) {
                return@withContext WorkFill.UNCHANGED
            }
            for (post in posts) {
                val article = try {
                    japaneseAsmr.article(post.postId, post.rj)
                } catch (failure: com.nanahoshi.audioplayer.japaneseasmr.JapaneseAsmrException) {
                    if (failure.failure in setOf(
                            com.nanahoshi.audioplayer.japaneseasmr.JaFailure.MISSING,
                            com.nanahoshi.audioplayer.japaneseasmr.JaFailure.IDENTITY,
                            com.nanahoshi.audioplayer.japaneseasmr.JaFailure.LEGACY,
                        )) continue
                    throw failure
                }
                if (article.media.isNotEmpty()) {
                    db.withTransaction {
                        check(db.folders().get(folderId) != null) { context.getString(R.string.library_work_removed) }
                        fillJapaneseAsmrArticle(folder, article)
                    }
                    return@withContext WorkFill.FILLED
                }
            }
            return@withContext WorkFill.EMPTY
        }
        if (workHasSource(folderId, source)) return@withContext WorkFill.UNCHANGED
        val facts = WorkFacts.fromJson(folder.workMeta) ?: WorkFacts(
            title = folder.name,
            circle = "",
            vas = emptyList(),
            tags = emptyList(),
            release = "",
            sourceId = sourceId,
        )
        if (source == TrackSource.DLSITE) {
            val purchased = try {
                dlsite.isPurchased(sourceId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (!purchased) return@withContext WorkFill.EMPTY
            val nodes = try {
                dlsite.playTree(sourceId, facts.circle.ifBlank { null }, folder.coverUri)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            if (nodes.isEmpty()) return@withContext WorkFill.EMPTY
            db.withTransaction { importPlayNodes(nodes, folderId, workId, facts, folder.coverUri, "") }
            return@withContext WorkFill.FILLED
        }
        val loaded = asmr.loadWork(sourceId)
        if (loaded.nodes.isEmpty()) return@withContext WorkFill.EMPTY
        db.withTransaction { importAsmrNodes(loaded.nodes, folderId, loaded, "") }
        WorkFill.FILLED
    }

    suspend fun ensureWorkMeta(folderId: Long) = withContext(Dispatchers.IO) {
        val folder = db.folders().get(folderId) ?: return@withContext
        val sourceId = folder.asmrSourceId ?: return@withContext
        if (!folder.workMeta.isNullOrBlank()) return@withContext
        db.folders().setWorkMeta(folderId, asmr.facts(sourceId).toJson())
    }

    suspend fun hasAsmrWork(sourceId: String): Boolean = withContext(Dispatchers.IO) {
        db.folders().findBySourceId(sourceId) != null
    }

    fun observeFilesIn(folderId: Long?): Flow<List<LibraryFileEntity>> = combine(
        db.files().observeIn(folderId), db.dlsiteOriginals().observeEntries(),
    ) { files, _ ->
        val ids = originalEntriesForFolder(folderId)?.mapNotNull { it.fileId }?.toSet()
        if (ids == null) files else files.filter { libraryFileSource(it.fileHash) != TrackSource.DLSITE || it.id in ids }
    }

    internal fun observeChildFolders(parentId: Long?, source: TrackSource? = null): Flow<List<FolderEntity>> =
        observeWorkTreeChanges().map {
            db.withTransaction {
                val originalFolders = parentId?.let { originalContentsByFolder()[it]?.folders }
                db.folders().childrenOf(parentId).filter { it.isVisibleWorkChild(source, originalFolders) }
            }
        }.distinctUntilChanged()

    internal fun observeVisibleWorkPath(ids: List<Long>, source: TrackSource?): Flow<List<FolderEntity>> =
        observeWorkTreeChanges().map {
            db.withTransaction {
                val folders = db.folders().getByIds(ids).associateBy { it.id }
                val originals = if (ids.size > 1) originalContentsByFolder().mapValues { it.value.folders } else emptyMap()
                validWorkPath(ids, folders, source, originals)
            }
        }.distinctUntilChanged()

    private fun observeWorkTreeChanges() =
        db.invalidationTracker.createFlow("folders", "dlsite_original_works", "dlsite_original_entries", "japaneseasmr_posts", "japaneseasmr_media",
            "japaneseasmr_download_works", "japaneseasmr_download_entries")

    internal fun observeLocalRemovalTracks(
        trackIds: Set<Long>,
        folderIds: Set<Long>,
        source: TrackSource?,
    ): Flow<List<TrackEntity>> =
        db.invalidationTracker.createFlow("tracks", "folders", "dlsite_original_works", "dlsite_original_entries")
            .map {
                db.withTransaction {
                    val tracks = trackIds.chunked(500).flatMap { db.tracks().getByIds(it) }
                    val nested = if (folderIds.isEmpty()) emptyList() else {
                        val originals = originalContentsByFolder()
                        val visible = db.folders().all().filter {
                            it.id in folderIds || it.isVisibleWorkChild(source, originals[it.parentId]?.folders)
                        }
                        val descendants = descendantFolderIds(visible, folderIds)
                        descendants.chunked(500).flatMap { db.tracks().inFolders(it) }.filter { track ->
                            val original = originals[track.folderId]
                            original == null || track.source != TrackSource.DLSITE || track.id in original.tracks
                        }
                    }
                    (tracks + nested).distinctBy { it.id }.filter { source == null || it.source == source }
                }
            }

    private data class OriginalContents(val folders: Set<Long>, val tracks: Set<Long>)

    private suspend fun originalContentsByFolder(): Map<Long, OriginalContents> {
        val originals = db.dlsiteOriginals()
        val byWork = originals.allEntries().groupBy { it.workno }.mapValues { (_, entries) ->
            OriginalContents(
                entries.filter { it.kind == "FOLDER" }.mapNotNull { it.folderId }.toSet(),
                entries.mapNotNull { it.trackId }.toSet(),
            )
        }
        return buildMap {
            for (contents in byWork.values) {
                for (id in contents.folders) putIfAbsent(id, contents)
            }
            for (work in originals.works()) {
                val rootId = work.rootFolderId ?: continue
                put(rootId, byWork[work.workno] ?: OriginalContents(emptySet(), emptySet()))
            }
        }
    }

    fun observeFolder(id: Long): Flow<FolderEntity?> = db.folders().observe(id)

    fun observeQueue(): Flow<List<QueuedTrack>> = db.queue().observe()

    fun observePlaylists(): Flow<List<PlaylistEntity>> = db.playlists().observeEditable()

    fun observePlaylist(id: Long): Flow<PlaylistEntity?> = db.playlists().observe(id)

    fun observePlaylistTracks(id: Long): Flow<List<PlaylistTrack>> = db.playlists().observeTracks(id)

    suspend fun importFile(uri: Uri, folderId: Long? = null): TrackEntity = withContext(Dispatchers.IO) {
        upsertLocal(uri, folderId)
    }

    suspend fun importTree(uri: Uri, folderId: Long?, keepStructure: Boolean): Int = withContext(Dispatchers.IO) {
        val root = LocalAudio.readTree(context, uri) ?: return@withContext 0
        if (keepStructure) {
            importNode(root, folderId)
        } else {
            root.allFiles().map { upsertLocal(it, folderId) }.size
        }
    }

    suspend fun createFolder(parentId: Long?, name: String): Long = withContext(Dispatchers.IO) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) error(context.getString(R.string.library_folder_name_required))
        val position = db.folders().maxPosition(parentId) + 1
        db.folders().insert(
            FolderEntity(
                parentId = parentId,
                name = trimmed,
                position = position,
                addedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun folder(id: Long): FolderEntity? = withContext(Dispatchers.IO) {
        db.folders().get(id)
    }

    suspend fun moveChoices(excludedFolderIds: Set<Long>): List<FolderChoice> = withContext(Dispatchers.IO) {
        val folders = db.folders().all()
        val hidden = descendantFolderIds(folders, excludedFolderIds)
        val byId = folders.associateBy { it.id }
        buildList {
            add(FolderChoice(id = null, label = context.getString(R.string.nav_library)))
            folders.filter { it.id !in hidden }
                .map { folder -> FolderChoice(folder.id, folderPath(folder, byId)) }
                .sortedBy { it.label }
                .forEach(::add)
        }
    }

    suspend fun moveItems(trackIds: List<Long>, folderIds: List<Long>, targetFolderId: Long?) = withContext(Dispatchers.IO) {
        val folders = db.folders().all()
        val movedFolders = descendantFolderIds(folders, folderIds.toSet())
        if (targetFolderId != null && targetFolderId in movedFolders) {
            error(context.getString(R.string.library_move_into_self))
        }
        folderIds.forEach { db.folders().setParent(it, targetFolderId) }
        val carried = trackIdsIn(movedFolders)
        trackIds.filterNot { it in carried }.forEach { db.tracks().setFolder(it, targetFolderId) }
    }

    suspend fun affectedTrackIds(trackIds: List<Long>, folderIds: List<Long>): Set<Long> = withContext(Dispatchers.IO) {
        val folders = db.folders().all()
        val doomedFolders = descendantFolderIds(folders, folderIds.toSet())
        trackIdsIn(doomedFolders) + trackIds
    }

    suspend fun deleteItems(trackIds: List<Long>, folderIds: List<Long>) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val policy = LibraryRemovalPolicy(db.folders().all())
            val parents = trackIds.mapNotNull { db.tracks().getById(it) }.map { it.folderId }
            if (!policy.allows(parents, folderIds.toSet())) throw LibraryRemovalRejected()
            val removedFolders = descendantFolderIds(db.folders().all(), folderIds.toSet())
            val removedTracks = trackIdsIn(removedFolders) + trackIds
            for (job in db.downloads().active()) {
                if (job.trackId in removedTracks || job.workFolderId in removedFolders) {
                    db.downloads().stopActive(job.id, DOWNLOAD_LIBRARY_REQUIRED)
                }
            }
            if (removedFolders.isNotEmpty()) db.downloads().invalidateJaWorkVerification(removedFolders.toList())
            folderIds.forEach { db.folders().delete(it) }
            trackIds.forEach { db.tracks().delete(it) }
            compactQueue()
        }
    }

    suspend fun orderedSelection(
        currentFolderId: Long?,
        selectedFolderIds: Set<Long>,
        selectedTrackIds: Set<Long>,
        sort: LibrarySort,
    ): List<TrackEntity> = withContext(Dispatchers.IO) {
        val folders = db.folders().all()
        val byParent = folders.groupBy { it.parentId }
        fun children(parentId: Long?) = byParent[parentId].orEmpty().sortedForLibrary(sort)
        val orderedIds = mutableListOf<Long>()
        val seen = mutableSetOf<Long>()
        fun addTrack(id: Long) {
            if (seen.add(id)) orderedIds += id
        }
        suspend fun expand(folderId: Long) {
            for (child in children(folderId)) expand(child.id)
            for (track in tracksIn(folderId).sortedForLibrary(sort)) addTrack(track.id)
        }
        for (folder in children(currentFolderId)) {
            if (folder.id in selectedFolderIds) expand(folder.id)
        }
        tracksIn(currentFolderId).sortedForLibrary(sort).forEach { track ->
            if (track.id in selectedTrackIds) addTrack(track.id)
        }
        if (orderedIds.isEmpty()) return@withContext emptyList()
        val byId = db.tracks().getByIds(orderedIds).associateBy { it.id }
        orderedIds.mapNotNull { byId[it] }
    }

    suspend fun recognizeLocalFolder(folderId: Long, input: String): String = withContext(Dispatchers.IO) {
        val code = input.trim()
        if (code.isEmpty()) error(context.getString(R.string.library_rj_required))
        val root = db.folders().get(folderId) ?: error(context.getString(R.string.library_folder_not_found))
        if (root.asmrSourceId != null || root.asmrWorkId != null) error(context.getString(R.string.library_folder_is_work))
        var parentId = root.parentId
        while (parentId != null) {
            val parent = db.folders().get(parentId) ?: break
            if (parent.asmrSourceId != null || parent.asmrWorkId != null) error(context.getString(R.string.library_folder_inside_work))
            parentId = parent.parentId
        }
        val local = localLevel(folderId)
        if (!local.hasMatchableFile()) error(context.getString(R.string.library_no_matchable_files))
        if (folderBySource(code) != null) error(context.getString(R.string.library_work_already_in_library))
        val dlsiteCatalog = dlsiteCatalog(code)
        var extraPath: String? = null
        if (dlsiteCatalog is DlsiteCatalog.Ready) {
            when (val fit = fitLocalFolder(local, dlsiteMatchTree(dlsiteCatalog.nodes))) {
                is FolderFit.Ready -> {
                    adoptDlsite(root, dlsiteCatalog.workno, fit.assignment)
                    return@withContext context.resolve(recognizeNotice(CatalogSide.Matched, asmrMatched = false))
                }
                is FolderFit.Extra -> extraPath = fit.path
                FolderFit.Empty -> error(context.getString(R.string.library_no_matchable_files))
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
            if (folderBySource(asmrWork.sourceId) != null) error(context.getString(R.string.library_work_already_in_library))
            when (val fit = fitLocalFolder(local, asmrMatchTree(asmrWork.nodes))) {
                is FolderFit.Ready -> {
                    adoptAsmr(root, asmrWork, fit.assignment)
                    return@withContext context.resolve(recognizeNotice(CatalogSide.Missed, asmrMatched = true))
                }
                is FolderFit.Extra -> extraPath = fit.path
                FolderFit.Empty -> error(context.getString(R.string.library_no_matchable_files))
            }
        }
        val side = when (dlsiteCatalog) {
            is DlsiteCatalog.Ready -> CatalogSide.Missed
            DlsiteCatalog.Unreachable -> CatalogSide.Unreachable
            DlsiteCatalog.NotPurchased -> CatalogSide.NotPurchased
        }
        error(context.resolve(recognizeNotice(side, asmrMatched = false, extraPath = extraPath)))
    }

    /**
     * 作品已经在音频库里：把手机上的一个文件夹对照这部作品的文件，对上的曲目挂上本地文件。
     * 曲目 id 不变，磁盘上的文件不动；本地可以少，不能多出作品里没有的。
     */
    suspend fun recognizeLocalTree(workFolderId: Long, treeUri: Uri, preferred: TrackSource?): String =
        withContext(Dispatchers.IO) {
            val root = db.folders().get(workFolderId) ?: throw UserFacingException(TextRef.Res(R.string.library_work_not_found_short))
            if (root.asmrSourceId == null) throw UserFacingException(TextRef.Res(R.string.library_not_a_work))
            backfillContentSources(workFolderId)
            val source = preferred?.takeIf { it in setOf(TrackSource.ASMR, TrackSource.DLSITE) &&
                workHasSource(root.id, it) }
                ?: if (workHasSource(root.id, TrackSource.DLSITE)) TrackSource.DLSITE else TrackSource.ASMR
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

    suspend fun importAsmr(input: String, parentId: Long?) = withContext(Dispatchers.IO) {
        workImportLock.withLock {
            try {
                val work = asmr.loadWork(input)
                if (db.folders().findBySourceId(work.sourceId) != null) error(context.getString(R.string.library_already_in_library))
                insertAsmrWork(work, parentId)
                restoreOriginalCopy(work.sourceId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (failure.hasConstraint()) error(context.getString(R.string.library_work_already_in_library))
                throw failure
            }
        }
    }

    suspend fun folderBySource(sourceId: String): FolderEntity? = withContext(Dispatchers.IO) {
        db.folders().findBySourceId(sourceId)?.let { return@withContext it }
        val key = DlsiteClient.canonicalWorkno(sourceId)
        db.folders().all().firstOrNull { folder ->
            val stored = folder.asmrSourceId ?: return@firstOrNull false
            DlsiteClient.canonicalWorkno(stored) == key
        }
    }

    suspend fun importPlayWork(facts: WorkFacts, nodes: List<PlayEntry>, coverUrl: String?) =
        withContext(Dispatchers.IO) {
            workImportLock.withLock {
                try {
                    if (folderBySource(facts.sourceId) != null) error(context.getString(R.string.library_already_in_library))
                    val workId = facts.sourceId.filter { it.isDigit() }.toLongOrNull() ?: 0L
                    db.withTransaction {
                        val rootId = db.folders().insert(
                            FolderEntity(
                                parentId = null,
                                name = sanitizeEntryName("${facts.sourceId} ${facts.title}"),
                                position = db.folders().maxPosition(null) + 1,
                                addedAt = System.currentTimeMillis(),
                                asmrWorkId = workId,
                                asmrSourceId = facts.sourceId,
                                coverUri = coverUrl,
                                workMeta = facts.toJson(),
                            ),
                        )
                        importPlayNodes(nodes, rootId, workId, facts, coverUrl, "")
                    }
                    restoreOriginalCopy(facts.sourceId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (failure.hasConstraint()) error(context.getString(R.string.library_work_already_in_library))
                    throw failure
                }
            }
        }

    private suspend fun restoreOriginalCopy(sourceId: String) {
        val key = runCatching { DlsiteClient.canonicalWorkno(sourceId) }.getOrNull() ?: return
        val saved = db.dlsiteOriginals().work(key) ?: return
        val entries = db.dlsiteOriginals().entries(key)
        if (entries.isEmpty() || entries.any { if (it.kind == "FOLDER") !java.io.File(it.localPath).isDirectory else !java.io.File(it.localPath).isFile }) return
        DlsiteOriginalStore(context, db, dlsite, this).importDirectory(key, java.io.File(saved.directory), saved.generation)
    }

    suspend fun childFolders(parentId: Long?): List<FolderEntity> = withContext(Dispatchers.IO) {
        db.folders().childrenOf(parentId)
    }

    suspend fun filesIn(folderId: Long?): List<LibraryFileEntity> = withContext(Dispatchers.IO) {
        db.files().listIn(folderId)
    }

    internal suspend fun hasSavedAsmr(root: FolderEntity): Boolean =
        workHasSource(root.id, TrackSource.ASMR)

    suspend fun asmrTrack(workId: Long, hash: String): TrackEntity? = withContext(Dispatchers.IO) {
        db.tracks().findAsmr(workId, hash)
    }

    suspend fun asmrFile(workId: Long, hash: String): LibraryFileEntity? = withContext(Dispatchers.IO) {
        db.files().findAsmr(workId, hash)
    }

    /** Reuses the shared work root and adds the available source selected by the caller. */
    suspend fun ensureAsmrWork(work: AsmrWork): Long = saveOnlineWork(
        work.facts(), work.coverUrl, work, emptyList(), emptyList(), prefer = TrackSource.ASMR, playChecked = false,
    )

    internal val jaWorkFiles = JaWorkFiles(db, context.filesDir)

    internal data class JapaneseAsmrImport(val rootId: Long, val added: Int, val retained: Int)

    internal data class JaRefreshResult(
        val added: Int,
        val removed: Int,
        val retained: Int,
        val queueIds: List<Long>,
        val playlistItems: Int,
        val complete: Boolean,
    ) {
        fun message(context: Context): String = buildString {
            append(context.getString(R.string.library_ja_change_summary, added, removed, retained))
            if (queueIds.isNotEmpty()) append(context.getString(R.string.library_ja_change_queue, queueIds.size))
            if (playlistItems > 0) append(context.getString(R.string.library_ja_change_playlist, playlistItems))
            if (!complete) append(context.getString(R.string.library_ja_incomplete))
        }
    }

    internal suspend fun refreshJapaneseAsmrPost(
        postId: String,
        rootId: Long,
        cleanup: Boolean,
        article: JaArticle? = null,
        currentQueueId: () -> Long = { -1 },
    ): JaRefreshResult = withContext(Dispatchers.IO) {
        val dao = db.japaneseAsmr()
        val expected = dao.post(postId)?.takeIf { it.rootFolderId == rootId } ?: error(PersistedErrors.JA_POST_REMOVED)
        val updated = article ?: japaneseAsmr.article(postId, expected.rj, refresh = true)
        check(updated.post.postId == postId && updated.post.rj.equals(expected.rj, ignoreCase = true)) { context.getString(R.string.library_ja_post_id_changed) }
        workImportLock.withLock {
            db.withTransaction {
                val root = db.folders().get(rootId)
                check(root?.asmrSourceId?.let(DlsiteClient::canonicalWorkno) == DlsiteClient.canonicalWorkno(expected.rj) &&
                    dao.post(postId)?.rootFolderId == rootId) { PersistedErrors.JA_POST_REMOVED }
                val rows = dao.mediaForPost(postId)
                val matches = resolveStoredJaMatches(updated, postId, dao)
                val visible = updated.media.map { it.key }.toSet()
                val sync = if (updated.media.isEmpty()) null else fillJapaneseAsmrArticle(requireNotNull(root), updated)
                val queue = db.queue().snapshot()
                val stored = resolveStoredJaMatches(updated, postId, dao)
                for (item in queue) {
                    val key = item.fileKey ?: continue
                    if (item.trackId != null || item.workno != postId || item.source != TrackSource.JAPANESE_ASMR) continue
                    val media = resolveQueuedJaMatch(updated, key, item.mediaPathHash, stored, queue).media ?: continue
                    if (item.mediaKind != media.kind.name) db.queue().setJaMediaKind(item.queueItemId, media.kind.name)
                }
                val gone = rows.filter { matches[it.mediaKey] == JaMatchResult.Gone }
                val queueIds = linkedSetOf<Long>()
                var playlistItems = 0
                var removed = 0
                val playlists = mutableSetOf<Long>()
                if (cleanup && updated.complete) {
                    val online = queue.filter {
                        it.source == TrackSource.JAPANESE_ASMR && it.trackId == null &&
                            it.workno == postId && it.fileKey != null
                    }
                    val candidates = matchOldJaQueue(rows, online)
                    val removedKeys = mutableSetOf<String>()
                    if (visible != rows.map { it.mediaKey }.toSet()) {
                        for (media in gone) {
                            val track = media.trackId?.let { db.tracks().getById(it) }
                            if (track == null) {
                                dao.deleteMedia(postId, media.mediaKey)
                                removedKeys += media.mediaKey
                                continue
                            }
                            val related = queue.filter { it.trackId == track.id }.map { it.queueItemId }
                            val onlineRelated = online.filter { media.mediaKey in candidates.getValue(it.queueItemId).mediaKeys }
                            if (track.biliLocalCache(context) != null ||
                                onlineRelated.any { candidates.getValue(it.queueItemId).ambiguous }
                            ) continue
                            val listItems = db.playlists().itemsForTrack(track.id)
                            val current = currentQueueId()
                            if (current in related || onlineRelated.any { it.queueItemId == current }) continue
                            failJaTrackTasks(track.id, related)
                            db.tracks().delete(track.id)
                            dao.deleteMedia(postId, media.mediaKey)
                            removedKeys += media.mediaKey
                            queueIds += related
                            playlistItems += listItems.size
                            playlists += listItems.map { it.playlistId }
                            removed++
                        }
                    }
                    val cachedKeys = if (online.isEmpty()) emptySet() else db.downloads()
                        .finishedForWork(TrackSource.JAPANESE_ASMR.name, postId)
                        .mapNotNull { job ->
                            val file = job.localPath?.let(::File)
                            job.fileKey?.takeIf { file != null && file.isFile && file.length() > 0 }
                        }.toSet()
                    val removableOnline = online.filter { item ->
                        val old = candidates.getValue(item.queueItemId)
                        item.queueItemId != currentQueueId() && !old.ambiguous && item.fileKey !in cachedKeys &&
                            old.mediaKeys.all { it in removedKeys } &&
                            resolveQueuedJaMatch(updated, requireNotNull(item.fileKey), item.mediaPathHash, matches, queue) == JaMatchResult.Gone
                    }.map { it.queueItemId }
                    for (id in removableOnline) {
                        if (id == currentQueueId()) continue
                        db.translations().failForRemovedAudio(null, listOf(id), context.getString(R.string.library_ja_track_invalid))
                        db.queue().delete(id)
                        queueIds += id
                    }
                }
                if (queueIds.isNotEmpty()) compactQueue()
                playlists.forEach { compactPlaylist(it) }
                val retained = rows.count { matches[it.mediaKey]?.media == null } - removed
                JaRefreshResult(sync?.added ?: 0, removed, retained, queueIds.toList(), playlistItems, updated.complete)
            }
        }
    }

    internal suspend fun jaQueueMatches(article: JaArticle, queueId: Long, key: String?): Boolean =
        withContext(Dispatchers.IO) {
            val expected = db.japaneseAsmr().post(article.post.postId)?.rj
            if (expected != null && !expected.equals(article.post.rj, ignoreCase = true)) return@withContext false
            val queue = db.queue().snapshot()
            val item = queue.firstOrNull { it.queueItemId == queueId && it.workno == article.post.postId &&
                it.fileKey == key } ?: return@withContext false
            val stored = resolveStoredJaMatches(article, article.post.postId, db.japaneseAsmr())
            resolveQueuedJaMatch(article, item.fileKey ?: return@withContext false, item.mediaPathHash, stored, queue).media != null
        }

    private suspend fun failJaTrackTasks(trackId: Long, queueIds: List<Long>) {
        val message = context.getString(R.string.library_ja_track_invalid)
        db.downloads().activeForTrack(trackId)?.let { db.downloads().stopActive(it.id, message) }
        db.whisper().failActiveForTrack(trackId, message)
        db.whisper().failTranslationForTrack(trackId, message)
        db.translations().failForRemovedAudio(trackId, queueIds, message)
    }

    internal suspend fun importJapaneseAsmrPost(
        article: JaArticle,
        expectedRoot: Long? = null,
    ): JapaneseAsmrImport = withContext(Dispatchers.IO) {
        require(article.media.isNotEmpty()) { context.getString(R.string.library_article_unavailable) }
        workImportLock.withLock {
            try {
                db.withTransaction {
                    val post = article.post
                    val existing = folderBySource(post.rj)
                    check(expectedRoot == null || existing?.id == expectedRoot) { context.getString(R.string.library_work_removed) }
                    val root = existing ?: createOnlineRoot(
                        WorkFacts(post.title, "", article.voiceActors, emptyList(), "", post.rj), post.coverUri,
                    )
                    fillJapaneseAsmrArticle(root, article).also {
                        jaWorkFiles.restore(root, post.rj)
                    }
                }
            } catch (failure: Exception) {
                if (failure.hasConstraint()) error(context.getString(R.string.library_work_already_in_library))
                throw failure
            }
        }
    }

    internal suspend fun rememberJapanesePosts(rootId: Long, posts: List<JaPostRef>) {
        if (posts.isEmpty()) return
        workImportLock.withLock {
            db.withTransaction {
                val rj = db.folders().get(rootId)?.asmrSourceId ?: return@withTransaction
                val key = DlsiteClient.canonicalWorkno(rj)
                for (post in posts.filter { DlsiteClient.canonicalWorkno(it.rj) == key }.distinctBy { it.postId }) {
                    val saved = db.japaneseAsmr().post(post.postId)
                    if (saved?.rootFolderId != rootId) db.japaneseAsmr().upsertPost(post.entity(rootId))
                }
            }
        }
    }

    internal suspend fun saveOnlineWork(
        facts: WorkFacts,
        cover: String?,
        asmrWork: AsmrWork?,
        play: List<PlayEntry>,
        candidates: List<JaPostRef>,
        prefer: TrackSource,
        selectedArticle: JaArticle? = null,
        playChecked: Boolean = true,
    ): Long = withContext(Dispatchers.IO) {
        val id = workImportLock.withLock {
            val posts = candidates.ifEmpty {
                folderBySource(facts.sourceId)?.let { root -> db.japaneseAsmr().postsForRoot(root.id).map { it.ref() } }.orEmpty()
            }
            val playNodes = if (!playChecked && dlsite.cachedPurchased(facts.sourceId) != false) {
                try {
                    if (dlsite.isPurchased(facts.sourceId)) dlsite.playTree(facts.sourceId, facts.circle, cover)
                    else emptyList()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
            } else play
            var article = selectedArticle?.takeIf { it.media.isNotEmpty() }
            if (article == null && (prefer == TrackSource.JAPANESE_ASMR || asmrWork?.nodes.isNullOrEmpty() && playNodes.isEmpty())) {
                for (post in posts) {
                    try {
                        article = japaneseAsmr.article(post.postId, facts.sourceId).takeIf { it.media.isNotEmpty() }
                        if (article != null) break
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Other articles and sources may still be available.
                    }
                }
            }
            try {
                db.withTransaction {
                    val existing = folderBySource(facts.sourceId)
                    val hasAsmr = !asmrWork?.nodes.isNullOrEmpty()
                    check(existing != null || hasAsmr || playNodes.isNotEmpty() || article != null) { context.getString(R.string.library_no_valid_source) }
                    val sourceFacts = facts.copy(asmrAvailable = hasAsmr || facts.asmrAvailable)
                    val root = existing ?: createOnlineRoot(sourceFacts, cover, asmrWork?.id)
                    if (existing != null && hasAsmr) db.folders().setWorkMeta(root.id, sourceFacts.toJson())
                    (posts + listOfNotNull(article?.post)).distinctBy { it.postId }.forEach { post ->
                        val previous = db.japaneseAsmr().post(post.postId)
                        if (previous?.rootFolderId != root.id) {
                            db.japaneseAsmr().upsertPost(post.entity(root.id))
                        }
                    }
                    val source = when {
                        prefer == TrackSource.JAPANESE_ASMR && article != null -> TrackSource.JAPANESE_ASMR
                        prefer == TrackSource.ASMR && hasAsmr -> TrackSource.ASMR
                        playNodes.isNotEmpty() -> TrackSource.DLSITE
                        hasAsmr -> TrackSource.ASMR
                        article != null -> TrackSource.JAPANESE_ASMR
                        else -> null
                    }
                    val workId = workIdOf(root)
                    when (source) {
                        TrackSource.ASMR -> if (!workHasSource(root.id, TrackSource.ASMR)) {
                            importAsmrNodes(requireNotNull(asmrWork).nodes, root.id, asmrWork, "")
                        }
                        TrackSource.DLSITE -> if (!workHasSource(root.id, TrackSource.DLSITE)) {
                            importPlayNodes(playNodes, root.id, workId, facts, cover, "")
                        }
                        TrackSource.JAPANESE_ASMR -> fillJapaneseAsmrArticle(root, requireNotNull(article))
                        else -> Unit
                    }
                    jaWorkFiles.restore(root, facts.sourceId)
                    root.id
                }
            } catch (failure: Exception) {
                if (failure.hasConstraint()) error(context.getString(R.string.library_work_already_in_library))
                throw failure
            }
        }
        val old = db.dlsiteOriginals().work(DlsiteClient.canonicalWorkno(facts.sourceId))
        if (old != null && old.rootFolderId != id) restoreOriginalCopy(facts.sourceId)
        id
    }

    private suspend fun createOnlineRoot(facts: WorkFacts, cover: String?, workId: Long? = null): FolderEntity {
        val root = FolderEntity(
            name = sanitizeEntryName("${facts.sourceId} ${facts.title}"),
            position = db.folders().maxPosition(null) + 1, addedAt = System.currentTimeMillis(),
            asmrWorkId = workId ?: facts.sourceId.filter(Char::isDigit).toLongOrNull() ?: 0L,
            asmrSourceId = facts.sourceId, coverUri = cover, workMeta = facts.toJson(),
        )
        return root.copy(id = db.folders().insert(root))
    }

    private fun JaPostRef.entity(rootId: Long) = JapaneseAsmrPostEntity(
        postId, rootId, null, rj, title, coverUri, publishedAt, url, null,
    )

    private suspend fun fillJapaneseAsmrArticle(root: FolderEntity, article: JaArticle): JapaneseAsmrImport {
        val dao = db.japaneseAsmr()
        val post = article.post
        val previous = dao.post(post.postId)?.takeIf { it.rootFolderId == root.id }
        val entry = post.entity(root.id).copy(
            coverUri = post.coverUri ?: previous?.coverUri,
            publishedAt = post.publishedAt ?: previous?.publishedAt,
            articleFolderId = previous?.articleFolderId,
            refreshedAt = System.currentTimeMillis(),
        )
        dao.upsertPost(entry)
        val filled = dao.postsForRoot(root.id).filter { candidate ->
            candidate.postId == post.postId || dao.mediaForPost(candidate.postId).any { row ->
                row.trackId?.let { db.tracks().getById(it) } != null
            }
        }
        var destination = root.id
        if (filled.size > 1) {
            for (candidate in filled) {
                val folderId = candidate.articleFolderId?.takeIf { db.folders().get(it)?.parentId == root.id }
                    ?: db.folders().insert(
                        FolderEntity(
                            parentId = root.id, name = sanitizeEntryName(candidate.title),
                            position = db.folders().maxPosition(root.id) + 1, asmrWorkId = root.asmrWorkId,
                            coverUri = candidate.coverUri, contentSource = TrackSource.JAPANESE_ASMR.name,
                        ),
                    )
                dao.upsertPost(candidate.copy(articleFolderId = folderId))
                dao.mediaForPost(candidate.postId).forEach { media ->
                    media.trackId?.let { db.tracks().setFolder(it, folderId) }
                }
                if (candidate.postId == post.postId) destination = folderId
            }
        }
        val old = dao.mediaForPost(post.postId).associateBy { it.mediaKey }
        val matches = resolveStoredJaMatches(article, post.postId, dao)
        val matchedKeys = matches.entries.mapNotNull { (key, match) -> match.media?.let { it.key to key } }.toMap()
        val downloaded = db.downloads().finishedForWork(TrackSource.JAPANESE_ASMR.name, post.postId)
            .sortedByDescending { it.id }.groupBy { it.fileKey }
        var added = 0
        for (media in article.media) {
            if (media.key in article.duplicateKeys && media.key in old) continue
            val key = matchedKeys[media.key] ?: media.key
            val saved = old[key]?.trackId?.let { db.tracks().getById(it) }
            val title = if (media.hasTitle) media.title else saved?.title ?: old[key]?.title ?: media.title
            val availablePath = downloaded[key].orEmpty().firstNotNullOfOrNull { it.localPath?.takeIf { path ->
                java.io.File(path).let { file -> file.isFile && file.length() > 0 }
            } }?.takeIf { db.tracks().findByLocalUri(it) == null }
            val track = saved?.copy(title = title, coverUri = entry.coverUri, folderId = destination,
                localUri = saved.localUri ?: availablePath) ?: TrackEntity(
                source = TrackSource.JAPANESE_ASMR, title = title, artist = article.voiceActors.joinToString(" / ").ifBlank { null },
                durationMs = old[key]?.durationMs ?: 0, coverUri = entry.coverUri,
                localUri = availablePath, bvid = null, cid = null, page = null, addedAt = System.currentTimeMillis(),
                folderId = destination,
            )
            val trackId = if (saved != null) {
                db.tracks().update(track)
                saved.id
            } else {
                added++
                db.tracks().insert(track)
            }
            dao.upsertMedia(JapaneseAsmrMediaEntity(
                post.postId, key, trackId, media.kind.name, title, track.durationMs,
                media.chapters.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it) },
                pathHash = media.pathHash ?: old[key]?.pathHash,
            ))
        }
        return JapaneseAsmrImport(root.id, added, matches.values.count { it.media == null })
    }

    private suspend fun insertAsmrWork(work: AsmrWork, parentId: Long?): Long = db.withTransaction {
        val position = db.folders().maxPosition(parentId) + 1
        val rootId = db.folders().insert(
            FolderEntity(
                parentId = parentId,
                name = sanitizeEntryName("${work.sourceId} ${work.title}"),
                position = position,
                addedAt = System.currentTimeMillis(),
                asmrWorkId = work.id,
                asmrSourceId = work.sourceId,
                coverUri = work.coverUrl,
                workMeta = work.facts().toJson(),
            ),
        )
        importAsmrNodes(work.nodes, rootId, work, "")
        rootId
    }

    suspend fun asmrTracksInTree(folderId: Long): List<TrackEntity> =
        tracksInTree(folderId).filter { it.source in setOf(TrackSource.ASMR, TrackSource.DLSITE, TrackSource.JAPANESE_ASMR) }

    internal suspend fun tracksInTree(folderId: Long): List<TrackEntity> = withContext(Dispatchers.IO) {
        val folders = db.folders().all()
        if (folders.none { it.id == folderId }) return@withContext emptyList()
        val ids = descendantFolderIds(folders, setOf(folderId))
        ids.toList().chunked(500).flatMap { db.tracks().inFolders(it) }
    }

    suspend fun libraryFile(id: Long): LibraryFileEntity? = withContext(Dispatchers.IO) {
        db.files().get(id)
    }

    suspend fun deleteFile(id: Long) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val file = db.files().get(id) ?: return@withTransaction
            if (!LibraryRemovalPolicy(db.folders().all()).canRemoveEntry(file.folderId)) throw LibraryRemovalRejected()
            db.files().delete(id)
        }
    }

    suspend fun lookupBvid(input: String): List<BiliPart> = withContext(Dispatchers.IO) {
        bilibili.lookup(input)
    }

    suspend fun importParts(parts: List<BiliPart>, folderId: Long? = null): List<TrackEntity> = withContext(Dispatchers.IO) {
        db.withTransaction { parts.map { upsertBili(it, folderId) } }
    }

    internal suspend fun completeYouTubeDownload(jobId: Long, videoId: String, path: String) = db.withTransaction {
        val job = db.downloads().get(jobId) ?: error(context.getString(R.string.library_download_task_removed))
        if (job.status != com.nanahoshi.audioplayer.data.db.DownloadStatus.RUNNING) throw CancellationException()
        val track = db.tracks().findYouTube(videoId)?.takeIf { it.id == job.trackId }
            ?: error(DOWNLOAD_LIBRARY_REQUIRED)
        val size = java.io.File(path).length()
        check(size > 0) { context.getString(R.string.library_track_file_missing) }
        db.tracks().updateLocalUri(track.id, path)
        db.downloads().setLocalPath(jobId, path)
        db.downloads().finish(jobId, size, size)
    }

    internal suspend fun completeBiliDownload(jobId: Long, bvid: String, cid: Long, path: String) = db.withTransaction {
        val job = db.downloads().get(jobId) ?: error(context.getString(R.string.library_download_task_removed))
        check(job.status == com.nanahoshi.audioplayer.data.db.DownloadStatus.RUNNING) { context.getString(R.string.library_download_task_ended) }
        val track = db.tracks().findByBvid(bvid, cid)?.takeIf { it.id == job.trackId }
            ?: error(DOWNLOAD_LIBRARY_REQUIRED)
        val size = java.io.File(path).length()
        check(size > 0) { context.getString(R.string.library_track_file_missing) }
        db.tracks().updateLocalUri(track.id, path)
        db.downloads().setLocalPath(jobId, path)
        db.downloads().setPendingBiliSubtitle(jobId, true)
        db.downloads().finish(jobId, size, size)
    }

    suspend fun deleteTrack(id: Long) = withContext(Dispatchers.IO) {
        deleteItems(listOf(id), emptyList())
    }

    suspend fun createPlaylist(name: String): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        db.playlists().insert(PlaylistEntity(name = name.trim(), createdAt = now, updatedAt = now))
    }

    suspend fun publishBiliPlaylist(name: String, parts: List<BiliPart>): Long? = withContext(Dispatchers.IO) {
        db.withTransaction {
            val ids = parts.distinctBy { it.bvid to it.cid }.map { part ->
                checkNotNull(db.tracks().findByBvid(part.bvid, part.cid)) { context.getString(R.string.library_bili_part_track_missing) }.id
            }
            if (ids.isEmpty()) return@withTransaction null
            val id = createPlaylist(name)
            addTracksToPlaylist(id, ids)
            id
        }
    }

    suspend fun renamePlaylist(id: Long, name: String) = withContext(Dispatchers.IO) {
        check(db.collections().get(id) == null) { context.getString(R.string.library_online_playlist_readonly) }
        db.playlists().rename(id, name.trim(), System.currentTimeMillis())
    }

    suspend fun deletePlaylist(id: Long) = withContext(Dispatchers.IO) {
        db.playlists().delete(id)
    }

    suspend fun addTracksToPlaylist(playlistId: Long, trackIds: List<Long>) = withContext(Dispatchers.IO) {
        check(db.collections().get(playlistId) == null) { context.getString(R.string.library_online_playlist_readonly) }
        db.withTransaction {
            val existing = db.playlists().trackIds(playlistId).toSet()
            var position = db.playlists().maxPosition(playlistId)
            trackIds.filterNot { it in existing }.forEach { trackId ->
                position += 1
                db.playlists().insertItem(
                    PlaylistItemEntity(playlistId = playlistId, trackId = trackId, position = position),
                )
            }
            db.playlists().touch(playlistId, System.currentTimeMillis())
        }
    }

    suspend fun removePlaylistItem(playlistId: Long, itemId: Long) = withContext(Dispatchers.IO) {
        check(db.collections().get(playlistId) == null) { context.getString(R.string.library_online_playlist_readonly) }
        db.withTransaction {
            db.playlists().deleteItem(itemId)
            compactPlaylist(playlistId)
            db.playlists().touch(playlistId, System.currentTimeMillis())
        }
    }

    suspend fun movePlaylistItem(playlistId: Long, from: Int, to: Int) = withContext(Dispatchers.IO) {
        check(db.collections().get(playlistId) == null) { context.getString(R.string.library_online_playlist_readonly) }
        db.withTransaction {
            reorder(db.playlists().items(playlistId), from, to) { id, position ->
                db.playlists().updatePosition(id, position)
            }
            db.playlists().touch(playlistId, System.currentTimeMillis())
        }
    }

    suspend fun playlistTracks(playlistId: Long): List<TrackEntity> = withContext(Dispatchers.IO) {
        db.playlists().tracks(playlistId).map { it.toTrack() }
    }

    suspend fun insertQueueAfter(anchorQueueItemId: Long?, trackIds: List<Long>): Pair<Int, List<Long>> =
        withContext(Dispatchers.IO) {
            if (trackIds.isEmpty()) return@withContext 0 to emptyList()
            db.withTransaction {
                val existing = db.queue().items()
                val anchorIndex = existing.indexOfFirst { it.id == anchorQueueItemId }
                val insertAt = if (anchorIndex < 0) 0 else anchorIndex + 1
                val newIds = trackIds.mapIndexed { offset, trackId ->
                    db.queue().insert(QueueItemEntity(trackId = trackId, position = insertAt + offset))
                }
                val ordered = existing.map { it.id }.toMutableList()
                ordered.addAll(insertAt, newIds)
                ordered.forEachIndexed { index, id -> db.queue().updatePosition(id, index) }
                insertAt to newIds
            }
        }

    suspend fun appendQueue(trackIds: List<Long>): List<Long> = withContext(Dispatchers.IO) {
        db.withTransaction {
            var position = db.queue().maxPosition()
            trackIds.map { trackId ->
                position += 1
                db.queue().insert(QueueItemEntity(trackId = trackId, position = position))
            }
        }
    }

    suspend fun replaceQueue(trackIds: List<Long>): List<Long> = withContext(Dispatchers.IO) {
        db.withTransaction {
            db.queue().clear()
            trackIds.mapIndexed { index, trackId ->
                db.queue().insert(QueueItemEntity(trackId = trackId, position = index))
            }
        }
    }

    suspend fun writeMixedQueue(items: List<com.nanahoshi.audioplayer.playback.QueuePlayable>, replace: Boolean): List<Long> = withContext(Dispatchers.IO) {
        db.withTransaction {
            if (replace) db.queue().clear()
            val start = if (replace) 0 else db.queue().maxPosition() + 1
            items.mapIndexed { index, item -> db.queue().insert(item.queueRow(start + index)) }
        }
    }

    suspend fun replaceRemoteQueue(items: List<RemotePlayable>): List<Long> = withContext(Dispatchers.IO) {
        db.withTransaction {
            db.queue().clear()
            items.mapIndexed { index, item ->
                db.queue().insert(
                    QueueItemEntity(
                        position = index,
                        title = item.title,
                        artist = item.artist,
                        coverUri = item.coverUri,
                        remoteUrl = item.remoteUrl,
                        source = item.source.name,
                        referer = item.referer,
                        workno = item.workno,
                        fileKey = item.fileKey,
                        mediaKind = item.mediaKind,
                        mediaPathHash = item.mediaPathHash,
                    ),
                )
            }
        }
    }

    suspend fun queueSnapshot(): List<QueuedTrack> = withContext(Dispatchers.IO) {
        db.queue().snapshot()
    }

    suspend fun firstQueueItemId(trackId: Long): Long? = withContext(Dispatchers.IO) {
        db.queue().firstIdForTrack(trackId)
    }

    suspend fun moveQueueItem(from: Int, to: Int) = withContext(Dispatchers.IO) {
        db.withTransaction {
            reorder(db.queue().items(), from, to) { id, position ->
                db.queue().updatePosition(id, position)
            }
        }
    }

    suspend fun removeQueueItem(queueItemId: Long) = withContext(Dispatchers.IO) {
        db.withTransaction {
            db.queue().delete(queueItemId)
            compactQueue()
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
            db.tracks().getByIds(assignment.tracks.keys.toList())
        }
        val foreign = tracks.firstOrNull { it.source != TrackSource.LOCAL }
        if (foreign != null) error(context.getString(R.string.library_not_local_import, foreign.title))
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
            if (failure.hasConstraint()) error(context.getString(R.string.library_work_already_in_library))
            throw failure
        }
    }

    private fun Throwable.hasConstraint(): Boolean =
        this is android.database.sqlite.SQLiteConstraintException || cause?.hasConstraint() == true

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

    private suspend fun importNode(node: AudioNode, parentId: Long?): Int {
        val position = db.folders().maxPosition(parentId) + 1
        val id = db.folders().insert(
            FolderEntity(
                parentId = parentId,
                name = node.name,
                position = position,
                addedAt = System.currentTimeMillis(),
            ),
        )
        var count = node.files.map { upsertLocal(it, id) }.size
        node.children.forEach { count += importNode(it, id) }
        return count
    }

    private suspend fun upsertLocal(uri: Uri, folderId: Long?): TrackEntity {
        val key = uri.toString()
        db.tracks().findByLocalUri(key)?.let { return it }
        val metadata = LocalAudio.readMetadata(context, uri)
        val id = db.tracks().insert(
            TrackEntity(
                source = TrackSource.LOCAL,
                title = metadata.title,
                artist = metadata.artist,
                durationMs = metadata.durationMs,
                coverUri = metadata.coverPath,
                localUri = key,
                bvid = null,
                cid = null,
                page = null,
                addedAt = System.currentTimeMillis(),
                folderId = folderId,
            ),
        )
        return db.tracks().getById(id) ?: error(context.getString(R.string.library_save_audio_failed))
    }

    private suspend fun importPlayNodes(
        nodes: List<PlayEntry>,
        parentId: Long,
        workId: Long,
        facts: WorkFacts,
        coverUrl: String?,
        path: String,
    ) {
        for (node in nodes) {
            val name = sanitizeEntryName(node.title)
            val here = if (path.isEmpty()) name else "$path/$name"
            if (node.children.isNotEmpty()) {
                val id = db.folders().insert(
                    FolderEntity(
                        parentId = parentId,
                        name = name,
                        position = db.folders().maxPosition(parentId) + 1,
                        addedAt = System.currentTimeMillis(),
                        asmrWorkId = workId,
                        contentSource = TrackSource.DLSITE.name,
                    ),
                )
                importPlayNodes(node.children, id, workId, facts, coverUrl, here)
                continue
            }
            val hash = scopedFileHash(TrackSource.DLSITE, node.audio?.fileKey?.ifBlank { null } ?: here)
            val audio = node.audio
            if (audio != null) {
                if (db.tracks().findAsmr(workId, hash) != null) continue
                db.tracks().insert(
                    TrackEntity(
                        source = TrackSource.DLSITE,
                        title = name,
                        artist = facts.circle.ifBlank { facts.sourceId },
                        durationMs = audio.durationMs,
                        coverUri = coverUrl,
                        localUri = null,
                        bvid = null,
                        cid = null,
                        page = null,
                        addedAt = System.currentTimeMillis(),
                        folderId = parentId,
                        remoteUrl = audio.remoteUrl,
                        asmrWorkId = workId,
                        fileHash = hash,
                    ),
                )
            } else {
                val preview = node.preview ?: continue
                if (db.files().findAsmr(workId, hash) != null) continue
                db.files().insert(
                    LibraryFileEntity(
                        folderId = parentId,
                        name = name,
                        kind = preview.kind,
                        remoteUrl = preview.url,
                        addedAt = System.currentTimeMillis(),
                        asmrWorkId = workId,
                        fileHash = hash,
                    ),
                )
            }
        }
    }

    private suspend fun importAsmrNodes(
        nodes: List<AsmrNode>,
        parentId: Long,
        work: AsmrWork,
        path: String,
    ) {
        for (node in nodes) {
            val name = sanitizeEntryName(node.title)
            val here = if (path.isEmpty()) name else "$path/$name"
            if (node.isContainer()) {
                val position = db.folders().maxPosition(parentId) + 1
                val id = db.folders().insert(
                    FolderEntity(
                        parentId = parentId,
                        name = name,
                        position = position,
                        addedAt = System.currentTimeMillis(),
                        asmrWorkId = work.id,
                        contentSource = TrackSource.ASMR.name,
                    ),
                )
                importAsmrNodes(node.children, id, work, here)
                continue
            }
            val hash = scopedFileHash(TrackSource.ASMR, node.storageKey(path))
            if (isAsmrAudio(node.type, node.title)) {
                if (db.tracks().findAsmr(work.id, hash) != null) continue
                db.tracks().insert(
                    TrackEntity(
                        source = TrackSource.ASMR,
                        title = name,
                        artist = work.circle.ifBlank { work.sourceId },
                        durationMs = node.durationMs,
                        coverUri = work.coverUrl,
                        localUri = null,
                        bvid = null,
                        cid = null,
                        page = null,
                        addedAt = System.currentTimeMillis(),
                        folderId = parentId,
                        remoteUrl = node.streamUrl,
                        asmrWorkId = work.id,
                        fileHash = hash,
                    ),
                )
            } else {
                if (db.files().findAsmr(work.id, hash) != null) continue
                db.files().insert(
                    LibraryFileEntity(
                        folderId = parentId,
                        name = name,
                        kind = previewKind(node.title),
                        remoteUrl = node.streamUrl.orEmpty(),
                        addedAt = System.currentTimeMillis(),
                        asmrWorkId = work.id,
                        fileHash = hash,
                    ),
                )
            }
        }
    }

    private suspend fun upsertBili(part: BiliPart, folderId: Long?): TrackEntity {
        val group = db.folders().findBiliVideo(part.bvid)
        db.tracks().findByBvid(part.bvid, part.cid)?.let { old ->
            val existing = if (group != null && old.folderId != group.id) {
                db.tracks().setFolder(old.id, group.id)
                old.copy(folderId = group.id)
            } else old
            val cached = readyBiliAudio(context.filesDir, part.bvid, part.cid)
            if (cached != null && existing.localUri != cached.absolutePath) {
                db.tracks().updateLocalUri(existing.id, cached.absolutePath)
                return existing.copy(localUri = cached.absolutePath)
            }
            return existing
        }
        val cached = readyBiliAudio(context.filesDir, part.bvid, part.cid)
        val id = db.tracks().insert(
            TrackEntity(
                source = TrackSource.BILIBILI,
                title = part.title,
                artist = part.artist,
                durationMs = part.durationMs,
                coverUri = part.coverUrl,
                localUri = cached?.absolutePath,
                bvid = part.bvid,
                cid = part.cid,
                page = part.page,
                addedAt = System.currentTimeMillis(),
                folderId = group?.id ?: folderId,
            ),
        )
        return db.tracks().getById(id) ?: error(context.getString(R.string.library_save_audio_failed))
    }

    private suspend fun trackIdsIn(folderIds: Set<Long>): Set<Long> {
        if (folderIds.isEmpty()) return emptySet()
        return db.tracks().inFolders(folderIds.toList()).map { it.id }.toSet()
    }

    private fun workIdOf(folder: FolderEntity): Long =
        folder.asmrWorkId ?: folder.asmrSourceId?.filter { it.isDigit() }?.toLongOrNull() ?: 0L

    private suspend fun workHasSource(rootId: Long, source: TrackSource): Boolean {
        val folders = descendantFolderIds(db.folders().all(), setOf(rootId))
        return folders.chunked(500).any { ids ->
            db.tracks().inFolders(ids).any { it.source == source } ||
                db.files().inFolders(ids).any { libraryFileSource(it.fileHash) == source }
        }
    }

    private suspend fun backfillContentSources(rootId: Long) {
        val folders = db.folders().all()
        val children = folders.groupBy { it.parentId }
        suspend fun visit(id: Long) {
            for (child in children[id].orEmpty()) visit(child.id)
            if (id == rootId) return
            val folder = db.folders().get(id) ?: return
            if (folder.asmrWorkId == null || folder.contentSource != null) return
            val fromTrack = db.tracks().inFolder(id)
                .firstOrNull { it.source == TrackSource.DLSITE || it.source == TrackSource.ASMR }
                ?.source
            val fromFile = db.files().listIn(id).firstNotNullOfOrNull { libraryFileSource(it.fileHash) }
            val fromChild = children[id].orEmpty().firstNotNullOfOrNull { child ->
                db.folders().get(child.id)?.contentSource?.let { runCatching { TrackSource.valueOf(it) }.getOrNull() }
            }
            val chosen = fromTrack ?: fromFile ?: fromChild ?: return
            db.folders().setContentSource(id, chosen.name)
        }
        visit(rootId)
    }

    private fun descendantFolderIds(folders: List<FolderEntity>, roots: Set<Long>): Set<Long> {
        if (roots.isEmpty()) return emptySet()
        val children = folders.groupBy { it.parentId }
        val result = mutableSetOf<Long>()
        val stack = ArrayDeque(roots)
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (!result.add(id)) continue
            children[id].orEmpty().forEach { stack.add(it.id) }
        }
        return result
    }

    private fun folderPath(folder: FolderEntity, byId: Map<Long, FolderEntity>): String {
        val names = ArrayDeque<String>()
        var current: FolderEntity? = folder
        while (current != null) {
            names.addFirst(current.name)
            current = current.parentId?.let { byId[it] }
        }
        return names.joinToString(" / ")
    }

    private suspend fun compactQueue() {
        db.queue().items().forEachIndexed { index, item ->
            db.queue().updatePosition(item.id, index)
        }
    }

    private suspend fun compactPlaylist(playlistId: Long) {
        db.playlists().items(playlistId).forEachIndexed { index, item ->
            db.playlists().updatePosition(item.id, index)
        }
    }

    private suspend fun reorder(
        items: List<Any>,
        from: Int,
        to: Int,
        update: suspend (Long, Int) -> Unit,
    ) {
        if (from !in items.indices || to !in items.indices || from == to) return
        val ids = items.map { item ->
            when (item) {
                is QueueItemEntity -> item.id
                is PlaylistItemEntity -> item.id
                else -> error(context.getString(R.string.library_unknown_list_item))
            }
        }.toMutableList()
        val moved = ids.removeAt(from)
        ids.add(to, moved)
        ids.forEachIndexed { index, id -> update(id, index) }
    }
}

data class FolderChoice(
    val id: Long?,
    val label: String,
)
