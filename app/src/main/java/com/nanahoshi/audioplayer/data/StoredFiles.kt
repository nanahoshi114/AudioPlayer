package com.nanahoshi.audioplayer.data

import android.content.Context
import android.net.Uri
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.dlsite.DlsiteClient
import com.nanahoshi.audioplayer.playback.SubtitleSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class StoredKind {
    AUDIO,
    GENERATED,
    SUBTITLE,
}

data class StoredItem(
    val path: String,
    val name: String,
    val kind: StoredKind,
    val workTitle: String?,
    val workCode: String?,
    val sourceId: String?,
    val trackId: Long?,
    val remoteUrl: String?,
    val audioPath: String?,
    val bytes: Long = 0,
)

class StoredCatalog(
    context: Context,
    private val db: AppDatabase,
    private val subtitles: SubtitleSource,
) {
    private val appContext = context.applicationContext
    private val filesDir = appContext.filesDir
    private val asmrRoot = File(filesDir, "asmr")
    private val dlsiteRoot = File(filesDir, "dlsite")
    private val biliRoot = File(filesDir, "bilibili")

    suspend fun list(): List<StoredItem> = withContext(Dispatchers.IO) {
        val asmr = asmrRoot.canonicalFile
        val dlsite = dlsiteRoot.canonicalFile
        val bili = biliRoot.canonicalFile
        val folders = db.folders().all()
        val byId = folders.associateBy { it.id }
        val bySource = HashMap<String, FolderEntity>()
        val byWorkId = HashMap<Long, FolderEntity>()
        for (folder in folders) {
            val source = folder.asmrSourceId?.trim()?.takeIf { it.isNotEmpty() }
            if (source != null) {
                bySource.putIfAbsent(source, folder)
                bySource.putIfAbsent(source.lowercase(), folder)
                val canonical = DlsiteClient.canonicalWorkno(source)
                bySource.putIfAbsent(canonical, folder)
                bySource.putIfAbsent(canonical.lowercase(), folder)
            }
            val workId = folder.asmrWorkId
            if (workId != null && (source != null || byWorkId[workId] == null)) {
                byWorkId[workId] = folder
            }
        }
        val audios = LinkedHashMap<String, AudioRef>()
        val looseSubtitles = mutableListOf<ListedSubtitle>()
        for (track in db.tracks().onlineWithLocal()) {
            val stored = track.localUri ?: continue
            val file = File(stored)
            if (!file.isFile || !inside(file, asmr, dlsite, bili)) continue
            rememberFile(
                file,
                stored,
                track.id,
                track.remoteUrl,
                null,
                workFolder(track.folderId, byId),
                file.name,
                null,
                audios,
                looseSubtitles,
                asmr,
                dlsite,
                bili,
                bySource,
                byWorkId,
            )
        }
        for (track in db.tracks().biliWithLocal()) {
            val stored = track.localUri ?: continue
            val file = File(stored)
            if (!file.isFile || !inside(file, asmr, dlsite, bili)) continue
            rememberFile(
                file,
                stored,
                track.id,
                track.remoteUrl,
                track.bvid,
                null,
                track.title.ifBlank { file.name },
                track.artist,
                audios,
                looseSubtitles,
                asmr,
                dlsite,
                bili,
                bySource,
                byWorkId,
            )
        }
        for (row in db.downloads().finished()) {
            val stored = row.localPath ?: continue
            val file = File(stored)
            if (!file.isFile || !inside(file, asmr, dlsite, bili)) continue
            val code = realCode(row.workKey)
            val biliFile = under(file, bili)
            rememberFile(
                file,
                stored,
                row.trackId.takeIf { it > 0L },
                row.remoteUrl,
                code,
                if (biliFile) null else code?.let { lookupSource(it, bySource) },
                if (biliFile) row.title.ifBlank { file.name } else file.name,
                null,
                audios,
                looseSubtitles,
                asmr,
                dlsite,
                bili,
                bySource,
                byWorkId,
            )
        }
        val jobs = db.whisper().done().map {
            ListedJob(it.id, it.fingerprint, it.trackId, it.localPath, it.awaitRemoteUrl, it.title)
        }
        val scannedWorks = mutableSetOf<String>()
        val listedAudios = audios.values.map { audio ->
            if (under(audio.file, bili)) {
                ListedAudio(
                    file = audio.file,
                    storedPath = audio.storedPath,
                    trackId = audio.trackId,
                    remoteUrl = audio.remoteUrl,
                    workTitle = audio.artist?.takeIf { it.isNotBlank() },
                    workCode = realCode(audio.workKey) ?: biliFolderKey(audio.file, bili),
                    sourceId = null,
                    name = audio.displayName.ifBlank { audio.file.name },
                )
            } else {
                val work = describe(audio, asmr, dlsite, bili, bySource, byWorkId)
                ListedAudio(
                    file = audio.file,
                    storedPath = audio.storedPath,
                    trackId = audio.trackId,
                    remoteUrl = audio.remoteUrl,
                    workTitle = work.title,
                    workCode = work.code,
                    sourceId = work.sourceId,
                    name = audio.file.name,
                )
            }
        }
        val built = buildStoredItems(listedAudios, jobs, ::cueFile, looseSubtitles) { audio ->
            val folder = downloadWorkDirectory(audio.file, asmr, dlsite, bili)
            if (folder == null) {
                audio.file.parentFile?.listFiles().orEmpty().filter { it.isFile && isSubtitleFile(it.name) }
            } else {
                val key = runCatching { folder.canonicalPath }.getOrDefault(folder.absolutePath)
                if (scannedWorks.add(key)) subtitleFilesIn(folder) else emptyList()
            }
        }
        val seen = built.map { canonicalPath(it.path) }.toSet()
        val trackIds = jobs.mapNotNull { it.trackId }.filter { it > 0L }.distinct()
        val tracksById = if (trackIds.isEmpty()) emptyMap() else db.tracks().getByIds(trackIds).associateBy { it.id }
        val extra = remainingGeneratedItems(generatedCueFiles(File(filesDir, "subtitles")), seen, jobs) { file, job ->
            val track = job?.trackId?.let { tracksById[it] }
            val folder = track?.let { workFolder(it.folderId, byId) }
            val code = realCode(folder?.asmrSourceId)
            val titled = job?.title?.takeIf { it.isNotBlank() }
            val audioName = when {
                track != null -> audioDisplayName(track)
                titled != null -> titled
                else -> file.name
            }
            StoredItem(
                path = file.absolutePath,
                name = generatedSubtitleName(audioName),
                kind = StoredKind.GENERATED,
                workTitle = folder?.name?.takeIf { it.isNotBlank() },
                workCode = code,
                sourceId = code,
                trackId = track?.id ?: job?.trackId,
                remoteUrl = job?.remoteUrl ?: track?.remoteUrl,
                audioPath = job?.localPath ?: track?.localUri,
                bytes = file.length(),
            )
        }
        storedSort(built + extra)
    }

    suspend fun delete(items: List<StoredItem>) = withContext(Dispatchers.IO) {
        val audios = items.filter { it.kind == StoredKind.AUDIO }
        val rest = items.filter { it.kind != StoredKind.AUDIO }
        for (item in rest) deleteOne(item)
        for (item in audios) deleteOne(item)
    }

    private suspend fun deleteOne(item: StoredItem) {
        val file = File(item.path)
        if (!owned(file)) return
        when (item.kind) {
            StoredKind.GENERATED -> {
                subtitles.discardStored(item.trackId, item.audioPath, item.remoteUrl)
                if (file.isFile) file.delete()
            }
            StoredKind.SUBTITLE -> {
                if (file.isFile) file.delete()
            }
            StoredKind.AUDIO -> deleteAudio(item, file)
        }
    }

    private suspend fun deleteAudio(item: StoredItem, file: File) {
        subtitles.discardStored(item.trackId, item.path, item.remoteUrl)
        if (file.isFile && !file.delete()) return
        val tracks = buildSet {
            item.trackId?.takeIf { it > 0L }?.let { add(it) }
            db.tracks().findByLocalUri(item.path)?.id?.let { add(it) }
            val canonical = file.canonicalPath
            if (canonical != item.path) db.tracks().findByLocalUri(canonical)?.id?.let { add(it) }
        }
        for (id in tracks) {
            db.tracks().clearLocalUri(id)
            db.downloads().deleteFinishedForTrack(id)
        }
        db.downloads().deleteFinishedForPath(item.path)
        val canonical = runCatching { file.canonicalPath }.getOrNull()
        if (canonical != null && canonical != item.path) db.downloads().deleteFinishedForPath(canonical)
    }

    private fun describe(
        audio: AudioRef,
        asmr: File,
        dlsite: File,
        bili: File,
        bySource: Map<String, FolderEntity>,
        byWorkId: Map<Long, FolderEntity>,
    ): WorkLabel {
        val directory = rootKey(audio.file, asmr, dlsite, bili)
        val fromDirectory = directory?.toLongOrNull()?.let { byWorkId[it] }
        val fromKey = audio.workKey?.let { lookupSource(it, bySource) }
        val folder = audio.folder ?: fromKey ?: fromDirectory
        val code = realCode(folder?.asmrSourceId) ?: realCode(audio.workKey) ?: directory
        val sourceId = realCode(folder?.asmrSourceId) ?: realCode(audio.workKey)
        return WorkLabel(folder?.name?.takeIf { it.isNotBlank() }, code, sourceId)
    }

    private fun rememberFile(
        file: File,
        stored: String,
        trackId: Long?,
        remoteUrl: String?,
        workKey: String?,
        folder: FolderEntity?,
        displayName: String,
        artist: String?,
        audios: LinkedHashMap<String, AudioRef>,
        looseSubtitles: MutableList<ListedSubtitle>,
        asmr: File,
        dlsite: File,
        bili: File,
        bySource: Map<String, FolderEntity>,
        byWorkId: Map<Long, FolderEntity>,
    ) {
        val key = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        if (isSubtitleFile(file.name)) {
            val work = describe(
                AudioRef(file, stored, trackId, remoteUrl, workKey, folder, displayName, artist),
                asmr,
                dlsite,
                bili,
                bySource,
                byWorkId,
            )
            looseSubtitles += ListedSubtitle(file, work.title, work.code, work.sourceId)
            return
        }
        val existing = audios[key]
        if (existing != null) {
            audios[key] = existing.copy(
                remoteUrl = existing.remoteUrl ?: remoteUrl,
                workKey = existing.workKey ?: workKey,
                trackId = existing.trackId ?: trackId,
                folder = existing.folder ?: folder,
                displayName = existing.displayName.ifBlank { displayName },
                artist = existing.artist ?: artist,
            )
            return
        }
        audios[key] = AudioRef(file, stored, trackId, remoteUrl, workKey, folder, displayName, artist)
    }

    private fun cueFile(fingerprint: String): File? {
        val safe = cueKey(fingerprint)
        if (safe.isEmpty()) return null
        val file = File(File(filesDir, "subtitles"), "$safe.json")
        return file.takeIf { it.isFile }
    }

    private fun audioDisplayName(track: TrackEntity): String {
        val uri = track.localUri
        if (!uri.isNullOrBlank()) {
            when {
                uri.startsWith("/") -> {
                    val name = File(uri).name
                    if (name.isNotBlank()) return name
                }
                uri.startsWith("file:") -> {
                    val name = Uri.parse(uri).path?.let(::File)?.name.orEmpty()
                    if (name.isNotBlank()) return name
                }
                uri.startsWith("content:") -> {
                    val name = LocalAudio.displayName(appContext, Uri.parse(uri))
                    if (name.isNotBlank()) return name
                }
            }
        }
        return track.title.ifBlank { "音频" }
    }

    private fun lookupSource(code: String, bySource: Map<String, FolderEntity>): FolderEntity? {
        bySource[code]?.let { return it }
        bySource[code.lowercase()]?.let { return it }
        val canonical = DlsiteClient.canonicalWorkno(code)
        return bySource[canonical] ?: bySource[canonical.lowercase()]
    }

    private fun workFolder(start: Long?, byId: Map<Long, FolderEntity>): FolderEntity? {
        var id = start
        val seen = mutableSetOf<Long>()
        var fallback: FolderEntity? = null
        while (id != null && seen.add(id)) {
            val folder = byId[id] ?: return fallback
            if (!folder.asmrSourceId.isNullOrBlank()) return folder
            if (fallback == null && folder.asmrWorkId != null) fallback = folder
            id = folder.parentId
        }
        return fallback
    }

    private fun rootKey(file: File, asmr: File, dlsite: File, bili: File): String? {
        val path = file.canonicalFile
        val root = when {
            path.startsWith(asmr) -> asmr
            path.startsWith(dlsite) -> dlsite
            path.startsWith(bili) -> bili
            else -> return null
        }
        val parent = path.parentFile ?: return null
        val relative = parent.relativeTo(root).path
        if (relative.isBlank() || relative == ".") return null
        return realCode(relative.substringBefore('/'))
    }

    private fun inside(file: File, asmr: File, dlsite: File, bili: File): Boolean {
        val path = runCatching { file.canonicalFile }.getOrNull() ?: return false
        return path.startsWith(asmr) || path.startsWith(dlsite) || path.startsWith(bili)
    }

    private fun under(file: File, root: File): Boolean {
        val path = runCatching { file.canonicalFile }.getOrNull() ?: return false
        return path.startsWith(root)
    }

    private fun biliFolderKey(file: File, bili: File): String? {
        val path = runCatching { file.canonicalFile }.getOrNull() ?: return null
        if (!path.startsWith(bili)) return null
        val relative = path.parentFile?.relativeTo(bili)?.path ?: return null
        if (relative.isBlank() || relative == ".") return null
        return realCode(relative.substringBefore('/'))
    }

    private fun owned(file: File): Boolean {
        val root = filesDir.canonicalFile
        val path = runCatching { file.canonicalFile }.getOrNull() ?: return false
        return path == root || path.startsWith(root)
    }

    private data class AudioRef(
        val file: File,
        val storedPath: String,
        val trackId: Long?,
        val remoteUrl: String?,
        val workKey: String?,
        val folder: FolderEntity?,
        val displayName: String,
        val artist: String?,
    )

    private data class WorkLabel(val title: String?, val code: String?, val sourceId: String?)
}

