package com.nanahoshi.audioplayer.data

/** 将要缓存的一首音频：同一目录里文件名前缀相同、扩展名不同的，算同一首的不同格式。 */
data class CacheCandidate(
    val id: Long,
    val folderId: Long?,
    val name: String,
)

/** 一组扩展名相同的冲突。`groups` 里每一项是「扩展名 -> 该格式的曲目 id」。 */
data class FormatConflict(
    val extensions: List<String>,
    val groups: List<Map<String, List<Long>>>,
)

fun findFormatConflicts(items: List<CacheCandidate>): List<FormatConflict> {
    val byStem = items
        .filter { fileExtension(it.name).isNotEmpty() }
        .groupBy { it.folderId to matchKey(it.name.substringBeforeLast('.')) }
    val conflicts = linkedMapOf<List<String>, MutableList<Map<String, List<Long>>>>()
    for (group in byStem.values) {
        val byExtension = group.groupBy { fileExtension(it.name) }
        if (byExtension.size < 2) continue
        val extensions = byExtension.keys.sorted()
        conflicts.getOrPut(extensions) { mutableListOf() }
            .add(byExtension.mapValues { (_, files) -> files.map { it.id } })
    }
    return conflicts.map { (extensions, groups) -> FormatConflict(extensions, groups) }
}

/** 选了 [keep] 之后，这组冲突里不再下载的曲目 id。 */
fun idsToDrop(conflict: FormatConflict, keep: String): Set<Long> =
    conflict.groups.flatMap { group ->
        group.filterKeys { it != keep }.values.flatten()
    }.toSet()
