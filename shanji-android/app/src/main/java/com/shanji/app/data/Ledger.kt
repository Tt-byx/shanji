package com.shanji.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

/* 业务层：统计聚合（纯函数），对应 Web 版 ledger.js */

data class Summary(val expense: Double, val income: Double, val balance: Double)

data class DailyGroup(
    val date: LocalDate, val label: String, val items: List<Tx>, val dayExpense: Double
)

data class TrendPoint(val label: String, val expense: Double, val isCurrent: Boolean)

data class CatShare(val cat: Cat, val amount: Double, val pct: Int)

fun toLocalDateTime(ts: Long): LocalDateTime =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDateTime()

object Ledger {

    fun summary(txs: List<Tx>): Summary {
        var e = 0.0; var i = 0.0
        for (t in txs) when (t.type) {
            "expense" -> e += t.amount
            "income" -> i += t.amount
            // transfer 不计入收支（账户间划转）
        }
        e = round2(e); i = round2(i)
        return Summary(e, i, round2(i - e))
    }

    /** 账户余额 = 初始余额 + 收入进账 - 支出付款 ± 转账 */
    fun accountBalance(account: Account, txs: List<Tx>): Double {
        var b = account.initialBalance
        for (t in txs) when {
            t.type == "expense" && t.accountId == account.id -> b -= t.amount
            t.type == "income" && t.accountId == account.id -> b += t.amount
            t.type == "transfer" && t.accountId == account.id -> b -= t.amount
            t.type == "transfer" && t.toAccountId == account.id -> b += t.amount
        }
        return round2(b)
    }

    /** 某账户本月收支（不含转账） */
    fun accountMonthFlow(account: Account, txs: List<Tx>, ym: YearMonth): Pair<Double, Double> {
        val list = monthTx(txs, ym).filter {
            it.type != "transfer" && it.accountId == account.id
        }
        val income = list.filter { it.type == "income" }.sumOf { it.amount }
        val expense = list.filter { it.type == "expense" }.sumOf { it.amount }
        return round2(expense) to round2(income)
    }

    fun monthTx(txs: List<Tx>, ym: YearMonth): List<Tx> =
        txs.filter { toLocalDateTime(it.ts).let { d -> d.year == ym.year && d.monthValue == ym.monthValue } }

    fun monthSummary(txs: List<Tx>, ym: YearMonth): Summary = summary(monthTx(txs, ym))

    fun monthOverMonth(txs: List<Tx>, ym: YearMonth): Triple<Double, Double, Double> {
        val cur = monthSummary(txs, ym).expense
        val prev = monthSummary(txs, ym.minusMonths(1)).expense
        return Triple(cur, prev, round2(cur - prev))
    }

    fun dailyGroups(txs: List<Tx>, ym: YearMonth): List<DailyGroup> {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        return monthTx(txs, ym)
            .groupBy { toLocalDateTime(it.ts).toLocalDate() }
            .toSortedMap(compareByDescending { it })
            .map { (date, items) ->
                DailyGroup(
                    date = date,
                    label = when (date) {
                        today -> "今天"
                        yesterday -> "昨天"
                        else -> "${date.monthValue}月${date.dayOfMonth}日"
                    },
                    items = items.sortedByDescending { it.ts },
                    dayExpense = round2(items.filter { it.type == "expense" }.sumOf { it.amount })
                )
            }
    }

    fun monthlyTrend(txs: List<Tx>, ym: YearMonth, n: Int = 5): List<TrendPoint> =
        (n - 1 downTo 0).map { i ->
            val m = ym.minusMonths(i.toLong())
            val cur = i == 0
            TrendPoint(
                label = if (m.year == ym.year) "${m.monthValue}月" else "${m.year}年${m.monthValue}月",
                expense = monthSummary(txs, m).expense,
                isCurrent = cur
            )
        }

    fun categoryBreakdown(txs: List<Tx>): List<CatShare> {
        val expenses = txs.filter { it.type == "expense" }
        val sum = expenses.sumOf { it.amount }
        if (sum <= 0) return emptyList()
        return expenses.groupBy { it.category }
            .map { (key, items) ->
                val amount = round2(items.sumOf { it.amount })
                CatShare(Categories.of(key), amount, Math.round(amount / sum * 100).toInt())
            }
            .sortedByDescending { it.amount }
    }

    fun weekRange(base: LocalDate = LocalDate.now()): Pair<LocalDateTime, LocalDateTime> {
        val monday = base.minusDays(((base.dayOfWeek.value + 6) % 7).toLong())
        return Pair(monday.atStartOfDay(), monday.plusDays(7).atStartOfDay())
    }