internal data class ListedAudio(
    val file: File,
    val storedPath: String,
    val trackId: Long?,
    val remoteUrl: String?,
    val workTitle: String?,
    val workCode: String?,
    val sourceId: String?,
    val name: String = file.name,
)

internal data class ListedJob(
    val id: Long,
    val fingerprint: String,
    val trackId: Long?,
    val localPath: String?,
    val remoteUrl: String?,
    val title: String,
)

internal data class ListedSubtitle(
    val file: File,
    val workTitle: String?,
    val workCode: String?,
    val sourceId: String?,
)

internal fun formatByteSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb)
    return String.format(java.util.Locale.US, "%.1f GB", mb / 1024.0)
}

internal fun generatedSubtitleName(audioName: String): String {
    val dot = audioName.lastIndexOf('.')
    val stem = if (dot > 0) audioName.substring(0, dot) else audioName
    val base = stem.trim().ifBlank { audioName.trim().ifBlank { "字幕" } }
    return "$base.json"
}

internal fun cueKey(fingerprint: String): String =
    fingerprint.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }

internal fun canonicalPath(path: String): String =
    runCatching { File(path).canonicalPath }.getOrDefault(path)

internal fun filesTouch(path: String?, file: File): Boolean {
    if (path.isNullOrBlank()) return false
    if (path == file.path || path == file.absolutePath) return true
    val left = runCatching { File(path).canonicalFile }.getOrNull() ?: return false
    val right = runCatching { file.canonicalFile }.getOrNull() ?: return false
    return left == right
}

