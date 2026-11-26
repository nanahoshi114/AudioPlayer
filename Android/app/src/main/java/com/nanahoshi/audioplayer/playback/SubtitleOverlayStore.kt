package com.nanahoshi.audioplayer.playback

import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.data.db.TranslationJobEntity
import com.nanahoshi.audioplayer.data.db.WhisperJobEntity
import com.nanahoshi.audioplayer.data.jaWorkFileIdentity
import com.nanahoshi.audioplayer.data.readyBiliAudio
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.japaneseasmr.jaAudioIdentity
import com.nanahoshi.audioplayer.japaneseasmr.jaIdentity
import java.io.File

internal suspend fun SubtitleSource.subtitleQueueRow(track: TrackEntity): QueuedTrack {
    val ja = if (track.source == TrackSource.JAPANESE_ASMR) db.japaneseAsmr().mediaForTrack(track.id) else null
    return storedQueueRow(track.id, track.localUri, track.remoteUrl).copy(
        source = track.source,
        title = track.title,
        artist = track.artist,
        durationMs = track.durationMs,
        coverUri = track.coverUri,
        bvid = track.bvid,
        cid = track.cid,
        page = track.page,
        workno = ja?.postId,
        fileKey = ja?.mediaKey,
        youtubeVideoId = track.youtubeVideoId,
        mediaKind = ja?.kind,
        mediaPathHash = ja?.pathHash,
    )
}

internal suspend fun SubtitleSource.storedRemovalRow(trackId: Long?, localPath: String?, remoteUrl: String?): QueuedTrack {
    val track = trackId?.takeIf { it > 0L }?.let { db.tracks().getById(it) }
    return if (track == null) storedQueueRow(trackId, localPath, remoteUrl)
    else subtitleQueueRow(track).copy(localUri = localPath, remoteUrl = remoteUrl ?: track.remoteUrl)
}

internal suspend fun SubtitleSource.withRemovalIdentity(row: QueuedTrack): QueuedTrack {
    val identity = jaSubtitleIdentity(row) ?: row.localUri?.let { db.jaDownloads().entryForPath(it) }
        ?.let { jaWorkFileIdentity(it.rj, it.relativePath) }
    return if (identity == null) row else row.copy(remoteUrl = identity)
}

internal suspend fun SubtitleSource.removalBiliIdentity(row: QueuedTrack): Pair<String, Long>? =
    biliIdentityFromPath(row.localUri) ?: biliIdentityFromPath(storedAudioPath(row))

internal fun SubtitleSource.removalOverlayBases(identities: List<QueuedTrack>, bili: Pair<String, Long>?): List<File> =
    (identities.flatMap { listOf(overlayFile(it), overlayFile(it, useBiliIdentity = false)) } +
        listOfNotNull(bili?.let { (bvid, cid) ->
            overlayFileName(null, null, null, null, bvid, cid)?.let { File(File(context.filesDir, "subtitles"), it) }
        })).distinct()

/** B 站音轨恒存于 filesDir/bilibili/{bvid}/{cid}.m4s（AGENTS §6.1），从路径确定性还原身份。 */
private fun SubtitleSource.biliIdentityFromPath(path: String?): Pair<String, Long>? {
    if (path.isNullOrBlank() || !path.startsWith("/")) return null
    val file = File(path)
    val dir = file.parentFile ?: return null
    if (dir.parentFile != File(context.filesDir, "bilibili")) return null
    val bvid = dir.name.takeIf { BVID_ROW.matches(it) } ?: return null
    val cid = file.nameWithoutExtension.toLongOrNull()?.takeIf { it > 0L } ?: return null
    return bvid to cid
}

internal suspend fun SubtitleSource.jaSubtitleIdentity(row: QueuedTrack): String? = row.jaAudioIdentity()
    ?: row.trackId?.let { db.japaneseAsmr().mediaForTrack(it) }?.let { jaIdentity(it.postId, it.mediaKey) }

internal suspend fun SubtitleSource.subtitleIdentity(row: QueuedTrack): String? = jaSubtitleIdentity(row)
    ?: row.remoteUrl?.takeIf { row.source != TrackSource.JAPANESE_ASMR && it.isNotBlank() &&
        (row.source != TrackSource.BILIBILI || it.startsWith("/")) }

internal suspend fun SubtitleSource.storedAudioPath(row: QueuedTrack): String? {
    val track = row.trackId?.let { db.tracks().getById(it) }
    val bili = bilibiliRowTrack(row)
    if (bili != null) {
        readyBiliAudio(context.filesDir, bili.bvid, bili.cid)?.let { return it.absolutePath }
    }
    listOf(track?.localUri, row.localUri, row.remoteUrl).firstOrNull { isFile(it) }?.let { return it }
    val ja = jaSubtitleIdentity(row)
    if (ja != null) {
        val uri = android.net.Uri.parse(ja)
        val post = uri.getQueryParameter("post") ?: return null
        val key = uri.getQueryParameter("media") ?: return null
        return db.downloads().finishedForWork(TrackSource.JAPANESE_ASMR.name, post)
            .firstOrNull { it.fileKey == key && isFile(it.localPath) }?.localPath
    }
    if (row.source == TrackSource.JAPANESE_ASMR) return null
    val url = row.remoteUrl
    if (!url.isNullOrBlank()) {
        val done = db.downloads().latestForUrl(url)
        if (done?.status == DownloadStatus.DONE && isFile(done.localPath)) return done.localPath
    }
    return null
}

private fun SubtitleSource.isFile(path: String?): Boolean =
    !path.isNullOrBlank() && path.startsWith("/") && File(path).isFile

internal fun SubtitleSource.sourceFingerprint(cues: List<SubtitleCue>) =
    subtitleSourceFingerprint(cues, includeTranslation = true)

