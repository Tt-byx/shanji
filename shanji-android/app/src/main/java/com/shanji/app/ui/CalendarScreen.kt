package com.shanji.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shanji.app.data.AppStore
import com.shanji.app.data.Ledger
import com.shanji.app.data.fmtAmount
import com.shanji.app.data.toLocalDateTime
import com.shanji.app.ui.theme.Primary
import java.time.LocalDate
import java.time.YearMonth

/* 日历视图：每日支出底色深浅、今日描边，点某天弹当日明细 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(onBack: () -> Unit) {
    val txs = AppStore.store.txs
    var ym by remember { mutableStateOf(YearMonth.now()) }
    var selectedDay by remember { mutableStateOf<LocalDate?>(null) }
    val today = LocalDate.now()

    // 当月每日支出
    val dayExpense = remember(ym, txs.size) {
        Ledger.monthTx(txs, ym)
            .filter { it.type == "expense" }
            .groupBy { toLocalDateTime(it.ts).toLocalDate() }
            .mapValues { (_, list) -> list.sumOf { it.amount } }
    }
    val maxExpense = dayExpense.values.maxOrNull() ?: 0.0

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = MaterialTheme.colorScheme.onSurface)
            }
            Text("日历", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { ym = ym.minusMonths(1) }) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    "上个月", tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "${ym.year}年${ym.monthValue}月", fontSize = 15.sp,
                // U13：不锁固定宽度（96dp 在字体缩放 ≥1.15x 必截断），padding 自适应
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            IconButton(onClick = { ym = ym.plusMonths(1) }) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    "下个月", tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 星期表头
        Row(Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { d ->
                Text(
                    d, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f).padding(vertical = 6.dp)
                )
            }
        }

        // 日期网格
        val firstDay = ym.atDay(1)
        val lead = (firstDay.dayOfWeek.value + 6) % 7   // 周一开头
        val days = ym.lengthOfMonth()
        val cells = lead + days
        val rows = (cells + 6) / 7

        repeat(rows) { r ->
            Row(Modifier.fillMaxWidth()) {
                for (c in 0 until 7) {
                    val idx = r * 7 + c
                    val dayNum = idx - lead + 1
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).padding(2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (dayNum in 1..days) {
                            val date = ym.atDay(dayNum)
                            val spent = dayExpense[date] ?: 0.0
                            val alpha = if (spent > 0 && maxExpense > 0)
                                (0.12 + 0.5 * (spent / maxExpense)).toFloat().coerceAtMost(0.65f)
                            else 0f
                            Column(
                                Modifier.fillMaxSize()
                                    .background(
                                        if (alpha > 0) Primary.copy(alpha = alpha)
                                        else Color.Transparent,
                                        CircleShape
                                    )
                                    .then(
                                        if (date == today) Modifier.border(1.5.dp, Primary, CircleShape)
                                        else Modifier
                                    )
                                    .clickable { selectedDay = date },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    "$dayNum", fontSize = 14.sp,
                                    fontWeight = if (date == today) FontWeight.Bold else FontWeight.Normal,
                                    color = if (date == today) Primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                                if (spent > 0) {
                                    Text(
                                        fmtAmount(spent).let { if (it.length > 5) it.take(5) else it },
                                        fontSize = 8.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }

    // 当日明细弹层
    selectedDay?.let { date ->
        val items = txs.filter { toLocalDateTime(it.ts).toLocalDate() == date }
            .sortedByDescending { it.ts }
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { selectedDay = null }, sheetState = sheetState) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                Text(
                    "${date.monthValue}月${date.dayOfMonth}日 · ${items.size} 笔",
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(12.dp))
                if (items.isEmpty()) {
                    Text("当天没有账单", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Column(Modifier.padding(4.dp)) {
                            items.forEach { tx -> TxRow(tx) }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
