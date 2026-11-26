package com.nanahoshi.audioplayer.playback

import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.fileExtension
import com.nanahoshi.audioplayer.data.resolveLibraryFileSource
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.LibraryFileEntity
import com.nanahoshi.audioplayer.data.db.TrackEntity
import java.text.Normalizer

enum class WorkSubtitleScope { SAME_FOLDER, SAME_SOURCE, CROSS_SOURCE }
enum class SubtitleMatchTier { EXACT, NORMALIZED, TRACK_NUMBER }

data class WorkSubtitleCandidate(
    val file: LibraryFileEntity,
    val source: TrackSource?,
    val scope: WorkSubtitleScope,
    val tier: SubtitleMatchTier?,
    val relativePath: String,
)

data class WorkSubtitleMatchResult(val best: LibraryFileEntity?, val candidates: List<WorkSubtitleCandidate>)

enum class BatchPairMode { BY_NAME, BY_ORDER }

data class SubtitleCandidateEntry(
    val name: String,
    val fileId: Long? = null,
    val uri: String? = null,
    /** 相对所选字幕文件夹的路径，用于区分嵌套目录中的同名文件。 */
    val relativePath: String = name,
)

object WorkSubtitleMatcher {
    fun match(
        track: TrackEntity,
        foldersById: Map<Long, FolderEntity>,
        workFiles: List<LibraryFileEntity>,
        jaFileIds: Set<Long> = emptySet(),
        activeDlsiteFileIds: Set<Long>? = null,
        crossSourceFallback: Boolean = true,
        folderTracks: List<TrackEntity> = listOf(track),
    ): WorkSubtitleMatchResult {
        val root = nearestSubtitleWorkRoot(track.folderId, foldersById)
        val trackPath = relativeFolderPath(track.folderId, root?.id, foldersById)
        val eligible = workFiles.mapNotNull { file ->
            if (subtitleFormatRank(file.name) == null) return@mapNotNull null
            val source = resolveLibraryFileSource(file, jaFileIds)
            if (source == TrackSource.DLSITE && activeDlsiteFileIds != null && file.id !in activeDlsiteFileIds) {
                return@mapNotNull null
            }
            val sameWork = root != null && nearestSubtitleWorkRoot(file.folderId, foldersById)?.id == root.id
            val scope = when {
                file.folderId == track.folderId && source == track.source -> WorkSubtitleScope.SAME_FOLDER
                sameWork && source == track.source -> WorkSubtitleScope.SAME_SOURCE
                sameWork && crossSourceFallback -> WorkSubtitleScope.CROSS_SOURCE
                else -> return@mapNotNull null
            }
            val path = relativeFolderPath(file.folderId, root?.id, foldersById)
            RankedCandidate(
                WorkSubtitleCandidate(
                    file, source, scope, subtitleNameTier(audioName(track), file.name), (path + file.name).joinToString("/"),
                ),
                pathOverlap(trackPath, path),
            )
        }
        val ranked = eligible.map { entry ->
            if (entry.candidate.tier != null || !numberMatches(track, entry.candidate, folderTracks, eligible)) entry
            else entry.copy(candidate = entry.candidate.copy(tier = SubtitleMatchTier.TRACK_NUMBER))
        }.sortedWith(compareBy<RankedCandidate> { it.candidate.tier == null }.then(confidence))
        val best = ranked.firstOrNull()?.takeIf { first ->
            first.candidate.tier != null && ranked.getOrNull(1)?.let { confidence.compare(first, it) != 0 } != false
        }?.candidate?.file
        return WorkSubtitleMatchResult(best, ranked.map { it.candidate })
    }

    fun suggestBatchPairs(
        tracks: List<TrackEntity>,
        candidates: List<SubtitleCandidateEntry>,
        mode: BatchPairMode,
    ): List<Pair<TrackEntity, SubtitleCandidateEntry>> {
        if (mode == BatchPairMode.BY_ORDER) return tracks.zip(candidates)
        // 候选的规范化、扩展名与格式排名只计算一次，避免 O(T × S) 的重复 NFKC 与正则。
        val prepared = candidates.mapNotNull { entry ->
            val rank = subtitleFormatRank(entry.name) ?: return@mapNotNull null
            val base = entry.name.substringBeforeLast('.')
            PreparedCandidate(entry, rank, base, normalizedSubtitleStem(base))
        }
        val proposed = tracks.mapNotNull { track ->
            val name = audioName(track)
            val nameWithoutExtension = name.removeAudioExtension()
            val stem = normalizedSubtitleStem(name)
            var best: PreparedCandidate? = null
            var bestScore = Int.MAX_VALUE
            var tied = false
            for (candidate in prepared) {
                val tier = when {
                    candidate.base.equals(name, ignoreCase = true) ||
                        candidate.base.equals(nameWithoutExtension, ignoreCase = true) -> SubtitleMatchTier.EXACT
                    candidate.stem.isNotBlank() && candidate.stem == stem -> SubtitleMatchTier.NORMALIZED
                    else -> null
                } ?: continue
                val score = tier.ordinal * 3 + candidate.rank
                when {
                    score < bestScore -> {
                        bestScore = score
                        best = candidate
                        tied = false
                    }
                    score == bestScore -> tied = true
                }
            }
            val winner = best ?: return@mapNotNull null
            if (tied) null else track to winner.entry
        }
        val uses = proposed.groupingBy { it.second }.eachCount()
        return proposed.filter { uses[it.second] == 1 }
    }

