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
import com.nanahoshi.audioplayer.data.readFullText
import com.nanahoshi.audioplayer.data.readLocalTextFull
import com.nanahoshi.audioplayer.data.resolveLibraryFileSource
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.LibraryFileEntity
import com.nanahoshi.audioplayer.data.db.SubtitlePublicationMeta
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
import kotlin.coroutines.coroutineContext

internal object SubtitleTranslationTarget {
    const val GENERATED = "GENERATED"
    const val OVERLAY = "OVERLAY"
}

enum class SubtitleSourceKind { NONE, GENERATED, LIBRARY_FILE, EXTERNAL_URI, APP_FILE, BILIBILI, LOCAL_FILE }

enum class SubtitleLanguageDeleteResult { DELETED, BUSY, NOT_APPLICABLE, FAILED }

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
    internal val context: Context,
    internal val db: AppDatabase,
    internal val bilibili: BilibiliClient,
    internal val asmr: AsmrClient,
    internal val dlsite: DlsiteClient? = null,
    internal val crossSourceFallback: () -> Boolean = { true },
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

    suspend fun discard(row: QueuedTrack) = withContext(Dispatchers.IO) {
        generatedWriteLock.withLock { removeGenerated(row) }
        db.translations().deleteInactiveForQueue(row.queueItemId)
        invalidate()
    }

    suspend fun discardGeneratedOnly(row: QueuedTrack) = withContext(Dispatchers.IO) {
        generatedWriteLock.withLock {
            val job = findGeneratedJob(row) ?: return@withLock
            val file = generatedFile(job.fingerprint)
            if (file.name == ".json" || db.translations().hasPendingPublication(file.name)) return@withLock
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
                if (db.translations().hasPendingPublication(file.name) ||
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
    suspend fun discardTranslationOverlay(file: File) {
        deleteTranslationOverlayInternal(file, blockWhileActive = false)
    }

    suspend fun deleteGeneratedLanguage(
        fingerprint: String,
        target: SubtitleLanguageDelete,
    ): SubtitleLanguageDeleteResult = withContext(Dispatchers.IO) {
        generatedWriteLock.withLock {
            val file = generatedFile(fingerprint)
            if (file.name == ".json" || !file.isFile) return@withLock SubtitleLanguageDeleteResult.NOT_APPLICABLE
            if (db.translations().hasPendingPublication(file.name)) {
                return@withLock SubtitleLanguageDeleteResult.BUSY
            }
            val jobs = db.whisper().forFingerprint(fingerprint)
            if (fingerprintIsActive(fingerprint, jobs)) return@withLock SubtitleLanguageDeleteResult.BUSY
            val document = runCatching { parseGeneratedSubtitles(file.readText()) }.getOrNull()
                ?: return@withLock SubtitleLanguageDeleteResult.NOT_APPLICABLE
            val next = when (target) {
                SubtitleLanguageDelete.CHINESE -> document.withoutChinese()
                SubtitleLanguageDelete.ORIGINAL -> document.withoutOriginal()
            } ?: return@withLock SubtitleLanguageDeleteResult.NOT_APPLICABLE
            val payload = encodeGeneratedCues(next.cues, next.origin, revisedCues = null, language = next.language)
            withContext(NonCancellable) {
                try {
                    file.writeTextAtomically(payload)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return@withContext SubtitleLanguageDeleteResult.FAILED
                }
                if (target == SubtitleLanguageDelete.CHINESE) {
                    db.withTransaction {
                        for (job in jobs) {
                            if (job.status != DownloadStatus.DONE) continue
                            val summary = com.nanahoshi.audioplayer.data.decodeProgressSummary(job.progressSummary)
                                .without(com.nanahoshi.audioplayer.data.ProgressPart.TRANSLATION)
                                .encodeOrNull()
                            db.whisper().clearTranslationForId(job.id, summary)
                        }
                        db.translations().deleteInactiveForFingerprint(fingerprint)
                    }
                }
                invalidate()
                SubtitleLanguageDeleteResult.DELETED
            }
        }
    }

    suspend fun deleteTranslationOverlayByName(filename: String): SubtitleLanguageDeleteResult {
        val dir = File(context.filesDir, "subtitles")
        val file = File(dir, filename)
        if (file.name != filename || !filename.startsWith("extra-") || !filename.endsWith(".json")) {
            return SubtitleLanguageDeleteResult.NOT_APPLICABLE
        }
        return deleteTranslationOverlayChecked(file)
    }

    suspend fun deleteTranslationOverlayChecked(file: File): SubtitleLanguageDeleteResult =
        deleteTranslationOverlayInternal(file, blockWhileActive = true)

    internal fun invalidate() {
        _revision.update { it + 1 }
    }

    /** 是否有可保留的生成字幕；删除音频前据此决定要不要询问。 */
    suspend fun hasGenerated(trackId: Long?, localPath: String?, remoteUrl: String?): Boolean = withContext(Dispatchers.IO) {
        hasRemovableSubtitles(storedRemovalRow(trackId, localPath, remoteUrl))
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
            document.displayCues, document.cues.isEmpty(), true, language = document.language, origin = document.origin,
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
                encodeGeneratedCues(document.cues, document.origin, revisedCues = cues, language = document.language)
            } else {
                // Keep the original attachment fingerprint so loading can apply the final
                // translation directly. A polish is checked against the pre-polish cues above.
                encodeGeneratedCues(cues, null, sourceFingerprint = expected?.sourceCues?.let { sourceFingerprint(it) })
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
            val stagedFile = if (payload.length > INLINE_PUBLICATION_CHAR_LIMIT) {
                publicationFile(target.name).also { it.writeTextAtomically(payload) }
            } else null
            val dbPayload = stagedFile?.let { "$PUBLICATION_FILE_PREFIX${it.name}" } ?: payload
            val publication = SubtitlePublicationEntity(target.name, dbPayload, whisperJobId, translationJobId, now)
            val staged = try {
                db.withTransaction {
                    if (translationJobId != null) {
                        if (db.translations().get(translationJobId)?.status != DownloadStatus.RUNNING) return@withTransaction false
                    } else if (whisperJobId != null && db.whisper().get(whisperJobId)?.translateStatus != TranslateMark.RUNNING) {
                        return@withTransaction false
                    }
                    db.translations().stagePublication(publication)
                    true
                }
            } catch (error: Exception) {
                stagedFile?.delete()
                throw error
            }
            if (!staged) {
                stagedFile?.delete()
                return@withContext false
            }
            val meta = SubtitlePublicationMeta(
                filename = target.name,
                whisperJobId = whisperJobId,
                translationJobId = translationJobId,
                translatedAt = now,
                payloadLength = dbPayload.length,
            )
            val published = finishPublication(meta, preloadedPayload = payload, recovering = false)
            if (published) invalidate()
            published
        }
    }

    internal suspend fun recoverPublications() = generatedWriteLock.withLock {
        val pending = db.translations().pendingPublications()
        for (meta in pending) {
            try {
                if (finishPublication(meta, preloadedPayload = null, recovering = true)) invalidate()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 单条恢复失败不阻塞其余发布补完，记录留待下次启动。
            }
        }
        val activeStagedNames = db.translations().pendingPublicationFilenames()
            .mapTo(mutableSetOf()) { publicationFile(it).name }
        File(context.filesDir, PUBLICATION_STAGING_DIR).listFiles().orEmpty()
            .filter { it.isFile && it.name !in activeStagedNames }
            .forEach { it.delete() }
    }

    internal fun generatedFile(fingerprint: String): File {
        val safe = fingerprint.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }
        return File(File(context.filesDir, "subtitles"), "$safe.json")
    }

    internal fun expectedOverlayFile(row: QueuedTrack, source: List<SubtitleCue>): File {
        val base = overlayFile(row)
        val fingerprint = sourceFingerprint(source)
        return File(base.parentFile, "${base.nameWithoutExtension}-${fingerprint.take(8)}.json")
    }
}