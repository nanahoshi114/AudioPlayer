package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.R
import androidx.room.withTransaction
import com.nanahoshi.audioplayer.data.db.AppDatabase
import com.nanahoshi.audioplayer.data.db.DownloadEntity
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.JaDownloadEntryEntity
import com.nanahoshi.audioplayer.data.db.JaDownloadPackageEntity
import com.nanahoshi.audioplayer.data.db.JaDownloadWorkEntity
import com.nanahoshi.audioplayer.data.db.LibraryFileEntity
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.dlsite.extractDownloadArchive
import com.nanahoshi.audioplayer.japaneseasmr.JA_WORK_DOWNLOAD
import com.nanahoshi.audioplayer.japaneseasmr.JaFileTransfer
import com.nanahoshi.audioplayer.japaneseasmr.JaMegaFileSource
import com.nanahoshi.audioplayer.japaneseasmr.JaRemoteFile
import com.nanahoshi.audioplayer.japaneseasmr.JaWorkClient
import com.nanahoshi.audioplayer.japaneseasmr.JaWorkException
import com.nanahoshi.audioplayer.japaneseasmr.JaWorkManifest
import com.nanahoshi.audioplayer.japaneseasmr.jaFileSha256
import com.nanahoshi.audioplayer.japaneseasmr.jaHash
import com.nanahoshi.audioplayer.japaneseasmr.jaWorkPathKey
import com.nanahoshi.audioplayer.japaneseasmr.normalizeJaWorkPath
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

internal fun jaWorkFileIdentity(rj: String, path: String): String =
    android.net.Uri.Builder().scheme("japaneseasmr").authority("file")
        .appendQueryParameter("rj", rj).appendQueryParameter("path", path).toString()

internal class JaWorkFiles(private val db: AppDatabase, filesDir: File) {
    private val root = File(filesDir, "japaneseasmr-downloads")

    private data class Group(val files: List<JaRemoteFile>, val packageKey: String?) {
        val path get() = files.first().relativePath
        val size: Long? get() = if (files.all { it.size != null }) files.sumOf { it.size ?: 0 } else null
        val hash: String? get() = files.mapNotNull { it.sha256 }.takeIf { it.size == files.size }?.let {
            if (it.size == 1) it.single() else jaHash(it.joinToString("/"))
        }
        val megaRevision: String? = if (packageKey != null && files.all { it.transfer is JaFileTransfer.Mega }) {
            "mega:" + jaHash(files.joinToString("/") { (it.transfer as JaFileTransfer.Mega).crypto.checksum })
        } else null
        val hasRemoteVersion: Boolean get() = (hash != null || megaRevision != null ||
            packageKey == null && files.single().transfer is JaFileTransfer.Mega)
        val archiveParent: String get() = if (packageKey != null && files.all {
                (it.transfer as? JaFileTransfer.Mega)?.source is JaMegaFileSource.FolderFile
            }) path.substringBeforeLast('/', "") else ""
    }

    internal data class Check(val pending: Int, val needsReplace: Boolean, val lengthOnly: Boolean, val retained: Int)
    private data class Plan(
        val groups: List<Group>,
        val reused: List<Group>,
        val old: Map<String, JaDownloadEntryEntity>,
        val packages: Map<String, JaDownloadPackageEntity>,
    )

    private val numberedVolume = Regex("""(?i)(.+)\.part(\d+)\.rar""")
private val oldStyleVolume = Regex("""(?i)(.+)\.r(\d\d)""")

