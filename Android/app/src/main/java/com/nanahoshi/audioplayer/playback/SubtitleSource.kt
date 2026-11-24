package com.nanahoshi.audioplayer.playback

import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.text.PersistedErrors
import com.nanahoshi.audioplayer.data.encode
import com.nanahoshi.audioplayer.japaneseasmr.jaAudioIdentity
import com.nanahoshi.audioplayer.japaneseasmr.jaIdentity
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import com.nanahoshi.audioplayer.asmr.AsmrClient
import com.nanahoshi.audioplayer.bilibili.BiliSubtitle
import com.nanahoshi.audioplayer.bilibili.BilibiliClient
import com.nanahoshi.audioplayer.data.LocalAudio
import com.nanahoshi.audioplayer.data.libraryFilePreview
import com.nanahoshi.audioplayer.data.loadWorkSubtitleCatalog
import com.nanahoshi.audioplayer.data.jaWorkFileIdentity
import com.nanahoshi.audioplayer.data.readLocalTextPreview
import com.nanahoshi.audioplayer.data.readRemoteTextPreview
import com.nanahoshi.audioplayer.data.resolveLibraryFileSource
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.LibraryFileEntity
import com.nanahoshi.audioplayer.data.db.TrackSubtitleBindingEntity
import com.nanahoshi.audioplayer.data.db.TrackSubtitleKind
import com.nanahoshi.audioplayer.dlsite.DlsiteClient
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.readBiliSubtitle
import com.nanahoshi.audioplayer.data.biliSubtitleLanguage
import com.nanahoshi.audioplayer.data.writeBiliSubtitle
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.QueuedTrack
import com.nanahoshi.audioplayer.data.db.SubtitlePublicationEntity
import com.nanahoshi.audioplayer.data.db.TranslateMark
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.data.db.WhisperJobEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

internal object SubtitleTranslationTarget {
    const val GENERATED = "GENERATED"
    const val OVERLAY = "OVERLAY"
}

enum class SubtitleSourceKind { NONE, GENERATED, LIBRARY_FILE, EXTERNAL_URI, APP_FILE, BILIBILI, LOCAL_FILE }

data class LoadedSubtitles(
    val cues: List<SubtitleCue>,
    val generatedEmpty: Boolean,
    val generated: Boolean,
    val notice: String? = null,
    val language: String? = null,
    val origin: SubtitleOrigin? = null,
    val sourceCues: List<SubtitleCue> = cues,
    val generatedFingerprint: String? = null,
    val sourceKind: SubtitleSourceKind = if (generated) SubtitleSourceKind.GENERATED else SubtitleSourceKind.NONE,
    val sourceLabel: String? = null,
    val hasManualBinding: Boolean = false,
    val appliedOffsetMs: Long = 0L,
    val overlayOutputName: String? = null,
    internal val unshiftedCues: List<SubtitleCue> = cues,
    /** 用户明确关闭了本曲目字幕，界面据此隐藏生成入口并提示可重新开启。 */
    val disabledByUser: Boolean = false,
) {
    val canTranslate: Boolean = canGenerateTranslation(unshiftedCues, language)
}

/** 一首曲目的字幕，以及取不到时需要告诉用户的原因。 */
data class TrackSubtitles(
    val cues: List<SubtitleCue>,
    val notice: String? = null,
    val language: String? = null,
    val sourceKind: SubtitleSourceKind = SubtitleSourceKind.NONE,
    val sourceLabel: String? = null,
    internal val allowLegacyOverlay: Boolean = false,
)