internal fun SubtitleSource.withOverlay(
    row: QueuedTrack, cues: List<SubtitleCue>, allowLegacyOverlay: Boolean,
): Pair<List<SubtitleCue>, String?> {
    if (cues.isEmpty()) return cues to null
    val candidates = listOf(expectedOverlayFile(row, cues)) + if (allowLegacyOverlay) {
        listOf(overlayFile(row), overlayFile(row, useBiliIdentity = false)).distinct()
    } else emptyList()
    for ((index, file) in candidates.withIndex()) {
        if (!file.isFile) continue
        val overlay = runCatching { parseGeneratedSubtitles(file.readText()) }.getOrNull() ?: continue
        val updated = applySubtitleOverlay(cues, overlay, allowLegacyOverlay && index > 0)
        if (updated !== cues && (updated != cues || overlay.sourceFingerprint != null)) return updated to file.name
    }
    return cues to null
}

internal fun SubtitleSource.overlayVersions(base: File): List<File> =
    base.parentFile?.listFiles { file ->
        file.isFile && (file.name == base.name || (file.name.startsWith("${base.nameWithoutExtension}-") && file.extension == "json"))
    }?.toList().orEmpty()

internal fun SubtitleSource.overlayFile(row: QueuedTrack, useBiliIdentity: Boolean = true): File {
    val bili = if (useBiliIdentity) bilibiliRowTrack(row) else null
    return File(File(context.filesDir, "subtitles"),
        overlayFileName(row.trackId, row.jaAudioIdentity() ?: row.remoteUrl, row.localUri, row.queueItemId, bili?.bvid, bili?.cid)!!)
}

/** 下载管理等只有路径/曲目身份的入口，用同一构造方式合成队列行。 */
internal fun storedQueueRow(trackId: Long?, localPath: String?, remoteUrl: String?) = QueuedTrack(
    queueItemId = -1,
    position = 0,
    trackId = trackId?.takeIf { it > 0L },
    source = TrackSource.LOCAL,
    title = "",
    artist = null,
    durationMs = 0,
    coverUri = null,
    localUri = localPath,
    bvid = null,
    cid = null,
    page = null,
    remoteUrl = remoteUrl,
    referer = null,
    workno = null,
    fileKey = null,
)

/**
 * 曲目已移除的孤儿条目没有 trackId/remoteUrl，翻译覆盖却仍按旧 track/url 身份命名。
 * 借按路径（或地址）匹配的听写任务找回身份，询问和删除都不漏掉这些覆盖文件与记录。
 */
internal fun recoveredOverlayIdentities(row: QueuedTrack, jobs: List<WhisperJobEntity>): List<QueuedTrack> {
    val known = overlayFileName(row.trackId, row.remoteUrl, row.localUri, row.queueItemId)
    return jobs.mapNotNull { job ->
        val trackId = job.trackId?.takeIf { it > 0L }
        val url = job.awaitRemoteUrl?.takeIf { it.isNotBlank() }
        if (trackId == null && url == null) return@mapNotNull null
        val name = overlayFileName(trackId, url, null, null)
        if (name == null || name == known) null else storedQueueRow(trackId, null, url)
    }.distinctBy { overlayFileName(it.trackId, it.remoteUrl, it.localUri, it.queueItemId) }
}

internal fun translationOverlayName(job: TranslationJobEntity): String? =
    job.outputFilename ?: overlayFileName(job.trackId, job.remoteUrl, job.localUri, job.queueItemId, job.bvid, job.cid)

/** 翻译覆盖文件名；身份优先级与队列行一致，按翻译任务匹配时 localUri 传 null。 */
internal fun overlayFileName(
    trackId: Long?, remoteUrl: String?, localUri: String?, queueItemId: Long?, bvid: String? = null, cid: Long? = null,
): String? {
    val raw = when {
        bvid != null && cid != null && (trackId == null || trackId <= 0L) -> "bili:$bvid:$cid"
        remoteUrl?.startsWith("japaneseasmr://audio?") == true -> "url:$remoteUrl"
        trackId != null && trackId > 0L -> "track:$trackId"
        !remoteUrl.isNullOrBlank() -> "url:$remoteUrl"
        !localUri.isNullOrBlank() -> "path:$localUri"
        queueItemId != null -> "queue:$queueItemId"
        else -> return null
    }
    val key = com.nanahoshi.audioplayer.data.sha256Hex(raw).take(16)
    return "extra-$key.json"
}

/**
 * 没入库的 B 站队列项（例如从收藏夹直接缓存的音轨）没有 trackId，
 * 用队列里保存的 workno / fileKey 当 bvid / cid，读同一份缓存字幕。
 */
internal fun bilibiliRowTrack(row: QueuedTrack): TrackEntity? {
    if (row.source != TrackSource.BILIBILI) return null
    val bvid = (row.bvid?.takeIf { BVID_ROW.matches(it) }
        ?: row.workno?.trim()?.takeIf { BVID_ROW.matches(it) })
        ?: return null
    val cid = row.cid?.takeIf { it > 0L }
        ?: row.fileKey?.toLongOrNull()?.takeIf { it > 0L }
        ?: return null
    val local = row.localUri?.takeIf { it.startsWith("/") }
        ?: row.remoteUrl?.takeIf { it.startsWith("/") }
    return TrackEntity(
        id = 0L,
        source = TrackSource.BILIBILI,
        title = row.title,
        artist = row.artist,
        durationMs = row.durationMs,
        coverUri = row.coverUri,
        localUri = local,
        bvid = bvid,
        cid = cid,
        page = row.page,
        addedAt = 0L,
    )
}

private val BVID_ROW = Regex("BV[0-9A-Za-z]{10}")