    private data class PreparedCandidate(
        val entry: SubtitleCandidateEntry,
        val rank: Int,
        val base: String,
        val stem: String,
    )

    /** Unmatched files remain selectable. Equal confidence groups are skipped during automatic playback. */
    internal fun automaticCandidates(result: WorkSubtitleMatchResult, track: TrackEntity, folders: Map<Long, FolderEntity>): List<WorkSubtitleCandidate> {
        val root = nearestSubtitleWorkRoot(track.folderId, folders)
        val trackPath = relativeFolderPath(track.folderId, root?.id, folders)
        val ranked = result.candidates.filter { it.tier != null }.map {
            RankedCandidate(it, pathOverlap(trackPath, relativeFolderPath(it.file.folderId, root?.id, folders)))
        }
        return ranked.filterIndexed { index, entry ->
            (index == 0 || confidence.compare(ranked[index - 1], entry) != 0) &&
                (index == ranked.lastIndex || confidence.compare(entry, ranked[index + 1]) != 0)
        }.map { it.candidate }
    }

    private fun numberMatches(
        track: TrackEntity,
        candidate: WorkSubtitleCandidate,
        tracks: List<TrackEntity>,
        eligible: List<RankedCandidate>,
    ): Boolean {
        val number = leadingNumber(audioName(track)) ?: return false
        if (number != leadingNumber(candidate.file.name)) return false
        val audio = tracks.filter { it.folderId == track.folderId && it.source == track.source }
            .map { leadingNumber(audioName(it)) }
        val subtitles = eligible.filter {
            it.candidate.file.folderId == candidate.file.folderId && it.candidate.source == candidate.source
        }.map { leadingNumber(it.candidate.file.name) }
        return audio.isNotEmpty() && null !in audio && audio.size == audio.toSet().size &&
            audio.size == subtitles.size && audio.toSet() == subtitles.toSet()
    }

    private data class RankedCandidate(val candidate: WorkSubtitleCandidate, val pathOverlap: Int)

    private val confidence = compareBy<RankedCandidate> { it.candidate.scope.ordinal }
        .thenBy { it.candidate.tier?.ordinal ?: Int.MAX_VALUE }
        .thenBy { if (it.candidate.scope == WorkSubtitleScope.CROSS_SOURCE && it.candidate.source != TrackSource.ASMR) 1 else 0 }
        .thenBy { subtitleFormatRank(it.candidate.file.name) }
        .thenByDescending { it.pathOverlap }
}

internal fun nearestSubtitleWorkRoot(folderId: Long?, folders: Map<Long, FolderEntity>): FolderEntity? =
    com.nanahoshi.audioplayer.data.FolderTree.nearestRoot(folderId, folders) {
        !it.asmrSourceId.isNullOrBlank() || it.biliBvid != null
    }

private fun relativeFolderPath(id: Long?, rootId: Long?, folders: Map<Long, FolderEntity>): List<String> =
    com.nanahoshi.audioplayer.data.FolderTree.relativePath(id, rootId, folders)

private fun pathOverlap(track: List<String>, subtitle: List<String>): Int {
    val trackParts = track.map(::normalizedSeparators)
    val subtitleParts = subtitle.map(::normalizedSeparators)
    val suffix = trackParts.asReversed().zip(subtitleParts.asReversed()).takeWhile { (a, b) -> a == b }.size
    return suffix + trackParts.toSet().intersect(subtitleParts.toSet()).size
}

internal fun subtitleNameTier(audioName: String, name: String): SubtitleMatchTier? {
    if (subtitleFormatRank(name) == null) return null
    val base = name.substringBeforeLast('.')
    if (base.equals(audioName, ignoreCase = true) || base.equals(audioName.removeAudioExtension(), ignoreCase = true)) {
        return SubtitleMatchTier.EXACT
    }
    val normalized = normalizedSubtitleStem(base)
    return SubtitleMatchTier.NORMALIZED.takeIf { normalized.isNotBlank() && normalized == normalizedSubtitleStem(audioName) }
}

internal fun subtitleFormatRank(name: String): Int? = when (fileExtension(name)) {
    "lrc" -> 0
    "vtt" -> 1
    "srt" -> 2
    else -> null
}

private fun audioName(track: TrackEntity) = track.title.substringAfterLast('/')
private fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase()
private fun normalizedSeparators(value: String) = normalized(value).replace(separators, " ").trim()
private fun leadingNumber(name: String) = trackNumber.find(normalized(name))?.groupValues?.get(1)?.toIntOrNull()
private fun String.removeAudioExtension(): String = replace(audioExtension, "")

private fun normalizedSubtitleStem(value: String): String {
    var stem = normalized(value).trim()
    do {
        val previous = stem
        stem = stem.replace(languageSuffix, "").trim().removeAudioExtension().trim()
    } while (stem != previous)
    return normalizedSeparators(stem)
}

private val audioExtension = Regex("""\.(mp3|wav|flac|m4a|aac|ogg|opus|wma|alac)$""", RegexOption.IGNORE_CASE)
private val separators = Regex("""[\s_\-]+""")
private val trackNumber = Regex("""^\s*(\d+)(?!\d)(?:[^\d]|$)""")
private const val languageTag = "zh(?:[-_](?:cn|tw|hans|hant))?|cn|ja|jp|sub(?:title)?s?|中文|简体|簡體|繁体|繁體|中字|字幕"
private val languageSuffix = Regex("""(?:\[(?:$languageTag)]|\((?:$languageTag)\)|【(?:$languageTag)】|[._\- ](?:$languageTag))$""")