class SubtitleSource(
    private val context: Context,
    private val db: AppDatabase,
    private val bilibili: BilibiliClient,
    private val asmr: AsmrClient,
    private val dlsite: DlsiteClient? = null,
    private val crossSourceFallback: () -> Boolean = { true },
) {
    private val _revision = MutableStateFlow(0L)
    internal val revision = _revision.asStateFlow()
    internal val generatedWriteLock = Mutex()

    suspend fun load(row: QueuedTrack): LoadedSubtitles = loadSelection(row, includeGenerated = true)

    internal suspend fun loadNonGenerated(row: QueuedTrack): LoadedSubtitles = loadSelection(row, includeGenerated = false)

    internal fun allowsCrossSourceFallback(): Boolean = crossSourceFallback()
    internal fun hasGeneratedFile(fingerprint: String): Boolean = generatedFile(fingerprint).isFile

    internal suspend fun hasGeneratedForTrack(track: TrackEntity): Boolean = withContext(Dispatchers.IO) {
        generatedState(subtitleQueueRow(track)) is GeneratedState.Ready
    }

    internal suspend fun hasRemovableSubtitlesForTrack(track: TrackEntity): Boolean = withContext(Dispatchers.IO) {
        hasRemovableSubtitles(subtitleQueueRow(track))
    }

    private suspend fun subtitleQueueRow(track: TrackEntity): QueuedTrack {
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

    private suspend fun loadSelection(row: QueuedTrack, includeGenerated: Boolean): LoadedSubtitles = withContext(Dispatchers.IO) {
        try {
            val trackId = row.trackId?.takeIf { it > 0L }
            val binding = trackId?.let { db.trackSubtitles().getForTrack(it) }
            if (binding != null && binding.kind != TrackSubtitleKind.GENERATED) {
                if (binding.kind == TrackSubtitleKind.NONE) {
                    return@withContext LoadedSubtitles(
                        emptyList(), false, false, hasManualBinding = true, disabledByUser = true,
                    )
                }
                return@withContext loadedAttachment(row, boundCues(binding), binding)
            }
            var generatedNotice: String? = null
            if (includeGenerated) {
                when (val state = generatedState(row)) {
                    is GeneratedState.Ready -> {
                        val loaded = loadedGenerated(state.fingerprint, state.document)
                        val offset = binding?.offsetMs ?: 0L
                        return@withContext loaded.copy(
                            cues = offsetCues(loaded.cues, offset), hasManualBinding = binding != null, appliedOffsetMs = offset,
                        )
                    }
                    GeneratedState.Corrupt -> {
                        generatedNotice = context.getString(R.string.player_error_generated_subtitles_corrupt)
                        if (binding == null) return@withContext LoadedSubtitles(emptyList(), false, false, generatedNotice)
                    }
                    GeneratedState.Missing -> if (binding != null) {
                        generatedNotice = context.getString(R.string.player_error_bound_generated_missing)
                    }
                }
            }
            val track = trackId?.let { db.tracks().getById(it) } ?: bilibiliRowTrack(row)
            val attachment = track?.let { cuesFor(it) } ?: TrackSubtitles(emptyList())
            loadedAttachment(row, attachment.copy(notice = attachment.notice ?: generatedNotice), binding)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            LoadedSubtitles(emptyList(), false, false)
        }
    }

    private suspend fun boundCues(binding: TrackSubtitleBindingEntity): TrackSubtitles {
        val kind = when (binding.kind) {
            TrackSubtitleKind.LIBRARY_FILE -> SubtitleSourceKind.LIBRARY_FILE
            TrackSubtitleKind.EXTERNAL_URI -> SubtitleSourceKind.EXTERNAL_URI
            TrackSubtitleKind.APP_FILE -> SubtitleSourceKind.APP_FILE
            else -> SubtitleSourceKind.NONE
        }
        var name = binding.displayName
        val cues = try {
            val text = when (kind) {
                SubtitleSourceKind.LIBRARY_FILE -> binding.fileId?.let { db.files().get(it) }?.let { file ->
                    name = name ?: file.name
                    val folders = db.folders().all().associateBy { it.id }
                    librarySubtitleText(file, folders, db.jaDownloads().fileIds().toSet())
                }
                SubtitleSourceKind.EXTERNAL_URI -> binding.uri?.let { uri ->
                    val grant = binding.treeUri ?: uri
                    if (context.contentResolver.persistedUriPermissions.none { it.isReadPermission && it.uri.toString() == grant }) null
                    else readLocalTextPreview(context, uri)?.text
                }
                SubtitleSourceKind.APP_FILE -> binding.uri?.let { readLocalTextPreview(context, it)?.text }
                else -> null
            }
            parseSubtitleDocument(text.orEmpty())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        val notice = if (cues.isEmpty()) {
            context.getString(if (kind == SubtitleSourceKind.EXTERNAL_URI) {
                R.string.player_error_bound_subtitle_uri
            } else R.string.player_error_bound_subtitle_file)
        } else null
        return TrackSubtitles(cues, notice, sourceKind = kind, sourceLabel = name)
    }

    private fun loadedAttachment(row: QueuedTrack, attachment: TrackSubtitles, binding: TrackSubtitleBindingEntity?): LoadedSubtitles {
        val (cues, outputName) = withOverlay(row, attachment.cues, binding == null && attachment.allowLegacyOverlay)
        val offset = binding?.offsetMs ?: 0L
        return LoadedSubtitles(
            offsetCues(cues, offset), false, false, attachment.notice, attachment.language,
            sourceCues = attachment.cues, sourceKind = attachment.sourceKind, sourceLabel = attachment.sourceLabel,
            hasManualBinding = binding != null, appliedOffsetMs = offset, overlayOutputName = outputName, unshiftedCues = cues,
        )
    }

    private fun offsetCues(cues: List<SubtitleCue>, offset: Long): List<SubtitleCue> {
        if (offset == 0L) return cues
        fun shifted(time: Long) = (time.coerceIn(0L, Long.MAX_VALUE - offset.coerceAtLeast(0)) + offset).coerceAtLeast(0L)
        return cues.mapNotNull { cue ->
            val start = shifted(cue.startMs)
            val end = shifted(cue.endMs)
            if (end > start) cue.copy(startMs = start, endMs = end) else null
        }
    }

    suspend fun discard(row: QueuedTrack) = withContext(Dispatchers.IO) {
        generatedWriteLock.withLock { removeGenerated(row) }
        db.translations().deleteInactiveForQueue(row.queueItemId)
        invalidate()
    }

    suspend fun discardGeneratedOnly(row: QueuedTrack) = withContext(Dispatchers.IO) {
        generatedWriteLock.withLock {
            val job = findGeneratedJob(row) ?: return@withLock
            val file = generatedFile(job.fingerprint)
            if (file.name == ".json" || db.translations().pendingPublications().any { it.filename == file.name }) return@withLock
            val jobs = db.whisper().forFingerprint(job.fingerprint)
            if (jobs.any { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING || TranslateMark.isActive(it.translateStatus) } ||
                db.translations().active().any { it.generatedFingerprint == job.fingerprint }
            ) return@withLock
            val trackId = row.trackId?.takeIf { it > 0 }
            val done = jobs.filter { it.status == DownloadStatus.DONE }
            val otherIds = done.mapNotNull { it.trackId }.filter { it != trackId }.distinct()
            if (otherIds.chunked(900).any { db.tracks().getByIds(it).isNotEmpty() }) return@withLock
            val shared = done.any { other ->
                other.localPath?.let { db.tracks().findByLocalUri(it)?.id?.let { id -> id != trackId } } == true ||
                    other.awaitRemoteUrl?.let { db.tracks().hasOtherRemoteUrl(it, trackId) } == true
            }
            if (shared) return@withLock
            withContext(NonCancellable) {
                if (file.exists() && !file.delete()) return@withContext
                db.withTransaction {
                    db.whisper().deleteDoneForFingerprint(job.fingerprint)
                    db.translations().deleteInactiveForFingerprint(job.fingerprint)
                }
                invalidate()
            }
        }
    }

    suspend fun discardStored(trackId: Long?, localPath: String?, remoteUrl: String?) = withContext(Dispatchers.IO) {
        generatedWriteLock.withLock {
            discardStoredLocked(trackId, localPath, remoteUrl)
            invalidate()
        }
    }

    /** Caller holds generatedWriteLock and invalidates after any enclosing transaction commits. */
    internal suspend fun discardStoredLocked(
        trackId: Long?, localPath: String?, remoteUrl: String?, excludedTrackIds: Set<Long> = emptySet(),
    ) {
        removeGenerated(storedRemovalRow(trackId, localPath, remoteUrl), excludedTrackIds)
    }

    /** Caller holds generatedWriteLock. */
    internal suspend fun discardGeneratedFileLocked(file: File): Boolean {
        val fingerprint = file.nameWithoutExtension
        if (!file.name.endsWith(".json") || fingerprint.isBlank() || file.name.startsWith("extra-")) return false
        return withContext(NonCancellable) {
            db.withTransaction {
                if (db.translations().pendingPublications().any { it.filename == file.name } ||
                    fingerprintIsActive(fingerprint, db.whisper().forFingerprint(fingerprint))
                ) return@withTransaction false
                if (file.exists() && (!file.isFile || !file.delete())) return@withTransaction false
                db.whisper().deleteDoneForFingerprint(fingerprint)
                db.translations().deleteInactiveForFingerprint(fingerprint)
                true
            }
        }
    }

    /** 与翻译发布串行删除覆盖文件及其终态记录，保留进行中的翻译和听写。 */
    suspend fun discardTranslationOverlay(file: File) =
        withContext(Dispatchers.IO) {
            generatedWriteLock.withLock {
                if (db.translations().pendingPublications().any { it.filename == file.name }) return@withLock
                if (file.isFile && !file.delete()) return@withLock
                db.withTransaction {
                    val candidates = db.translations().all().filter {
                        it.generatedFingerprint == null && translationOverlayName(it) == file.name
                    }
                    candidates.forEach { db.translations().deleteInactive(it.id) }
                }
                invalidate()
            }
        }

    internal fun invalidate() {
        _revision.update { it + 1 }
    }

    /** 是否有可保留的生成字幕；删除音频前据此决定要不要询问。 */
    suspend fun hasGenerated(trackId: Long?, localPath: String?, remoteUrl: String?): Boolean = withContext(Dispatchers.IO) {
        hasRemovableSubtitles(storedRemovalRow(trackId, localPath, remoteUrl))
    }

    private suspend fun hasRemovableSubtitles(input: QueuedTrack): Boolean {
        val row = withRemovalIdentity(input)
        if (generatedState(row) != GeneratedState.Missing) return true
        val jobs = relatedDone(row)
        return jobs.any { it.fingerprint.isNotBlank() && generatedFile(it.fingerprint).let { file -> file.name != ".json" && file.isFile } } ||
            removalOverlayBases(listOf(input, row) + recoveredOverlayIdentities(row, jobs), removalBiliIdentity(row))
                .any { overlayVersions(it).isNotEmpty() }
    }

    suspend fun cuesFor(track: TrackEntity): TrackSubtitles = withContext(Dispatchers.IO) {
        try {
            when (track.source) {
                TrackSource.BILIBILI -> bilibiliCues(track)
                TrackSource.ASMR, TrackSource.DLSITE, TrackSource.JAPANESE_ASMR -> workCues(track)
                TrackSource.YOUTUBE -> TrackSubtitles(emptyList())
                TrackSource.LOCAL -> localCues(track)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            TrackSubtitles(emptyList())
        }
    }

    internal fun loadGenerated(fingerprint: String): LoadedSubtitles? =
        generatedFile(fingerprint).takeIf { it.isFile }?.let { file ->
            parseGeneratedSubtitles(file.readText())?.let { loadedGenerated(fingerprint, it) }
        }

    private fun loadedGenerated(fingerprint: String, document: GeneratedSubtitles) =
        LoadedSubtitles(
            document.displayCues, document.cues.isEmpty(), true, origin = document.origin,
            sourceCues = document.cues, generatedFingerprint = fingerprint,
        )

    internal suspend fun generatedFingerprint(row: QueuedTrack): String? = findGeneratedJob(row)?.fingerprint

    suspend fun saveTranslations(
        row: QueuedTrack,
        cues: List<SubtitleCue>,
        expected: LoadedSubtitles? = null,
        translationJobId: Long? = null,
        generatedFingerprint: String? = null,
        targetKind: String? = null,
        outputFilename: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val overlay = targetKind == SubtitleTranslationTarget.OVERLAY ||
            (expected != null && !expected.generated && generatedFingerprint == null)
        val polishing = overlay && expected?.unshiftedCues?.hasTranslation() == true
        if (overlay && expected != null) {
            val verified = loadNonGenerated(row)
            if (verified.sourceCues.isNotEmpty() &&
                (sourceFingerprint(verified.sourceCues) != sourceFingerprint(expected.sourceCues) ||
                    (polishing && sourceFingerprint(verified.unshiftedCues) != sourceFingerprint(expected.unshiftedCues)))
            ) return@withContext false
        }
        generatedWriteLock.withLock {
            val currentJob = if (overlay) null else findGeneratedJob(row)
            if (!overlay && generatedFingerprint != null && currentJob != null && currentJob.fingerprint != generatedFingerprint) {
                return@withLock false
            }
            val fingerprint = if (overlay) null else generatedFingerprint ?: currentJob?.fingerprint
            val file = fingerprint?.let(::generatedFile)
            val document = file?.takeIf { it.isFile }?.let { parseGeneratedSubtitles(it.readText()) }
            if (document == null && !overlay &&
                (generatedFingerprint != null || targetKind == SubtitleTranslationTarget.GENERATED || expected?.generated == true)
            ) return@withLock false
            if (document != null && expected != null &&
                (document.cues != expected.sourceCues || document.displayCues != expected.unshiftedCues || document.origin != expected.origin)
            ) return@withLock false
            if (polishing && expected != null) {
                val fromLegacy = expected.overlayOutputName == overlayFile(row).name ||
                    expected.overlayOutputName == overlayFile(row, useBiliIdentity = false).name
                val (current, _) = withOverlay(row, expected.sourceCues, fromLegacy)
                if (sourceFingerprint(current) != sourceFingerprint(expected.unshiftedCues)) return@withLock false
            }
            coroutineContext.ensureActive()
            val target = when {
                document != null -> checkNotNull(file)
                expected != null -> expectedOverlayFile(row, expected.sourceCues)
                else -> overlayFile(row)
            }
            if (outputFilename != null && outputFilename != target.name) return@withLock false
            val payload = if (document != null) {
                encodeGeneratedCues(document.cues, document.origin, revisedCues = cues)
            } else {
                // Keep the original attachment fingerprint so loading can apply the final
                // translation directly. A polish is checked against the pre-polish cues above.
                encodeGeneratedCues(cues, null, sourceFingerprint = expected?.sourceCues?.let(::sourceFingerprint))
            }
            val whisperJobId = if (document != null) {
                currentJob?.takeIf { it.fingerprint == fingerprint }?.id
                    ?: fingerprint?.let { db.whisper().forFingerprint(it).lastOrNull { job -> job.status == DownloadStatus.DONE }?.id }
            } else null
            if (translationJobId == null) {
                withContext(NonCancellable) {
                    target.writeTextAtomically(payload)
                    whisperJobId?.let { db.whisper().markTranslated(it, System.currentTimeMillis()) }
                    invalidate()
                }
                true
            } else publishTranslation(target, payload, whisperJobId, translationJobId)
        }
    }

    /** 调用者持有 generatedWriteLock。写入发布记录后，中断由启动恢复接手。 */
    internal suspend fun publishTranslation(
        target: File,
        payload: String,
        whisperJobId: Long?,
        translationJobId: Long?,
    ): Boolean {
        coroutineContext.ensureActive()
        return withContext(NonCancellable) {
            val now = System.currentTimeMillis()
            val publication = SubtitlePublicationEntity(target.name, payload, whisperJobId, translationJobId, now)
            val staged = db.withTransaction {
                if (translationJobId != null) {
                    if (db.translations().get(translationJobId)?.status != DownloadStatus.RUNNING) return@withTransaction false
                } else if (whisperJobId != null && db.whisper().get(whisperJobId)?.translateStatus != TranslateMark.RUNNING) {
                    return@withTransaction false
                }
                db.translations().stagePublication(publication)
                true
            }
            if (!staged) return@withContext false
            val published = finishPublication(publication)
            if (published) invalidate()
            published
        }
    }

    internal suspend fun recoverPublications() = generatedWriteLock.withLock {
        for (publication in db.translations().pendingPublications()) {
            try {
                if (finishPublication(publication, recovering = true)) invalidate()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 单条恢复失败不阻塞其余发布补完，记录留待下次启动。
            }
        }
    }

    private suspend fun finishPublication(publication: SubtitlePublicationEntity, recovering: Boolean = false): Boolean {
        val target = File(File(context.filesDir, "subtitles"), publication.filename)
        var written = false
        // 非法文件名（如目录穿越）与写入失败同走失败分支，不抛出中断恢复。
        if (target.name == publication.filename) {
            try {
                target.writeTextAtomically(publication.payload)
                written = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 含非 IO 异常（如 SecurityException）：清发布记录并标失败，可重试。
            }
        }
        if (!written) {
            db.withTransaction {
                db.translations().clearPublication(publication.filename)
                publication.translationJobId?.let { db.translations().failIfActive(it, PersistedErrors.TRANSLATE_SAVE_FAILED) }
                    ?: publication.whisperJobId?.let { db.whisper().markTranslateFailed(it, PersistedErrors.TRANSLATE_SAVE_FAILED) }
                if (recovering) finishProgressPublication(publication, false)
            }
            return false
        }
        return try {
            db.withTransaction {
                publication.translationJobId?.let { db.translations().finishIfActive(it) }
                publication.whisperJobId?.let { db.whisper().markTranslated(it, publication.translatedAt) }
                if (recovering) finishProgressPublication(publication, true)
                db.translations().clearPublication(publication.filename)
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 文件已写成但 DB 提交失败：保留发布记录，下次启动幂等重写再提交。
            false
        }
    }

    /** 发布恢复先完成，再登记进度；丢失的进程内统计不推算补齐。调用者持有事务。 */
    private suspend fun finishProgressPublication(publication: SubtitlePublicationEntity, success: Boolean) {
        val translationId = publication.translationJobId
        val whisperId = publication.whisperJobId
        val raw = translationId?.let { db.translations().get(it)?.progressSummary }
            ?: whisperId?.let { db.whisper().get(it)?.progressSummary }
        val summary = com.nanahoshi.audioplayer.data.decodeProgressSummary(raw)
        val part = com.nanahoshi.audioplayer.data.ProgressPart.TRANSLATION
        val previous = summary.parts[part]
        val result = com.nanahoshi.audioplayer.data.TaskSegmentSummary(
            phase = com.nanahoshi.audioplayer.data.ProgressPhase.SAVING_TRANSLATION,
            result = if (success) com.nanahoshi.audioplayer.data.ProgressResult.COMPLETED else com.nanahoshi.audioplayer.data.ProgressResult.FAILED,
            failurePhase = com.nanahoshi.audioplayer.data.ProgressPhase.SAVING_TRANSLATION.takeUnless { success },
            model = previous?.model, platform = previous?.platform,
        )
        val encoded = summary.merge(part, result).encode()
        if (translationId != null) db.translations().setProgressSummary(translationId, encoded)
        else if (whisperId != null) db.whisper().setProgressSummary(whisperId, encoded)
    }

    private suspend fun generatedState(row: QueuedTrack): GeneratedState {
        val job = findGeneratedJob(row) ?: return GeneratedState.Missing
        val file = generatedFile(job.fingerprint)
        if (file.name == ".json" || !file.isFile) return GeneratedState.Missing
        val document = parseGeneratedSubtitles(file.readText()) ?: return GeneratedState.Corrupt
        return GeneratedState.Ready(document, job.fingerprint)
    }

    private sealed interface GeneratedState {
        data class Ready(val document: GeneratedSubtitles, val fingerprint: String) : GeneratedState
        data object Corrupt : GeneratedState
        data object Missing : GeneratedState
    }

    private suspend fun removeGenerated(input: QueuedTrack, excludedTrackIds: Set<Long> = emptySet()) =
        withContext(NonCancellable) {
            db.withTransaction {
                val row = withRemovalIdentity(input)
                val effectiveExcludedTrackIds = excludedTrackIds + listOfNotNull(row.trackId?.takeIf { it > 0L })
                val jobs = relatedDone(row)
                val pending = db.translations().pendingPublications().map { it.filename }.toSet()
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
                removalOverlayBases(removable, bili).flatMap(::overlayVersions).distinct()
                    .filter { it.name !in pending }.forEach { it.delete() }
                removable.mapNotNull { it.trackId }.distinct().forEach { db.translations().deleteInactiveForTrack(it) }
                removable.mapNotNull { it.remoteUrl }.filter { it.isNotBlank() }.distinct().forEach {
                    if (!hasOtherRemoteUrlExcluding(it, effectiveExcludedTrackIds)) db.translations().deleteInactiveForUrl(it)
                }
                if (bili != null) db.translations().deleteInactiveForBili(bili.first, bili.second)
            }
        }

    private suspend fun fingerprintIsActive(fingerprint: String, jobs: List<WhisperJobEntity>): Boolean =
        jobs.any { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING || TranslateMark.isActive(it.translateStatus) } ||
            db.translations().active().any { it.generatedFingerprint == fingerprint }

    private suspend fun hasSurvivingTrack(done: List<WhisperJobEntity>, excluded: Set<Long>): Boolean {
        val otherIds = done.mapNotNull { it.trackId }.filter { it !in excluded }.distinct()
        if (otherIds.chunked(900).any { db.tracks().getByIds(it).isNotEmpty() }) return true
        return done.any { job ->
            job.localPath?.let { db.tracks().findByLocalUri(it)?.id?.let { id -> id !in excluded } } == true ||
                hasOtherRemoteUrlExcluding(job.awaitRemoteUrl, excluded)
        }
    }

    private suspend fun hasOtherRemoteUrlExcluding(url: String?, excluded: Set<Long>): Boolean =
        !url.isNullOrBlank() && db.tracks().idsForRemoteUrl(url).any { it !in excluded }

    private suspend fun storedRemovalRow(trackId: Long?, localPath: String?, remoteUrl: String?): QueuedTrack {
        val track = trackId?.takeIf { it > 0L }?.let { db.tracks().getById(it) }
        return if (track == null) storedQueueRow(trackId, localPath, remoteUrl)
        else subtitleQueueRow(track).copy(localUri = localPath, remoteUrl = remoteUrl ?: track.remoteUrl)
    }

    private suspend fun withRemovalIdentity(row: QueuedTrack): QueuedTrack {
        val identity = jaSubtitleIdentity(row) ?: row.localUri?.let { db.jaDownloads().entryForPath(it) }
            ?.let { jaWorkFileIdentity(it.rj, it.relativePath) }
        return if (identity == null) row else row.copy(remoteUrl = identity)
    }

    private suspend fun removalBiliIdentity(row: QueuedTrack): Pair<String, Long>? =
        biliIdentityFromPath(row.localUri) ?: biliIdentityFromPath(storedAudioPath(row))

    private fun removalOverlayBases(identities: List<QueuedTrack>, bili: Pair<String, Long>?): List<File> =
        (identities.flatMap { listOf(overlayFile(it), overlayFile(it, useBiliIdentity = false)) } +
            listOfNotNull(bili?.let { (bvid, cid) ->
                overlayFileName(null, null, null, null, bvid, cid)?.let { File(File(context.filesDir, "subtitles"), it) }
            })).distinct()

    /** B 站音轨恒存于 filesDir/bilibili/{bvid}/{cid}.m4s（AGENTS §6.1），从路径确定性还原身份。 */
    private fun biliIdentityFromPath(path: String?): Pair<String, Long>? {
        if (path.isNullOrBlank() || !path.startsWith("/")) return null
        val file = File(path)
        val dir = file.parentFile ?: return null
        if (dir.parentFile != File(context.filesDir, "bilibili")) return null
        val bvid = dir.name.takeIf { BVID_ROW.matches(it) } ?: return null
        val cid = file.nameWithoutExtension.toLongOrNull()?.takeIf { it > 0L } ?: return null
        return bvid to cid
    }

    private suspend fun relatedDone(row: QueuedTrack): List<WhisperJobEntity> {
        val found = mutableListOf<WhisperJobEntity>()
        row.trackId?.takeIf { it > 0L }?.let { found += db.whisper().doneForTrack(it) }
        subtitleIdentity(row)
            ?.let { found += db.whisper().doneForUrl(it) }
        listOfNotNull(row.localUri, storedAudioPath(row)).distinct().forEach { found += db.whisper().doneForPath(it) }
        return found.distinctBy { it.id }
    }

    private suspend fun findGeneratedJob(row: QueuedTrack): WhisperJobEntity? {
        val jobs = buildList {
            row.trackId?.let { db.whisper().latestDoneForTrack(it) }?.let(::add)
            subtitleIdentity(row)
                ?.let { db.whisper().latestDoneForUrl(it) }?.let(::add)
            storedAudioPath(row)?.let { db.whisper().latestDoneForPath(it) }?.let(::add)
        }
        return jobs.maxByOrNull { it.id }
    }

    internal fun expectedOverlayFile(row: QueuedTrack, source: List<SubtitleCue>): File {
        val base = overlayFile(row)
        val fingerprint = sourceFingerprint(source)
        return File(base.parentFile, "${base.nameWithoutExtension}-${fingerprint.take(8)}.json")
    }

    private fun sourceFingerprint(cues: List<SubtitleCue>) = subtitleSourceFingerprint(cues, includeTranslation = true)

    private fun withOverlay(
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

    private fun overlayVersions(base: File): List<File> =
        base.parentFile?.listFiles { file ->
            file.isFile && (file.name == base.name || (file.name.startsWith("${base.nameWithoutExtension}-") && file.extension == "json"))
        }?.toList().orEmpty()

    private fun overlayFile(row: QueuedTrack, useBiliIdentity: Boolean = true): File {
        val bili = if (useBiliIdentity) bilibiliRowTrack(row) else null
        return File(File(context.filesDir, "subtitles"),
            overlayFileName(row.trackId, row.jaAudioIdentity() ?: row.remoteUrl, row.localUri, row.queueItemId, bili?.bvid, bili?.cid)!!)
    }

    private suspend fun jaSubtitleIdentity(row: QueuedTrack): String? = row.jaAudioIdentity()
        ?: row.trackId?.let { db.japaneseAsmr().mediaForTrack(it) }?.let { jaIdentity(it.postId, it.mediaKey) }

    private suspend fun subtitleIdentity(row: QueuedTrack): String? = jaSubtitleIdentity(row)
        ?: row.remoteUrl?.takeIf { row.source != TrackSource.JAPANESE_ASMR && it.isNotBlank() &&
            (row.source != TrackSource.BILIBILI || it.startsWith("/")) }

    private suspend fun storedAudioPath(row: QueuedTrack): String? {
        val track = row.trackId?.let { db.tracks().getById(it) }
        val bili = bilibiliRowTrack(row)
        if (bili != null) {
            com.nanahoshi.audioplayer.data.readyBiliAudio(context.filesDir, bili.bvid, bili.cid)?.let { return it.absolutePath }
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

    internal fun generatedFile(fingerprint: String): File {
        val safe = fingerprint.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }
        return File(File(context.filesDir, "subtitles"), "$safe.json")
    }

    private fun isFile(path: String?): Boolean =
        !path.isNullOrBlank() && path.startsWith("/") && File(path).isFile

    private suspend fun bilibiliCues(track: TrackEntity): TrackSubtitles {
        val bvid = track.bvid ?: return TrackSubtitles(emptyList())
        val cid = track.cid ?: return TrackSubtitles(emptyList())
        // 下载音轨时顺带存下的字幕，播放时优先读本地，不再依赖登录和网络。
        readBiliSubtitle(context.filesDir, bvid, cid)?.let { document ->
            val cues = parseSubtitleDocument(document)
            if (cues.isNotEmpty()) return TrackSubtitles(
                cues, language = biliSubtitleLanguage(document), sourceKind = SubtitleSourceKind.BILIBILI,
                allowLegacyOverlay = true,
            )
        }
        return when (val result = bilibili.subtitleDocument(bvid, cid)) {
            is BiliSubtitle.Ready -> {
                val cues = parseSubtitleDocument(result.document)
                // 音轨已经在本地时，顺手把这次拿到的字幕补存下来。
                if (cues.isNotEmpty() && LocalAudio.hasStoredCopy(track.localUri)) {
                    generatedWriteLock.withLock {
                        val path = if (track.id > 0) db.tracks().getById(track.id)?.localUri else track.localUri
                        if (LocalAudio.hasStoredCopy(path)) {
                            writeBiliSubtitle(context.filesDir, bvid, cid, result.document, result.language)
                        }
                    }
                }
                TrackSubtitles(cues, language = result.language, sourceKind = SubtitleSourceKind.BILIBILI, allowLegacyOverlay = true)
            }
            BiliSubtitle.NeedsLogin -> TrackSubtitles(emptyList(), context.getString(R.string.player_error_bili_subtitle_login))
            BiliSubtitle.Failed -> TrackSubtitles(emptyList(), context.getString(R.string.player_error_bili_subtitle_load))
            BiliSubtitle.Missing -> TrackSubtitles(emptyList())
        }
    }

    private suspend fun workCues(track: TrackEntity): TrackSubtitles {
        val catalog = loadWorkSubtitleCatalog(db, track.folderId) ?: return TrackSubtitles(emptyList())
        val match = catalog.match(track, crossSourceFallback())
        val candidates = WorkSubtitleMatcher.automaticCandidates(match, track, catalog.folders)
        for (candidate in candidates) {
            val cues = try {
                parseSubtitleDocument(librarySubtitleText(candidate.file, catalog.folders, catalog.jaFileIds).orEmpty())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            if (cues.isNotEmpty()) {
                val historyName = if (track.source == TrackSource.ASMR) pickSubtitleName(track.title,
                    catalog.files.filter {
                        it.folderId == track.folderId && resolveLibraryFileSource(it, catalog.jaFileIds) == TrackSource.ASMR
                    }.map { it.name },
                ) else null
                return TrackSubtitles(
                    cues, sourceKind = SubtitleSourceKind.LIBRARY_FILE, sourceLabel = candidate.file.name,
                    allowLegacyOverlay = candidate.scope == WorkSubtitleScope.SAME_FOLDER && candidate.file.name == historyName,
                )
            }
        }
        return TrackSubtitles(
            emptyList(), if (candidates.isNotEmpty()) context.getString(R.string.player_error_bound_subtitle_file) else null,
        )
    }

    private suspend fun librarySubtitleText(
        file: LibraryFileEntity, folders: Map<Long, FolderEntity>, jaFileIds: Set<Long>,
    ): String? {
        try {
            file.localUri?.let { readLocalTextPreview(context, it)?.text }?.let { return it }
        } catch (_: java.io.IOException) {
            // A removed local copy can still have a valid online source.
        } catch (_: SecurityException) {
            // A revoked local association can still have a valid online source.
        }
        if (file.remoteUrl.isBlank()) return null
        return when (val source = resolveLibraryFileSource(file, jaFileIds)) {
            TrackSource.ASMR -> asmr.readText(file.remoteUrl).text
            TrackSource.DLSITE -> dlsite?.readText(libraryFilePreview(file, folders, source))?.text
            else -> null
        }
    }

    private fun localCues(track: TrackEntity): TrackSubtitles {
        val uri = track.localUri ?: return TrackSubtitles(emptyList())
        val siblings = siblingsOf(uri)
        val audioName = audioFileName(uri).ifBlank { track.title }
        val historyName = pickSubtitleName(audioName, siblings.map { it.name })
        val matches = siblings.mapNotNull { candidate ->
            val tier = subtitleNameTier(audioName, candidate.name) ?: return@mapNotNull null
            candidate to (tier.ordinal * 3 + checkNotNull(subtitleFormatRank(candidate.name)))
        }.groupBy { it.second }.toSortedMap()
        for (group in matches.values) {
            val candidate = group.singleOrNull()?.first ?: break
            val cues = parseSubtitleDocument(runCatching { candidate.read() }.getOrNull().orEmpty())
            if (cues.isNotEmpty()) return TrackSubtitles(
                cues, sourceKind = SubtitleSourceKind.LOCAL_FILE, sourceLabel = candidate.name,
                allowLegacyOverlay = candidate.name == historyName,
            )
        }
        return TrackSubtitles(emptyList())
    }

    private fun audioFileName(localUri: String): String {
        val uri = localUri.toUri()
        return if (uri.scheme == "content") {
            LocalAudio.displayName(context, uri)
        } else {
            File(uri.path ?: localUri).name
        }
    }

    private fun siblingsOf(localUri: String): List<NamedBytes> {
        val uri = localUri.toUri()
        if (uri.scheme == "content") return documentSiblings(uri)
        val file = File(if (uri.scheme == "file") uri.path.orEmpty() else localUri)
        val parent = file.parentFile ?: return emptyList()
        return parent.listFiles().orEmpty().filter { it.isFile }.map { child ->
            NamedBytes(child.name) { readLimited(child.inputStream()) }
        }
    }

    private fun documentSiblings(uri: Uri): List<NamedBytes> {
        return try {
            if (!DocumentsContract.isDocumentUri(context, uri)) return emptyList()
            val documentId = DocumentsContract.getDocumentId(uri)
            val slash = documentId.lastIndexOf('/')
            if (slash <= 0) return emptyList()
            val parentId = documentId.substring(0, slash)
            val authority = uri.authority ?: return emptyList()
            val children = DocumentsContract.buildChildDocumentsUri(authority, parentId)
            val rows = mutableListOf<NamedBytes>()
            context.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                while (cursor.moveToNext()) {
                    if (nameColumn < 0 || idColumn < 0) continue
                    val name = cursor.getString(nameColumn) ?: continue
                    val id = cursor.getString(idColumn) ?: continue
                    val child = DocumentsContract.buildDocumentUri(authority, id)
                    rows += NamedBytes(name) { readLimited(context.contentResolver.openInputStream(child)) }
                }
            }
            rows
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun readLimited(input: InputStream?): String? =
        input?.use { it.readRemoteTextPreview().text }

    private fun String.toUri(): Uri = when {
        startsWith("content:") || startsWith("file:") -> Uri.parse(this)
        else -> Uri.EMPTY
    }

    private class NamedBytes(val name: String, val read: () -> String?)

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

internal fun translationOverlayName(job: com.nanahoshi.audioplayer.data.db.TranslationJobEntity): String? =
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
    val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
    val key = digest.take(8).joinToString("") { "%02x".format(it) }
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
