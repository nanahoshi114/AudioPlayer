package com.nanahoshi.audioplayer.data

import androidx.room.withTransaction
import com.nanahoshi.audioplayer.R
import com.nanahoshi.audioplayer.asmr.AsmrNode
import com.nanahoshi.audioplayer.asmr.AsmrWork
import com.nanahoshi.audioplayer.asmr.WorkFacts
import com.nanahoshi.audioplayer.data.db.FolderEntity
import com.nanahoshi.audioplayer.data.db.JapaneseAsmrMediaEntity
import com.nanahoshi.audioplayer.data.db.JapaneseAsmrPostEntity
import com.nanahoshi.audioplayer.data.db.TrackEntity
import com.nanahoshi.audioplayer.data.db.TrackSubtitleBindingEntity
import com.nanahoshi.audioplayer.dlsite.DlsiteClient
import com.nanahoshi.audioplayer.dlsite.PlayEntry
import com.nanahoshi.audioplayer.japaneseasmr.JaArticle
import com.nanahoshi.audioplayer.japaneseasmr.JaFailure
import com.nanahoshi.audioplayer.japaneseasmr.JaMatchResult
import com.nanahoshi.audioplayer.japaneseasmr.JaPostRef
import com.nanahoshi.audioplayer.japaneseasmr.JapaneseAsmrException
import com.nanahoshi.audioplayer.japaneseasmr.matchOldJaQueue
import com.nanahoshi.audioplayer.japaneseasmr.resolveQueuedJaMatch
import com.nanahoshi.audioplayer.japaneseasmr.resolveStoredJaMatches
import com.nanahoshi.audioplayer.playback.biliLocalCache
import com.nanahoshi.audioplayer.text.PersistedErrors
import com.nanahoshi.audioplayer.text.TextRef
import com.nanahoshi.audioplayer.text.UserFacingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/** 负责三来源作品的建立、刷新与 Japanese ASMR 篇目同步。 */
internal class LibraryWorkImporter(private val repo: LibraryRepository) {
    private val db get() = repo.db
    private val context get() = repo.context
    private val asmr get() = repo.asmr
    private val dlsite get() = repo.dlsite
    private val japaneseAsmr get() = repo.japaneseAsmr
    private val jaWorkFiles get() = repo.jaWorkFiles

