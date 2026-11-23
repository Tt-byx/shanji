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
 * AI 接口免登录（除云同步外全功能可用）：登录了就带 token，没登录不带。
 * 所有异常统一过 SyncManager.friendlyError，抛出的 message 已是人话。
 */
object AiClient {

    private fun bearer(context: Context): String? =
        SyncManager.token(context)?.let { "Bearer $it" }

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
