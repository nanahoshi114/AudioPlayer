package com.nanahoshi.audioplayer.data

import androidx.room.withTransaction
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.data.db.LibraryFileEntity
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.data.db.TrackSubtitleBindingEntity
import com.nanahoshi.audioplayer.playback.SubtitleSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class DeleteResult(val deleted: Int, val unlinked: Int, val changed: Int = 0, val deletedFiles: Int = 0) {
    val total: Int get() = deleted + deletedFiles + unlinked
}

internal enum class LocalRemoval { DOWNLOAD, ASSOCIATION }

internal data class LocalRemovalPlan(
    val audioDownloads: List<TrackEntity> = emptyList(),
    val audioAssociations: List<TrackEntity> = emptyList(),
    val subtitleFiles: List<LibraryFileEntity> = emptyList(),
    val otherFiles: List<LibraryFileEntity> = emptyList(),
    val customSubtitlePaths: List<String> = emptyList(),
)

internal fun TrackEntity.localRemoval(filesDir: File): LocalRemoval? {
    if (source == TrackSource.LOCAL) return null
    val path = localUri ?: return null
    if (path.startsWith("/")) {
        return LocalRemoval.DOWNLOAD.takeIf { ownedLocalFile(filesDir, path)?.isFile == true }
    }
    val userFile = path.startsWith("content:", ignoreCase = true) || path.startsWith("file:", ignoreCase = true)
    return LocalRemoval.ASSOCIATION.takeIf { userFile }
}

internal fun LibraryFileEntity.localRemoval(filesDir: File): LocalRemoval? =
    LocalRemoval.DOWNLOAD.takeIf { localUri?.let { ownedLocalFile(filesDir, it)?.isFile } == true }

private fun ownedLocalFile(filesDir: File, path: String): File? {
    if (!path.startsWith("/")) return null
    return runCatching {
        val root = filesDir.canonicalFile
        File(path).canonicalFile.takeIf { it != root && it.startsWith(root) }
    }.getOrNull()
}

internal suspend fun deleteLocalCopies(
    db: AppDatabase,
    filesDir: File,
    subtitles: SubtitleSource,
    tracks: List<TrackEntity>,
    deleteSubtitles: Boolean,
): DeleteResult {
    val (downloads, associations) = tracks.partition { it.localUri?.startsWith("/") == true }
    return deleteLocalTargets(db, filesDir, subtitles, null,
        LocalRemovalPlan(audioDownloads = downloads, audioAssociations = associations), deleteSubtitles)
}

