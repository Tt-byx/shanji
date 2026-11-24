package com.shanji.app.data

import android.content.Context
import com.shanji.app.data.net.AiApi
import com.shanji.app.data.net.AiChatMsg
import com.shanji.app.data.net.AiChatReq
import com.shanji.app.data.net.AiInsightReq
import com.shanji.app.data.net.AiParseData
import com.shanji.app.data.net.AiParseReq
import com.shanji.app.data.net.AiVisionReq
import com.shanji.app.data.net.ApiFactory
import com.shanji.app.data.net.SubscribeScanReq
import com.shanji.app.data.net.SubscribeScanItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AI 中转客户端（服务器持 GLM key）。
 * AI 接口免登录（除云同步外全功能可用）：登录了就带 token，没登录不带。
 * 所有异常统一过 SyncManager.friendlyError，抛出的 message 已是人话。
 * 每次调用计入 AiUsage（我的页「AI 助手」可查本月调用/失败次数）。
 */
object AiClient {

    private fun bearer(context: Context): String? =
        SyncManager.token(context)?.let { "Bearer $it" }

    /** 一句话记账解析：「午饭 15 块」→ 账单字段；rules = 本机学到的分类规则摘要 */
    suspend fun parse(
        context: Context,
        rawText: String,
        persona: String? = null,
        rules: List<String>? = null
    ): AiParseData = withContext(Dispatchers.IO) {
        runCatching {
            val resp = ApiFactory.ai(context)
                .parse(bearer(context), AiParseReq(rawText, persona, rules))
            if (resp.code != 0 || resp.data == null) error(resp.message.ifEmpty { "" })
            AiUsage.record(context, true)
            resp.data
        }.getOrElse {
            AiUsage.record(context, false)
            throw Exception(SyncManager.friendlyError(it))
        }
    }

    /** 截图识别（多模态走服务器，端侧不再需要自己配 key）：压缩 JPEG → base64 → /api/ai/vision */
    suspend fun vision(context: Context, jpegBytes: ByteArray): AiResult =
        withContext(Dispatchers.IO) {
            val b64 = java.util.Base64.getEncoder().encodeToString(jpegBytes)
            runCatching {
                val resp = ApiFactory.ai(context).vision(bearer(context), AiVisionReq(b64))
                if (resp.code != 0 || resp.data == null) error(resp.message.ifEmpty { "" })
                val d = resp.data
                AiUsage.record(context, true)
                AiResult(d.amount, d.merchant, d.time)
            }.getOrElse {
                AiUsage.record(context, false)
                throw Exception(SyncManager.friendlyError(it))
            }
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
            AiUsage.record(context, true)
            resp.data
        }.getOrElse {
            AiUsage.record(context, false)
            throw Exception(SyncManager.friendlyError(it))
        }
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
            AiUsage.record(context, true)
            resp.data.text
        }.getOrElse {
            AiUsage.record(context, false)
            throw Exception(SyncManager.friendlyError(it))
        }
    }

    /**
     * 订阅识别（F7 真调 AI）：把近几个月账单摘要交给服务端做语义判断，
     * 只认「连续按月出现、金额稳定」的固定支出；失败由调用方降级到本地规则。
     */
    suspend fun subscribeScan(
        context: Context,
        txSummary: String,
        existing: List<String>
    ): List<SubscribeScanItem> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = ApiFactory.ai(context)
                .subscribeScan(bearer(context), SubscribeScanReq(txSummary, existing))
            if (resp.code != 0 || resp.data == null) error(resp.message.ifEmpty { "" })
            AiUsage.record(context, true)
            resp.data.items
        }.getOrElse {
            AiUsage.record(context, false)
            throw Exception(SyncManager.friendlyError(it))
        }
    }
}
