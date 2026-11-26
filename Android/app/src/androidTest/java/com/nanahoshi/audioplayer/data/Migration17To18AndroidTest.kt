package com.nanahoshi.audioplayer.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nanahoshi.audioplayer.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Migration17To18AndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun seededLegacyImportRowsAreCleanedAndArchivePartIsCleared() = runBlocking {
        val name = "bili-import-migration-${UUID.randomUUID()}.db"
        val seed = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        seed.openHelper.writableDatabase
        seed.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { old ->
            old.downgradeTo17()
            old.execSQL(
                "INSERT INTO downloads(id, trackId, title, bytesDone, bytesTotal, status, biliImportPart) " +
                    "VALUES(41, 7, 'legacy bili archive', 5, 10, 'DONE', '1')",
            )
            old.execSQL("INSERT INTO bili_import_intents(bvid, batchId, requestJson) VALUES('BV1', 'batch-a', '{}')")
            old.execSQL("INSERT INTO bili_import_intents(bvid, batchId, requestJson) VALUES('BV2', 'batch-b', '{}')")
            old.version = 17
        }
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS).build()
        try {
            assertEquals(24, migrated.openHelper.writableDatabase.version)
            val row = checkNotNull(migrated.downloads().get(41))
            assertEquals("legacy bili archive", row.title)
            assertNull("MIGRATION_17_18 必须清空旧导入分 P 标记", row.biliImportPart)
            val tables = migrated.openHelper.readableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'bili_import_intents'",
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            assertTrue("MIGRATION_18_19 应移除旧导入意图表", tables.isEmpty())
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
        Unit
    }
}