    suspend fun fillWorkFiles(folderId: Long): WorkFill = withContext(Dispatchers.IO) {
        val folder = db.folders().get(folderId) ?: return@withContext WorkFill.UNCHANGED
        val sourceId = folder.asmrSourceId ?: return@withContext WorkFill.UNCHANGED
        if (db.folders().childrenOf(folderId).isNotEmpty()) return@withContext WorkFill.UNCHANGED
        if (db.tracks().inFolder(folderId).isNotEmpty()) return@withContext WorkFill.UNCHANGED
        if (db.files().listIn(folderId).isNotEmpty()) return@withContext WorkFill.UNCHANGED
        val facts = WorkFacts.fromJson(folder.workMeta) ?: WorkFacts(
            title = folder.name,
            circle = "",
            vas = emptyList(),
            tags = emptyList(),
            release = "",
            sourceId = sourceId,
        )
        val workId = folder.asmrWorkId ?: sourceId.filter { it.isDigit() }.toLongOrNull() ?: 0L
        val purchased = try {
            dlsite.isPurchased(sourceId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (purchased) {
            val nodes = try {
                dlsite.playTree(sourceId, facts.circle.ifBlank { null }, folder.coverUri)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            if (nodes.isNotEmpty()) {
                db.withTransaction { importPlayNodes(nodes, folderId, workId, facts, folder.coverUri, "") }
                return@withContext WorkFill.FILLED
            }
        }
        val loaded = try {
            asmr.loadWork(sourceId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (loaded != null && loaded.nodes.isNotEmpty()) {
            db.withTransaction { importAsmrNodes(loaded.nodes, folderId, loaded, "") }
            return@withContext WorkFill.FILLED
        }
        WorkFill.EMPTY
    }

    suspend fun prepareWorkSources(folderId: Long): WorkSources? = withContext(Dispatchers.IO) {
        val folder = db.folders().get(folderId) ?: return@withContext null
        val sourceId = folder.asmrSourceId ?: return@withContext null
        repo.backfillContentSources(folderId)
        val hasPlay = repo.workHasSource(folderId, TrackSource.DLSITE)
        WorkSources(
            // 有 Play 文件就不必再问 DLsite；离线时那一步会一直等到超时。
            purchased = if (hasPlay) null else dlsite.cachedPurchased(sourceId),
            hasPlay = hasPlay,
            hasAsmr = repo.workHasSource(folderId, TrackSource.ASMR) || WorkFacts.fromJson(folder.workMeta)?.asmrAvailable == true,
            hasJapaneseAsmr = db.japaneseAsmr().postsForRoot(folderId).isNotEmpty(),
        )
    }

    suspend fun checkPurchased(folderId: Long): Boolean = withContext(Dispatchers.IO) {
        val sourceId = db.folders().get(folderId)?.asmrSourceId ?: return@withContext false
        try {
            dlsite.isPurchased(sourceId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    suspend fun fillWorkSource(folderId: Long, source: TrackSource): WorkFill = withContext(Dispatchers.IO) {
        val folder = db.folders().get(folderId) ?: return@withContext WorkFill.UNCHANGED
        val sourceId = folder.asmrSourceId ?: return@withContext WorkFill.UNCHANGED
        val workId = repo.workIdOf(folder)
        if (source == TrackSource.JAPANESE_ASMR) {
            val posts = db.japaneseAsmr().postsForRoot(folderId)
            if (posts.any { post -> db.japaneseAsmr().mediaForPost(post.postId).any { it.trackId?.let { id -> db.tracks().getById(id) } != null } }) {
                return@withContext WorkFill.UNCHANGED
            }
            for (post in posts) {
                val article = try {
                    japaneseAsmr.article(post.postId, post.rj)
                } catch (failure: JapaneseAsmrException) {
                    if (failure.failure in setOf(JaFailure.MISSING, JaFailure.IDENTITY, JaFailure.LEGACY)) continue
                    throw failure
                }
                if (article.media.isNotEmpty()) {
                    db.withTransaction {
                        if (db.folders().get(folderId) == null) throw UserFacingException(TextRef.Res(R.string.library_work_removed))
                        fillJapaneseAsmrArticle(folder, article)
                    }
                    return@withContext WorkFill.FILLED
                }
            }
            return@withContext WorkFill.EMPTY
        }
        if (repo.workHasSource(folderId, source)) return@withContext WorkFill.UNCHANGED
        val facts = WorkFacts.fromJson(folder.workMeta) ?: WorkFacts(
            title = folder.name,
            circle = "",
            vas = emptyList(),
            tags = emptyList(),
            release = "",
            sourceId = sourceId,
        )
        if (source == TrackSource.DLSITE) {
            val purchased = try {
                dlsite.isPurchased(sourceId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (!purchased) return@withContext WorkFill.EMPTY
            val nodes = try {
                dlsite.playTree(sourceId, facts.circle.ifBlank { null }, folder.coverUri)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            if (nodes.isEmpty()) return@withContext WorkFill.EMPTY
            db.withTransaction { importPlayNodes(nodes, folderId, workId, facts, folder.coverUri, "") }
            return@withContext WorkFill.FILLED
        }
        val loaded = asmr.loadWork(sourceId)
        if (loaded.nodes.isEmpty()) return@withContext WorkFill.EMPTY
        db.withTransaction { importAsmrNodes(loaded.nodes, folderId, loaded, "") }
        WorkFill.FILLED
    }

    suspend fun ensureWorkMeta(folderId: Long) = withContext(Dispatchers.IO) {
        val folder = db.folders().get(folderId) ?: return@withContext
        val sourceId = folder.asmrSourceId ?: return@withContext
        if (!folder.workMeta.isNullOrBlank()) return@withContext
        db.folders().setWorkMeta(folderId, asmr.facts(sourceId).toJson())
    }

    suspend fun importAsmr(input: String, parentId: Long?) = withContext(Dispatchers.IO) {
        repo.workImportLock.withLock {
            try {
                val work = asmr.loadWork(input)
                if (db.folders().findBySourceId(work.sourceId) != null) throw UserFacingException(TextRef.Res(R.string.library_already_in_library))
                val rootId = insertAsmrWork(work, parentId)
                restoreOriginalCopy(work.sourceId, rootId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (failure.hasConstraint()) throw UserFacingException(TextRef.Res(R.string.library_work_already_in_library))
                throw failure
            }
        }
    }

    suspend fun folderBySource(sourceId: String): FolderEntity? = withContext(Dispatchers.IO) {
        db.folders().findBySourceId(sourceId)?.let { return@withContext it }
        val key = DlsiteClient.canonicalWorkno(sourceId)
        db.folders().all().firstOrNull { folder ->
            val stored = folder.asmrSourceId ?: return@firstOrNull false
            DlsiteClient.canonicalWorkno(stored) == key
        }
    }

    private suspend fun restoreOriginalCopy(sourceId: String, expectedRootId: Long? = null) {
        val key = runCatching { DlsiteClient.canonicalWorkno(sourceId) }.getOrNull() ?: return
        val saved = db.dlsiteOriginals().work(key) ?: return
        val entries = db.dlsiteOriginals().entries(key)
        if (!isOriginalWorkIntact(entries)) return
        DlsiteOriginalStore(context, db, dlsite, repo).importDirectory(
            key, File(saved.directory), saved.generation, expectedRootId = expectedRootId,
        )
    }

    suspend fun ensureAsmrWork(work: AsmrWork): Long = saveOnlineWork(
        work.facts(), work.coverUrl, work, emptyList(), emptyList(), prefer = TrackSource.ASMR, playChecked = false,
    )

    suspend fun refreshJapaneseAsmrPost(
        postId: String,
        rootId: Long,
        cleanup: Boolean,
        article: JaArticle? = null,
        currentQueueId: () -> Long = { -1 },
    ): LibraryRepository.JaRefreshResult = withContext(Dispatchers.IO) {
        val dao = db.japaneseAsmr()
        val expected = dao.post(postId)?.takeIf { it.rootFolderId == rootId } ?: error(PersistedErrors.JA_POST_REMOVED)
        val updated = article ?: japaneseAsmr.article(postId, expected.rj, refresh = true)
        if (!(updated.post.postId == postId && updated.post.rj.equals(expected.rj, ignoreCase = true))) throw UserFacingException(TextRef.Res(R.string.library_ja_post_id_changed))
        repo.workImportLock.withLock {
            withContext(NonCancellable) {
                val detachedBindings = mutableListOf<TrackSubtitleBindingEntity>()
                val result = db.withTransaction {
                    val root = db.folders().get(rootId)
                    check(root?.asmrSourceId?.let(DlsiteClient::canonicalWorkno) == DlsiteClient.canonicalWorkno(expected.rj) &&
                        dao.post(postId)?.rootFolderId == rootId) { PersistedErrors.JA_POST_REMOVED }
                    val rows = dao.mediaForPost(postId)
                    val matches = resolveStoredJaMatches(updated, postId, dao)
                    val visible = updated.media.map { it.key }.toSet()
                    val sync = if (updated.media.isEmpty()) null else fillJapaneseAsmrArticle(requireNotNull(root), updated)
                    val queue = db.queue().snapshot()
                    val stored = resolveStoredJaMatches(updated, postId, dao)
                    for (item in queue) {
                        val key = item.fileKey ?: continue
                        if (item.trackId != null || item.workno != postId || item.source != TrackSource.JAPANESE_ASMR) continue
                        val media = resolveQueuedJaMatch(updated, key, item.mediaPathHash, stored, queue).media ?: continue
                        if (item.mediaKind != media.kind.name) db.queue().setJaMediaKind(item.queueItemId, media.kind.name)
                    }
                    val gone = rows.filter { matches[it.mediaKey] == JaMatchResult.Gone }
                    val queueIds = linkedSetOf<Long>()
                    var playlistItems = 0
                    var removed = 0
                    val playlists = mutableSetOf<Long>()
                    if (cleanup && updated.complete) {
                        val online = queue.filter {
                            it.source == TrackSource.JAPANESE_ASMR && it.trackId == null &&
                                it.workno == postId && it.fileKey != null
                        }
                        val candidates = matchOldJaQueue(rows, online)
                        val removedKeys = mutableSetOf<String>()
                        if (visible != rows.map { it.mediaKey }.toSet()) {
                            for (media in gone) {
                                val track = media.trackId?.let { db.tracks().getById(it) }
                                if (track == null) {
                                    dao.deleteMedia(postId, media.mediaKey)
                                    removedKeys += media.mediaKey
                                    continue
                                }
                                val related = queue.filter { it.trackId == track.id }.map { it.queueItemId }
                                val onlineRelated = online.filter { media.mediaKey in candidates.getValue(it.queueItemId).mediaKeys }
                                if (track.biliLocalCache(context) != null ||
                                    onlineRelated.any { candidates.getValue(it.queueItemId).ambiguous }
                                ) continue
                                val listItems = db.playlists().itemsForTrack(track.id)
                                val current = currentQueueId()
                                if (current in related || onlineRelated.any { it.queueItemId == current }) continue
                                failJaTrackTasks(track.id, related)
                                db.trackSubtitles().getForTrack(track.id)?.let(detachedBindings::add)
                                db.tracks().delete(track.id)
                                dao.deleteMedia(postId, media.mediaKey)
                                removedKeys += media.mediaKey
                                queueIds += related
                                playlistItems += listItems.size
                                playlists += listItems.map { it.playlistId }
                                removed++
                            }
                        }
                        val cachedKeys = if (online.isEmpty()) emptySet() else db.downloads()
                            .finishedForWork(TrackSource.JAPANESE_ASMR.name, postId)
                            .mapNotNull { job ->
                                val file = job.localPath?.let(::File)
                                job.fileKey?.takeIf { file != null && file.isFile && file.length() > 0 }
                            }.toSet()
                        val removableOnline = online.filter { item ->
                            val old = candidates.getValue(item.queueItemId)
                            item.queueItemId != currentQueueId() && !old.ambiguous && item.fileKey !in cachedKeys &&
                                old.mediaKeys.all { it in removedKeys } &&
                                resolveQueuedJaMatch(updated, requireNotNull(item.fileKey), item.mediaPathHash, matches, queue) == JaMatchResult.Gone
                        }.map { it.queueItemId }
                        for (id in removableOnline) {
                            if (id == currentQueueId()) continue
                            db.translations().failForRemovedAudio(null, listOf(id), context.getString(R.string.library_ja_track_invalid))
                            db.queue().delete(id)
                            queueIds += id
                        }
                    }
                    if (queueIds.isNotEmpty()) repo.compactQueue()
                    playlists.forEach { repo.compactPlaylist(it) }
                    val retained = rows.count { matches[it.mediaKey]?.media == null } - removed
                    LibraryRepository.JaRefreshResult(sync?.added ?: 0, removed, retained, queueIds.toList(), playlistItems, updated.complete)
                }
                repo.onBindingsDetached?.invoke(detachedBindings, false)
                result
            }
        }
    }

    suspend fun jaQueueMatches(article: JaArticle, queueId: Long, key: String?): Boolean =
        withContext(Dispatchers.IO) {
            val expected = db.japaneseAsmr().post(article.post.postId)?.rj
            if (expected != null && !expected.equals(article.post.rj, ignoreCase = true)) return@withContext false
            val queue = db.queue().snapshot()
            val item = queue.firstOrNull { it.queueItemId == queueId && it.workno == article.post.postId &&
                it.fileKey == key } ?: return@withContext false
            val stored = resolveStoredJaMatches(article, article.post.postId, db.japaneseAsmr())
            resolveQueuedJaMatch(article, item.fileKey ?: return@withContext false, item.mediaPathHash, stored, queue).media != null
        }

    private suspend fun failJaTrackTasks(trackId: Long, queueIds: List<Long>) {
        val message = context.getString(R.string.library_ja_track_invalid)
        db.downloads().activeForTrack(trackId)?.let { db.downloads().stopActive(it.id, message) }
        db.whisper().failActiveForTrack(trackId, message)
        db.whisper().failTranslationForTrack(trackId, message)
        db.translations().failForRemovedAudio(trackId, queueIds, message)
    }

    suspend fun importJapaneseAsmrPost(
        article: JaArticle,
        expectedRoot: Long? = null,
    ): LibraryRepository.JapaneseAsmrImport = withContext(Dispatchers.IO) {
        if (article.media.isEmpty()) throw UserFacingException(TextRef.Res(R.string.library_article_unavailable))
        repo.workImportLock.withLock {
            try {
                db.withTransaction {
                    val post = article.post
                    val existing = folderBySource(post.rj)
                    if (!(expectedRoot == null || existing?.id == expectedRoot)) throw UserFacingException(TextRef.Res(R.string.library_work_removed))
                    val root = existing ?: createOnlineRoot(
                        WorkFacts(post.title, "", article.voiceActors, emptyList(), "", post.rj), post.coverUri,
                    )
                    fillJapaneseAsmrArticle(root, article).also {
                        jaWorkFiles.restore(root, post.rj)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (failure.hasConstraint()) throw UserFacingException(TextRef.Res(R.string.library_work_already_in_library))
                throw failure
            }
        }
    }

    suspend fun rememberJapanesePosts(rootId: Long, posts: List<JaPostRef>) {
        if (posts.isEmpty()) return
        repo.workImportLock.withLock {
            db.withTransaction {
                val rj = db.folders().get(rootId)?.asmrSourceId ?: return@withTransaction
                val key = DlsiteClient.canonicalWorkno(rj)
                for (post in posts.filter { DlsiteClient.canonicalWorkno(it.rj) == key }.distinctBy { it.postId }) {
                    val saved = db.japaneseAsmr().post(post.postId)
                    if (saved?.rootFolderId != rootId) db.japaneseAsmr().upsertPost(post.entity(rootId))
                }
            }
        }
    }

    suspend fun saveOnlineWork(
        facts: WorkFacts,
        cover: String?,
        asmrWork: AsmrWork?,
        play: List<PlayEntry>,
        candidates: List<JaPostRef>,
        prefer: TrackSource,
        selectedArticle: JaArticle? = null,
        playChecked: Boolean = true,
    ): Long = withContext(Dispatchers.IO) {
        val id = repo.workImportLock.withLock {
            val posts = candidates.ifEmpty {
                folderBySource(facts.sourceId)?.let { root -> db.japaneseAsmr().postsForRoot(root.id).map { it.ref() } }.orEmpty()
            }
            val playNodes = if (!playChecked && dlsite.cachedPurchased(facts.sourceId) != false) {
                try {
                    if (dlsite.isPurchased(facts.sourceId)) dlsite.playTree(facts.sourceId, facts.circle, cover)
                    else emptyList()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
            } else play
            var article = selectedArticle?.takeIf { it.media.isNotEmpty() }
            if (article == null && (prefer == TrackSource.JAPANESE_ASMR || asmrWork?.nodes.isNullOrEmpty() && playNodes.isEmpty())) {
                for (post in posts) {
                    try {
                        article = japaneseAsmr.article(post.postId, facts.sourceId).takeIf { it.media.isNotEmpty() }
                        if (article != null) break
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Other articles and sources may still be available.
                    }
                }
            }
            try {
                db.withTransaction {
                    val existing = folderBySource(facts.sourceId)
                    val hasAsmr = !asmrWork?.nodes.isNullOrEmpty()
                    if (!(existing != null || hasAsmr || playNodes.isNotEmpty() || article != null)) throw UserFacingException(TextRef.Res(R.string.library_no_valid_source))
                    val sourceFacts = facts.copy(asmrAvailable = hasAsmr || facts.asmrAvailable)
                    val root = existing ?: createOnlineRoot(sourceFacts, cover, asmrWork?.id)
                    if (existing != null && hasAsmr) db.folders().setWorkMeta(root.id, sourceFacts.toJson())
                    (posts + listOfNotNull(article?.post)).distinctBy { it.postId }.forEach { post ->
                        val previous = db.japaneseAsmr().post(post.postId)
                        if (previous?.rootFolderId != root.id) {
                            db.japaneseAsmr().upsertPost(post.entity(root.id))
                        }
                    }
                    val source = when {
                        prefer == TrackSource.JAPANESE_ASMR && article != null -> TrackSource.JAPANESE_ASMR
                        prefer == TrackSource.ASMR && hasAsmr -> TrackSource.ASMR
                        playNodes.isNotEmpty() -> TrackSource.DLSITE
                        hasAsmr -> TrackSource.ASMR
                        article != null -> TrackSource.JAPANESE_ASMR
                        else -> null
                    }
                    val workId = repo.workIdOf(root)
                    when (source) {
                        TrackSource.ASMR -> if (!repo.workHasSource(root.id, TrackSource.ASMR)) {
                            importAsmrNodes(requireNotNull(asmrWork).nodes, root.id, asmrWork, "")
                        }
                        TrackSource.DLSITE -> if (!repo.workHasSource(root.id, TrackSource.DLSITE)) {
                            importPlayNodes(playNodes, root.id, workId, facts, cover, "")
                        }
                        TrackSource.JAPANESE_ASMR -> fillJapaneseAsmrArticle(root, requireNotNull(article))
                        else -> Unit
                    }
                    jaWorkFiles.restore(root, facts.sourceId)
                    root.id
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (failure.hasConstraint()) throw UserFacingException(TextRef.Res(R.string.library_work_already_in_library))
                throw failure
            }
        }
        val old = db.dlsiteOriginals().work(DlsiteClient.canonicalWorkno(facts.sourceId))
        if (old != null && old.rootFolderId != id) restoreOriginalCopy(facts.sourceId, id)
        id
    }

    private suspend fun createOnlineRoot(facts: WorkFacts, cover: String?, workId: Long? = null): FolderEntity {
        val root = FolderEntity(
            name = sanitizeEntryName("${facts.sourceId} ${facts.title}"),
            position = db.folders().maxPosition(null) + 1, addedAt = System.currentTimeMillis(),
            asmrWorkId = workId ?: facts.sourceId.filter(Char::isDigit).toLongOrNull() ?: 0L,
            asmrSourceId = facts.sourceId, coverUri = cover, workMeta = facts.toJson(),
        )
        return root.copy(id = db.folders().insert(root))
    }

    private fun JaPostRef.entity(rootId: Long) = JapaneseAsmrPostEntity(
        postId, rootId, null, rj, title, coverUri, publishedAt, url, null,
    )

    private suspend fun fillJapaneseAsmrArticle(root: FolderEntity, article: JaArticle): LibraryRepository.JapaneseAsmrImport {
        val dao = db.japaneseAsmr()
        val post = article.post
        val previous = dao.post(post.postId)?.takeIf { it.rootFolderId == root.id }
        val entry = post.entity(root.id).copy(
            coverUri = post.coverUri ?: previous?.coverUri,
            publishedAt = post.publishedAt ?: previous?.publishedAt,
            articleFolderId = previous?.articleFolderId,
            refreshedAt = System.currentTimeMillis(),
        )
        dao.upsertPost(entry)
        val filled = dao.postsForRoot(root.id).filter { candidate ->
            candidate.postId == post.postId || dao.mediaForPost(candidate.postId).any { row ->
                row.trackId?.let { db.tracks().getById(it) } != null
            }
        }
        var destination = root.id
        if (filled.size > 1) {
            for (candidate in filled) {
                val folderId = candidate.articleFolderId?.takeIf { db.folders().get(it)?.parentId == root.id }
                    ?: db.folders().insert(
                        FolderEntity(
                            parentId = root.id, name = sanitizeEntryName(candidate.title),
                            position = db.folders().maxPosition(root.id) + 1, asmrWorkId = root.asmrWorkId,
                            coverUri = candidate.coverUri, contentSource = TrackSource.JAPANESE_ASMR.name,
                        ),
                    )
                dao.upsertPost(candidate.copy(articleFolderId = folderId))
                dao.mediaForPost(candidate.postId).forEach { media ->
                    media.trackId?.let { db.tracks().setFolder(it, folderId) }
                }
                if (candidate.postId == post.postId) destination = folderId
            }
        }
        val old = dao.mediaForPost(post.postId).associateBy { it.mediaKey }
        val matches = resolveStoredJaMatches(article, post.postId, dao)
        val matchedKeys = matches.entries.mapNotNull { (key, match) -> match.media?.let { it.key to key } }.toMap()
        val downloaded = db.downloads().finishedForWork(TrackSource.JAPANESE_ASMR.name, post.postId)
            .sortedByDescending { it.id }.groupBy { it.fileKey }
        var added = 0
        for (media in article.media) {
            if (media.key in article.duplicateKeys && media.key in old) continue
            val key = matchedKeys[media.key] ?: media.key
            val saved = old[key]?.trackId?.let { db.tracks().getById(it) }
            val title = if (media.hasTitle) media.title else saved?.title ?: old[key]?.title ?: media.title
            val availablePath = downloaded[key].orEmpty().firstNotNullOfOrNull { it.localPath?.takeIf { path ->
                File(path).let { file -> file.isFile && file.length() > 0 }
            } }?.takeIf { db.tracks().findByLocalUri(it) == null }
            val track = saved?.copy(title = title, coverUri = entry.coverUri, folderId = destination,
                localUri = saved.localUri ?: availablePath) ?: TrackEntity(
                source = TrackSource.JAPANESE_ASMR, title = title, artist = article.voiceActors.joinToString(" / ").ifBlank { null },
                durationMs = old[key]?.durationMs ?: 0, coverUri = entry.coverUri,
                localUri = availablePath, bvid = null, cid = null, page = null, addedAt = System.currentTimeMillis(),
                folderId = destination,
            )
            val trackId = if (saved != null) {
                db.tracks().update(track)
                saved.id
            } else {
                added++
                db.tracks().insert(track)
            }
            dao.upsertMedia(JapaneseAsmrMediaEntity(
                post.postId, key, trackId, media.kind.name, title, track.durationMs,
                media.chapters.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it) },
                pathHash = media.pathHash ?: old[key]?.pathHash,
            ))
        }
        return LibraryRepository.JapaneseAsmrImport(root.id, added, matches.values.count { it.media == null })
    }

    private suspend fun insertAsmrWork(work: AsmrWork, parentId: Long?): Long = db.withTransaction {
        val position = db.folders().maxPosition(parentId) + 1
        val rootId = db.folders().insert(
            FolderEntity(
                parentId = parentId,
                name = sanitizeEntryName("${work.sourceId} ${work.title}"),
                position = position,
                addedAt = System.currentTimeMillis(),
                asmrWorkId = work.id,
                asmrSourceId = work.sourceId,
                coverUri = work.coverUrl,
                workMeta = work.facts().toJson(),
            ),
        )
        importAsmrNodes(work.nodes, rootId, work, "")
        rootId
    }

    private suspend fun importPlayNodes(
        nodes: List<PlayEntry>,
        parentId: Long,
        workId: Long,
        facts: WorkFacts,
        coverUrl: String?,
        path: String,
    ) {
        for (node in nodes) {
            val name = sanitizeEntryName(node.title)
            val here = if (path.isEmpty()) name else "$path/$name"
            if (node.children.isNotEmpty()) {
                val id = db.folders().insert(
                    FolderEntity(
                        parentId = parentId,
                        name = name,
                        position = db.folders().maxPosition(parentId) + 1,
                        addedAt = System.currentTimeMillis(),
                        asmrWorkId = workId,
                        contentSource = TrackSource.DLSITE.name,
                    ),
                )
                importPlayNodes(node.children, id, workId, facts, coverUrl, here)
                continue
            }
            val hash = scopedFileHash(TrackSource.DLSITE, node.audio?.fileKey?.ifBlank { null } ?: here)
            val audio = node.audio
            if (audio != null) {
                if (db.tracks().findAsmr(workId, hash) != null) continue
                db.tracks().insert(
                    TrackEntity(
                        source = TrackSource.DLSITE,
                        title = name,
                        artist = facts.circle.ifBlank { facts.sourceId },
                        durationMs = audio.durationMs,
                        coverUri = coverUrl,
                        localUri = null,
                        bvid = null,
                        cid = null,
                        page = null,
                        addedAt = System.currentTimeMillis(),
                        folderId = parentId,
                        remoteUrl = audio.remoteUrl,
                        asmrWorkId = workId,
                        fileHash = hash,
                    ),
                )
            } else {
                val preview = node.preview ?: continue
                if (db.files().findAsmr(workId, hash) != null) continue
                db.files().insert(
                    com.nanahoshi.audioplayer.data.db.LibraryFileEntity(
                        folderId = parentId,
                        name = name,
                        kind = preview.kind,
                        remoteUrl = preview.url,
                        addedAt = System.currentTimeMillis(),
                        asmrWorkId = workId,
                        fileHash = hash,
                    ),
                )
            }
        }
    }

    private suspend fun importAsmrNodes(
        nodes: List<AsmrNode>,
        parentId: Long,
        work: AsmrWork,
        path: String,
    ) {
        for (node in nodes) {
            val name = sanitizeEntryName(node.title)
            val here = if (path.isEmpty()) name else "$path/$name"
            if (node.isContainer()) {
                val position = db.folders().maxPosition(parentId) + 1
                val id = db.folders().insert(
                    FolderEntity(
                        parentId = parentId,
                        name = name,
                        position = position,
                        addedAt = System.currentTimeMillis(),
                        asmrWorkId = work.id,
                        contentSource = TrackSource.ASMR.name,
                    ),
                )
                importAsmrNodes(node.children, id, work, here)
                continue
            }
            val hash = scopedFileHash(TrackSource.ASMR, node.storageKey(path))
            if (isAsmrAudio(node.type, node.title)) {
                if (db.tracks().findAsmr(work.id, hash) != null) continue
                db.tracks().insert(
                    TrackEntity(
                        source = TrackSource.ASMR,
                        title = name,
                        artist = work.circle.ifBlank { work.sourceId },
                        durationMs = node.durationMs,
                        coverUri = work.coverUrl,
                        localUri = null,
                        bvid = null,
                        cid = null,
                        page = null,
                        addedAt = System.currentTimeMillis(),
                        folderId = parentId,
                        remoteUrl = node.streamUrl,
                        asmrWorkId = work.id,
                        fileHash = hash,
                    ),
                )
            } else {
                if (db.files().findAsmr(work.id, hash) != null) continue
                db.files().insert(
                    com.nanahoshi.audioplayer.data.db.LibraryFileEntity(
                        folderId = parentId,
                        name = name,
                        kind = previewKind(node.title),
                        remoteUrl = node.streamUrl.orEmpty(),
                        addedAt = System.currentTimeMillis(),
                        asmrWorkId = work.id,
                        fileHash = hash,
                    ),
                )
            }
        }
    }
}