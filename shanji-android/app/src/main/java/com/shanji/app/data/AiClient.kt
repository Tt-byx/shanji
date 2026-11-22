package com.shanji.app.data

import android.content.Context
import com.shanji.app.data.net.AiApi
import com.shanji.app.data.net.AiChatMsg
import com.shanji.app.data.net.AiChatReq
import com.shanji.app.data.net.AiInsightReq
import com.shanji.app.data.net.AiParseData
import com.shanji.app.data.net.AiParseReq
import com.shanji.app.data.net.ApiFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AI 中转客户端（服务器持 GLM key）。
 * 所有异常统一过 SyncManager.friendlyError，抛出的 message 已是人话。
 */
object AiClient {

    private fun bearer(context: Context): String =
        "Bearer " + (SyncManager.token(context) ?: error("尚未登录"))

    /** 一句话记账解析：「午饭 15 块」→ 账单字段 */
    suspend fun parse(context: Context, rawText: String, persona: String? = null): AiParseData =
        withContext(Dispatchers.IO) {
            runCatching {
                val resp = ApiFactory.ai(context).parse(bearer(context), AiParseReq(rawText, persona))
                if (resp.code != 0 || resp.data == null) error(resp.message.ifEmpty { "" })
                resp.data
            }.getOrElse { throw Exception(SyncManager.friendlyError(it)) }
        }

    /** 对话查账/记账（多轮历史由调用方维护） */
    suspend fun chat(
        context: Context,
        messages: List<AiChatMsg>,
        monthContext: String? = null,
        persona: String? = null
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val resp = ApiFactory.ai(context)
                .chat(bearer(context), AiChatReq(messages, monthContext, persona))
            if (resp.code != 0 || resp.data == null) error(resp.message.ifEmpty { "" })
            resp.data
        }.getOrElse { throw Exception(SyncManager.friendlyError(it)) }
    }

    /**
     * 流式对话：onDelta 逐段收到正文（主线程回调由调用方保证）；
     * 首行 #ACT# 动作行通过 onAct 单独返回（正文不含它）。
     * 返回完整正文（不含动作行）。
     *
     * 读取方式：按网络 chunk 即读即抛（InputStreamReader 自带 UTF-8 跨包解码）。
     * 旧实现按行读（readUtf8Line），模型不发换行就一直憋着 → 整段整段蹦，
     * 看起来根本不是流式；换行密集时又变成「先一句再一句」。
     */
    suspend fun chatStream(
        context: Context,
        messages: List<AiChatMsg>,
        monthContext: String? = null,
        persona: String? = null,
        onDelta: (String) -> Unit,
        onAct: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val body = runCatching {
            ApiFactory.ai(context).chatStream(bearer(context), AiChatReq(messages, monthContext, persona))
        }.getOrElse { throw Exception(SyncManager.friendlyError(it)) }
        val sb = StringBuilder()
        try {
            val reader = java.io.InputStreamReader(body.source().inputStream(), Charsets.UTF_8)
            val buf = CharArray(4096)
            // 首行判定（#ACT# 可能跨 chunk）：攒够 5 字符或等到换行再决定
            val head = StringBuilder()
            var headDone = false
            fun emit(s: String) {
                if (s.isNotEmpty()) { sb.append(s); onDelta(s) }
            }
            while (true) {
                val n = reader.read(buf)
                if (n <= 0) break
                val chunk = String(buf, 0, n)
                if (headDone) { emit(chunk); continue }
                head.append(chunk)
                val nl = head.indexOf('\n')
                when {
                    nl >= 0 -> {
                        headDone = true
                        val first = head.substring(0, nl)
                        if (first.trim().startsWith("#ACT#")) {
                            onAct(first.trim().substring(5))
                        } else {
                            emit(first)      // 首行正文
                            emit("\n")       // 模型自己的换行保留
                        }
                        emit(head.substring(nl + 1))
                    }
                    head.length >= 5 && !head.startsWith("#ACT#") -> {
                        headDone = true
                        emit(head.toString())
                    }
                }
            }
            if (!headDone) emit(head.toString()) // 流在首行结束前断开
        } catch (e: Exception) {
            if (sb.isEmpty()) throw Exception(SyncManager.friendlyError(e))
            // 已有部分内容：降级为把已收到的当完整回复
        } finally {
            body.close()
        }
        sb.toString()
    }

    /** 洞察卡 / 月度报告文案 */
    suspend fun insight(
        context: Context,
        kind: String,
        ruleHits: List<String>,
        stats: String,
        persona: String? = null
    ): String = withContext(Dispatchers.IO) {
        runCatching {
            val resp = ApiFactory.ai(context)
                .insight(bearer(context), AiInsightReq(kind, ruleHits, stats, persona))
            if (resp.code != 0 || resp.data == null) error(resp.message.ifEmpty { "" })
            resp.data.text
        }.getOrElse { throw Exception(SyncManager.friendlyError(it)) }
    }
}
