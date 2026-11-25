package com.shanji.app.data

import android.content.Context
import java.time.YearMonth
import java.util.UUID

/** 识别结果：items + 是否真走了 AI（false = AI 不可用降级本地规则） */
data class ScanResult(val items: List<Recurring>, val usedAi: Boolean, val error: String? = null)

/**
 * 订阅识别。
 * 首选：账单摘要交服务端 AI 语义判断（F7 真调 AI）；
 * 兜底：本地规则（连续 3 个月以上每月都有、金额波动 ±30% 内的支出）。
 */
object SubscriptionDetector {

    /** 近 6 个月支出摘要（每行 yyyy-MM-dd|商户|金额|分类key|账户id），最多 400 行防打爆提示词 */
    fun summaryLines(txs: List<Tx>): String {
        val monthsAgo = YearMonth.now().minusMonths(6)
        return txs.filter {
            it.type == "expense" && it.merchant.isNotBlank() && it.source != "recurring" &&
                YearMonth.from(toLocalDateTime(it.ts)) >= monthsAgo
        }.sortedByDescending { it.ts }
            .take(400)
            .joinToString("\n") { t ->
                val d = toLocalDateTime(t.ts).toLocalDate()
                "$d|${t.merchant.trim()}|${round2(t.amount)}|${t.category}|${t.accountId.ifBlank { "acc_cash" }}"
            }
    }

    /** AI 优先、本地兜底的完整扫描。[existing] 已有固定支出的商户名（AI 结果里跳过） */
    suspend fun scan(
        context: Context,
        txs: List<Tx>,
        existing: List<String>,
        accountId: String = "acc_cash"
    ): ScanResult {
        val summary = summaryLines(txs)
        if (summary.isBlank()) return ScanResult(emptyList(), usedAi = false, error = "没有可分析的账单")
        val r = runCatching { AiClient.subscribeScan(context, summary, existing) }
        return if (r.isSuccess) {
            val list = r.getOrThrow().filter { it.name.isNotBlank() && it.amount > 0 }.map { s ->
                Recurring(
                    id = "sub_" + UUID.randomUUID().toString().take(8),
                    type = "expense", amount = round2(s.amount), merchant = s.name.trim().take(30),
                    // U2+D8：AI 给的分类先归一（key/中文标签→合法 key，认不出→other），脏值不落库
                    category = normalizeCatKey(s.category) ?: "other",
                    // 优先用服务端从账单回填的账户（G5），取不到才用调用方兜底值
                    accountId = s.accountId.ifBlank { accountId },
                    dayOfMonth = s.dayOfMonth.coerceIn(1, 31),
                    enabled = true, lastRunDate = "",
                    cycle = s.cycle, dueMonth = if (s.cycle == "yearly") s.dueMonth else 0,
                    autoRecord = false, source = "ai"
                )
            }
            ScanResult(list, usedAi = true)
        } else {
            ScanResult(
                detect(txs, existing), usedAi = false,
                error = r.exceptionOrNull()?.message ?: "AI 识别失败，已用本地规则"
            )
        }
    }

    /** 本地规则识别（AI 不可用时的兜底）：**连续** 3 个月以上每月都有、金额波动 ±30% 内 */
    fun detect(txs: List<Tx>, existing: Collection<String> = emptyList()): List<Recurring> =
        txs.filter {
            it.type == "expense" && it.merchant.isNotBlank() &&
                // 周期记账自动生成的账单不算：房租规则每月生成的账单会被再认成订阅，重复
                it.source != "recurring"
        }
            .groupBy { it.merchant.trim() }
            .mapNotNull { (name, list) ->
                if (name in existing) return@mapNotNull null
                val months = list.map { YearMonth.from(toLocalDateTime(it.ts)) }.distinct().sorted()
                // 要求「连续」月份：1月/4月/9月 各一笔不算订阅（UI 文案写的是"连续3个月以上"）
                var run = 1
                var best = 1
                for (i in 1 until months.size) {
                    run = if (months[i] == months[i - 1].plusMonths(1)) run + 1 else 1
                    if (run > best) best = run
                }
                if (best < 3) return@mapNotNull null
                val avg = list.map { it.amount }.average()
                val min = list.minOf { it.amount }
                val max = list.maxOf { it.amount }
                if (min <= 0 || max > avg * 1.3 || max < avg * 0.7) return@mapNotNull null
                val last = list.maxBy { it.ts }
                Recurring(
                    id = "sub_" + UUID.randomUUID().toString().take(8),
                    type = "expense",
                    amount = round2(avg),
                    merchant = name,
                    category = normalizeCatKey(last.category) ?: "other",
                    accountId = last.accountId,
                    dayOfMonth = toLocalDateTime(last.ts).dayOfMonth,
                    enabled = true,
                    lastRunDate = "",
                    cycle = "monthly",
                    // 保留真实日期（29~31 号也行），扣款时按当月实际天数夹取
                    autoRecord = false,
                    source = "local"
                )
            }

    /** 给 AI 的一句话结论（对话里 subscribe 动作用） */
    fun summarize(candidates: List<Recurring>): String =
        if (candidates.isEmpty()) "没有发现连续3个月以上的固定支出"
        else "疑似订阅/固定支出：" + candidates.joinToString("；") {
            "${it.merchant} 约¥${fmtAmount(it.amount)}/月（每月${it.dayOfMonth}号）"
        }
}
