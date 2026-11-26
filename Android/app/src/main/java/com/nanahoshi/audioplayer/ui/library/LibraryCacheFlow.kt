package com.nanahoshi.audioplayer.ui.library

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.lazy.LazyListState
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nanahoshi.audioplayer.AppGraph
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.asmr.AsmrErrorKind
import com.nanahoshi.audioplayer.asmr.AsmrException
import com.nanahoshi.audioplayer.asmr.AsmrPreview
import com.nanahoshi.audioplayer.asmr.WorkFacts
import com.nanahoshi.audioplayer.bilibili.BilibiliException
import com.nanahoshi.audioplayer.data.BatchExternalPair
import com.nanahoshi.audioplayer.data.BindExternalResult
import com.nanahoshi.audioplayer.data.CacheCandidate
import com.nanahoshi.audioplayer.data.DeleteResult
import com.nanahoshi.audioplayer.data.DlsiteDownloadMode
import com.nanahoshi.audioplayer.data.FormatConflict
import com.nanahoshi.audioplayer.data.LibrarySort
import com.nanahoshi.audioplayer.data.LocalAudio
import com.nanahoshi.audioplayer.data.LocalRemoval
import com.nanahoshi.audioplayer.data.LocalRemovalPlan
import com.nanahoshi.audioplayer.data.TrackSource
import com.nanahoshi.audioplayer.data.WorkFill
import com.nanahoshi.audioplayer.data.canonicalPath
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.LibraryFileEntity
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.data.db.TrackSubtitleBindingEntity
import com.nanahoshi.audioplayer.data.findFormatConflicts
import com.nanahoshi.audioplayer.data.idsToDrop
import com.nanahoshi.audioplayer.data.isSubtitleFile
import com.nanahoshi.audioplayer.data.loadWorkSubtitleCatalog
import com.nanahoshi.audioplayer.data.localRemoval
import com.nanahoshi.audioplayer.data.resolveLibraryFileSource
import com.nanahoshi.audioplayer.data.sortedForLibrary
import com.nanahoshi.audioplayer.graph
import com.nanahoshi.audioplayer.playback.SubtitleCandidateEntry
import com.nanahoshi.audioplayer.playback.WorkSubtitleCandidate
import com.nanahoshi.audioplayer.playback.nearestSubtitleWorkRoot
import com.nanahoshi.audioplayer.playback.pickSubtitleName
import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.UserFacingException
import com.nanahoshi.audioplayer.text.resolveNoUrl
import com.nanahoshi.audioplayer.text.resolveUserError
import com.nanahoshi.audioplayer.ui.components.SelectionState
import com.nanahoshi.audioplayer.ui.components.SubtitlePickerActions
import com.nanahoshi.audioplayer.ui.components.subtitlePickerActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 音频库「下载 / 缓存」向导：来源选择、Japanese ASMR 清单与格式冲突确认，及对应的提示状态。 */
internal class LibraryCacheFlow(
    private val graph: AppGraph,
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val workFileSource: () -> TrackSource?,
    private val workLocation: () -> WorkFolderLocation?,
) {
    private fun text(@StringRes id: Int, vararg args: Any): String = appContext.getString(id, *args)

    val cachePrompt = MutableStateFlow<CachePrompt?>(null)
    private var cacheJob: Job? = null
    private var cacheAnswer: CompletableDeferred<Any?>? = null

    fun answerCache(answer: Any?) {
        cacheAnswer?.complete(answer)
    }

    fun cancelCacheWait(prompt: CachePrompt.JapaneseWaiting) {
        if (cachePrompt.compareAndSet(prompt, null)) cacheJob?.cancel()
    }

    private suspend fun <T> waitForJapaneseDownload(
        prompt: CachePrompt.JapaneseWaiting,
        read: suspend () -> T,
    ): T {
        cachePrompt.value = prompt
        try {
            return read()
        } finally {
            cachePrompt.compareAndSet(prompt, null)
        }
    }

    private suspend fun askCache(prompt: CachePrompt): Any? {
        val answer = CompletableDeferred<Any?>()
        cacheAnswer = answer
        cachePrompt.value = prompt
        try {
            return answer.await()
        } finally {
            if (cacheAnswer === answer) {
                cachePrompt.value = null
                cacheAnswer = null
            }
        }
    }

    fun cacheFolder(folderId: Long) {
        cacheJob?.cancel()
        cacheJob = scope.launch {
            try {
                cacheWork(folderId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                graph.showError(error, R.string.library_operation_failed)
            }
        }
    }

    private suspend fun selectJapanesePosts(
        posts: List<com.nanahoshi.audioplayer.japaneseasmr.JaPostRef>,
        refresh: Boolean = false,
    ) = if (posts.size <= 1) posts else when (val answer = askCache(CachePrompt.JapanesePosts(posts, refresh))) {
        is String -> posts.filter { it.postId == answer }
        true -> if (refresh) posts else emptyList()
        else -> emptyList()
    }

    fun refreshJapaneseArticles(rootId: Long) {
        cacheJob?.cancel()
        cacheJob = scope.launch {
            val rows = graph.db.japaneseAsmr().postsForRoot(rootId)
            val trail = workLocation()?.path.orEmpty().map { it.id }
            val current = rows.firstOrNull { it.articleFolderId in trail }
            val selected = if (current != null) listOf(current.ref()) else selectJapanesePosts(rows.map { it.ref() }, refresh = true)
            val result = mutableListOf<String>()
            for (post in selected) {
                try {
                    val article = try {
                        graph.japaneseAsmr.article(post.postId, post.rj, refresh = true)
                    } catch (failure: com.nanahoshi.audioplayer.japaneseasmr.JapaneseAsmrException) {
                        if (failure.verificationPage == null) throw failure
                        graph.jaRefreshRequests.verify(post) { token ->
                            askCache(CachePrompt.JapaneseVerify(token, post.title)) == true
                        } ?: break
                    }
                    val updated = graph.player.refreshJaArticle(article, rootId, cleanup = true)
                    result += appContext.getString(R.string.library_ja_refresh_line, post.title, updated.message(appContext))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    val reason = appContext.resolveUserError(failure, R.string.library_refresh_failed)
                    result += appContext.getString(R.string.library_ja_refresh_line, post.title, reason)
                }
            }
            if (result.isNotEmpty()) graph.messages.value = result.joinToString("\n")
        }
    }

    private suspend fun cacheJapaneseWork(root: FolderEntity): Int {
        val posts = graph.db.japaneseAsmr().postsForRoot(root.id).map { it.ref() }
        val selected = selectJapanesePosts(posts)
        if (selected.isEmpty()) return 0
        try {
            val entries = waitForJapaneseDownload(CachePrompt.JapaneseWaiting(text(R.string.ja_download_reading_entry))) {
                graph.downloads.jaWorkEntries(selected)
            }
            val entry = if (entries.size == 1) entries.single() else {
                val titles = entries.groupingBy { it.post.title }.eachCount()
                val options = entries.mapIndexed { index, option ->
                    val title = option.post.title
                    index to if (titles[title] == 1) title else text(R.string.ja_download_entry_suffix, title, index + 1)
                }
                val pick = askCache(CachePrompt.JapaneseChoice(
                    text(R.string.ja_download_choose_entry_title),
                    text(R.string.ja_download_choose_entry_message),
                    options,
                ))
                entries.getOrNull(pick as? Int ?: return 0) ?: return 0
            }
            val readingFiles = CachePrompt.JapaneseWaiting(text(R.string.ja_download_reading_manifest))
            val manifest = waitForJapaneseDownload(readingFiles) {
                graph.downloads.prepareJaManifest(
                    entry, root.id,
                    onReadVerifiedManifest = { cachePrompt.value = readingFiles },
                    onVerify = { token, buzzheavier -> askCache(CachePrompt.JapaneseVerify(token, buzzheavier = buzzheavier)) == true },
                )
            } ?: return 0
            suspend fun checkFiles(force: Boolean) =
                waitForJapaneseDownload(CachePrompt.JapaneseWaiting(text(R.string.ja_download_checking_local))) {
                    graph.downloads.checkJaWork(manifest, force)
                }
            var check = checkFiles(force = false)
            val retained = if (check.retained > 0) text(R.string.ja_download_retained_suffix, check.retained) else ""
            if (check.pending == 0 && !check.lengthOnly) {
                graph.show(R.string.ja_download_files_local, retained)
                return 0
            }
            val total = manifest.bytes?.let {
                android.text.format.Formatter.formatShortFileSize(appContext, it)
            } ?: text(R.string.ja_download_total_unknown)
            val note = if (check.lengthOnly) text(R.string.ja_download_length_only_note) else ""
            val title = text(if (check.pending == 0) R.string.ja_download_files_local_title else R.string.ja_download_download_work)
            val options = buildList<Pair<Any, String>> {
                if (check.pending > 0) add(false to text(R.string.ja_download_confirm))
                if (check.lengthOnly) add(true to text(R.string.ja_download_redownload))
            }
            val force = askCache(CachePrompt.JapaneseChoice(
                title,
                text(R.string.ja_download_manifest_summary, manifest.files.size, total, check.pending, note, retained),
                options,
            )) as? Boolean ?: return 0
            if (force) check = checkFiles(force = true)
            val replace = !check.needsReplace || askCache(CachePrompt.JapaneseChoice(
                text(R.string.ja_download_replace_title),
                text(R.string.ja_download_replace_message),
                listOf(true to text(R.string.ja_download_replace), false to text(R.string.ja_download_keep_old)),
            )) == true
            val queued = graph.downloads.enqueueJaWork(root.id, manifest, replace, force)
            if (!queued.added) graph.show(R.string.ja_download_task_exists)
            return if (queued.added) 1 else 0
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val detail = appContext.resolveUserError(failure, R.string.ja_download_read_failed)
            val fallback = askCache(CachePrompt.JapaneseChoice(
                text(R.string.ja_download_failed_title),
                detail,
                listOf(true to text(R.string.ja_download_use_track)),
            )) == true
            if (!fallback) return 0
            val tracks = selected.flatMap { post ->
                val article = graph.japaneseAsmr.article(post.postId, post.rj)
                graph.library.importJapaneseAsmrPost(article, root.id)
                val matches = com.nanahoshi.audioplayer.japaneseasmr.resolveStoredJaMatches(article, post.postId, graph.db.japaneseAsmr())
                matches.filterValues { it.media != null }.keys.mapNotNull { graph.db.japaneseAsmr().track(post.postId, it) }
            }
            return graph.downloads.enqueue(tracks)
        }
    }

    private suspend fun cacheWork(folderId: Long) {
        val byId = graph.library.allFolders().associateBy { it.id }
        val root = workRootOf(folderId, byId)
        val sourceFolderId = root?.id ?: folderId
        // 已购买（读缓存）且 asmr.one 也有这部作品时，让用户选来源。
        val sources = graph.library.prepareWorkSources(sourceFolderId)
        val available = buildList {
            if (sources?.hasPlay == true || sources?.purchased == true) add(TrackSource.DLSITE)
            if (sources?.hasAsmr == true) add(TrackSource.ASMR)
            if (sources?.hasJapaneseAsmr == true) add(TrackSource.JAPANESE_ASMR)
        }
        val chosen = if (available.size > 1) askCache(CachePrompt.Source(available)) as? TrackSource ?: return else null
        val tracks = if (root == null) graph.library.tracksInTree(folderId)
            else graph.library.asmrTracksInTree(folderId)
        // 普通父目录仍下载整个子树；DLsite 子作品各自转为一个完整原始包任务。
        if (root == null) {
            val worknos = tracks.filter { it.source == TrackSource.DLSITE }
                .mapNotNull { graph.downloads.requestWorkno(it) }
                .distinctBy { com.nanahoshi.audioplayer.dlsite.DlsiteClient.canonicalWorkno(it) }
            val jaWorks = tracks.filter { it.source == TrackSource.JAPANESE_ASMR }
                .mapNotNull { workRootOf(it.folderId, byId) }.distinctBy { it.id }
            var jaCount = 0
            for (work in jaWorks) jaCount += cacheJapaneseWork(work)
            val count = graph.downloads.prepare {
                val archives = worknos.count { graph.downloads.enqueueDlsiteArchive(it).added }
                archives + graph.downloads.enqueue(tracks.filter {
                    it.source != TrackSource.DLSITE && it.source != TrackSource.JAPANESE_ASMR
                })
            } + jaCount
            if (count > 0 || jaWorks.isEmpty()) {
                if (count == 0) graph.show(R.string.library_no_audio_to_download)
        else graph.show(R.string.library_added_download_count, count)
            }
            return
        }
        // 没弹窗时：当前来源优先，作品外面按 Play 优先，其次 asmr.one。
        val source = chosen
            ?: workFileSource()?.takeIf { current -> current in available || tracks.any { it.source == current } }
            ?: available.firstOrNull()
            ?: tracks.firstOrNull()?.source ?: TrackSource.ASMR
        if (source == TrackSource.JAPANESE_ASMR) {
            val count = cacheJapaneseWork(root)
            if (count > 0) graph.show(R.string.library_added_download_count, count)
            return
        }
        if (source == TrackSource.DLSITE) {
            val worknos = root?.asmrSourceId?.let { listOf(it) }
                ?: tracks.filter { it.source == TrackSource.DLSITE }.mapNotNull { graph.downloads.requestWorkno(it) }
                    .distinctBy { com.nanahoshi.audioplayer.dlsite.DlsiteClient.canonicalWorkno(it) }
            if (worknos.isEmpty()) throw UserFacingException(TextRef.Res(R.string.library_missing_work_id))
            val count = graph.downloads.prepare {
                worknos.count { graph.downloads.enqueueDlsiteArchive(it, root?.name ?: it, root?.id).added }
            }
            if (count == 0) graph.show(R.string.library_no_audio_to_download)
            else graph.show(R.string.library_added_archive_download_count, count)
            return
        }
        val sourceTracks = if (source == TrackSource.ASMR && tracks.none { it.source == source }) {
            graph.library.ensureWorkSource(root.id, source)
            graph.library.asmrTracksInTree(folderId)
        } else tracks
        val pending = sourceTracks.filter { it.source == source && !LocalAudio.hasStoredCopy(appContext, it.localUri) }
        var dropped = emptySet<Long>()
        val conflicts = findFormatConflicts(pending.map { CacheCandidate(it.id, it.folderId, it.title) })
        for (conflict in conflicts) {
            val keep = askCache(CachePrompt.Format(conflict)) as? String ?: return
            dropped = dropped + idsToDrop(conflict, keep)
        }
        val count = graph.downloads.enqueue(pending.filter { it.id !in dropped })
        if (count == 0) graph.show(R.string.library_no_audio_to_download)
        else graph.show(R.string.library_added_download_count, count)
    }
}
