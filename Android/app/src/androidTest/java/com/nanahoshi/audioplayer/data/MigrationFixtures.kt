package com.nanahoshi.audioplayer.data

import android.database.sqlite.SQLiteDatabase

/**
 * 把当前 v24 Room 库逐级还原成旧版本的「真实结构」，供迁移测试使用。
 *
 * Android API 24–33 的 SQLite 不支持 `ALTER TABLE ... DROP COLUMN`（需要 3.35+），
 * 因此这里用「新建旧表 → 复制共有列 → 删除新表 → 改名」的方式逆向重建，
 * 并手动补回随表删除的索引。相比在测试里直接写 `DROP COLUMN`，这样在 API 24–36
 * 都能执行，也保留了旧版本真实的列顺序与可空性。
 */

private val DOWNLOAD_COLUMNS = linkedMapOf(
    "id" to "INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL",
    "trackId" to "INTEGER NOT NULL",
    "title" to "TEXT NOT NULL",
    "bytesDone" to "INTEGER NOT NULL",
    "bytesTotal" to "INTEGER NOT NULL",
    "status" to "TEXT NOT NULL",
    "error" to "TEXT",
    "remoteUrl" to "TEXT",
    "referer" to "TEXT",
    "source" to "TEXT",
    "workKey" to "TEXT",
    "localPath" to "TEXT",
    "fileKey" to "TEXT",
    "biliImportPart" to "TEXT",
    "kind" to "TEXT",
    "phase" to "TEXT",
    "workFolderId" to "INTEGER",
    "pendingBiliSubtitle" to "INTEGER",
    "progressSummary" to "TEXT",
    "jaEntryKey" to "TEXT",
    "jaReplace" to "INTEGER",
    "jaForce" to "INTEGER",
    "dismissedAt" to "INTEGER",
)

private val WHISPER_COLUMNS = linkedMapOf(
    "id" to "INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL",
    "title" to "TEXT NOT NULL",
    "fingerprint" to "TEXT NOT NULL",
    "trackId" to "INTEGER",
    "localPath" to "TEXT",
    "remoteJobId" to "TEXT",
    "status" to "TEXT NOT NULL",
    "progress" to "REAL NOT NULL",
    "error" to "TEXT",
    "awaitRemoteUrl" to "TEXT",
    "endpoint" to "TEXT",
    "translatedAt" to "INTEGER",
    "translateStatus" to "TEXT",
    "skipTranslate" to "INTEGER",
    "model" to "TEXT",
    "awaitDownloadId" to "INTEGER",
    "awaitFileKey" to "TEXT",
    "remoteSubmittedAt" to "INTEGER",
    "progressSummary" to "TEXT",
    "createdAt" to "INTEGER",
    "dismissedAt" to "INTEGER",
)

private val TRANSLATION_COLUMNS = linkedMapOf(
    "id" to "INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL",
    "title" to "TEXT NOT NULL",
    "trackId" to "INTEGER",
    "remoteUrl" to "TEXT",
    "queueItemId" to "INTEGER",
    "status" to "TEXT NOT NULL",
    "error" to "TEXT",
    "kind" to "TEXT",
    "generatedFingerprint" to "TEXT",
    "sourceFingerprint" to "TEXT",
    "bvid" to "TEXT",
    "cid" to "INTEGER",
    "localUri" to "TEXT",
    "progressSummary" to "TEXT",
    "createdAt" to "INTEGER",
    "dismissedAt" to "INTEGER",
)

private val QUEUE_COLUMNS = linkedMapOf(
    "id" to "INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL",
    "trackId" to "INTEGER",
    "position" to "INTEGER NOT NULL",
    "title" to "TEXT",
    "artist" to "TEXT",
    "coverUri" to "TEXT",
    "remoteUrl" to "TEXT",
    "source" to "TEXT",
    "referer" to "TEXT",
    "workno" to "TEXT",
    "fileKey" to "TEXT",
    "mediaKind" to "TEXT",
    "mediaPathHash" to "TEXT",
)

private val MEDIA_COLUMNS = linkedMapOf(
    "postId" to "TEXT NOT NULL",
    "mediaKey" to "TEXT NOT NULL",
    "trackId" to "INTEGER",
    "kind" to "TEXT NOT NULL",
    "title" to "TEXT NOT NULL",
    "durationMs" to "INTEGER NOT NULL",
    "chapters" to "TEXT",
    "pathHash" to "TEXT",
)

private fun Map<String, String>.columnsUntil(last: String): List<String> {
    val columns = keys.toList()
    val end = columns.indexOf(last)
    require(end >= 0) { "missing column $last" }
    return columns.subList(0, end + 1).toList()
}

private fun SQLiteDatabase.rebuild(
    table: String,
    definitions: Map<String, String>,
    columns: List<String>,
    indexes: List<String>,
    extraConstraints: List<String> = emptyList(),
) {
    val names = columns.joinToString(", ")
    val body = columns.joinToString(", ") { "${it} ${definitions.getValue(it)}" } +
        extraConstraints.joinToString("") { ", $it" }
    val temporary = "${table}__downgrade"
    execSQL("CREATE TABLE $temporary ($body)")
    execSQL("INSERT INTO $temporary ($names) SELECT $names FROM $table")
    execSQL("DROP TABLE $table")
    execSQL("ALTER TABLE $temporary RENAME TO $table")
    indexes.forEach { execSQL(it) }
}

