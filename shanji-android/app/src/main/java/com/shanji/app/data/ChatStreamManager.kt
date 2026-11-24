package com.shanji.app.data

import android.content.Context
import com.shanji.app.data.net.AiChatReq
import com.shanji.app.data.net.ApiFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 全局 AI 流管理器（规则2：流式管理与视图解耦）：
 * - 按 messageId 持有已收 token 缓冲、状态（streaming/done/error）与订阅（StateFlow）；
 * - 独立于任何组件生命周期：切 Session、最小化、组件销毁只是 UI 取消订阅，
 *   本管理器的读取协程与服务端连接继续存在、缓冲继续追加（规则3）；
 * - 切回时 StateFlow replay 立即渲染已有缓冲并继续实时追加（规则4）；
 * - 断线/杀进程后凭 messageId + offset 向服务端断点续传；任务早已完成则直接拉完整消息（规则5）；
 * - 生成任务归属服务端（POST /chat/task），客户端断开不取消生成（规则1）。
 * 记录仍落本地 Room：以 messageId 为主键幂等入库，重复续传不产生重复消息。
 */
object ChatStreamManager {

    enum class Status { STREAMING, DONE, ERROR }

    data class Snapshot(
        val messageId: String,
        val conversationId: String,
        val text: String,                 // 已收正文缓冲
        val status: Status,
        val act: String? = null,          // 首轮 #ACT# 动作行（正文不含它）
        val allowAct: Boolean = true,     // 二轮不递归动作
        val persisted: Boolean = false,   // 已落库（用于裁剪与去重）
        val order: Long = 0               // 创建序，保证多任务展示顺序
    )

    private val _snapshots = MutableStateFlow<Map<String, Snapshot>>(emptyMap())
    val snapshots: StateFlow<Map<String, Snapshot>> = _snapshots

    // App 级 scope：UI 销毁不影响读取协程（规则3）
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val actConsumed = mutableSetOf<String>()

    private const val PREF = "shanji_streams"
    private const val KEY_PENDING = "pending" // 未完成任务：messageId|convId|allowAct

    private fun bearer(context: Context): String? =
        SyncManager.token(context)?.let { "Bearer $it" }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /**
     * 启动后台生成任务：POST 拿 messageId → 登记缓冲 → 开读。
     * 返回后 UI 靠 collect [snapshots] 感知气泡与增量。
     * 用 applicationContext，读取协程长生命周期不泄漏 Activity。
     */
    suspend fun start(
        context: Context,
        conversationId: String,
        req: AiChatReq,
        allowAct: Boolean = true
    ): String {
        val app = context.applicationContext
        val resp = runCatching {
            ApiFactory.ai(app).startTask(bearer(app), req)
        }.getOrElse {
            AiUsage.record(app, false)
            throw Exception(SyncManager.friendlyError(it))
        }
        if (resp.code != 0 || resp.data == null) {
            AiUsage.record(app, false)
            error(resp.message.ifEmpty { "发起对话失败" })
        }
        AiUsage.record(app, true)
        val id = resp.data
        android.util.Log.d("SHANJI", "stream start id=${id.take(8)} conv=${conversationId.take(6)}")
        pruneFinished()
        _snapshots.update {
            it + (id to Snapshot(
                messageId = id,
                conversationId = conversationId,
                text = "",
                status = Status.STREAMING,
                allowAct = allowAct,
                order = System.nanoTime()
            ))
        }
        savePending(app, id, conversationId, allowAct)
        launchReader(app, id)
        return id
    }

    /** 消费首轮动作行（幂等：同一任务只返回一次），等 DONE 后由 UI 调用 */
    fun consumeAct(messageId: String): String? = synchronized(actConsumed) {
        val s = _snapshots.value[messageId] ?: return null
        if (!s.allowAct || s.act == null) return null
        if (!actConsumed.add(messageId)) null else s.act
    }