internal suspend fun deleteLocalTargets(
    db: AppDatabase,
    filesDir: File,
    subtitles: SubtitleSource,
    bindings: SubtitleBindingStore?,
    plan: LocalRemovalPlan,
    deleteSubtitles: Boolean,
): DeleteResult = withContext(Dispatchers.IO) {
    subtitles.generatedWriteLock.withLock {
        val customLock = bindings?.customSubtitleLock?.takeIf { deleteSubtitles && plan.customSubtitlePaths.isNotEmpty() }
        suspend fun remove(): DeleteResult {
            currentCoroutineContext().ensureActive()
            return withContext(NonCancellable) {
                var invalidationNeeded = false
                val result = db.withTransaction {
                    var deleted = 0
                    var unlinked = 0
                    var changed = 0
                    var deletedFiles = 0
                    val removedPaths = mutableSetOf<String>()
                    val removedAudio = mutableListOf<Pair<TrackEntity, String>>()
                    val files = plan.otherFiles + if (deleteSubtitles) plan.subtitleFiles else emptyList()
                    val libraryPaths = if (files.isEmpty()) emptyMap() else libraryPathAliases(db)
                    val fileTargets = files.distinctBy { it.id }.mapNotNull { expected ->
                        val path = expected.localUri
                        val current = db.files().get(expected.id)
                        val file = path?.let { ownedLocalFile(filesDir, it) }
                        if (current == null || current.localUri != path || file == null) {
                            changed++
                            null
                        } else file.path to current
                    }.groupBy({ it.first }, { it.second })
                    val audioTargets = (plan.audioDownloads.map { it to LocalRemoval.DOWNLOAD } +
                        plan.audioAssociations.map { it to LocalRemoval.ASSOCIATION }).distinctBy { it.first.id }
                    // Resolve aliases before removing their common target.
                    val audioPaths = plan.audioDownloads.mapNotNull { it.localUri }.distinct()
                        .mapNotNull { path -> ownedLocalFile(filesDir, path)?.let { path to it } }.toMap()

                    for ((expected, type) in audioTargets) {
                        val path = expected.localUri ?: continue
                        if (expected.source == TrackSource.LOCAL) continue
                        val track = db.tracks().getById(expected.id)
                        if (track == null || track.localUri != path || track.source != expected.source) {
                            changed++
                            continue
                        }
                        val ja = db.jaDownloads().entryForTrack(track.id)
                        when (type) {
                            LocalRemoval.DOWNLOAD -> {
                                val file = audioPaths[path] ?: continue
                                if (file.path !in removedPaths) {
                                    if (!file.isFile || !file.delete()) continue
                                    removedPaths += file.path
                                    deleted++
                                }
                                removedAudio += track to path
                                if (track.source == TrackSource.BILIBILI) {
                                    deleteBiliSubtitle(filesDir, track.bvid, track.cid)
                                    invalidationNeeded = true
                                }
                            }
                            LocalRemoval.ASSOCIATION -> {
                                if (track.localRemoval(filesDir) != type) continue
                                unlinked++
                            }
                        }
                        db.tracks().clearLocalUri(track.id)
                        if (ja == null) db.downloads().deleteFinishedForTrack(track.id)
                    }

                    val removedAudioTrackIds = removedAudio.map { it.first.id }.toSet()
                    if (deleteSubtitles && removedAudio.isNotEmpty()) {
                        for ((track, path) in removedAudio) {
                            subtitles.discardStoredLocked(track.id, path, track.remoteUrl, excludedTrackIds = removedAudioTrackIds)
                        }
                        invalidationNeeded = true
                    }
                    for ((track, path) in removedAudio) {
                        val canonical = audioPaths.getValue(path).path
                        if (db.jaDownloads().entryForTrack(track.id) != null) {
                            db.jaDownloads().clearPath(path)
                            if (canonical != path) db.jaDownloads().clearPath(canonical)
                            db.jaDownloads().clearTrackPath(track.id)
                        } else {
                            db.downloads().deleteFinishedForPath(path)
                            if (canonical != path) db.downloads().deleteFinishedForPath(canonical)
                        }
                    }

                    for ((canonical, targets) in fileTargets) {
                        val expected = targets.first()
                        val path = requireNotNull(expected.localUri)
                        when (deleteLibraryFileInTransaction(db, path, File(canonical), expected.id, true,
                            libraryPaths[canonical].orEmpty(), alreadyRemoved = canonical in removedPaths)) {
                            LibraryFileDeleteOutcome.REMOVED -> {
                                if (removedPaths.add(canonical)) deletedFiles++
                                if (targets.any { isSubtitleFile(it.name) } || isSubtitleFile(path)) invalidationNeeded = true
                            }
                            LibraryFileDeleteOutcome.CHANGED -> changed++
                        }
                    }

                    if (deleteSubtitles && removedAudioTrackIds.isNotEmpty() && plan.customSubtitlePaths.isNotEmpty()) {
                        val targets = plan.customSubtitlePaths.mapNotNull { customSubtitleFile(filesDir, it)?.path }.toSet()
                        for ((path, rows) in exclusiveCustomSubtitleBindings(db, filesDir, removedAudioTrackIds)) {
                            if (path in targets && deleteCustomSubtitleCopy(db, File(path), rows, path in removedPaths)) {
                                invalidationNeeded = true
                            }
                        }
                    }
                    DeleteResult(deleted, unlinked, changed, deletedFiles)
                }
                if (invalidationNeeded) subtitles.invalidate()
                result
            }
        }
        if (customLock == null) remove() else customLock.withLock { remove() }
    }
}

internal enum class LibraryFileDeleteOutcome { REMOVED, CHANGED }

