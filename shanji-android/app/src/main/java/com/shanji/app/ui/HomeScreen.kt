package com.shanji.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shanji.app.data.AppStore
import com.shanji.app.data.Categories
import com.shanji.app.data.Ledger
import com.shanji.app.data.Tx
import com.shanji.app.data.fmtAmount
import com.shanji.app.ui.theme.BorderSubtle
import com.shanji.app.ui.theme.Bg
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Add
import java.time.YearMonth

@Composable
fun HomeScreen(onEdit: (String) -> Unit, onSearch: () -> Unit = {}, onAdd: (YearMonth) -> Unit = {}) {
    val txs = AppStore.store.txs
    // rememberSaveable：切到统计/AI 再回来（或旋转）不丢浏览月份，原来 remember 每次回首页都重置回本月
    var viewYm by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(YearMonth.now()) }
    val isCurrent = viewYm == YearMonth.now()

    // 统计只在 数据版本/月份 变化时重算：其它重组（滚动、状态切换）不再跑 O(n) 分组
    val dataVersion = AppStore.version.value
    val summary = remember(viewYm, dataVersion) { Ledger.monthSummary(txs, viewYm) }
    val groups = remember(viewYm, dataVersion) { Ledger.dailyGroups(txs, viewYm) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        // 页头
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("闪记", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = onSearch) {
                Icon(Icons.Filled.Search, "搜索", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { viewYm = viewYm.minusMonths(1) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "上个月", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // 月份标签带年份（跨年可区分）；宽度自适应不再锁死 40dp
            Text(
                if (viewYm.year == YearMonth.now().year) "${viewYm.monthValue}月"
                else "${viewYm.year}年${viewYm.monthValue}月",
                fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            IconButton(onClick = { if (!isCurrent) viewYm = viewYm.plusMonths(1) }, enabled = !isCurrent) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "下个月", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // 月度概览卡
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                // 标题跟随浏览月份（切到历史月不再显示「本月支出 ¥0.00」）
                Text(
                    if (isCurrent) "本月支出"
                    else if (viewYm.year == YearMonth.now().year) "${viewYm.monthValue}月支出"
                    else "${viewYm.year}年${viewYm.monthValue}月支出",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "¥ ${fmtAmount(summary.expense)}",
                    fontSize = 30.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Row(Modifier.padding(top = 6.dp)) {
                    Text("收入 ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("¥${fmtAmount(summary.income)}", fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = com.shanji.app.ui.theme.Green)
                    Spacer(Modifier.width(16.dp))
                    Text("结余 ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("¥${fmtAmount(summary.balance)}", fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 预算超支横幅：文案跟随浏览月份（原来写死「本月」，翻历史月时说的不是屏幕上这笔账）
        val totalBudget = com.shanji.app.data.AppStore.budgets.total()
        if (totalBudget > 0 && summary.expense > totalBudget) {
            val monthLabel = if (isCurrent) "本月"
            else if (viewYm.year == YearMonth.now().year) "${viewYm.monthValue}月"
            else "${viewYm.year}年${viewYm.monthValue}月"
            Text(
                "⚠ ${monthLabel}预算已超支 ¥" + fmtAmount(summary.expense - totalBudget) + "，明细见统计页预算卡",
                fontSize = 13.sp, color = com.shanji.app.ui.theme.Red,
                lineHeight = 19.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .background(
                        com.shanji.app.ui.theme.Red.copy(alpha = 0.1f),
                        RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }

        // 账单列表
        if (groups.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(top = 64.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "还没有账单",
                    fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { onAdd(viewYm) },
                    shape = RoundedCornerShape(24.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = com.shanji.app.ui.theme.Primary
                    )
                ) {
                    Text("记一笔", fontSize = 14.sp, color = Color.White)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                groups.forEach { g ->
                    item(key = "label_" + g.date) {
                        Text(g.label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item(key = "card_" + g.date) {
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Column(Modifier.padding(4.dp)) {
                                g.items.forEach { tx ->
                                    TxRow(tx, onClick = { onEdit(tx.id) })
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(72.dp)) } // 给悬浮 FAB 留位，避免遮住最后一笔
            }
        }
    }

    // 悬浮「记一笔」FAB：列表滚动时入口不再消失，记账继承当前浏览月份
    FloatingActionButton(
        onClick = { onAdd(viewYm) },
        shape = RoundedCornerShape(16.dp),
        containerColor = com.shanji.app.ui.theme.Primary,
        contentColor = Color.White,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 20.dp, bottom = 20.dp)
    ) {
        Icon(Icons.Filled.Add, "记一笔")
    }
    }
}

@Composable
internal fun TxRow(tx: Tx, onClick: () -> Unit = {}) {
    val cat = Categories.of(tx.category)
    val isTransfer = tx.type == "transfer"
    val accounts = com.shanji.app.data.AppStore.accounts
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(40.dp).background(Color(if (isTransfer) 0xFFF3F3F6 else cat.bg), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (isTransfer) "转" else cat.label.take(1),
                fontSize = 16.sp,
                color = Color(if (isTransfer) 0xFF6B7280 else cat.color),
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tx.merchant, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (tx.source == "share") {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "分享", fontSize = 10.sp, color = com.shanji.app.ui.theme.Primary,
                        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
            }
            val base = if (isTransfer)
                accounts.nameOf(tx.accountId) + " → " + accounts.nameOf(tx.toAccountId)
            else
                tx.note.removePrefix("备注：").ifEmpty { cat.label } + " · " + accounts.nameOf(tx.accountId)
            Text(
                base,
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        Text(
            when {
                isTransfer -> "→¥" + fmtAmount(tx.amount)
                tx.type == "income" -> "+¥" + fmtAmount(tx.amount)
                else -> "-¥" + fmtAmount(tx.amount)
            },
            fontSize = 15.sp, fontFamily = FontFamily.Monospace,
            color = when {
                isTransfer -> MaterialTheme.colorScheme.onSurfaceVariant
                tx.type == "income" -> com.shanji.app.ui.theme.Green
                else -> MaterialTheme.colorScheme.onSurface
            }
        )
    }
}
