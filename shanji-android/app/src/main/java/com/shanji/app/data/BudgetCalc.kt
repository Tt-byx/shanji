package com.shanji.app.data

import java.time.YearMonth

/** 预算计算（纯函数，与 Ledger 并列） */
data class BudgetProgress(
    val budget: Double,
    val spent: Double,
    val pct: Int,          // 0~100+（超支时 >100）
    val remain: Double,    // 剩余额度（超支为负）
    val over: Boolean
)

object BudgetCalc {

    /** 本月总支出（不含转账） */
    fun spentTotal(txs: List<Tx>, ym: YearMonth): Double =
        Ledger.monthSummary(txs, ym).expense

    /** 本月某分类支出 */
    fun spentOf(txs: List<Tx>, ym: YearMonth, category: String): Double =
        Ledger.monthTx(txs, ym)
            .filter { it.type == "expense" && it.category == category }
            .sumOf { it.amount }
            .let { round2(it) }

    fun progress(budget: Double, spent: Double): BudgetProgress {
        val over = budget > 0 && spent > budget
        val pct = if (budget <= 0) 0 else Math.round(spent / budget * 100).toInt()
        return BudgetProgress(
            budget = round2(budget), spent = round2(spent),
            pct = pct, remain = round2(budget - spent), over = over
        )
    }

    /** 记账后是否“刚刚越过”预算（用于超支提醒） */
    fun justCrossed(oldSpent: Double, newSpent: Double, budget: Double): Boolean =
        budget > 0 && oldSpent <= budget && newSpent > budget
}