    fun weekSummary(txs: List<Tx>): Summary {
        val (s, e) = weekRange()
        return summary(txs.filter { val t = toLocalDateTime(it.ts); t >= s && t < e })
    }

    fun weekOverWeek(txs: List<Tx>): Triple<Double, Double, Double> {
        val (s, e) = weekRange()
        val cur = summary(txs.filter { val t = toLocalDateTime(it.ts); t >= s && t < e }).expense
        val (ps, pe) = weekRange(LocalDate.now().minusDays(7))
        val prev = summary(txs.filter { val t = toLocalDateTime(it.ts); t >= ps && t < pe }).expense
        return Triple(cur, prev, round2(cur - prev))
    }

    /**
     * 上周（周一~周日） vs 前一周——周报专用。
     * 周一早上推送时「本周」才过了几小时，拿它比完整一周会得出误导结论（实测 9 小时 vs 7 天）。
     */
    fun lastWeekOverWeek(txs: List<Tx>): Triple<Double, Double, Double> {
        val (s, e) = weekRange(LocalDate.now().minusDays(7))   // 上周一 ~ 本周一
        val cur = summary(txs.filter { val t = toLocalDateTime(it.ts); t >= s && t < e }).expense
        val (ps, pe) = weekRange(LocalDate.now().minusDays(14)) // 前一周
        val prev = summary(txs.filter { val t = toLocalDateTime(it.ts); t >= ps && t < pe }).expense
        return Triple(cur, prev, round2(cur - prev))
    }

    /** 某时间段内的支出分类占比（周报用：上周哪类花得最多） */
    fun categoryBetween(txs: List<Tx>, s: LocalDateTime, e: LocalDateTime): List<CatShare> =
        categoryBreakdown(txs.filter { val t = toLocalDateTime(it.ts); t >= s && t < e })

    fun weekDaily(txs: List<Tx>): List<TrendPoint> {
        val monday = LocalDate.now().minusDays(((LocalDate.now().dayOfWeek.value + 6) % 7).toLong())
        val names = listOf("一", "二", "三", "四", "五", "六", "日")
        return (0 until 7).map { i ->
            val d = monday.plusDays(i.toLong())
            val s = d.atStartOfDay(); val e = d.plusDays(1).atStartOfDay()
            TrendPoint(
                label = names[i],
                expense = round2(txs.filter { it.type == "expense" }
                    .filter { val t = toLocalDateTime(it.ts); t >= s && t < e }
                    .sumOf { it.amount }),
                isCurrent = d == LocalDate.now()
            )
        }
    }

    /** 消费洞察（规则引擎），最多 3 条 */
    fun insights(txs: List<Tx>, ym: YearMonth): List<String> {
        val out = mutableListOf<String>()
        val month = monthTx(txs, ym).filter { it.type == "expense" }
        if (month.isEmpty()) return out
        val total = month.sumOf { it.amount }
        if (total <= 0) return out
        val fmt = { n: Double -> String.format("%,.2f", n) }

        categoryBreakdown(month).firstOrNull()?.let { top ->
            out.add("本月「${top.cat.label}」花了 ¥${fmt(top.amount)}，占总支出 ${Math.round(top.amount / total * 100)}%")
        }
        val coffee = month.filter { Regex("咖啡|瑞幸|星巴克|奶茶|蜜雪").containsMatchIn(it.merchant + it.note) }
        if (coffee.size >= 2) {
            out.add("本月咖啡奶茶喝了 ${coffee.size} 次、共 ¥${fmt(coffee.sumOf { it.amount })}，试试隔天自己做？")
        }
        val waimai = month.filter { Regex("外卖").containsMatchIn(it.merchant + it.note) }
        if (waimai.size >= 3) {
            out.add("外卖共 ${waimai.size} 单、占比 ${Math.round(waimai.sumOf { it.amount } / total * 100)}%，本周挑两天自己带饭吧")
        }
        val (cur, prev, diff) = monthOverMonth(txs, ym)
        if (prev > 0 && Math.abs(diff) >= 50) {
            out.add(if (diff < 0) "比上月少花 ¥${fmt(-diff)}，继续保持" else "比上月多花 ¥${fmt(diff)}，看看钱花哪去了")
        }
        return out.take(3)
    }
}

fun fmtAmount(n: Double): String = String.format("%,.2f", n)
