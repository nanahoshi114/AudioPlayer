package com.nanahoshi.audioplayer.data

import androidx.room.withTransaction
import com.nanahoshi.audioplayer.data.db.LibraryFileEntity
import com.nanahoshi.audioplayer.data.db.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** 音频库本地删除的查询、计划与执行。 */
internal class LibraryRemovalOps(private val repo: LibraryRepository) {
    private val db get() = repo.db

    fun observeLocalRemovalTracks(
        trackIds: Set<Long>,
        folderIds: Set<Long>,
        source: TrackSource?,
    ): Flow<List<TrackEntity>> =
        db.invalidationTracker.createFlow("tracks", "folders", "dlsite_original_works", "dlsite_original_entries")
            .map {
                db.withTransaction { localRemovalSelection(trackIds, folderIds, emptySet(), source).first }
            }

    fun observeLocalRemovalTargets(
        trackIds: Set<Long>,
        folderIds: Set<Long>,
        fileIds: Set<Long>,
        source: TrackSource?,
    ): Flow<LocalRemovalPlan> = db.invalidationTracker.createFlow(
        "tracks", "folders", "library_files", "track_subtitles",
        "dlsite_original_works", "dlsite_original_entries", "japaneseasmr_download_entries",
    ).map {
        buildLocalRemovalPlan(trackIds, folderIds, fileIds, source)
    }.distinctUntilChanged()

    suspend fun localRemovalPlan(
        trackIds: Set<Long>,
        folderIds: Set<Long>,
        fileIds: Set<Long>,
        source: TrackSource?,
    ): LocalRemovalPlan = withContext(Dispatchers.IO) {
        buildLocalRemovalPlan(trackIds, folderIds, fileIds, source)
    }

    private suspend fun buildLocalRemovalPlan(
        trackIds: Set<Long>,
        folderIds: Set<Long>,
        fileIds: Set<Long>,
        source: TrackSource?,
    ): LocalRemovalPlan = db.withTransaction {
        val (tracks, files) = localRemovalSelection(trackIds, folderIds, fileIds, source)
        val filesDir = repo.context.filesDir
        val tracksByRemoval = tracks.groupBy { it.localRemoval(filesDir) }
        val downloads = tracksByRemoval[LocalRemoval.DOWNLOAD].orEmpty()
        val (subtitles, others) = files.filter { it.localRemoval(filesDir) == LocalRemoval.DOWNLOAD }
            .partition { isSubtitleFile(it.name) }
        LocalRemovalPlan(
            audioDownloads = downloads,
            audioAssociations = tracksByRemoval[LocalRemoval.ASSOCIATION].orEmpty(),
            subtitleFiles = subtitles,
            otherFiles = others,
            customSubtitlePaths = exclusiveCustomSubtitlePaths(db, filesDir, downloads.mapTo(mutableSetOf()) { it.id }),
        )
    }

    private suspend fun localRemovalSelection(
        trackIds: Set<Long>, folderIds: Set<Long>, fileIds: Set<Long>, source: TrackSource?,
    ): Pair<List<TrackEntity>, List<LibraryFileEntity>> {
        val originals = repo.originalContentsByFolder()
        val folders = if (folderIds.isNotEmpty() || originals.isNotEmpty()) db.folders().all() else emptyList()
        val parents = folders.associate { it.id to it.parentId }
        fun originalFor(folderId: Long?): LibraryRepository.OriginalContents? =
            generateSequence(folderId) { parents[it] }.firstNotNullOfOrNull { originals[it] }

        val descendants = if (folderIds.isEmpty()) emptySet() else repo.descendantFolderIds(folders.filter {
            it.id in folderIds || it.isVisibleWorkChild(source, originals[it.parentId]?.folders)
        }, folderIds)
        val tracks = (trackIds.chunked(500).flatMap { db.tracks().getByIds(it) } +
            descendants.chunked(500).flatMap { db.tracks().inFolders(it) }).distinctBy { it.id }.filter { track ->
            (source == null || track.source == source) &&
                (track.source != TrackSource.DLSITE || originalFor(track.folderId)?.let { track.id in it.tracks } != false)
        }
        val candidates = (fileIds.chunked(500).flatMap { db.files().getByIds(it) } +
            descendants.chunked(500).flatMap { db.files().inFolders(it) }).distinctBy { it.id }
        val jaFileIds = if (candidates.isEmpty()) emptySet() else db.jaDownloads().fileIds().toSet()
        val files = candidates.filter { file ->
            val fileSource = resolveLibraryFileSource(file, jaFileIds)
            (source == null || fileSource == source) &&
                (fileSource != TrackSource.DLSITE || originalFor(file.folderId)?.let { file.id in it.files } != false)
        }
        return tracks to files
    }

    suspend fun affectedTrackIds(trackIds: List<Long>, folderIds: List<Long>): Set<Long> = withContext(Dispatchers.IO) {
        val folders = db.folders().all()
        val doomedFolders = repo.descendantFolderIds(folders, folderIds.toSet())
        repo.trackIdsIn(doomedFolders) + trackIds
    }

    suspend fun deleteItems(
        trackIds: List<Long>,
        folderIds: List<Long>,
        fileIds: List<Long> = emptyList(),
    ): Unit = withContext(Dispatchers.IO) {
        withContext(NonCancellable) {
            val (detachedBindings, subtitleFileRemoved) = db.withTransaction {
                val folders = db.folders().all()
                val policy = LibraryRemovalPolicy(folders)
                val explicitFiles = fileIds.chunked(900).flatMap { db.files().getByIds(it) }
                val parents = trackIds.chunked(900).flatMap { db.tracks().getByIds(it) }.map { it.folderId } +
                    explicitFiles.map { it.folderId }
                if (!policy.allows(parents, folderIds.toSet())) throw LibraryRemovalRejected()
                val removedFolders = repo.descendantFolderIds(folders, folderIds.toSet())
                val removedTracks = repo.trackIdsIn(removedFolders) + trackIds
                val removedFiles = (removedFolders.chunked(900).flatMap { db.files().inFolders(it) } + explicitFiles)
                    .distinctBy { it.id }
                val trackBindings = removedTracks.chunked(900).flatMap { db.trackSubtitles().getForTracks(it) }
                val fileBindings = removedFiles.map { it.id }.chunked(900).flatMap { db.trackSubtitles().getForFileIds(it) }
                val detached = (trackBindings + fileBindings).distinctBy { it.id }
                val subtitleRemoved = removedFiles.any { isSubtitleFile(it.name) }
                for (job in db.downloads().active()) {
                    if (job.trackId in removedTracks || job.workFolderId in removedFolders) {
                        db.downloads().stopActive(job.id, DOWNLOAD_LIBRARY_REQUIRED)
                    }
                }
                removedFolders.chunked(900).forEach { db.downloads().invalidateJaWorkVerification(it) }
                folderIds.forEach { db.folders().delete(it) }
                trackIds.forEach { db.tracks().delete(it) }
                fileIds.forEach { db.files().delete(it) }
                repo.compactQueue()
                detached to subtitleRemoved
            }
            repo.onBindingsDetached?.invoke(detachedBindings, subtitleFileRemoved)
        }
    }

    suspend fun deleteFile(id: Long) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val file = db.files().get(id) ?: return@withTransaction
            if (!LibraryRemovalPolicy(db.folders().all()).canRemoveEntry(file.folderId)) throw LibraryRemovalRejected()
            db.files().delete(id)
        }
    }

    suspend fun deleteTrack(id: Long) = withContext(Dispatchers.IO) {
        deleteItems(listOf(id), emptyList())
    }
}