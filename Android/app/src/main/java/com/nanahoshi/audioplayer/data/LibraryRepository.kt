package com.nanahoshi.audioplayer.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.asmr.AsmrClient
import com.nanahoshi.audioplayer.asmr.AsmrWork
import com.nanahoshi.audioplayer.bilibili.BiliPart
import com.nanahoshi.audioplayer.bilibili.BilibiliClient
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.data.db.DlsiteOriginalEntryEntity
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.JapaneseAsmrPostEntity
import com.nanahoshi.audioplayer.data.db.LibraryFileEntity
import com.nanahoshi.audioplayer.data.db.PlaylistEntity
import com.nanahoshi.audioplayer.data.db.PlaylistTrack
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.data.db.TrackSubtitleBindingEntity
import com.nanahoshi.audioplayer.dlsite.DlsiteClient
import com.nanahoshi.audioplayer.dlsite.RemotePlayable
import com.nanahoshi.audioplayer.japaneseasmr.JaArticle
import com.nanahoshi.audioplayer.japaneseasmr.JapaneseAsmrClient
import com.nanahoshi.audioplayer.japaneseasmr.JaPostRef
import com.nanahoshi.audioplayer.playback.QueuePlayable
import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.UserFacingException
import com.nanahoshi.audioplayer.text.resolve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class WorkFill {
    UNCHANGED,
    FILLED,
    EMPTY,
}

internal fun FolderEntity.isVisibleWorkChild(source: TrackSource?, originalFolders: Set<Long>?): Boolean =
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

internal fun Throwable.hasConstraint(): Boolean =
    this is android.database.sqlite.SQLiteConstraintException || cause?.hasConstraint() == true

