package com.nanahoshi.audioplayer.playback

import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import com.nanahoshi.audioplayer.data.db.WhisperJobEntity

internal sealed interface GeneratedState {
    data class Ready(val document: GeneratedSubtitles, val fingerprint: String) : GeneratedState
    data object Corrupt : GeneratedState
    data object Missing : GeneratedState
}

internal suspend fun SubtitleSource.findGeneratedJob(row: QueuedTrack): WhisperJobEntity? {
    val jobs = buildList {
        row.trackId?.let { db.whisper().latestDoneForTrack(it) }?.let(::add)
        subtitleIdentity(row)
            ?.let { db.whisper().latestDoneForUrl(it) }?.let(::add)
        storedAudioPath(row)?.let { db.whisper().latestDoneForPath(it) }?.let(::add)
    }
    return jobs.maxByOrNull { it.id }
}

internal suspend fun SubtitleSource.relatedDone(row: QueuedTrack): List<WhisperJobEntity> {
    val found = mutableListOf<WhisperJobEntity>()
    row.trackId?.takeIf { it > 0L }?.let { found += db.whisper().doneForTrack(it) }
    subtitleIdentity(row)
        ?.let { found += db.whisper().doneForUrl(it) }
    listOfNotNull(row.localUri, storedAudioPath(row)).distinct().forEach { found += db.whisper().doneForPath(it) }
    return found.distinctBy { it.id }
}

internal suspend fun SubtitleSource.generatedState(row: QueuedTrack): GeneratedState {
    val job = findGeneratedJob(row) ?: return GeneratedState.Missing
    val file = generatedFile(job.fingerprint)
    if (file.name == ".json" || !file.isFile) return GeneratedState.Missing
    val document = parseGeneratedSubtitles(file.readText()) ?: return GeneratedState.Corrupt
    return GeneratedState.Ready(document, job.fingerprint)
}