package com.nanahoshi.audioplayer.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DlsiteOriginalDao {
    @Query("SELECT * FROM dlsite_original_works WHERE workno = :workno")
    suspend fun work(workno: String): DlsiteOriginalWorkEntity?

    @Query("SELECT * FROM dlsite_original_works WHERE workno = :workno")
    fun observeWork(workno: String): Flow<DlsiteOriginalWorkEntity?>

    @Query("SELECT * FROM dlsite_original_works")
    suspend fun works(): List<DlsiteOriginalWorkEntity>

    @Query("SELECT * FROM dlsite_original_entries WHERE workno = :workno")
    suspend fun entries(workno: String): List<DlsiteOriginalEntryEntity>

    @Query("SELECT * FROM dlsite_original_entries")
    suspend fun allEntries(): List<DlsiteOriginalEntryEntity>

    @Query("SELECT * FROM dlsite_original_entries")
    fun observeEntries(): Flow<List<DlsiteOriginalEntryEntity>>

    @Query("SELECT * FROM dlsite_original_entries WHERE trackId = :trackId LIMIT 1")
    suspend fun entryForTrack(trackId: Long): DlsiteOriginalEntryEntity?

    @Query("SELECT * FROM dlsite_original_entries WHERE workno = :workno AND playFileKey = :key LIMIT 1")
    suspend fun entryForPlay(workno: String, key: String): DlsiteOriginalEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWork(work: DlsiteOriginalWorkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntries(entries: List<DlsiteOriginalEntryEntity>)

    @Query("DELETE FROM dlsite_original_entries WHERE workno = :workno")
    suspend fun deleteEntries(workno: String)
}