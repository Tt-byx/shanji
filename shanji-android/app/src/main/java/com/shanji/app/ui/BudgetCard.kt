package com.shanji.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shanji.app.data.AppStore
import com.shanji.app.data.BudgetCalc
import com.shanji.app.data.Categories
import com.shanji.app.data.Tx
import com.shanji.app.data.fmtAmount
import com.shanji.app.ui.theme.Red
import java.time.YearMonth

/* 本月预算卡：总预算进度 + 已设置的分类预算（超支红色） */
@Composable
fun BudgetCard(ym: YearMonth = YearMonth.now()) {
    val budgets = AppStore.budgets.list
    val txs = AppStore.store.txs
    if (budgets.isEmpty()) return

    val totalBudget = AppStore.budgets.total()
    val totalSpent = BudgetCalc.spentTotal(txs, ym)

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            if (totalBudget > 0) {
                BudgetRow("本月总预算", totalBudget, totalSpent, txs, ym, null)
            }
            val catBudgets = budgets.filter { it.scope == "category" }
            if (catBudgets.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                catBudgets.forEach { b ->
                    val spent = BudgetCalc.spentOf(txs, ym, b.category)
                    BudgetRow(
                        Categories.of(b.category).label, b.amount, spent, txs, ym, b.category
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun BudgetRow(
    label: String, budget: Double, spent: Double,
    txs: List<Tx>, ym: YearMonth, category: String?
) {
    val p = BudgetCalc.progress(budget, spent)
    val color = if (p.over) Red else MaterialTheme.colorScheme.primary

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text(
            if (p.over) "超支 ¥" + fmtAmount(-p.remain)
            else "剩余 ¥" + fmtAmount(p.remain),
            fontSize = 13.sp,
            color = if (p.over) Red else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(Modifier.height(8.dp))
    LinearProgressIndicator(
        progress = { (p.pct.coerceAtMost(100)) / 100f },
        color = color,
        trackColor = MaterialTheme.colorScheme.outline,
        modifier = Modifier.fillMaxWidth().height(8.dp)
    )
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth()) {
        Text(
            "已花 ¥" + fmtAmount(p.spent) + " / ¥" + fmtAmount(p.budget),
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            "${p.pct}%", fontSize = 12.sp,
            color = if (p.over) Red else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