internal fun generatedCueFiles(dir: File): List<File> =
    dir.listFiles().orEmpty().filter { file ->
        file.isFile &&
            file.extension.equals("json", ignoreCase = true) &&
            !file.name.startsWith("extra-")
    }

internal fun subtitleFilesIn(root: File): List<File> {
    if (!root.isDirectory) return emptyList()
    val found = mutableListOf<File>()
    val pending = ArrayDeque<File>()
    val seen = mutableSetOf<String>()
    pending.add(root)
    while (pending.isNotEmpty()) {
        val dir = pending.removeFirst()
        val key = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
        if (!seen.add(key)) continue
        for (child in dir.listFiles().orEmpty()) {
            when {
                child.isDirectory -> pending.add(child)
                child.isFile && isSubtitleFile(child.name) -> found.add(child)
            }
        }
    }
    return found
}

internal fun downloadWorkDirectory(file: File, asmrRoot: File, dlsiteRoot: File, biliRoot: File? = null): File? {
    val path = runCatching { file.canonicalPath }.getOrNull() ?: return null
    val asmr = asmrRoot.canonicalPath
    val dlsite = dlsiteRoot.canonicalPath
    val bili = biliRoot?.canonicalPath
    val root = when {
        isInsidePath(path, asmr) -> asmr
        isInsidePath(path, dlsite) -> dlsite
        bili != null && isInsidePath(path, bili) -> bili
        else -> return null
    }
    val parent = File(path).parent ?: return null
    if (parent == root) return null
    val relative = parent.removePrefix(root).trimStart('/', '\\')
    if (relative.isBlank() || relative == ".") return null
    val key = relative.substringBefore('/').substringBefore('\\')
    if (key.isBlank() || key == ".") return null
    return File(root, key)
}