    private fun groups(files: List<JaRemoteFile>): List<Group> {
        val numbered = numberedVolume
        val oldVolume = oldStyleVolume
        val volumes = files.filter { numbered.matches(it.relativePath) || oldVolume.matches(it.relativePath) }
        val used = mutableSetOf<JaRemoteFile>()
        val result = mutableListOf<Group>()
        for (file in files) {
            if (file in used || oldVolume.matches(file.relativePath)) continue
            val part = numbered.matchEntire(file.relativePath)
            val extension = file.relativePath.substringAfterLast('.', "").lowercase()
            if (extension == "7z" || extension == "001") throw JaWorkException(TextRef.Res(R.string.ja_error_split_archive_unsupported))
            val members = when {
                part != null -> {
                    val prefix = part.groupValues[1]
                    val sequence = volumes.mapNotNull { volume ->
                        val other = numbered.matchEntire(volume.relativePath)?.takeIf { it.groupValues[1] == prefix }
                        other?.groupValues?.get(2)?.toIntOrNull()?.let { it to volume }
                    }.sortedBy { it.first }
                    if (sequence.map { it.first } != (1..sequence.size).toList()) errorVolumes()
                    sequence.map { it.second }
                }
                extension == "rar" -> {
                    val prefix = file.relativePath.substringBeforeLast('.')
                    val sequence = volumes.mapNotNull { volume ->
                        val other = oldVolume.matchEntire(volume.relativePath)?.takeIf { it.groupValues[1] == prefix }
                        other?.groupValues?.get(2)?.toIntOrNull()?.let { it to volume }
                    }.sortedBy { it.first }
                    if (sequence.map { it.first } != (0 until sequence.size).toList()) errorVolumes()
                    listOf(file) + sequence.map { it.second }
                }
                else -> listOf(file)
            }
            used += members
            val key = if (extension == "zip" || extension == "rar") {
                jaHash(members.joinToString("\u0000") { jaWorkPathKey(it.relativePath) })
            } else null
            result += Group(members, key)
        }
        if (used.size != files.size) errorVolumes()
        return result
    }

    private fun errorVolumes(): Nothing = throw JaWorkException(TextRef.Res(R.string.ja_error_rar_volume_missing))

    private suspend fun intact(entry: JaDownloadEntryEntity): Boolean {
        val name = entry.localPath ?: return false
        val file = File(name)
        return file.isFile && file.length() == entry.size && (entry.sha256 == null || jaFileSha256(file) == entry.sha256)
    }

    private suspend fun sameContent(row: JaDownloadEntryEntity, file: JaRemoteFile): Boolean {
        if (row.size != file.size || (file.sha256 != null && row.sha256 != file.sha256)) return false
        val transfer = file.transfer as? JaFileTransfer.Mega ?: return true
        return row.localPath?.let { transfer.crypto.verify(File(it)) } == true
    }

    private suspend fun conflictsWithIntactCopy(row: JaDownloadEntryEntity?, file: JaRemoteFile): Boolean =
        row != null && intact(row) && !sameContent(row, file)

    private suspend fun plan(manifest: JaWorkManifest, force: Boolean): Plan {
        val rj = manifest.entry.post.rj
        val old = db.jaDownloads().entries(rj).associateBy { jaWorkPathKey(it.relativePath) }
        val packages = db.jaDownloads().packages(rj).associateBy { it.packageKey }
        val groups = groups(manifest.files)
        val reused = mutableListOf<Group>()
        for (group in groups) {
            if (group.size == null || (force && !group.hasRemoteVersion)) continue
            val key = group.packageKey
            if (key == null) {
                val row = old[jaWorkPathKey(group.path)] ?: continue
                if (row.packageKey == null && intact(row) && sameContent(row, group.files.single())) reused += group
            } else {
                val row = packages[key] ?: continue
                if (row.size != group.size || row.publishedAt == null) continue
                val versionMatches = group.megaRevision?.let { row.revision == it }
                    ?: (group.hash == null || row.sha256 == group.hash)
                if (!versionMatches) continue
                val files = old.values.filter { it.packageKey == key && it.kind != "FOLDER" && it.packageRevision == row.revision }
                if (files.isNotEmpty() && files.all { intact(it) }) reused += group
            }
        }
        return Plan(groups, reused, old, packages)
    }

    private fun reusedRows(plan: Plan, group: Group): List<JaDownloadEntryEntity> {
        val packageKey = group.packageKey ?: return listOfNotNull(plan.old[jaWorkPathKey(group.path)])
        val revision = plan.packages[packageKey]?.revision
        return plan.old.values.filter { it.kind != "FOLDER" && it.packageKey == packageKey && it.packageRevision == revision }
    }

