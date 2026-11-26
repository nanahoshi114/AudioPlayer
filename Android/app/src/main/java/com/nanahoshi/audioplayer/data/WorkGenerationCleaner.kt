package com.nanahoshi.audioplayer.data

import java.io.File
import java.util.UUID

/**
 * 作品目录下的 UUID 代次目录（下载暂存、解压或生成文件）。
 * 清理入口统一在这里判定代次名称，避免各仓储把普通子目录误当作代次删除。
 */
internal object WorkGenerationCleaner {
    fun isGeneration(name: String): Boolean = runCatching { UUID.fromString(name) }.isSuccess

    /** 遍历作品根下 `<directory>/<uuid>` 形式的代次目录。 */
    fun forEachGeneration(workRoot: File, directoryName: String, block: (work: File, generation: File) -> Unit) {
        for (work in workRoot.listFiles().orEmpty()) {
            if (!work.isDirectory || !WorkIds.matches(work.name)) continue
            for (generation in File(work, directoryName).listFiles().orEmpty()) {
                if (!generation.isDirectory || !isGeneration(generation.name)) continue
                block(work, generation)
            }
        }
    }

    /**
     * 删除没有被任何引用路径覆盖的整个代次目录。
     * [protectedCanonical] 为规范化后的绝对路径集合。
     */
    fun removeUnreferenced(
        workRoot: File,
        directoryName: String,
        protectedCanonical: Set<String>,
        mustBeInside: File? = null,
    ) {
        val boundary = mustBeInside?.let { it.canonicalPath + File.separator }
        forEachGeneration(workRoot, directoryName) { _, generation ->
            val path = runCatching { generation.canonicalPath }.getOrNull() ?: return@forEachGeneration
            if (boundary != null && !path.startsWith(boundary)) return@forEachGeneration
            if (protectedCanonical.any { it == path || it.startsWith(path + File.separator) }) return@forEachGeneration
            generation.deleteRecursively()
        }
    }
}