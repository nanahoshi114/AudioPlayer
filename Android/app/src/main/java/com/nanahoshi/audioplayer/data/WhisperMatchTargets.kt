package com.nanahoshi.audioplayer.data

import android.net.Uri
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.data.db.TranslateMark
import com.nanahoshi.audioplayer.data.db.WhisperJobEntity
import com.nanahoshi.audioplayer.japaneseasmr.jaAudioIdentity
import com.nanahoshi.audioplayer.playback.LoadedSubtitles
import com.nanahoshi.audioplayer.youtube.readyYouTubeAudio
import java.io.File
import java.security.MessageDigest

internal const val WHISPER_STAGING_DIRECTORY = "whisper-src"

internal fun stagedFilePath(filesDir: File, contentUri: String): String {
    val hash = MessageDigest.getInstance("SHA-256")
        .digest(contentUri.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return File(filesDir, "$WHISPER_STAGING_DIRECTORY/$hash").absolutePath
}

internal fun QueuedTrack.whisperRemoteIdentity(): String? =
    if (source == TrackSource.JAPANESE_ASMR) jaAudioIdentity()
    else remoteUrl?.takeIf { it.isNotBlank() && (source != TrackSource.BILIBILI || it.startsWith("/")) }

internal data class WhisperMatchTargets(
    val trackIds: Set<Long>,
    val remoteIdentity: String?,
    val localPaths: Set<String>,
    val generatedFingerprint: String?,
) {
    fun matches(job: WhisperJobEntity): Boolean =
        job.trackId in trackIds ||
            (remoteIdentity != null && job.awaitRemoteUrl == remoteIdentity) ||
            job.localPath in localPaths ||
            (generatedFingerprint != null && job.fingerprint == generatedFingerprint)
}

/** Collects job keys from known locations without opening or staging the audio. */
internal fun resolveReadOnlyMatchTargets(
    row: QueuedTrack,
    loaded: LoadedSubtitles?,
    loadedQueueItemId: Long?,
    filesDir: File,
    libraryTrack: TrackEntity?,
    savedPaths: List<String>,
): WhisperMatchTargets {
    val cachedFile = when (row.source) {
        TrackSource.BILIBILI -> readyBiliAudio(
            filesDir,
            row.bvid ?: row.workno ?: libraryTrack?.bvid,
            row.cid ?: row.fileKey?.toLongOrNull() ?: libraryTrack?.cid,
        )
        TrackSource.YOUTUBE -> readyYouTubeAudio(filesDir, row.youtubeVideoId ?: row.workno ?: libraryTrack?.youtubeVideoId)
        else -> null
    }
    val paths = (listOf(
        row.localUri,
        libraryTrack?.localUri,
        row.remoteUrl?.takeIf { it.startsWith("/") },
        cachedFile?.absolutePath,
    ) + savedPaths).mapNotNullTo(mutableSetOf()) { path ->
        when {
            path.isNullOrBlank() -> null
            path.startsWith("content:") -> stagedFilePath(filesDir, path)
            path.startsWith("file:") -> Uri.parse(path).path?.let { File(it).absolutePath }
            path.startsWith("/") -> File(path).absolutePath
            else -> null
        }
    }
    return WhisperMatchTargets(
        trackIds = listOfNotNull(row.trackId, libraryTrack?.id).filter { it > 0L }.toSet(),
        remoteIdentity = row.whisperRemoteIdentity(),
        localPaths = paths,
        generatedFingerprint = loaded?.generatedFingerprint?.takeIf {
            loadedQueueItemId == row.queueItemId && it.isNotBlank()
        },
    )
}

internal fun matchesActiveWhisper(job: WhisperJobEntity, targets: WhisperMatchTargets): Boolean =
    (job.status == DownloadStatus.QUEUED || job.status == DownloadStatus.RUNNING ||
        TranslateMark.isActive(job.translateStatus)) && targets.matches(job)

internal fun matchesFailedWhisper(job: WhisperJobEntity, targets: WhisperMatchTargets): Boolean =
    job.dismissedAt == null &&
        (job.status == DownloadStatus.FAILED ||
            (job.status == DownloadStatus.DONE && job.translateStatus == TranslateMark.FAILED)) &&
        targets.matches(job)
