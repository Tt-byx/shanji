package com.shanji.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BudgetDao {

    @Query("SELECT * FROM budgets WHERE deleted = 0")
    fun observeAll(): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM budgets WHERE deleted = 0")
    suspend fun listAll(): List<BudgetEntity>

    /** 含墓碑：同步 push 用，本地删除才能传播到其它设备（D4） */
    @Query("SELECT * FROM budgets")
    suspend fun listAllWithDeleted(): List<BudgetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(budget: BudgetEntity)

    @Query("UPDATE budgets SET deleted = 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun softDelete(id: String, updatedAt: Long)

    @Query("SELECT * FROM budgets WHERE id = :id")
    suspend fun get(id: String): BudgetEntity?
}
