package com.shanji.app.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** 会话（参考 Agent 项目 conversation：title/summary/软删） */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    /** 溢出摘要：滑窗挤出的旧消息压缩成的一段话（长期记忆） */
    val summary: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Int = 0
)

/** 会话消息（参考 chat_message：role/content 关联 conversation） */
@Entity(
    tableName = "chat_messages",
    indices = [Index("conversationId")]
)
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,          // user | assistant
    val content: String,
    val createdAt: Long
)

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE deleted = 0 ORDER BY updatedAt DESC")
    suspend fun list(): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun get(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(c: ConversationEntity)

    @Query("UPDATE conversations SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: String, title: String, now: Long)

    @Query("UPDATE conversations SET summary = :summary, updatedAt = :now WHERE id = :id")
    suspend fun saveSummary(id: String, summary: String, now: Long)

    @Query("UPDATE conversations SET deleted = 1 WHERE id = :id")
    suspend fun softDelete(id: String)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun hardDelete(id: String)
}

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages WHERE conversationId = :convId ORDER BY createdAt ASC")
    suspend fun listByConversation(convId: String): List<ChatMessageEntity>

    @Query("SELECT COUNT(*) FROM chat_messages WHERE conversationId = :convId")
    suspend fun count(convId: String): Int

    // REPLACE：流式回复以 messageId 为主键重复落库时幂等去重
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(m: ChatMessageEntity)

    /** 取滑窗用：最近 n 条（倒序） */
    @Query("SELECT * FROM chat_messages WHERE conversationId = :convId ORDER BY createdAt DESC LIMIT :n")
    suspend fun recent(convId: String, n: Int): List<ChatMessageEntity>

    /** 摘要后删除最早的一批消息（保留摘要即可还原语义） */
    @Query("DELETE FROM chat_messages WHERE conversationId = :convId AND createdAt <= :cutoff")
    suspend fun deleteUpTo(convId: String, cutoff: Long)
}