private fun isInsidePath(path: String, root: String): Boolean {
    if (path == root) return true
    val prefix = if (root.endsWith("/") || root.endsWith("\\")) root else "$root/"
    return path.startsWith(prefix) || path.startsWith(prefix.replace("/", "\\"))
}

internal fun storedItemMatches(item: StoredItem, query: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true
    val kind = when (item.kind) {
        StoredKind.GENERATED -> "生成字幕"
        StoredKind.SUBTITLE -> "字幕"
        StoredKind.AUDIO -> ""
    }
    return item.name.contains(needle, ignoreCase = true) ||
        item.workTitle.orEmpty().contains(needle, ignoreCase = true) ||
        item.workCode.orEmpty().contains(needle, ignoreCase = true) ||
        kind.contains(needle, ignoreCase = true) ||
        File(item.path).name.contains(needle, ignoreCase = true)
}

internal fun storedSort(items: List<StoredItem>): List<StoredItem> =
    items.sortedWith(
        compareBy(
            { it.workCode.orEmpty().lowercase() },
            { storedGroup(it).lowercase() },
            { it.kind },
            { it.name.lowercase() },
        ),
    )

internal fun buildStoredItems(
    audios: List<ListedAudio>,
    jobs: List<ListedJob>,
    cueFile: (String) -> File?,
    looseSubtitles: List<ListedSubtitle>,
    subtitlesFor: (ListedAudio) -> List<File>,
): List<StoredItem> {
    val items = mutableListOf<StoredItem>()
    val seen = mutableSetOf<String>()
    for (audio in audios) {
        items += StoredItem(
            path = audio.storedPath,
            name = audio.name,
            kind = StoredKind.AUDIO,
            workTitle = audio.workTitle,
            workCode = audio.workCode,
            sourceId = audio.sourceId,
            trackId = audio.trackId,
            remoteUrl = audio.remoteUrl,
            audioPath = audio.storedPath,
            bytes = audio.file.length(),
        )
        for (job in jobs) {
            if (!jobLinksAudio(job, audio)) continue
            val json = cueFile(job.fingerprint) ?: continue
            if (!seen.add(canonicalPath(json.path))) continue
            items += StoredItem(
                path = json.absolutePath,
                name = generatedSubtitleName(audio.name),
                kind = StoredKind.GENERATED,
                workTitle = audio.workTitle,
                workCode = audio.workCode,
                sourceId = audio.sourceId,
                trackId = audio.trackId,
                remoteUrl = audio.remoteUrl,
                audioPath = audio.storedPath,
                bytes = json.length(),
            )
        }
        for (child in subtitlesFor(audio)) {
            if (!child.isFile || !isSubtitleFile(child.name)) continue
            if (!seen.add(canonicalPath(child.path))) continue
            items += StoredItem(
                path = child.absolutePath,
                name = child.name,
                kind = StoredKind.SUBTITLE,
                workTitle = audio.workTitle,
                workCode = audio.workCode,
                sourceId = audio.sourceId,
                trackId = null,
                remoteUrl = null,
                audioPath = null,
                bytes = child.length(),
            )
        }
    }
    for (loose in looseSubtitles) {
        if (!loose.file.isFile || !isSubtitleFile(loose.file.name)) continue
        if (!seen.add(canonicalPath(loose.file.path))) continue
        items += StoredItem(
            path = loose.file.absolutePath,
            name = loose.file.name,
            kind = StoredKind.SUBTITLE,
            workTitle = loose.workTitle,
            workCode = loose.workCode,
            sourceId = loose.sourceId,
            trackId = null,
            remoteUrl = null,
            audioPath = null,
            bytes = loose.file.length(),
        )
    }
    return items
}