    private fun retainedCount(plan: Plan, currentPaths: Set<String>): Int =
        plan.old.count { (key, entry) -> key !in currentPaths && entry.localPath?.let { File(it).isFile } == true }

    suspend fun check(manifest: JaWorkManifest, entryKey: String, force: Boolean): Check {
        val plan = plan(manifest, force)
        val pending = plan.groups - plan.reused.toSet()
        val work = db.jaDownloads().work(manifest.entry.post.rj)
        val changedEntry = work != null && work.entryKey != entryKey
        return Check(
            pending.sumOf { it.files.size },
            changedEntry && pending.any { group ->
                if (group.packageKey != null) plan.old.values.any { it.localPath != null }
                else conflictsWithIntactCopy(plan.old[jaWorkPathKey(group.path)], group.files.single())
            },
            plan.groups.any { !it.hasRemoteVersion },
            if (pending.isEmpty()) {
                val current = plan.reused.flatMap { reusedRows(plan, it) }.map { jaWorkPathKey(it.relativePath) }.toSet()
                retainedCount(plan, current)
            } else 0,
        )
    }

    fun staging(rj: String, id: Long): File = File(root, "$rj/staging/$id")

    suspend fun execute(
        job: DownloadEntity,
        manifest: JaWorkManifest,
        client: JaWorkClient,
        report: TaskReporter,
        phase: suspend (ProgressPhase) -> Unit,
        requireTarget: suspend () -> Unit,
    ): TextRef {
        val post = manifest.entry.post
        val rj = post.rj
        val rootId = requireNotNull(job.workFolderId)
        val before = db.jaDownloads().work(rj)
        val differentEntry = before != null && before.entryKey != job.jaEntryKey
        val plan = plan(manifest, job.jaForce == true)
        val pending = plan.groups - plan.reused.toSet()
        val current = currentCoroutineContext()
        val task = staging(rj, job.id).apply { check(mkdirs() || isDirectory) }
        val generation = File(root, "$rj/files/${UUID.randomUUID()}")
        val entries = linkedMapOf<String, JaDownloadEntryEntity>()
        val freshKeys = mutableSetOf<String>()
        val packages = mutableListOf<JaDownloadPackageEntity>()
        val retired = mutableListOf<JaDownloadEntryEntity>()
        fun ancestors(path: String) = generateSequence(path.substringBeforeLast('/', "")) {
            it.substringBeforeLast('/', "")
        }.takeWhile { it.isNotEmpty() }

        val directories = plan.old.asSequence().flatMap { (key, row) ->
            if (row.kind == "FOLDER") sequenceOf(key) + ancestors(key) else ancestors(key)
        }.toMutableSet()
        var skipped = manifest.skipped
        val manifestFilePaths = mutableSetOf<String>()
        val keptOriginalPaths = mutableSetOf<String>()
        var declined = 0
        var downloaded = 0L
        var lastProgress = 0L
        var units = 0L
        val totalFiles = pending.sumOf { it.files.size }.toLong()
        val totalBytes = pending.flatMap { it.files }.let { files ->
            if (files.all { it.size != null }) files.sumOf { it.size ?: 0 } else null
        }
        var published = false

        fun overlaps(key: String): Boolean =
            key in directories || ancestors(key).any { parent ->
                (entries[parent] ?: plan.old[parent])?.kind?.let { it != "FOLDER" } == true
            }

        for (group in plan.reused) {
            reusedRows(plan, group).forEach { entries[jaWorkPathKey(it.relativePath)] = it }
        }

        suspend fun accept(relative: String, file: File, sha: String?, packageKey: String?, packageRevision: String? = null): Boolean {
            val path = normalizeJaWorkPath(relative) ?: run { skipped++; return false }
            val key = jaWorkPathKey(path)
            manifestFilePaths += key
            val old = plan.old[key]
            if (key in entries || overlaps(key) || old?.kind == "FOLDER") {
                skipped++
                return false
            }
            val size = file.length()
            val checksum = sha ?: jaFileSha256(file)
            if (old != null && old.size == size && old.sha256 == checksum && intact(old)) {
                entries[key] = old.copy(postId = post.postId, packageKey = packageKey, packageRevision = packageRevision)
                directories.addAll(ancestors(key))
                return true
            }
            if (old?.localPath != null && differentEntry && job.jaReplace != true && intact(old)) {
                skipped++
                declined++
                keptOriginalPaths += key
                return false
            }
            val finalPath = old?.relativePath ?: path
            val target = File(generation, finalPath)
            check(target.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
            file.copyTo(target)
            val kind = if (duration(target) == null) "FILE" else "AUDIO"
            if (kind == "FILE" && old?.kind == "AUDIO") {
                target.delete()
                skipped++
                declined++
                keptOriginalPaths += key
                return false
            }
            old?.let(retired::add)
            entries[key] = JaDownloadEntryEntity(rj, post.postId, finalPath, kind, size, checksum,
                target.absolutePath, packageKey, old?.trackId, old?.fileId, packageRevision = packageRevision)
            freshKeys += key
            directories.addAll(ancestors(key))
            return true
        }

        try {
            for (group in pending) {
                current.ensureActive()
                requireTarget()
                phase(ProgressPhase.DOWNLOADING)
                val direct = group.files.singleOrNull()
                val previous = direct?.let { plan.old[jaWorkPathKey(it.relativePath)] }
                if (group.packageKey == null && direct != null && differentEntry &&
                    job.jaReplace != true && conflictsWithIntactCopy(previous, direct)
                ) {
                    skipped++
                    declined++
                    val key = jaWorkPathKey(group.path)
                    manifestFilePaths += key
                    keptOriginalPaths += key
                    report.units(++units, totalFiles)
                    continue
                }
                val downloads = mutableListOf<File>()
                for (file in group.files) {
                    val progress = report.phase(ProgressPhase.DOWNLOADING)
                    progress.units(downloaded, totalBytes, ProgressUnit.BYTES, TextRef.Res(R.string.progress_detail_files, listOf(units + 1, totalFiles)))
                    var last = 0L
                    val slot = File(task, jaHash(file.relativePath)).apply { check(mkdirs() || isDirectory) }
                    val destination = File(slot, file.relativePath.substringAfterLast('/'))
                    client.download(file, destination) { done ->
                        val delta = (done - last).coerceAtLeast(0)
                        progress.bytes(delta, downloaded + done, totalBytes)
                        last = done
                    }
                    if (downloaded + destination.length() - lastProgress >= 256 * 1024) {
                        lastProgress = downloaded + destination.length()
                        db.downloads().activeProgress(job.id, lastProgress, totalBytes ?: 0)
                    }
                    downloaded += destination.length()
                    downloads += destination
                    units++
                }
                val packageKey = group.packageKey
                if (packageKey == null) {
                    accept(group.path, downloads.single(), direct?.sha256, null)
                    continue
                }
                phase(ProgressPhase.EXTRACTING)
                val unpacked = File(task, "extracted-$packageKey")
                unpacked.deleteRecursively()
                val extraction = extractDownloadArchive(downloads, unpacked, ::normalizeJaWorkPath, ::jaWorkPathKey) {
                    current.ensureActive()
                }
                skipped += extraction.skipped
                val packageRevision = group.megaRevision ?: group.hash ?: jaHash(downloads.map { jaFileSha256(it) }.joinToString("/"))
                val archiveParent = group.archiveParent
                fun publishedPath(path: String) = if (archiveParent.isEmpty()) path else "$archiveParent/$path"
                var complete = true
                for (file in extraction.entries.filter { !it.isDirectory }) {
                    complete = accept(publishedPath(file.relativePath), File(unpacked, file.relativePath), null,
                        packageKey, packageRevision) && complete
                }
                // Preserve empty folders as well as the parents implied by files.
                val archiveDirectories = extraction.entries.filter { it.isDirectory }.map { publishedPath(it.relativePath) }
                val outputDirectories = if (archiveParent.isEmpty()) archiveDirectories else archiveDirectories + archiveParent
                for (directory in outputDirectories) {
                    val path = normalizeJaWorkPath(directory)
                    val key = path?.let(::jaWorkPathKey)
                    if (key == null || entries[key]?.kind == "AUDIO" || entries[key]?.kind == "FILE" ||
                        plan.old[key]?.let { it.kind != "FOLDER" } == true ||
                        (archiveParent.isNotEmpty() && ancestors(key).any { parent ->
                            (entries[parent] ?: plan.old[parent])?.kind?.let { it != "FOLDER" } == true
                        })
                    ) {
                        skipped++
                        complete = false
                        continue
                    }
                    directories += key
                    directories.addAll(ancestors(key))
                    entries.putIfAbsent(key, JaDownloadEntryEntity(rj, post.postId, requireNotNull(path), "FOLDER", 0,
                        null, null, packageKey, folderId = plan.old[key]?.folderId, packageRevision = packageRevision))
                }
                val now = System.currentTimeMillis()
                packages += JaDownloadPackageEntity(rj, packageKey, group.path, downloads.sumOf { it.length() },
                    group.hash, null, now, now.takeIf { complete }, packageRevision)
            }
            phase(ProgressPhase.IMPORTING)
            val rjDirectory = File(root, "$rj/files").apply { check(mkdirs() || isDirectory) }
            current.ensureActive()
            val (publishedPaths, stale) = withContext(NonCancellable) {
                val result = db.withTransaction {
                    current.ensureActive()
                    requireTarget()
                    val work = db.folders().get(rootId) ?: error(DOWNLOAD_LIBRARY_REQUIRED)
                    val currentWork = db.jaDownloads().work(rj)
                    val currentRows = db.jaDownloads().entries(rj).associateBy { jaWorkPathKey(it.relativePath) }
                    // A reused path must still belong to the current manifest. Only new copies can replace it.
                    val all = currentRows.toMutableMap()
                    for ((key, row) in entries) {
                        val latest = currentRows[key]
                        when {
                            key in freshKeys -> all[key] = row.copy(trackId = latest?.trackId, fileId = latest?.fileId)
                            row.kind == "FOLDER" -> all[key] = row.copy(folderId = latest?.folderId)
                            latest != null && latest.localPath == row.localPath -> {
                                all[key] = latest.copy(postId = row.postId, packageKey = row.packageKey,
                                    packageRevision = row.packageRevision)
                            }
                        }
                    }
                    val linked = link(work, currentWork, rj, all.values.toList(), post.coverUri)
                    val linkedPaths = linked.filter { it.localPath != null }
                    val plannedPaths = entries.keys + manifestFilePaths
                    val retained = linkedPaths.count { jaWorkPathKey(it.relativePath) !in plannedPaths }
                    if (packages.isNotEmpty()) db.jaDownloads().savePackages(packages)
                    val previousKey = currentWork?.entryKey
                    val entryKey = if (declined > 0) previousKey else job.jaEntryKey
                    db.jaDownloads().saveWork(JaDownloadWorkEntity(rj, rootId, post.postId,
                        rjDirectory.absolutePath, System.currentTimeMillis(), entryKey))
                    db.downloads().setLocalPath(job.id, rjDirectory.absolutePath)
                    db.downloads().finish(job.id, downloaded, downloaded)
                    linkedPaths.filter { jaWorkPathKey(it.relativePath) in entries }.mapNotNull { it.localPath } to retained
                }
                published = true
                phase(ProgressPhase.CLEANING)
                if (retired.isNotEmpty()) cleanupGenerations(retired, startup = false)
                task.deleteRecursively()
                result
            }
            val added = publishedPaths.count { it.startsWith(generation.absolutePath + File.separator) }
            val existing = publishedPaths.size - added
            val unsaved = skipped - declined
            report.workFiles(existing, skipped, stale)
            if (pending.isEmpty() && publishedPaths.isNotEmpty()) report.reused()
            val completionRes = if (plan.groups.any { !it.hasRemoteVersion }) R.string.ja_work_complete_no_checksum
            else R.string.ja_work_complete
            val summary = buildList {
                add(TextRef.Res(R.string.ja_work_added_files, listOf(added)))
                if (existing > 0) add(TextRef.Res(R.string.ja_work_existing_files, listOf(existing)))
                if (keptOriginalPaths.isNotEmpty()) {
                    add(TextRef.Res(R.string.ja_work_kept_original_files, listOf(keptOriginalPaths.size)))
                }
                if (unsaved > 0) add(TextRef.Res(R.string.ja_work_skipped_files, listOf(unsaved)))
                if (stale > 0) add(TextRef.Res(R.string.ja_work_retained_files, listOf(stale)))
            }
            return TextRef.Res(completionRes, listOf(TextRef.Joined(summary, separator = "，")))
        } finally {
            if (!published) generation.deleteRecursively()
        }
    }

    suspend fun restore(rootFolder: FolderEntity, rj: String) {
        val previous = db.jaDownloads().work(rj) ?: return
        if (previous.rootFolderId == rootFolder.id) return
        val rows = db.jaDownloads().entries(rj)
        if (rows.isEmpty()) return
        val post = previous.postId?.let { db.japaneseAsmr().post(it) }
        link(rootFolder, previous, rj, rows, post?.coverUri ?: rootFolder.coverUri)
        db.jaDownloads().saveWork(previous.copy(rootFolderId = rootFolder.id))
    }

    private suspend fun link(
        rootFolder: FolderEntity,
        previous: JaDownloadWorkEntity?,
        rj: String,
        rows: List<JaDownloadEntryEntity>,
        cover: String?,
    ): List<JaDownloadEntryEntity> {
        val dao = db.jaDownloads()
        val folders = db.folders()
        val now = System.currentTimeMillis()
        val rootRow = rows.firstOrNull { it.relativePath.isEmpty() }
        val trackRoot = rootRow?.folderId?.let { folders.get(it) }?.takeIf { it.parentId == rootFolder.id &&
            previous?.rootFolderId == rootFolder.id }
        val directoryId = trackRoot?.id ?: run {
            val names = folders.childrenOf(rootFolder.id).map { it.name }.toSet()
            val name = generateSequence(1) { it + 1 }.map { if (it == 1) "下载文件" else "下载文件 ($it)" }
                .first { it !in names }
            folders.insert(FolderEntity(parentId = rootFolder.id, name = name,
                position = folders.maxPosition(rootFolder.id) + 1, addedAt = now, asmrWorkId = rootFolder.id,
                coverUri = cover, contentSource = TrackSource.JAPANESE_ASMR.name))
        }
        val rootEntry = JaDownloadEntryEntity(rj, previous?.postId ?: rows.firstOrNull()?.postId.orEmpty(), "", "FOLDER",
            0, null, null, null, folderId = directoryId)
        val saved = mutableListOf(rootEntry)
        val oldFolders = rows.filter { it.kind == "FOLDER" }.associateBy { jaWorkPathKey(it.relativePath) }
        val mapped = mutableMapOf("" to directoryId)
        suspend fun folder(path: String, postId: String): Long {
            val key = jaWorkPathKey(path)
            mapped[key]?.let { return it }
            val parent = folder(path.substringBeforeLast('/', ""), postId)
            val old = oldFolders[key]
            val id = old?.folderId?.let { folders.get(it) }?.takeIf { it.parentId == parent }?.id
                ?: folders.insert(FolderEntity(parentId = parent, name = path.substringAfterLast('/'),
                    position = folders.maxPosition(parent) + 1, addedAt = now, asmrWorkId = rootFolder.id,
                    coverUri = cover, contentSource = TrackSource.JAPANESE_ASMR.name))
            mapped[key] = id
            saved += old?.copy(folderId = id) ?: JaDownloadEntryEntity(rj, postId, path, "FOLDER", 0,
                null, null, null, folderId = id)
            return id
        }
        for (row in rows) {
            if (row.relativePath.isEmpty()) continue
            if (row.kind == "FOLDER") {
                folder(row.relativePath, row.postId)
                continue
            }
            val parent = folder(row.relativePath.substringBeforeLast('/', ""), row.postId)
            val identity = jaWorkFileIdentity(rj, row.relativePath)
            val path = row.localPath?.takeIf { File(it).isFile }
            val title = row.relativePath.substringAfterLast('/')
            if (row.kind == "AUDIO") {
                val old = row.trackId?.let { db.tracks().getById(it) }?.takeIf { it.remoteUrl == identity }
                val ms = path?.takeIf { it != old?.localUri }?.let { duration(File(it)) } ?: old?.durationMs ?: 0
                val track = old?.copy(folderId = parent, localUri = path, durationMs = ms) ?: TrackEntity(
                    source = TrackSource.JAPANESE_ASMR, title = title, coverUri = cover, localUri = path,
                    folderId = parent, asmrWorkId = rootFolder.id, remoteUrl = identity, durationMs = ms,
                    artist = null, bvid = null, cid = null, page = null, addedAt = now,
                )
                val id = if (old == null) db.tracks().insert(track) else old.id.also { db.tracks().update(track) }
                row.fileId?.let { db.files().get(it) }?.takeIf { it.remoteUrl == identity }?.let { db.files().delete(it.id) }
                saved += row.copy(trackId = id, fileId = null, folderId = null, localPath = path)
            } else {
                val old = row.fileId?.let { db.files().get(it) }?.takeIf { it.remoteUrl == identity }
                val file = old?.copy(folderId = parent, localUri = path) ?: LibraryFileEntity(
                    folderId = parent, name = title, kind = previewKind(title), remoteUrl = identity,
                    addedAt = now, asmrWorkId = rootFolder.id, fileHash = jaHash(identity), localUri = path,
                )
                val id = if (old == null) db.files().insert(file) else old.id.also { db.files().update(file) }
                saved += row.copy(trackId = null, fileId = id, folderId = null, localPath = path)
            }
        }
        dao.saveEntries(saved)
        return saved
    }

    private fun duration(file: File): Long? {
        if (!LocalAudio.isAudio(file.name)) return null
        return LocalAudio.readDurationMs(file.absolutePath)
    }

    suspend fun cleanup() {
        val live = db.downloads().observe().first().filter { it.kind == JA_WORK_DOWNLOAD && it.status != DownloadStatus.DONE }
            .mapNotNull { task -> task.workKey?.let { staging(it, task.id) } }.toSet()
        for (work in root.listFiles().orEmpty()) {
            if (!WorkIds.matches(work.name)) continue
            File(work, "staging").listFiles().orEmpty().filter { it !in live }.forEach { it.deleteRecursively() }
        }
        cleanupGenerations()
    }

    private suspend fun cleanupGenerations(retired: List<JaDownloadEntryEntity> = emptyList(), startup: Boolean = true) {
        val queue = db.queue().snapshot()
        val queued = queue.mapNotNull { it.trackId }.toSet()
        val references = db.jaDownloads().paths() + db.tracks().onlineWithLocal().mapNotNull { it.localUri } +
            db.files().observeAll().first().mapNotNull { it.localUri } + db.whisper().activeLocalPaths() +
            db.translations().active().mapNotNull { it.localUri } + queue.flatMap { listOfNotNull(it.localUri, it.remoteUrl) } +
            retired.filter { it.trackId in queued }.mapNotNull { it.localPath }
        val paths = references.filter { it.startsWith('/') }.toSet()
        val candidates = retired.mapNotNull { it.localPath }.toSet()
        WorkGenerationCleaner.forEachGeneration(root, "files") { _, generation ->
            for (file in generation.walkBottomUp()) {
                if (file.isDirectory) {
                    if (startup) file.delete()
                } else if (file.absolutePath !in paths && (startup || file.absolutePath in candidates)) file.delete()
            }
        }
    }
}
