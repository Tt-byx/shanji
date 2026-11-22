package com.shanji.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecurringDao {

    @Query("SELECT * FROM recurring WHERE deleted = 0 ORDER BY dayOfMonth ASC")
    fun observeAll(): Flow<List<RecurringEntity>>

    @Query("SELECT * FROM recurring WHERE deleted = 0 AND enabled = 1")
    suspend fun listEnabled(): List<RecurringEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: RecurringEntity)

    @Update
    suspend fun update(rule: RecurringEntity)

    @Query("UPDATE recurring SET lastRunDate = :date, updatedAt = :updatedAt WHERE id = :id")
    suspend fun markRun(id: String, date: String, updatedAt: Long)

    @Query("UPDATE recurring SET deleted = 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun softDelete(id: String, updatedAt: Long)
}
