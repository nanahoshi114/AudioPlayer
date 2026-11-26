package com.nanahoshi.audioplayer.data

import androidx.room.withTransaction
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.data.db.DownloadEntity
import com.nanahoshi.audioplayer.data.db.DownloadStatus
import com.nanahoshi.audioplayer.dlsite.DlsiteClient
import com.nanahoshi.audioplayer.text.PersistedErrors
import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.UserFacingException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** DLsite 完整原始包的排队、下载、解压、发布与暂存回收。 */
internal class DownloadArchiveRunner(private val center: DownloadCenter) {
    private val db get() = center.db
    private val library get() = center.library
    private val filesDir get() = center.filesDir

    internal suspend fun enqueueDlsiteArchive(
        workno: String,
        title: String = workno,
        expectedFolderId: Long? = null,
    ): DownloadCenter.Enqueued = center.prepare {
        center.archiveLock.withLock {
            val key = DlsiteClient.canonicalWorkno(workno)
            val folder = library.folderBySource(key) ?: error(DOWNLOAD_LIBRARY_REQUIRED)
            check(expectedFolderId == null || folder.id == expectedFolderId) { DOWNLOAD_LIBRARY_REQUIRED }
            db.downloads().activeArchive(key)?.takeIf { it.workFolderId == folder.id }?.let { return@withLock DownloadCenter.Enqueued(it.id, false) }
            val saved = db.dlsiteOriginals().work(key)
            val entries = db.dlsiteOriginals().entries(key)
            if (saved != null && entries.any { it.kind == "AUDIO" } && isOriginalWorkIntact(entries)) {
                val needsRelink = saved.rootFolderId == null || db.folders().get(saved.rootFolderId) == null ||
                    entries.any { entry ->
                        when (entry.kind) {
                            "AUDIO" -> entry.trackId?.let { db.tracks().getById(it) }?.localUri != entry.localPath
                            "FOLDER" -> entry.folderId?.let { db.folders().get(it) } == null
                            else -> entry.fileId?.let { db.files().get(it) }?.localUri != entry.localPath
                        }
                    }
                check(library.folderBySource(key)?.id == folder.id) { DOWNLOAD_LIBRARY_REQUIRED }
                if (!needsRelink) db.downloads().latestArchive(key)?.takeIf { it.status == DownloadStatus.DONE }
                    ?.let { return@withLock DownloadCenter.Enqueued(it.id, false) }
            }
            db.withTransaction {
                check(library.folderBySource(key)?.id == folder.id) { DOWNLOAD_LIBRARY_REQUIRED }
                val id = db.downloads().insert(DownloadEntity(
                    trackId = 0, title = "$title · ${center.appContext.getString(R.string.download_volume_archive)}", bytesDone = 0, bytesTotal = 0,
                    status = DownloadStatus.QUEUED, error = null, source = TrackSource.DLSITE.name,
                    workKey = key, kind = "DLSITE_ARCHIVE", workFolderId = folder.id,
                ))
                center.registerJob(id)
                DownloadCenter.Enqueued(id, true)
            }
        }
    }

    internal suspend fun downloadArchive(job: DownloadEntity, report: TaskReporter) = kotlinx.coroutines.coroutineScope {
        val workno = job.workKey ?: throw UserFacingException(TextRef.Res(R.string.download_missing_workno))
        val self = currentCoroutineContext()[kotlinx.coroutines.Job]!!
        val saved = db.dlsiteOriginals().work(workno)
        val entries = db.dlsiteOriginals().entries(workno)
        if (saved != null && entries.any { it.kind == "AUDIO" } && isOriginalWorkIntact(entries)) {
            report.phase(ProgressPhase.IMPORTING)
            db.downloads().setPhase(job.id, "IMPORTING")
            val imported = center.originals.importDirectory(workno, File(saved.directory), saved.generation,
                beforeCommit = { self.ensureActive(); center.targetResolver.requireRunningTarget(job) },
                onPublish = {
                    db.downloads().setLocalPath(job.id, saved.directory)
                    db.downloads().finish(job.id, 0, 0)
                },
                onProgress = { done, total -> report.units(done.toLong(), total.toLong()) },
            )
            report.skipped(imported.skipped.size)
            reportSkipped(imported)
            File(filesDir, "dlsite-archives/$workno/${job.id}").deleteRecursively()
            report.reused()
            return@coroutineScope
        }
        val generation = java.util.UUID.randomUUID().toString()
        val directory = File(center.dlsiteRoot, "$workno/original/$generation")
        val staging = File(filesDir, "dlsite-archives/$workno/${job.id}")
        var published = false
        val oldCopies = db.tracks().onlineWithLocal().filter { track ->
            track.source == TrackSource.DLSITE &&
                center.requestWorkno(track)?.let { DlsiteClient.canonicalWorkno(it) } == workno
        }.mapNotNull { track -> track.localUri?.takeIf { path -> path.startsWith("/") }?.let { track.id to it } }
        val oldDirectory = db.dlsiteOriginals().work(workno)?.directory
        try {
            center.targetResolver.requireRunningTarget(job)
            report.phase(ProgressPhase.RESOLVING)
            db.downloads().setPhase(job.id, "RESOLVING")
            db.downloads().setPhase(job.id, "DOWNLOADING")
            var lastProgress = 0L
            val packages = center.archiveClient.download(workno, staging, taskProgress = report, onProgress = { done, total ->
                if (done - lastProgress >= 256 * 1024 || total == done) {
                    lastProgress = done
                    center.scope.launch {
                        if (self.isActive) db.downloads().activeProgress(job.id, done, total ?: 0L)
                    }
                }
            }, checkCancelled = { self.ensureActive() })
            self.ensureActive()
            report.cumulative(0)
            report.phase(ProgressPhase.EXTRACTING)
            db.downloads().setPhase(job.id, "EXTRACTING")
            com.nanahoshi.audioplayer.dlsite.DlsiteArchiveExtractor.extract(packages, directory, onProgress = { files, bytes -> report.units(files.toLong(), null); report.cumulative(bytes) }) { self.ensureActive() }
            self.ensureActive()
            report.phase(ProgressPhase.IMPORTING)
            db.downloads().setPhase(job.id, "IMPORTING")
            val imported = center.originals.importDirectory(
                workno, directory, generation,
                beforeCommit = {
                    self.ensureActive()
                    center.targetResolver.requireRunningTarget(job)
                },
                onPublish = {
                    db.downloads().setLocalPath(job.id, directory.absolutePath)
                    val bytes = packages.sumOf { it.length() }
                    db.downloads().finish(job.id, bytes, bytes)
                },
                onCommitted = { published = true },
                onProgress = { done, total -> report.units(done.toLong(), total.toLong()) },
            )
            report.skipped(imported.skipped.size)
            reportSkipped(imported)
            report.phase(ProgressPhase.CLEANING)
            // 清单发布后不再响应中止；完整原始包只在发布成功后回收。
            staging.deleteRecursively()
            withContext(NonCancellable) {
                retireOriginalCopies(oldCopies, oldDirectory)
            }
        } catch (error: com.nanahoshi.audioplayer.dlsite.DlsiteArchiveIntegrityException) {
            // 已确认损坏的包无法通过再次解压修复；仅清理这个任务的暂存。
            staging.deleteRecursively()
            throw error
        } finally {
            if (!published) directory.deleteRecursively()
        }
    }