    /**
     * 断线/杀进程恢复（规则5）：把本地记录的未完成任务重新接上，
     * offset=0 → 服务端补发完整内容；服务端任务已完成则直接拉到完整消息。
     */
    fun resumePending(context: Context) {
        val app = context.applicationContext
        val set = prefs(app).getStringSet(KEY_PENDING, emptySet()) ?: emptySet()
        for (entry in set) {
            val parts = entry.split("|")
            if (parts.size < 2) continue
            val id = parts[0]
            val convId = parts[1]
            val allowAct = parts.getOrNull(2) == "1"
            if (_snapshots.value.containsKey(id)) continue
            android.util.Log.d("SHANJI", "stream resume id=${id.take(8)}")
            _snapshots.update {
                it + (id to Snapshot(
                    messageId = id,
                    conversationId = convId,
                    text = "",
                    status = Status.STREAMING,
                    allowAct = allowAct,
                    order = System.nanoTime()
                ))
            }
            launchReader(app, id)
        }
    }

    // ---------- 内部 ----------

    private fun launchReader(context: Context, messageId: String) {
        scope.launch {
            var retries = 0
            var lastError: Throwable? = null
            while (true) {
                val snap = _snapshots.value[messageId] ?: return@launch
                if (snap.status != Status.STREAMING) return@launch
                val offset = snap.text.length
                try {
                    val body = ApiFactory.ai(context)
                        .streamTask(bearer(context), messageId, offset.toLong())
                    val sawDone = try {
                        readEvents(context, messageId, body)
                    } finally {
                        body.close()
                    }
                    if (sawDone) return@launch
                    // EOF 未见 done：服务端断开 → 带新 offset 退避重连（断点续传）
                    retries++
                } catch (e: Exception) {
                    lastError = e
                    retries++
                    android.util.Log.w("SHANJI", "stream read#$retries id=${messageId.take(8)}: ${e.message}")
                }
                if (retries > 8) {
                    val partial = _snapshots.value[messageId]?.text?.isNotBlank() == true
                    val msg = when {
                        partial -> null                        // 有部分内容：降级为完整回复
                        lastError != null -> SyncManager.friendlyError(lastError!!)
                        else -> "网络中断，回复未完成"
                    }
                    finish(context, messageId, msg)
                    return@launch
                }
                delay(minOf(500L * retries, 5000L))
            }
        }
    }

    /**
     * 读一个 SSE 连接直到结束：event: act|delta|done|expired，data 行按 SSE 规范拼接。
     * 返回是否见过 done/expired（终态）。
     */
    private suspend fun readEvents(context: Context, messageId: String, body: okhttp3.ResponseBody): Boolean {
        val reader = BufferedReader(InputStreamReader(body.source().inputStream(), Charsets.UTF_8))
        var event = ""
        val data = StringBuilder()
        var hasData = false // 不能用 data.isNotEmpty()：首行为空串会丢前导换行
        while (true) {
            val line = reader.readLine() ?: break // EOF
            when {
                line.startsWith("event:") -> event = line.substring(6).trim()
                line.startsWith("data:") -> {
                    if (hasData) data.append('\n')
                    data.append(line.substring(5).removePrefix(" "))
                    hasData = true
                }
                line.isEmpty() -> {
                    when (event) {
                        "act" -> {
                            android.util.Log.d("SHANJI", "stream act id=${messageId.take(8)}")
                            _snapshots.update { m ->
                                m[messageId]?.let { m + (messageId to it.copy(act = data.toString())) } ?: m
                            }
                        }
                        "delta" -> appendText(messageId, data.toString())
                        "done" -> {
                            finish(context, messageId, null)
                            return true
                        }
                        "expired" -> {
                            android.util.Log.w("SHANJI", "stream expired id=${messageId.take(8)}")
                            finish(context, messageId, data.toString().ifBlank { "回复已失效，请重新发送" })
                            return true
                        }
                    }
                    event = ""
                    data.clear()
                    hasData = false
                }
                // ": ping" 保活注释行忽略
            }
        }
        return false
    }

    private fun appendText(messageId: String, delta: String) {
        if (delta.isEmpty()) return
        _snapshots.update { m ->
            m[messageId]?.let { m + (messageId to it.copy(text = it.text + delta)) } ?: m
        }
    }

