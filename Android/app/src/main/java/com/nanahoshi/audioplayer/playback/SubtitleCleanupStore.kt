package com.nanahoshi.audioplayer.playback

import androidx.room.withTransaction
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import com.nanahoshi.audioplayer.data.db.TranslateMark
import com.nanahoshi.audioplayer.data.db.WhisperJobEntity
import com.nanahoshi.audioplayer.japaneseasmr.jaAudioIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

internal suspend fun SubtitleSource.deleteTranslationOverlayInternal(
    file: File,
    blockWhileActive: Boolean,
): SubtitleLanguageDeleteResult =
    withContext(Dispatchers.IO) {
        val expectedDir = File(context.filesDir, "subtitles").canonicalFile
        val canonical = runCatching { file.canonicalFile }.getOrNull()
            ?: return@withContext SubtitleLanguageDeleteResult.NOT_APPLICABLE
        if (canonical.parentFile != expectedDir || !canonical.name.startsWith("extra-") || !canonical.name.endsWith(".json")) {
            return@withContext SubtitleLanguageDeleteResult.NOT_APPLICABLE
        }
        generatedWriteLock.withLock {
            if (db.translations().hasPendingPublication(canonical.name)) {
                return@withLock SubtitleLanguageDeleteResult.BUSY
            }
            if (blockWhileActive && db.translations().active().any {
                    it.generatedFingerprint == null && translationOverlayName(it) == canonical.name
                }
            ) {
                return@withLock SubtitleLanguageDeleteResult.BUSY
            }
            if (!canonical.isFile) return@withLock SubtitleLanguageDeleteResult.NOT_APPLICABLE
            withContext(NonCancellable) {
                if (!canonical.delete()) return@withContext SubtitleLanguageDeleteResult.FAILED
                db.withTransaction {
                    val candidates = db.translations().all().filter {
                        it.generatedFingerprint == null && translationOverlayName(it) == canonical.name
                    }
                    candidates.forEach { db.translations().deleteInactive(it.id) }
                }
                invalidate()
                SubtitleLanguageDeleteResult.DELETED
            }
        }
    }

internal suspend fun SubtitleSource.hasRemovableSubtitles(input: QueuedTrack): Boolean {
    val row = withRemovalIdentity(input)
    if (generatedState(row) != GeneratedState.Missing) return true
    val jobs = relatedDone(row)
    return jobs.any { it.fingerprint.isNotBlank() && generatedFile(it.fingerprint).let { file -> file.name != ".json" && file.isFile } } ||
        removalOverlayBases(listOf(input, row) + recoveredOverlayIdentities(row, jobs), removalBiliIdentity(row))
            .any { overlayVersions(it).isNotEmpty() }
}

internal suspend fun SubtitleSource.removeGenerated(input: QueuedTrack, excludedTrackIds: Set<Long> = emptySet()) =
    withContext(NonCancellable) {
        db.withTransaction {
            val row = withRemovalIdentity(input)
            val effectiveExcludedTrackIds = excludedTrackIds + listOfNotNull(row.trackId?.takeIf { it > 0L })
            val jobs = relatedDone(row)
            val pending = db.translations().pendingPublicationFilenames().toSet()
            val scoped = jobs.filterNot { hasSurvivingTrack(listOf(it), effectiveExcludedTrackIds) }
            for ((fingerprint, matchingJobs) in jobs.groupBy { it.fingerprint }) {
                val file = generatedFile(fingerprint)
                val canClearFingerprint = file.name != ".json" && file.name !in pending &&
                    db.whisper().forFingerprint(fingerprint).let { fpJobs ->
                        !fingerprintIsActive(fingerprint, fpJobs) &&
                            !hasSurvivingTrack(fpJobs.filter { it.status == DownloadStatus.DONE }, effectiveExcludedTrackIds)
                    }
                if (!canClearFingerprint) {
                    matchingJobs.filter { it in scoped }.forEach { db.whisper().deleteDoneForId(it.id) }
                } else if (!file.exists() || (file.isFile && file.delete())) {
                    db.whisper().deleteDoneForFingerprint(fingerprint)
                    db.translations().deleteInactiveForFingerprint(fingerprint)
                }
            }

            val identities = (listOf(input, row) + recoveredOverlayIdentities(row, scoped)).distinct()
            val removable = identities.filterNot { identity ->
                val url = identity.jaAudioIdentity() ?: identity.remoteUrl
                val usesUrl = identity.trackId == null || url?.startsWith("japaneseasmr://audio?") == true
                usesUrl && hasOtherRemoteUrlExcluding(url, effectiveExcludedTrackIds)
            }
            val bili = removalBiliIdentity(row)
            removalOverlayBases(removable, bili).flatMap { overlayVersions(it) }.distinct()
                .filter { it.name !in pending }.forEach { it.delete() }
            removable.mapNotNull { it.trackId }.distinct().forEach { db.translations().deleteInactiveForTrack(it) }
            removable.mapNotNull { it.remoteUrl }.filter { it.isNotBlank() }.distinct().forEach {
                if (!hasOtherRemoteUrlExcluding(it, effectiveExcludedTrackIds)) db.translations().deleteInactiveForUrl(it)
            }
            if (bili != null) db.translations().deleteInactiveForBili(bili.first, bili.second)
        }
    }

internal suspend fun SubtitleSource.fingerprintIsActive(fingerprint: String, jobs: List<WhisperJobEntity>): Boolean =
    jobs.any { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING || TranslateMark.isActive(it.translateStatus) } ||
        db.translations().active().any { it.generatedFingerprint == fingerprint }

private suspend fun SubtitleSource.hasSurvivingTrack(done: List<WhisperJobEntity>, excluded: Set<Long>): Boolean {
    val otherIds = done.mapNotNull { it.trackId }.filter { it !in excluded }.distinct()
    if (otherIds.chunked(900).any { db.tracks().getByIds(it).isNotEmpty() }) return true
    return done.any { job ->
        job.localPath?.let { db.tracks().findByLocalUri(it)?.id?.let { id -> id !in excluded } } == true ||
            hasOtherRemoteUrlExcluding(job.awaitRemoteUrl, excluded)
    }
}

private suspend fun SubtitleSource.hasOtherRemoteUrlExcluding(url: String?, excluded: Set<Long>): Boolean =
    !url.isNullOrBlank() && db.tracks().idsForRemoteUrl(url).any { it !in excluded }