package com.shanji.app.data

import android.content.Context
import com.shanji.app.data.db.AppDatabase
import com.shanji.app.data.db.ChatMessageEntity
import com.shanji.app.data.db.ConversationEntity
import java.util.UUID

/**
 * 聊天记忆与对话管理（参考 Agent 项目 ConversationHistoryStore 分层）：
 * - 短期记忆：滑窗只取最近 WINDOW 条发给 AI
 * - 长期记忆：消息超过 SUMMARIZE_AT 条时，把最旧的一半 + 旧摘要压成新摘要（AI 生成），
 *   压缩后删除对应原始消息 —— 会话表 summary 字段持久化
 * - 对话管理：会话列表/新建/切换/软删除/自动标题
 * 全部本地 Room，无网络依赖（摘要生成除外，失败则跳过不阻塞聊天）。
 */
object ChatStore {

    /** 滑窗：发给 AI 的最近消息条数 */
    const val WINDOW = 20
    /** 超过该条数触发摘要压缩 */
    const val SUMMARIZE_AT = 40

    private fun db(context: Context) = AppDatabase.getInstance(context)

    /** 拿最近会话；没有则新建 */
    suspend fun latestOrCreate(context: Context): ConversationEntity {
        val dao = db(context).conversationDao()
        return dao.list().firstOrNull() ?: newConversation(context)
    }

    suspend fun newConversation(context: Context): ConversationEntity {
        val now = System.currentTimeMillis()
        val c = ConversationEntity(
            id = UUID.randomUUID().toString(),
            title = "新对话",
            createdAt = now,
            updatedAt = now
        )
        db(context).conversationDao().upsert(c)
        return c
    }

    suspend fun list(context: Context): List<ConversationEntity> =
        db(context).conversationDao().list()

    suspend fun rename(context: Context, id: String, title: String) {
        db(context).conversationDao().rename(id, title.trim().take(20).ifBlank { "新对话" }, System.currentTimeMillis())
    }

    suspend fun delete(context: Context, id: String) {
        val dao = db(context)
        dao.conversationDao().softDelete(id)
        // 会话已软删，消息一并清掉省空间
        dao.chatMessageDao().listByConversation(id).forEach { m ->
            dao.chatMessageDao().deleteUpTo(id, m.createdAt)
        }
    }

    /** 会话首条用户消息 → 自动标题 */
    suspend fun maybeAutoTitle(context: Context, convId: String, firstUserMsg: String) {
        val dao = db(context).conversationDao()
        val c = dao.get(convId) ?: return
        if (c.title == "新对话") {
            dao.rename(convId, firstUserMsg.take(12), System.currentTimeMillis())
        }
    }

    suspend fun messages(context: Context, convId: String): List<ChatMessageEntity> =
        db(context).chatMessageDao().listByConversation(convId)

    suspend fun append(context: Context, convId: String, role: String, content: String): ChatMessageEntity {
        val m = ChatMessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = convId,
            role = role,
            content = content,
            createdAt = System.currentTimeMillis() + (seq++)
        )
        db(context).chatMessageDao().insert(m)
        touch(context, convId)
        return m
    }

    private var seq = 0L

    private suspend fun touch(context: Context, convId: String) {
        val dao = db(context).conversationDao()
        val c = dao.get(convId) ?: return
        dao.upsert(c.copy(updatedAt = System.currentTimeMillis()))
    }

    /**
     * 组装发给 AI 的消息：摘要（如有）+ 最近 WINDOW 条。
     * 超过 SUMMARIZE_AT 条时先做摘要压缩（异步失败不影响本次）。
     * 返回按时间正序的 messages。
     */
    suspend fun buildMemory(context: Context, convId: String): List<Pair<String, String>> {
        val msgDao = db(context).chatMessageDao()
        val conv = db(context).conversationDao().get(convId)
        val all = msgDao.listByConversation(convId)

        // 触发压缩：旧消息一半 + 旧摘要 → 新摘要
        if (all.size > SUMMARIZE_AT) {
            try {
                compress(context, convId, all, conv?.summary ?: "")
            } catch (_: Exception) {
                // 摘要失败不阻塞本次聊天，下次再试
            }
        }

        val fresh = msgDao.recent(convId, WINDOW).asReversed()
        val summary = db(context).conversationDao().get(convId)?.summary ?: ""
        val out = ArrayList<Pair<String, String>>()
        if (summary.isNotBlank()) {
            out.add("system" to "以下是此前对话的摘要（长期记忆）：$summary")
        }
        fresh.forEach { out.add(it.role to it.content) }
        return out
    }

    /** 溢出摘要：最旧一半 + 旧摘要 → 新摘要，删除已压缩消息 */
    private suspend fun compress(
        context: Context,
        convId: String,
        all: List<ChatMessageEntity>,
        oldSummary: String
    ) {
        val half = all.size / 2
        val toCompress = all.take(half)
        val keep = all.drop(half)
        val transcript = toCompress.joinToString("\n") { "${it.role}: ${it.content}" }
        val prompt = buildString {
            if (oldSummary.isNotBlank()) append("已有摘要：\n$oldSummary\n\n")
            append("把下面的对话压缩成摘要，保留用户提到的关键事实（偏好、常消费的店、目标等），80字以内，只输出摘要：\n")
            append(transcript)
        }
        // 复用洞察接口做摘要（同一 GLM 通道）
        val summary = AiClient.insight(context, "summary", emptyList(), prompt, null)
        if (summary.isBlank()) return
        db(context).conversationDao().saveSummary(convId, summary, System.currentTimeMillis())
        val cutoff = keep.firstOrNull()?.createdAt ?: Long.MAX_VALUE
        db(context).chatMessageDao().deleteUpTo(convId, cutoff - 1)
    }
}
