package com.nanahoshi.audioplayer

import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.db.DownloadEntity
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.data.db.TranslationJobEntity
import com.nanahoshi.audioplayer.data.db.WhisperJobEntity

/** 单元测试共用的默认实体工厂；只覆盖测试关心的字段。 */

fun folderEntity(
    id: Long = 0,
    parentId: Long? = null,
    name: String = "folder",
    position: Int = 0,
    addedAt: Long = 0,
    asmrWorkId: Long? = null,
    asmrSourceId: String? = null,
    coverUri: String? = null,
    workMeta: String? = null,
    contentSource: String? = null,
    biliBvid: String? = null,
) = FolderEntity(
    id = id, parentId = parentId, name = name, position = position, addedAt = addedAt,
    asmrWorkId = asmrWorkId, asmrSourceId = asmrSourceId, coverUri = coverUri,
    workMeta = workMeta, contentSource = contentSource, biliBvid = biliBvid,
)

fun trackEntity(
    id: Long = 0,
    source: TrackSource = TrackSource.LOCAL,
    title: String = "track",
    artist: String? = null,
    durationMs: Long = 0,
    coverUri: String? = null,
    localUri: String? = null,
    bvid: String? = null,
    cid: Long? = null,
    page: Int? = null,
    addedAt: Long = 0,
    folderId: Long? = null,
    remoteUrl: String? = null,
    asmrWorkId: Long? = null,
    fileHash: String? = null,
    youtubeVideoId: String? = null,
) = TrackEntity(
    id = id, source = source, title = title, artist = artist, durationMs = durationMs,
    coverUri = coverUri, localUri = localUri, bvid = bvid, cid = cid, page = page,
    addedAt = addedAt, folderId = folderId, remoteUrl = remoteUrl, asmrWorkId = asmrWorkId,
    fileHash = fileHash, youtubeVideoId = youtubeVideoId,
)

fun queuedTrack(
    queueItemId: Long = 1,
    position: Int = 0,
    trackId: Long? = null,
    source: TrackSource = TrackSource.LOCAL,
    title: String = "track",
    artist: String? = null,
    durationMs: Long = 0,
    coverUri: String? = null,
    localUri: String? = null,
    bvid: String? = null,
    cid: Long? = null,
    page: Int? = null,
    remoteUrl: String? = null,
    referer: String? = null,
    workno: String? = null,
    fileKey: String? = null,
    youtubeVideoId: String? = null,
    mediaKind: String? = null,
    mediaPathHash: String? = null,
) = QueuedTrack(
    queueItemId = queueItemId, position = position, trackId = trackId, source = source,
    title = title, artist = artist, durationMs = durationMs, coverUri = coverUri,
    localUri = localUri, bvid = bvid, cid = cid, page = page, remoteUrl = remoteUrl,
    referer = referer, workno = workno, fileKey = fileKey, youtubeVideoId = youtubeVideoId,
    mediaKind = mediaKind, mediaPathHash = mediaPathHash,
)

fun whisperJob(
    id: Long = 0,
    title: String = "whisper",
    fingerprint: String = "fingerprint",
    trackId: Long? = null,
    localPath: String? = null,
    remoteJobId: String? = null,
    status: DownloadStatus = DownloadStatus.DONE,
    progress: Float = 1f,
    error: String? = null,
    awaitRemoteUrl: String? = null,
    endpoint: String? = null,
    translateStatus: String? = null,
    skipTranslate: Boolean? = null,
    model: String? = null,
    progressSummary: String? = null,
    createdAt: Long? = null,
    dismissedAt: Long? = null,
    forceRegenerate: Boolean? = null,
) = WhisperJobEntity(
    id = id, title = title, fingerprint = fingerprint, trackId = trackId,
    localPath = localPath, remoteJobId = remoteJobId, status = status, progress = progress,
    error = error, awaitRemoteUrl = awaitRemoteUrl, endpoint = endpoint,
    translateStatus = translateStatus, skipTranslate = skipTranslate, model = model,
    progressSummary = progressSummary, createdAt = createdAt, dismissedAt = dismissedAt,
    forceRegenerate = forceRegenerate,
)

fun translationJob(
    id: Long = 0,
    title: String = "translation",
    trackId: Long? = null,
    remoteUrl: String? = null,
    queueItemId: Long? = null,
    status: DownloadStatus = DownloadStatus.DONE,
    error: String? = null,
    kind: String? = null,
    createdAt: Long? = null,
    dismissedAt: Long? = null,
    targetKind: String? = null,
    outputFilename: String? = null,
) = TranslationJobEntity(
    id = id, title = title, trackId = trackId, remoteUrl = remoteUrl,
    queueItemId = queueItemId, status = status, error = error, kind = kind,
    createdAt = createdAt, dismissedAt = dismissedAt, targetKind = targetKind,
    outputFilename = outputFilename,
)

fun downloadEntity(
    id: Long = 0,
    trackId: Long = 0,
    title: String = "download",
    bytesDone: Long = 0,
    bytesTotal: Long = 0,
    status: DownloadStatus = DownloadStatus.QUEUED,
    error: String? = null,
    source: String? = null,
    workKey: String? = null,
    fileKey: String? = null,
    kind: String? = null,
    phase: String? = null,
    pendingBiliSubtitle: Boolean? = null,
    progressSummary: String? = null,
    dismissedAt: Long? = null,
) = DownloadEntity(
    id = id, trackId = trackId, title = title, bytesDone = bytesDone,
    bytesTotal = bytesTotal, status = status, error = error, source = source,
    workKey = workKey, fileKey = fileKey, kind = kind, phase = phase,
    pendingBiliSubtitle = pendingBiliSubtitle, progressSummary = progressSummary,
    dismissedAt = dismissedAt,
)