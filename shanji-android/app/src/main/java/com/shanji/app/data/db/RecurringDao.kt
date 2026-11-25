package com.shanji.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecurringDao {

    @Query("SELECT * FROM recurring WHERE deleted = 0 ORDER BY autoRecord DESC, dayOfMonth ASC")
    fun observeAll(): Flow<List<RecurringEntity>>

    /** 自动记账的周期规则（订阅 autoRecord=0 不参与生成） */
    @Query("SELECT * FROM recurring WHERE deleted = 0 AND enabled = 1 AND autoRecord = 1")
    suspend fun listEnabled(): List<RecurringEntity>

    @Query("SELECT * FROM recurring WHERE deleted = 0 AND enabled = 1")
    suspend fun listActive(): List<RecurringEntity>

    @Query("SELECT * FROM recurring WHERE deleted = 0")
    suspend fun listAll(): List<RecurringEntity>

    /** 同步推送专用：不过滤墓碑，删除的行要推到服务端才能传播到其它设备 */
    @Query("SELECT * FROM recurring")
    suspend fun listAllWithDeleted(): List<RecurringEntity>

    @Query("SELECT * FROM recurring WHERE id = :id")
    suspend fun get(id: String): RecurringEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: RecurringEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(list: List<RecurringEntity>)

    @Update
    suspend fun update(rule: RecurringEntity)

    @Query("UPDATE recurring SET lastRunDate = :date, updatedAt = :updatedAt WHERE id = :id")
    suspend fun markRun(id: String, date: String, updatedAt: Long)

    @Query("UPDATE recurring SET deleted = 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun softDelete(id: String, updatedAt: Long)
}
