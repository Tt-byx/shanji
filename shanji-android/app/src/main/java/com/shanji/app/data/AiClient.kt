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
            body.source().use { src ->
                // 按 UTF-8 行读：chunked 流里 \n 分隔
                while (!src.exhausted()) {
                    val line = src.readUtf8Line() ?: break
                    if (line.isEmpty()) continue
                    if (line.startsWith("#ACT#")) {
                        onAct(line.substring(5))
                    } else {
                        sb.append(line).append('\n')
                        onDelta(line + "\n")
                    }
                }
            }
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