/** Caller holds generatedWriteLock. */
internal suspend fun deleteStoredLibraryFileLocked(
    db: AppDatabase,
    filesDir: File,
    path: String,
    expectedFileId: Long? = null,
    reconcileMissing: Boolean = true,
): LibraryFileDeleteOutcome = db.withTransaction {
    val file = ownedLocalFile(filesDir, path) ?: return@withTransaction LibraryFileDeleteOutcome.CHANGED
    deleteLibraryFileInTransaction(db, path, file, expectedFileId, reconcileMissing, libraryPathAliases(db)[file.path].orEmpty())
}

private suspend fun libraryPathAliases(db: AppDatabase): Map<String, List<String>> =
    (db.files().withLocalUri().mapNotNull { it.localUri } + db.jaDownloads().paths())
        .filter { it.startsWith("/") }.distinct().groupBy(::canonicalPath)

private suspend fun deleteLibraryFileInTransaction(
    db: AppDatabase,
    path: String,
    file: File,
    expectedFileId: Long?,
    reconcileMissing: Boolean,
    aliases: List<String>,
    alreadyRemoved: Boolean = false,
): LibraryFileDeleteOutcome {
    val files = db.files()
    if (expectedFileId != null && files.get(expectedFileId)?.localUri != path) return LibraryFileDeleteOutcome.CHANGED
    if (!alreadyRemoved && !deleteAppFile(file, reconcileMissing)) return LibraryFileDeleteOutcome.CHANGED
    if (expectedFileId != null) files.clearLocalUri(expectedFileId)
    for (alias in (aliases + path + file.path).distinct()) {
        files.clearLocalUriByPath(alias)
        db.jaDownloads().clearPath(alias)
    }
    return LibraryFileDeleteOutcome.REMOVED
}

private fun deleteAppFile(file: File, reconcileMissing: Boolean): Boolean =
    if (!file.exists()) reconcileMissing else file.isFile && file.delete()

internal fun customSubtitleFile(filesDir: File, path: String): File? {
    val owned = ownedLocalFile(filesDir, path) ?: return null
    val directory = ownedLocalFile(filesDir, File(filesDir, "custom-subtitles").absolutePath) ?: return null
    return owned.takeIf { it.parentFile == directory && isSubtitleFile(it.name) }
}

internal suspend fun appSubtitleBindingsByPath(
    db: AppDatabase, filesDir: File,
): Map<String, List<TrackSubtitleBindingEntity>> =
    db.trackSubtitles().appFileBindings().mapNotNull { row ->
        row.uri?.let { customSubtitleFile(filesDir, it)?.path }?.let { it to row }
    }.groupBy({ it.first }, { it.second })

private suspend fun exclusiveCustomSubtitleBindings(
    db: AppDatabase, filesDir: File, trackIds: Set<Long>,
): Map<String, List<TrackSubtitleBindingEntity>> =
    if (trackIds.isEmpty()) emptyMap()
    else appSubtitleBindingsByPath(db, filesDir).filterValues { rows -> rows.all { it.trackId in trackIds } }

internal suspend fun exclusiveCustomSubtitlePaths(db: AppDatabase, filesDir: File, trackIds: Set<Long>): List<String> =
    exclusiveCustomSubtitleBindings(db, filesDir, trackIds).keys.toList()

/** Caller holds the subtitle locks and a Room transaction. */
internal suspend fun deleteCustomSubtitleCopy(db: AppDatabase, filesDir: File, path: String): Boolean {
    val file = customSubtitleFile(filesDir, path) ?: return false
    return deleteCustomSubtitleCopy(db, file, appSubtitleBindingsByPath(db, filesDir)[file.path].orEmpty())
}

private suspend fun deleteCustomSubtitleCopy(
    db: AppDatabase, file: File, rows: List<TrackSubtitleBindingEntity>, alreadyRemoved: Boolean = false,
): Boolean {
    if (!alreadyRemoved && !deleteAppFile(file, reconcileMissing = true)) return false
    rows.mapNotNull { it.uri }.distinct().forEach { db.trackSubtitles().deleteByAppPath(it) }
    return true
}