internal fun remainingGeneratedItems(
    files: List<File>,
    already: Set<String>,
    jobs: List<ListedJob>,
    itemFor: (File, ListedJob?) -> StoredItem,
): List<StoredItem> {
    val seen = already.toMutableSet()
    val items = mutableListOf<StoredItem>()
    for (file in files) {
        if (!file.isFile) continue
        if (!seen.add(canonicalPath(file.path))) continue
        val key = cueKey(file.name.substringBeforeLast('.'))
        val job = if (key.isEmpty()) {
            null
        } else {
            jobs.filter { cueKey(it.fingerprint) == key }.maxByOrNull { it.id }
        }
        items += itemFor(file, job)
    }
    return items
}

private fun jobLinksAudio(job: ListedJob, audio: ListedAudio): Boolean {
    if (audio.trackId != null && audio.trackId > 0L && job.trackId == audio.trackId) return true
    if (!audio.remoteUrl.isNullOrBlank() && job.remoteUrl == audio.remoteUrl) return true
    return filesTouch(job.localPath, audio.file)
}

private fun storedGroup(item: StoredItem): String = when (item.kind) {
    StoredKind.AUDIO -> File(item.path).name.substringBeforeLast('.')
    StoredKind.GENERATED -> item.name.substringBeforeLast('.')
    StoredKind.SUBTITLE -> item.name.substringBeforeLast('.')
}

private fun realCode(raw: String?): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (value.equals("work", ignoreCase = true) || value.equals("remote", ignoreCase = true)) return null
    return value
}
