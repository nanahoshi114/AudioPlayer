package com.nanahoshi.audioplayer

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.nanahoshi.audioplayer.data.db.AppDatabase
import org.junit.Assert.assertEquals
import java.io.File
import java.util.UUID

/**
 * 只暴露给单个测试的 Context。
 *
 * 文件与缓存目录指向独立根目录；SharedPreferences 使用本次运行唯一的文件名，
 * 避免测试读写真实应用的 `player_prefs`，并在 [withIsolatedApp] 结束时清理。
 */
class IsolatedTestApp private constructor(
    private val base: Context,
    val root: File,
    private val prefsPrefix: String,
) : ContextWrapper(base) {
    private val requestedPreferences = linkedSetOf<String>()

    override fun getApplicationContext(): Context = this

    override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }

    override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }

    override fun getNoBackupFilesDir(): File = File(root, "no_backup").apply { mkdirs() }

    override fun getDatabasePath(name: String): File =
        File(root, "databases").apply { mkdirs() }.resolve(name)

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        synchronized(requestedPreferences) { requestedPreferences += name }
        return base.getSharedPreferences("$prefsPrefix-$name", mode)
    }

    fun inMemoryDatabase(): AppDatabase =
        Room.inMemoryDatabaseBuilder(this, AppDatabase::class.java).build()

    internal fun cleanup() {
        val names = synchronized(requestedPreferences) { requestedPreferences.toList() }
        val dataDir = base.applicationInfo.dataDir
        for (name in names) {
            val proxied = "$prefsPrefix-$name"
            base.getSharedPreferences(proxied, Context.MODE_PRIVATE).edit().clear().commit()
            File(dataDir, "shared_prefs/$proxied.xml").delete()
        }
        root.deleteRecursively()
    }

    companion object {
        internal fun create(base: Context): IsolatedTestApp {
            val runId = UUID.randomUUID().toString()
            val root = File(base.cacheDir, "isolated-test-$runId").apply { mkdirs() }
            return IsolatedTestApp(base, root, "test-$runId")
        }
    }
}

/** 在隔离目录与隔离偏好中运行测试块，结束后清理所有临时内容。 */
suspend fun <T> withIsolatedApp(
    base: Context = InstrumentationRegistry.getInstrumentation().targetContext,
    block: suspend (IsolatedTestApp) -> T,
): T {
    val app = IsolatedTestApp.create(base)
    return try {
        block(app)
    } finally {
        app.cleanup()
    }
}

/**
 * 结构指纹只包含用户表/索引/外键，忽略 Room 与 SQLite 的内部表。
 *
 * 不比较 `sqlite_master.sql` 原文：Room 新建库与迁移 DDL 在标识符引号、`IF NOT EXISTS`
 * 以及 `ALTER TABLE ADD COLUMN` 追加位置上的文本差异不代表结构差异。这里改用 PRAGMA
 * 读取列顺序、类型、可空、默认值、主键，以及外键与显式索引，结果稳定且跨 API 版本一致。
 */
internal fun schemaFingerprint(database: AppDatabase): List<String> {
    val sql = database.openHelper.readableDatabase
    fun rows(query: String): List<List<String?>> = sql.query(query).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(List(cursor.columnCount) { index -> cursor.getString(index) })
            }
        }
    }
    val tables = rows(
        "SELECT name FROM sqlite_master WHERE type = 'table' " +
            "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' AND name NOT LIKE 'room_%' " +
            "ORDER BY name",
    ).mapNotNull { it.firstOrNull() }
    val indexes = rows(
        "SELECT name, tbl_name FROM sqlite_master WHERE type = 'index' " +
            "AND name NOT LIKE 'sqlite_%' AND sql IS NOT NULL ORDER BY name",
    ).filter { it.size >= 2 }.map { it[0].orEmpty() to it[1].orEmpty() }
    return buildList {
        for (table in tables) {
            add("table:$table")
            for (row in rows("PRAGMA table_info(`$table`)")) {
                add("column:$table:" + row.joinToString("|") { it.orEmpty() })
            }
            for (row in rows("PRAGMA foreign_key_list(`$table`)")) {
                add("foreignKey:$table:" + row.joinToString("|") { it.orEmpty() })
            }
            val indexList = rows("PRAGMA index_list(`$table`)").associateBy { it.getOrNull(1) }
            for ((name, _) in indexes.filter { it.second == table }) {
                val unique = indexList[name]?.getOrNull(2).orEmpty()
                add("index:$table:$name:unique=$unique")
                for (row in rows("PRAGMA index_info(`$name`)")) {
                    add("indexColumn:$table:$name:" + row.joinToString("|") { it.orEmpty() })
                }
            }
        }
    }.sorted()
}

/** 断言迁移后的库与按当前实体新建的库结构完全一致。 */
internal fun assertSchemaMatchesFresh(migrated: AppDatabase, app: IsolatedTestApp) {
    val fresh = app.inMemoryDatabase()
    try {
        assertEquals(schemaFingerprint(fresh), schemaFingerprint(migrated))
    } finally {
        fresh.close()
    }
}