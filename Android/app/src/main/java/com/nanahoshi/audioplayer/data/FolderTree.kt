package com.nanahoshi.audioplayer.data

import com.nanahoshi.audioplayer.data.db.FolderEntity

/**
 * 音频库目录树的通用遍历。所有入口对祖先、后代和相对路径使用同一套
 * 环检测与终止条件，避免各文件自行实现后出现细微差异。
 */
object FolderTree {
    /** 从 [folderId] 自身开始逐级向上，遇到缺失节点或环即停止。 */
    fun ancestorChain(folderId: Long?, byId: Map<Long, FolderEntity>): Sequence<FolderEntity> = sequence {
        var current = folderId
        val visited = mutableSetOf<Long>()
        while (current != null && visited.add(current)) {
            val folder = byId[current] ?: break
            yield(folder)
            current = folder.parentId
        }
    }

    /** 最近的满足 [predicate] 的祖先（含自身）。 */
    fun nearestRoot(
        folderId: Long?,
        byId: Map<Long, FolderEntity>,
        predicate: (FolderEntity) -> Boolean,
    ): FolderEntity? = ancestorChain(folderId, byId).firstOrNull(predicate)

    /** [rootId] 自身及全部后代。 */
    fun descendantIds(rootId: Long, folders: List<FolderEntity>): Set<Long> {
        val byParent = folders.groupBy { it.parentId }
        val found = linkedSetOf<Long>()
        val pending = ArrayDeque<Long>()
        pending.add(rootId)
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (found.add(id)) byParent[id].orEmpty().forEach { pending.add(it.id) }
        }
        return found
    }

    /** 从 [rootId] 下的目录到 [folderId] 的各级名称，不含根目录自身。 */
    fun relativePath(
        folderId: Long?,
        rootId: Long?,
        byId: Map<Long, FolderEntity>,
    ): List<String> {
        val path = mutableListOf<String>()
        for (folder in ancestorChain(folderId, byId)) {
            if (folder.id == rootId) break
            path += folder.name
        }
        return path.asReversed()
    }
}