    /**
     * 终态收尾：先落库（幂等）→ 清 pending → 发布终态。
     * 顺序保证：任意时刻杀进程，pending 与 Room 至少一侧完整，恢复时不会丢也不会重。
     * failureMsg 仅在失败且无任何内容时作为正文入库（同旧版失败文案入库行为）。
     */
    private suspend fun finish(context: Context, messageId: String, failureMsg: String?) {
        val snap = _snapshots.value[messageId] ?: return
        var text = snap.text
        val status: Status = when {
            text.isNotBlank() -> Status.DONE                    // 正常完成 或 断线已有部分内容
            failureMsg != null -> {
                text = failureMsg                              // 失败且无内容：错误文案入库
                Status.ERROR
            }
            snap.act != null -> Status.DONE                     // 只有动作行没正文：空泡交给二轮，不占位
            else -> {
                text = "没听懂，换个说法试试"                     // 空回复：占位只展示不入库
                Status.DONE
            }
        }
        // 结构化卡片（act JSON 的 card 字段）随正文持久化成 #CARD# 行：
        // 切走/重进会话仍能渲染，渲染时该行从 Markdown 正文中剥离
        val cardJson = if (status == Status.DONE) snap.act?.let { extractCard(it) } else null
        if (cardJson != null) text = text.trimEnd() + "\n#CARD#" + cardJson
        var persisted = false
        if (status != Status.DONE || snap.text.isNotBlank() || cardJson != null) {
            persisted = persistOnce(context, snap.conversationId, messageId, text.trim())
        }
        clearPending(context, messageId)
        android.util.Log.d("SHANJI", "stream finish id=${messageId.take(8)} status=$status len=$text.length card=${cardJson != null}")
        _snapshots.update { m ->
            m[messageId]?.let { m + (messageId to it.copy(text = text, status = status, persisted = persisted)) } ?: m
        }
    }

    /** 从 #ACT# JSON 里取出 card 对象并紧凑序列化；没有 card 返回 null */
    private fun extractCard(act: String): String? = runCatching {
        val el = kotlinx.serialization.json.Json.parseToJsonElement(act)
        val card = (el as? kotlinx.serialization.json.JsonObject)?.get("card") ?: return null
        kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.json.JsonElement.serializer(), card
        )
    }.getOrNull()

    /** 幂等落库：messageId 作主键，已存在则跳过（保 createdAt 不被 REPLACE 冲掉顺序） */
    private suspend fun persistOnce(context: Context, convId: String, messageId: String, text: String): Boolean {
        if (text.isBlank()) return false
        val exists = ChatStore.messages(context, convId).any { it.id == messageId }
        if (exists) {
            android.util.Log.d("SHANJI", "persist skip(exists) id=${messageId.take(8)} conv=${convId.take(6)}")
            return true
        }
        ChatStore.append(context, convId, "assistant", text, id = messageId)
        android.util.Log.d(
            "SHANJI",
            "persist ok id=${messageId.take(8)} conv=${convId.take(6)} len=${text.length} head=${text.take(30)}"
        )
        return true
    }

    /** 裁掉已落库、无待处理动作的终态快照，防 map 无限增长 */
    private fun pruneFinished() {
        _snapshots.update { m ->
            val consumed = synchronized(actConsumed) { actConsumed.toSet() }
            m.filterValues { s ->
                s.status == Status.STREAMING ||
                    !s.persisted ||
                    (s.act != null && s.messageId !in consumed)
            }
        }
    }

    // ---------- pending 持久化（杀进程后恢复用） ----------

    private fun savePending(context: Context, id: String, convId: String, allowAct: Boolean) {
        val s = prefs(context).getStringSet(KEY_PENDING, emptySet())!!.toMutableSet()
        s.add("$id|$convId|${if (allowAct) 1 else 0}")
        prefs(context).edit().putStringSet(KEY_PENDING, s).apply()
    }

    private fun clearPending(context: Context, id: String) {
        val p = prefs(context)
        val s = p.getStringSet(KEY_PENDING, emptySet())!!.toMutableSet()
        if (s.removeAll { it.startsWith("$id|") }) p.edit().putStringSet(KEY_PENDING, s).apply()
    }
}