    private fun reportSkipped(result: DlsiteOriginalImportResult) {
        if (result.skipped.isEmpty()) return
        center.notice(center.appContext.getString(R.string.download_archive_published_notice, result.skipped.size))
    }

    private suspend fun retireOriginalCopies(paths: List<Pair<Long, String>>, oldDirectory: String?) {
        val active = db.whisper().activeLocalPaths().toSet()
        val tracks = db.tracks().onlineWithLocal().mapNotNull { it.localUri }.toSet()
        val files = db.files().observeAll().first().mapNotNull { it.localUri }.toSet()
        val queue = db.queue().snapshot()
        val queued = queue.flatMap { listOfNotNull(it.localUri, it.remoteUrl) }.toSet()
        // 已创建的MediaItem可能仍持有切换前的本地路径，队列里的曲目暂不回收旧副本。
        val queuedTrackIds = queue.mapNotNull { it.trackId }.toSet()
        val protected = active + tracks + files + queued + paths.filter { it.first in queuedTrackIds }.map { it.second }
        for (path in paths.map { it.second }.distinct()) {
            val file = File(path)
            val canonical = runCatching { file.canonicalPath }.getOrNull() ?: continue
            val managed = listOf(center.root, center.dlsiteRoot).any { canonical.startsWith(it.canonicalPath + File.separator) }
            if (managed && path !in protected && file.isFile && file.delete()) db.downloads().deleteFinishedForPath(path)
        }
        val old = oldDirectory?.let(::File) ?: return
        if (old.canonicalPath.startsWith(center.dlsiteRoot.canonicalPath + File.separator) &&
            protected.none { it == old.path || it.startsWith(old.path + File.separator) }) {
            old.deleteRecursively()
        }
    }

    /** 回收进程中断后未发布的解压代次；失败任务的完整压缩包保留供重试。 */
    internal suspend fun cleanUnpublishedOriginals() {
        val works = db.dlsiteOriginals().works()
        val references = works.map { it.directory } +
            db.tracks().onlineWithLocal().mapNotNull { it.localUri } +
            db.files().observeAll().first().mapNotNull { it.localUri } +
            db.queue().snapshot().flatMap { listOfNotNull(it.localUri, it.remoteUrl) } +
            db.whisper().activeLocalPaths()
        val protected = references.filter { it.startsWith("/") }.mapNotNull {
            runCatching { File(it).canonicalPath }.getOrNull()
        }.toSet()
        WorkGenerationCleaner.removeUnreferenced(
            workRoot = center.dlsiteRoot,
            directoryName = "original",
            protectedCanonical = protected,
            mustBeInside = center.dlsiteRoot,
        )
        // 未完成的任务保留暂存供断点续传；已完成和数据库里已无对应任务的孤儿目录一律回收。
        val live = db.downloads().archives()
            .filter { it.status != DownloadStatus.DONE }
            .mapNotNull { job -> job.workKey?.let { "$it/${job.id}" } }
            .toSet()
        val archivesRoot = File(filesDir, "dlsite-archives")
        for (workDirectory in archivesRoot.listFiles().orEmpty()) {
            if (!workDirectory.isDirectory || !WorkIds.matches(workDirectory.name)) continue
            for (jobDirectory in workDirectory.listFiles().orEmpty()) {
                if (!jobDirectory.isDirectory || jobDirectory.name.toLongOrNull() == null) continue
                if ("${workDirectory.name}/${jobDirectory.name}" in live) continue
                jobDirectory.deleteRecursively()
            }
            if (workDirectory.listFiles().isNullOrEmpty()) workDirectory.delete()
        }
    }
}