internal fun SQLiteDatabase.downgradeTo22() {
    removeV24SchemaChanges()
    rebuild(
        "downloads", DOWNLOAD_COLUMNS,
        DOWNLOAD_COLUMNS.columnsUntil("jaForce"),
        listOf("CREATE INDEX index_downloads_trackId ON downloads (trackId)"),
    )
    rebuild(
        "whisper_jobs", WHISPER_COLUMNS,
        WHISPER_COLUMNS.columnsUntil("progressSummary"),
        emptyList(),
    )
    rebuild(
        "translation_jobs", TRANSLATION_COLUMNS,
        TRANSLATION_COLUMNS.columnsUntil("progressSummary"),
        emptyList(),
    )
}

internal fun SQLiteDatabase.downgradeTo21() {
    downgradeTo22()
    rebuild(
        "downloads", DOWNLOAD_COLUMNS,
        DOWNLOAD_COLUMNS.columnsUntil("progressSummary"),
        listOf("CREATE INDEX index_downloads_trackId ON downloads (trackId)"),
    )
    rebuild(
        "queue_items", QUEUE_COLUMNS,
        QUEUE_COLUMNS.columnsUntil("mediaKind"),
        listOf("CREATE INDEX index_queue_items_trackId ON queue_items (trackId)"),
        extraConstraints = listOf(
            "FOREIGN KEY(trackId) REFERENCES tracks(id) ON UPDATE NO ACTION ON DELETE CASCADE",
        ),
    )
    rebuild(
        "japaneseasmr_media", MEDIA_COLUMNS,
        MEDIA_COLUMNS.columnsUntil("chapters"),
        listOf("CREATE INDEX index_japaneseasmr_media_trackId ON japaneseasmr_media (trackId)"),
        extraConstraints = listOf("PRIMARY KEY(postId, mediaKey)"),
    )
    execSQL("DROP TABLE IF EXISTS japaneseasmr_download_entries")
    execSQL("DROP TABLE IF EXISTS japaneseasmr_download_packages")
    execSQL("DROP TABLE IF EXISTS japaneseasmr_download_works")
}

internal fun SQLiteDatabase.downgradeTo20() {
    downgradeTo21()
    rebuild(
        "queue_items", QUEUE_COLUMNS,
        QUEUE_COLUMNS.columnsUntil("fileKey"),
        listOf("CREATE INDEX index_queue_items_trackId ON queue_items (trackId)"),
        extraConstraints = listOf(
            "FOREIGN KEY(trackId) REFERENCES tracks(id) ON UPDATE NO ACTION ON DELETE CASCADE",
        ),
    )
    execSQL("DROP TABLE IF EXISTS japaneseasmr_media")
    execSQL("DROP TABLE IF EXISTS japaneseasmr_posts")
}

internal fun SQLiteDatabase.downgradeTo19() {
    downgradeTo20()
    rebuild(
        "downloads", DOWNLOAD_COLUMNS,
        DOWNLOAD_COLUMNS.columnsUntil("pendingBiliSubtitle"),
        listOf("CREATE INDEX index_downloads_trackId ON downloads (trackId)"),
    )
    rebuild(
        "whisper_jobs", WHISPER_COLUMNS,
        WHISPER_COLUMNS.columnsUntil("remoteSubmittedAt"),
        emptyList(),
    )
    rebuild(
        "translation_jobs", TRANSLATION_COLUMNS,
        TRANSLATION_COLUMNS.columnsUntil("localUri"),
        emptyList(),
    )
}

internal fun SQLiteDatabase.downgradeTo18() {
    downgradeTo19()
    execSQL(
        "CREATE TABLE IF NOT EXISTS bili_import_intents " +
            "(bvid TEXT NOT NULL PRIMARY KEY, batchId TEXT NOT NULL, requestJson TEXT NOT NULL)",
    )
    execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_bili_import_intents_batchId ON bili_import_intents (batchId)")
}

internal fun SQLiteDatabase.downgradeTo17() {
    downgradeTo18()
    rebuild(
        "downloads", DOWNLOAD_COLUMNS,
        DOWNLOAD_COLUMNS.columnsUntil("phase"),
        listOf("CREATE INDEX index_downloads_trackId ON downloads (trackId)"),
    )
    rebuild(
        "whisper_jobs", WHISPER_COLUMNS,
        WHISPER_COLUMNS.columnsUntil("awaitFileKey"),
        emptyList(),
    )
    rebuild(
        "translation_jobs", TRANSLATION_COLUMNS,
        TRANSLATION_COLUMNS.columnsUntil("error"),
        emptyList(),
    )
    execSQL("DROP TABLE IF EXISTS subtitle_publications")
}

internal fun SQLiteDatabase.downgradeTo16() {
    downgradeTo17()
    execSQL("DROP TABLE IF EXISTS bili_collection_videos")
    execSQL("DROP TABLE IF EXISTS bili_collections")
}