package com.shanji.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TxDao {

    /* 活跃账单（未软删），按时间倒序 */
    @Query("SELECT * FROM txs WHERE deleted = 0 ORDER BY ts DESC")
    fun observeAll(): Flow<List<TxEntity>>

    @Query("SELECT * FROM txs WHERE deleted = 0 ORDER BY ts DESC")
    suspend fun listAll(): List<TxEntity>

    @Query("SELECT COUNT(*) FROM txs WHERE deleted = 0")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<TxEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tx: TxEntity)

    @Update
    suspend fun update(tx: TxEntity)

    /* 软删：留墓碑供云同步传播，避免 pull 把删除的账单复活 */
    @Query("UPDATE txs SET deleted = 1, dirty = 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun delete(id: String, updatedAt: Long)

    @Query("UPDATE txs SET deleted = 1, dirty = 1, updatedAt = :updatedAt WHERE deleted = 0")
    suspend fun softClear(updatedAt: Long)

    @Query("DELETE FROM txs")
    suspend fun clear()

    /* 同步相关 */
    @Query("SELECT * FROM txs WHERE dirty = 1")
    suspend fun listDirty(): List<TxEntity>

    @Query("UPDATE txs SET dirty = 0 WHERE id IN (:ids)")
    suspend fun clearDirty(ids: List<String>)

    @Query("SELECT * FROM txs WHERE id = :id")
    suspend fun get(id: String): TxEntity?

    @Query("SELECT * FROM txs WHERE ts >= :fromTs AND ts < :toTs AND deleted = 0 ORDER BY ts DESC")
    suspend fun queryByRange(fromTs: Long, toTs: Long): List<TxEntity>
}
