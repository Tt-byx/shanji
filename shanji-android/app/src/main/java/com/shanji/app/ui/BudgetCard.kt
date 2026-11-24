package com.shanji.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.shanji.app.ui.theme.Primary
import com.shanji.app.ui.theme.Red
import java.time.YearMonth

/* 本月预算卡：总预算进度 + 已设置的分类预算（超支红色） */
@Composable
fun BudgetCard(ym: YearMonth = YearMonth.now()) {
    val budgets = AppStore.budgets.list
    val txs = AppStore.store.txs
    var showSet by remember { mutableStateOf(false) }

    // 没设预算时不再整块消失：新用户得知道「预算」这个功能存在
    if (budgets.isEmpty()) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("本月还没设预算", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "设个总预算，超支前会提前提醒你",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "设置预算 →", fontSize = 13.sp, color = Primary,
                    modifier = Modifier
                        .clickable { showSet = true }
                        .padding(start = 12.dp)
                )
            }
        }
        if (showSet) BudgetDialog(onDismiss = { showSet = false })
        return
    }

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

/** 预算设置弹窗（我的页 / 统计页空态共用一份实现） */
@Composable
fun BudgetDialog(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val budgets = AppStore.budgets
    var totalText by remember {
        mutableStateOf(budgets.total().takeIf { it > 0 }?.let { fmtAmount(it) } ?: "")
    }
    val catTexts = remember {
        androidx.compose.runtime.mutableStateMapOf<String, String>().apply {
            Categories.all.forEach { c ->
                val v = budgets.ofCategory(c.key)
                if (v > 0) put(c.key, fmtAmount(v))
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("预算设置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = totalText,
                    onValueChange = { totalText = it.filter { c -> c.isDigit() || c == '.' }.take(10) },
                    label = { Text("本月总预算（元）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Categories.all.forEach { c ->
                    OutlinedTextField(
                        value = catTexts[c.key] ?: "",
                        onValueChange = { catTexts[c.key] = it.filter { ch -> ch.isDigit() || ch == '.' }.take(10) },
                        label = { Text("${c.label}预算（元）") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                budgets.set("total", "", totalText.toDoubleOrNull() ?: 0.0)
                Categories.all.forEach { c ->
                    budgets.set("category", c.key, (catTexts[c.key] ?: "").toDoubleOrNull() ?: 0.0)
                }
                android.widget.Toast.makeText(context, "预算已保存", android.widget.Toast.LENGTH_SHORT).show()
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
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