class LibraryRepository(
    internal val context: Context,
    internal val db: AppDatabase,
    internal val bilibili: BilibiliClient,
    internal val asmr: AsmrClient,
    internal val dlsite: DlsiteClient,
    internal val japaneseAsmr: JapaneseAsmrClient = JapaneseAsmrClient(),
) {
    private val sourcePublisher = LibrarySourcePublisher(this)
    internal val biliLibrary = BiliLibraryStore(context, db, ::upsertBili)

    internal var onBindingsDetached: (suspend (detached: List<TrackSubtitleBindingEntity>, invalidateSubtitles: Boolean) -> Unit)? = null

    suspend fun importBili(request: com.nanahoshi.audioplayer.bilibili.BiliImportRequest): List<TrackEntity> = biliLibrary.save(request)

    internal val youtubeLock = kotlinx.coroutines.sync.Mutex()

    /** 作品导入串行化：前置检查必须在拿锁后重新生效，否则两个入口会同时填充同一作品。 */
    internal val workImportLock = kotlinx.coroutines.sync.Mutex()

    internal val jaWorkFiles = JaWorkFiles(db, context.filesDir)

    private val workImporter = LibraryWorkImporter(this)
    private val localMatcher = LibraryLocalTreeMatcher(this)
    private val removalOps = LibraryRemovalOps(this)
    private val playlistOps = LibraryPlaylistQueueOps(this)

    private suspend fun upsertBili(part: BiliPart, folderId: Long?): TrackEntity =
        sourcePublisher.upsertBili(part, folderId)

    suspend fun saveYouTube(video: com.nanahoshi.audioplayer.youtube.YouTubeVideo): TrackEntity =
        sourcePublisher.saveYouTube(video)

    fun observeTracks(): Flow<List<TrackEntity>> = db.tracks().observeAll()

    fun observeAllFolders(): Flow<List<FolderEntity>> = db.folders().observeAll()

    suspend fun allFolders(): List<FolderEntity> = withContext(Dispatchers.IO) {
        db.folders().all()
    }

    fun observeAllFiles(): Flow<List<LibraryFileEntity>> = db.files().observeAll()

    suspend fun allFiles(): List<LibraryFileEntity> = withContext(Dispatchers.IO) { db.files().observeAll().first() }

    internal fun observeJaFileIds(): Flow<Set<Long>> =
        db.invalidationTracker.createFlow("japaneseasmr_download_entries")
            .map { db.jaDownloads().fileIds().toSet() }
            .distinctUntilChanged()

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

    internal suspend fun storedAudioTrack(trackId: Long?, path: String): TrackEntity? = withContext(Dispatchers.IO) {
        val byId = trackId?.takeIf { it > 0L }?.let { db.tracks().getById(it) }
        if (byId != null && byId.localUri == path) byId else db.tracks().findByLocalUri(path)
    }

    internal suspend fun jaPostAndMediaKind(postId: String, key: String): Pair<com.nanahoshi.audioplayer.data.db.JapaneseAsmrPostEntity, String?>? =
        withContext(Dispatchers.IO) {
            val post = db.japaneseAsmr().post(postId) ?: return@withContext null
            val kind = db.japaneseAsmr().media(postId, key)?.kind
            post to kind
        }

    suspend fun tracksIn(folderId: Long?): List<TrackEntity> = withContext(Dispatchers.IO) {
        filterOriginalTracks(folderId, db.tracks().inFolder(folderId))
    }

    suspend fun ensureWorkFiles(folderId: Long): WorkFill = workImportLock.withLock { workImporter.fillWorkFiles(folderId) }

    suspend fun prepareWorkSources(folderId: Long): WorkSources? = workImporter.prepareWorkSources(folderId)

    suspend fun checkPurchased(folderId: Long): Boolean = workImporter.checkPurchased(folderId)

    suspend fun ensureWorkSource(folderId: Long, source: TrackSource): WorkFill =
        workImportLock.withLock { workImporter.fillWorkSource(folderId, source) }

    suspend fun ensureWorkMeta(folderId: Long) = workImporter.ensureWorkMeta(folderId)

    fun observeFilesIn(folderId: Long?): Flow<List<LibraryFileEntity>> = combine(
        db.files().observeIn(folderId), db.dlsiteOriginals().observeEntries(), observeJaFileIds(),
    ) { files, _, jaFileIds ->
        val ids = originalEntriesForFolder(folderId)?.mapNotNull { it.fileId }?.toSet()
        if (ids == null) files else files.filter { resolveLibraryFileSource(it, jaFileIds) != TrackSource.DLSITE || it.id in ids }
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
    ): Flow<List<TrackEntity>> = removalOps.observeLocalRemovalTracks(trackIds, folderIds, source)

    internal fun observeLocalRemovalTargets(
        trackIds: Set<Long>,
        folderIds: Set<Long>,
        fileIds: Set<Long>,
        source: TrackSource?,
    ): Flow<LocalRemovalPlan> = removalOps.observeLocalRemovalTargets(trackIds, folderIds, fileIds, source)

    internal suspend fun localRemovalPlan(
        trackIds: Set<Long>,
        folderIds: Set<Long>,
        fileIds: Set<Long>,
        source: TrackSource?,
    ): LocalRemovalPlan = removalOps.localRemovalPlan(trackIds, folderIds, fileIds, source)

    internal data class OriginalContents(val folders: Set<Long>, val tracks: Set<Long>, val files: Set<Long>)

    internal suspend fun originalContentsByFolder(): Map<Long, OriginalContents> {
        val originals = db.dlsiteOriginals()
        val byWork = originals.allEntries().groupBy { it.workno }.mapValues { (_, entries) ->
            OriginalContents(
                entries.filter { it.kind == "FOLDER" }.mapNotNull { it.folderId }.toSet(),
                entries.mapNotNull { it.trackId }.toSet(),
                entries.mapNotNull { it.fileId }.toSet(),
            )
        }
        return buildMap {
            for (contents in byWork.values) {
                for (id in contents.folders) putIfAbsent(id, contents)
            }
            for (work in originals.works()) {
                val rootId = work.rootFolderId ?: continue
                put(rootId, byWork[work.workno] ?: OriginalContents(emptySet(), emptySet(), emptySet()))
            }
        }
    }

    fun observeFolder(id: Long): Flow<FolderEntity?> = db.folders().observe(id)

    fun observeQueue(): Flow<List<QueuedTrack>> = db.queue().observe()

    fun observePlaylists(): Flow<List<PlaylistEntity>> = db.playlists().observeEditable()

    fun observePlaylist(id: Long): Flow<PlaylistEntity?> = db.playlists().observe(id)

    fun observePlaylistTracks(id: Long): Flow<List<PlaylistTrack>> = db.playlists().observeTracks(id)

    suspend fun importFile(uri: Uri, folderId: Long? = null): TrackEntity = localMatcher.importFile(uri, folderId)

    suspend fun importTree(uri: Uri, folderId: Long?, keepStructure: Boolean): Int =
        localMatcher.importTree(uri, folderId, keepStructure)

    suspend fun folder(id: Long): FolderEntity? = withContext(Dispatchers.IO) {
        db.folders().get(id)
    }

    suspend fun affectedTrackIds(trackIds: List<Long>, folderIds: List<Long>): Set<Long> =
        removalOps.affectedTrackIds(trackIds, folderIds)

    suspend fun deleteItems(
        trackIds: List<Long>,
        folderIds: List<Long>,
        fileIds: List<Long> = emptyList(),
    ): Unit = removalOps.deleteItems(trackIds, folderIds, fileIds)

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
        val byId = orderedIds.chunked(500).flatMap { db.tracks().getByIds(it) }.associateBy { it.id }
        orderedIds.mapNotNull { byId[it] }
    }

    suspend fun recognizeLocalFolder(folderId: Long, input: String): String =
        localMatcher.recognizeLocalFolder(folderId, input)

    suspend fun recognizeLocalTree(workFolderId: Long, treeUri: Uri, preferred: TrackSource?): String =
        localMatcher.recognizeLocalTree(workFolderId, treeUri, preferred)

    suspend fun importAsmr(input: String, parentId: Long?) = workImporter.importAsmr(input, parentId)

    suspend fun folderBySource(sourceId: String): FolderEntity? = workImporter.folderBySource(sourceId)

    internal suspend fun hasSavedAsmr(root: FolderEntity): Boolean = workHasSource(root.id, TrackSource.ASMR)

    suspend fun asmrTrack(workId: Long, hash: String): TrackEntity? = withContext(Dispatchers.IO) {
        db.tracks().findAsmr(workId, hash)
    }

    suspend fun asmrFile(workId: Long, hash: String): LibraryFileEntity? = withContext(Dispatchers.IO) {
        db.files().findAsmr(workId, hash)
    }

    /** Reuses the shared work root and adds the available source selected by the caller. */
    suspend fun ensureAsmrWork(work: AsmrWork): Long = workImporter.ensureAsmrWork(work)

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
    ): JaRefreshResult = workImporter.refreshJapaneseAsmrPost(postId, rootId, cleanup, article, currentQueueId)

    internal suspend fun jaQueueMatches(article: JaArticle, queueId: Long, key: String?): Boolean =
        workImporter.jaQueueMatches(article, queueId, key)

    internal suspend fun importJapaneseAsmrPost(
        article: JaArticle,
        expectedRoot: Long? = null,
    ): JapaneseAsmrImport = workImporter.importJapaneseAsmrPost(article, expectedRoot)

    internal suspend fun rememberJapanesePosts(rootId: Long, posts: List<JaPostRef>) =
        workImporter.rememberJapanesePosts(rootId, posts)

    internal suspend fun saveOnlineWork(
        facts: com.nanahoshi.audioplayer.asmr.WorkFacts,
        cover: String?,
        asmrWork: AsmrWork?,
        play: List<com.nanahoshi.audioplayer.dlsite.PlayEntry>,
        candidates: List<JaPostRef>,
        prefer: TrackSource,
        selectedArticle: JaArticle? = null,
        playChecked: Boolean = true,
    ): Long = workImporter.saveOnlineWork(
        facts, cover, asmrWork, play, candidates, prefer, selectedArticle, playChecked,
    )

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

    suspend fun deleteFile(id: Long) = removalOps.deleteFile(id)

    suspend fun importParts(parts: List<BiliPart>, folderId: Long? = null): List<TrackEntity> =
        sourcePublisher.importParts(parts, folderId)

    internal suspend fun completeYouTubeDownload(jobId: Long, videoId: String, path: String) =
        sourcePublisher.completeYouTubeDownload(jobId, videoId, path)

    internal suspend fun completeBiliDownload(jobId: Long, bvid: String, cid: Long, path: String) =
        sourcePublisher.completeBiliDownload(jobId, bvid, cid, path)

    suspend fun deleteTrack(id: Long) = removalOps.deleteTrack(id)

    suspend fun createPlaylist(name: String): Long = playlistOps.createPlaylist(name)

    suspend fun publishBiliPlaylist(name: String, parts: List<BiliPart>): Long? =
        playlistOps.publishBiliPlaylist(name, parts)

    suspend fun renamePlaylist(id: Long, name: String) = playlistOps.renamePlaylist(id, name)

    suspend fun deletePlaylist(id: Long) = playlistOps.deletePlaylist(id)

    suspend fun addTracksToPlaylist(playlistId: Long, trackIds: List<Long>) =
        playlistOps.addTracksToPlaylist(playlistId, trackIds)

    suspend fun removePlaylistItem(playlistId: Long, itemId: Long) =
        playlistOps.removePlaylistItem(playlistId, itemId)

    suspend fun movePlaylistItem(playlistId: Long, from: Int, to: Int) =
        playlistOps.movePlaylistItem(playlistId, from, to)

    suspend fun playlistTracks(playlistId: Long): List<TrackEntity> = playlistOps.playlistTracks(playlistId)

    suspend fun insertQueueAfter(anchorQueueItemId: Long?, trackIds: List<Long>): Pair<Int, List<Long>> =
        playlistOps.insertQueueAfter(anchorQueueItemId, trackIds)

    suspend fun appendQueue(trackIds: List<Long>): List<Long> = playlistOps.appendQueue(trackIds)

    suspend fun replaceQueue(trackIds: List<Long>): List<Long> = playlistOps.replaceQueue(trackIds)

    suspend fun writeMixedQueue(items: List<QueuePlayable>, replace: Boolean): List<Long> =
        playlistOps.writeMixedQueue(items, replace)

    suspend fun replaceRemoteQueue(items: List<RemotePlayable>): List<Long> =
        playlistOps.replaceRemoteQueue(items)

    suspend fun queueSnapshot(): List<QueuedTrack> = playlistOps.queueSnapshot()

    suspend fun firstQueueItemId(trackId: Long): Long? = playlistOps.firstQueueItemId(trackId)

    suspend fun moveQueueItem(from: Int, to: Int) = playlistOps.moveQueueItem(from, to)

    suspend fun removeQueueItem(queueItemId: Long) = playlistOps.removeQueueItem(queueItemId)

    internal suspend fun trackIdsIn(folderIds: Set<Long>): Set<Long> {
        return folderIds.chunked(900).flatMap { db.tracks().inFolders(it) }.map { it.id }.toSet()
    }

    internal fun workIdOf(folder: FolderEntity): Long =
        folder.asmrWorkId ?: folder.asmrSourceId?.filter { it.isDigit() }?.toLongOrNull() ?: 0L

    internal suspend fun workHasSource(rootId: Long, source: TrackSource): Boolean {
        val folders = descendantFolderIds(db.folders().all(), setOf(rootId))
        val jaFileIds = db.jaDownloads().fileIds().toSet()
        return folders.chunked(500).any { ids ->
            db.tracks().inFolders(ids).any { it.source == source } ||
                db.files().inFolders(ids).any { resolveLibraryFileSource(it, jaFileIds) == source }
        }
    }

    internal suspend fun backfillContentSources(rootId: Long) {
        val folders = db.folders().all()
        val children = folders.groupBy { it.parentId }
        val jaFileIds = db.jaDownloads().fileIds().toSet()
        suspend fun visit(id: Long) {
            for (child in children[id].orEmpty()) visit(child.id)
            if (id == rootId) return
            val folder = db.folders().get(id) ?: return
            if (folder.asmrWorkId == null || folder.contentSource != null) return
            val fromTrack = db.tracks().inFolder(id)
                .firstOrNull { it.source == TrackSource.DLSITE || it.source == TrackSource.ASMR }
                ?.source
            val fromFile = db.files().listIn(id).firstNotNullOfOrNull { resolveLibraryFileSource(it, jaFileIds) }
            val fromChild = children[id].orEmpty().firstNotNullOfOrNull { child ->
                db.folders().get(child.id)?.contentSource?.let { runCatching { TrackSource.valueOf(it) }.getOrNull() }
            }
            val chosen = fromTrack ?: fromFile ?: fromChild ?: return
            db.folders().setContentSource(id, chosen.name)
        }
        visit(rootId)
    }

    internal fun descendantFolderIds(folders: List<FolderEntity>, roots: Set<Long>): Set<Long> {
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

    internal suspend fun compactQueue() {
        db.queue().items().forEachIndexed { index, item ->
            db.queue().updatePosition(item.id, index)
        }
    }

    internal suspend fun compactPlaylist(playlistId: Long) {
        db.playlists().items(playlistId).forEachIndexed { index, item ->
            db.playlists().updatePosition(item.id, index)
        }
    }
}