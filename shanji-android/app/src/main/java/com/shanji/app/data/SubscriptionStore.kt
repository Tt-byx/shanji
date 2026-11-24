package com.shanji.app.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/**
 * 订阅：会员 / 话费 / 云盘这类周期性固定支出。
 * 与「周期记账」互补——周期记账是自己建规则自动记账；订阅是从历史账单里识别出来，
 * 再做「到期前提醒」，避免忘续费或多扣一笔。
 */
@Serializable
data class Subscription(
    val id: String,
    val name: String,
    val amount: Double,          // 识别时的月均金额
    val category: String,
    val accountId: String = "acc_cash",
    val cycle: String = "monthly",   // monthly | yearly
    val dayOfMonth: Int = 1,         // 每月几号（1~28）；yearly 为到期日几号
    val dueMonth: Int = 0,           // yearly 到期月份（1~12）；monthly 为 0
    val enabled: Boolean = true,
    val source: String = "ai"        // ai=识别出来的 | manual=手动加的
)

/** 订阅识别：近 3 个月以上每月都有、金额波动在 ±30% 内的支出 → 疑似订阅（纯本地，不联网） */
object SubscriptionDetector {

    fun detect(txs: List<Tx>, existing: Collection<String> = emptyList()): List<Subscription> =
        txs.filter { it.type == "expense" && it.merchant.isNotBlank() }
            .groupBy { it.merchant.trim() }
            .mapNotNull { (name, list) ->
                if (name in existing) return@mapNotNull null
                val months = list.map { YearMonth.from(toLocalDateTime(it.ts)) }.distinct()
                if (months.size < 3) return@mapNotNull null
                val avg = list.map { it.amount }.average()
                val min = list.minOf { it.amount }
                val max = list.maxOf { it.amount }
                if (min <= 0 || max > avg * 1.3 || max < avg * 0.7) return@mapNotNull null
                val last = list.maxBy { it.ts }
                Subscription(
                    id = "sub_" + UUID.randomUUID().toString().take(8),
                    name = name,
                    amount = round2(avg),
                    category = last.category,
                    accountId = last.accountId,
                    cycle = "monthly",
                    dayOfMonth = toLocalDateTime(last.ts).dayOfMonth.coerceIn(1, 28),
                    source = "ai"
                )
            }

    /** 给 AI 的一句话结论（对话里 subscribe 动作用） */
    fun summarize(candidates: List<Subscription>): String =
        if (candidates.isEmpty()) "没有发现连续3个月以上的固定支出"
        else "疑似订阅/固定支出：" + candidates.joinToString("；") {
            "${it.name} 约¥${fmtAmount(it.amount)}/月（每月${it.dayOfMonth}号）"
        }
}

class SubscriptionStore(context: Context) {
    private val prefs = context.getSharedPreferences("shanji_subscriptions", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    val list = mutableStateListOf<Subscription>()

    init {
        prefs.getString("list", null)?.let { raw ->
            if (raw.isNotBlank()) {
                runCatching { json.decodeFromString<List<Subscription>>(raw) }
                    .onSuccess { list.addAll(it) }
            }
        }
    }

    private fun persist() {
        prefs.edit().putString("list", json.encodeToString(list.toList())).apply()
    }

    fun addAll(items: List<Subscription>) {
        if (items.isEmpty()) return
        list.addAll(items); persist()
    }

    fun add(item: Subscription) { list.add(item); persist() }

    fun remove(id: String) {
        list.removeAll { it.id == id }; persist()
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) { list[i] = list[i].copy(enabled = enabled); persist() }
    }

    /** 下次扣款日（今天或之后）；yearly 按 dueMonth+dayOfMonth 算 */
    fun nextDue(sub: Subscription, from: LocalDate = LocalDate.now()): LocalDate? {
        if (!sub.enabled) return null
        val day = sub.dayOfMonth.coerceIn(1, 28)
        return when (sub.cycle) {
            "yearly" -> {
                val month = sub.dueMonth.coerceIn(1, 12)
                val thisYear = LocalDate.of(from.year, month, day)
                if (thisYear.isBefore(from)) LocalDate.of(from.year + 1, month, day) else thisYear
            }
            else -> {
                val thisMonth = from.withDayOfMonth(day)
                if (thisMonth.isBefore(from)) from.plusMonths(1).withDayOfMonth(day) else thisMonth
            }
        }
    }

    /** 未来 N 天内要扣款的订阅（到期提醒用） */
    fun dueWithin(days: Int, from: LocalDate = LocalDate.now()): List<Pair<Subscription, LocalDate>> {
        val end = from.plusDays(days.toLong())
        return list.mapNotNull { s -> nextDue(s, from)?.let { s to it } }
            .filter { it.second <= end }
            .sortedBy { it.second }
    }
}
