package com.shanji.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {

    @Query("SELECT * FROM accounts WHERE deleted = 0 ORDER BY sortOrder ASC")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE deleted = 0 ORDER BY sortOrder ASC")
    suspend fun listAll(): List<AccountEntity>

    @Query("SELECT COUNT(*) FROM accounts WHERE deleted = 0")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<AccountEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(account: AccountEntity)

    @Update
    suspend fun update(account: AccountEntity)

    @Query("UPDATE accounts SET isDefault = 0 WHERE deleted = 0")
    suspend fun clearDefault()

    @Query("UPDATE accounts SET deleted = 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun softDelete(id: String, updatedAt: Long)

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun get(id: String): AccountEntity?

    @Query("UPDATE accounts SET isDefault = 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setDefault(id: String, updatedAt: Long)
}
