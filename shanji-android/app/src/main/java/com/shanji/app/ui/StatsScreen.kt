package com.shanji.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shanji.app.data.AppStore
import com.shanji.app.data.Categories
import com.shanji.app.data.Ledger
import com.shanji.app.data.fmtAmount
import com.shanji.app.ui.theme.Amber
import com.shanji.app.ui.theme.Bg
import com.shanji.app.ui.theme.BorderSubtle
import com.shanji.app.ui.theme.Green
import com.shanji.app.ui.theme.Primary
import com.shanji.app.ui.theme.PrimaryDeep
import com.shanji.app.ui.theme.PrimarySoft
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import kotlinx.coroutines.launch
import java.time.YearMonth

@Composable
fun StatsScreen(onCalendar: () -> Unit = {}) {
    val txs = AppStore.store.txs
    var range by remember { mutableStateOf("month") }
    val ym = YearMonth.now()

    val isWeek = range == "week"
    // 五组统计只在 数据版本/周月切换 时重算，避免无关重组跑 O(n)
    val dataVersion = AppStore.version.value
    val summary = remember(range, dataVersion) {
        if (isWeek) Ledger.weekSummary(txs) else Ledger.monthSummary(txs, ym)
    }
    val (cur, prev, diff) = remember(range, dataVersion) {
        if (isWeek) Ledger.weekOverWeek(txs) else Ledger.monthOverMonth(txs, ym)
    }
    val trend = remember(range, dataVersion) {
        if (isWeek) Ledger.weekDaily(txs) else Ledger.monthlyTrend(txs, ym, 5)
    }
    val cats = remember(range, dataVersion) {
        if (isWeek) {
            val (s, e) = Ledger.weekRange()
            Ledger.categoryBreakdown(txs.filter { t ->
                val d = com.shanji.app.data.toLocalDateTime(t.ts); d >= s && d < e && t.type == "expense"
            })
        } else {
            Ledger.categoryBreakdown(Ledger.monthTx(txs, ym))
        }
    }
    val insights = remember(range, dataVersion) {
        if (isWeek) emptyList() else Ledger.insights(txs, ym)
    }

    // AI 洞察：规则命中改写成人话；失败降级回规则文案（本地已有的）
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var aiInsight by remember { mutableStateOf<String?>(null) }
    val persona = remember {
        ctx.getSharedPreferences("shanji_prefs", android.content.Context.MODE_PRIVATE)
            .getString("ai_persona", null)
    }
    androidx.compose.runtime.LaunchedEffect(ym, insights.size) {
        if (insights.isEmpty() || isWeek) return@LaunchedEffect
        aiInsight = runCatching {
            val stats = "本月支出¥${fmtAmount(summary.expense)}、收入¥${fmtAmount(summary.income)}；" +
                cats.take(5).joinToString("、") { "${it.cat.label}¥${fmtAmount(it.amount)}" }
            com.shanji.app.data.AiClient.insight(ctx, "insight", insights, stats, persona)
        }.getOrNull()
    }

    // 月度报告：有缓存直接看，没有就现生成
    var reportText by remember { mutableStateOf<String?>(null) }
    var reportLoading by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val loadReport: () -> Unit = {
        val ymKey = ym.toString()
        val cached = com.shanji.app.data.MonthlyReportStore.get(ctx, ymKey)
        if (cached != null) {
            reportText = cached
        } else if (!reportLoading) {
            reportLoading = true
            scope.launch {
                runCatching {
                    val prev = ym.minusMonths(1)
                    val ps = Ledger.monthSummary(txs, prev)
                    val pc = Ledger.categoryBreakdown(Ledger.monthTx(txs, prev)).take(5)
                    val stats = "上月(${prev})支出¥${fmtAmount(ps.expense)}、收入¥${fmtAmount(ps.income)}、结余¥${fmtAmount(ps.balance)}；" +
                        pc.joinToString("、") { "${it.cat.label}¥${fmtAmount(it.amount)}" }
                    val hits = Ledger.insights(txs, prev)
                    com.shanji.app.data.AiClient.insight(ctx, "monthly_report", hits, stats, persona)
                }.onSuccess {
                    reportText = it
                    com.shanji.app.data.MonthlyReportStore.put(ctx, ymKey, it)
                }.onFailure {
                    reportText = it.message ?: "报告生成失败，稍后再试"
                }
                reportLoading = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        // 页头
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("统计", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onCalendar) {
                Icon(Icons.Filled.CalendarMonth, "日历", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SingleChoiceSegmentedButtonRow {
                listOf("week" to "周", "month" to "月").forEachIndexed { i, (key, label) ->
                    SegmentedButton(
                        selected = range == key,
                        onClick = { range = key },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = 2),
                        label = { Text(label, fontSize = 13.sp) }
                    )
                }
            }
        }

        // 本月预算卡（仅月视图）
        if (!isWeek) BudgetCard(ym)

        // 总览卡
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    if (isWeek) "本周总支出" else "${ym.monthValue}月总支出",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "¥ ${fmtAmount(summary.expense)}", fontSize = 30.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                )
                if (prev > 0 || cur > 0) {
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (diff <= 0) "较上${if (isWeek) "周" else "月"}少花 ¥${fmtAmount(-diff)}"
                            else "较上${if (isWeek) "周" else "月"}多花 ¥${fmtAmount(diff)}",
                            fontSize = 12.sp,
                            color = if (diff <= 0) Green else Amber
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 趋势图卡
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    if (isWeek) "本周各天支出" else "近 5 个月趋势",
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(14.dp))
                BarChart(trend)
            }
        }

        Spacer(Modifier.height(14.dp))

        // 分类占比卡
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("分类占比", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(14.dp))
                if (cats.isEmpty()) {
                    Text("还没有支出记录", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
                } else {
                    val max = cats.first().amount
                    cats.forEach { c ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).background(Color(c.cat.color), CircleShape))
                            Spacer(Modifier.width(8.dp))
                            Text(c.cat.label, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text("¥${fmtAmount(c.amount)}", fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                            Spacer(Modifier.width(8.dp))
                            Text("${c.pct}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(36.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                        }
                        Box(
                            Modifier.fillMaxWidth().height(6.dp).background(MaterialTheme.colorScheme.outline, RoundedCornerShape(3.dp))
                        ) {
                            Box(
                                Modifier.fillMaxWidth((c.amount / max).toFloat())
                                    .height(6.dp).background(Color(c.cat.color), RoundedCornerShape(3.dp))
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 洞察卡：AI 文案优先，失败/未返回时显示规则文案
        val insightLines = when {
            !aiInsight.isNullOrBlank() -> aiInsight!!.lines().filter { it.isNotBlank() }
            else -> insights
        }
        if (!isWeek && insightLines.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)).padding(14.dp)
            ) {
                insightLines.forEach {
                    Text("· $it", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, lineHeight = 20.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        // 月度报告
        if (!isWeek) {
            var showReport by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("上月总结", fontSize = 13.sp, color = Primary)
                Spacer(Modifier.size(8.dp))
                Text(
                    if (reportLoading) "生成中…" else "查看",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { if (!reportLoading) { showReport = true; loadReport() } }
                )
            }
            Spacer(Modifier.height(14.dp))
            if (showReport) {
                AlertDialog(
                    onDismissRequest = { showReport = false },
                    title = { Text("月度报告") },
                    text = {
                        Column(
                            Modifier.heightIn(max = 400.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                reportText ?: "还没生成",
                                fontSize = 14.sp, lineHeight = 22.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showReport = false }) { Text("好的") }
                    }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun BarChart(points: List<com.shanji.app.data.TrendPoint>) {
    val max = (points.maxOfOrNull { it.expense } ?: 1.0).coerceAtLeast(1.0)
    Column {
        Row(
            Modifier.fillMaxWidth().height(84.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom
        ) {
            points.forEach { p ->
                val h = (4 + (p.expense / max * 70)).dp
                Box(
                    Modifier.width(16.dp).height(h)
                        .background(if (p.isCurrent) Primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(5.dp))
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            points.forEach { p ->
                Text(
                    p.label, fontSize = 10.sp,
                    color = if (p.isCurrent) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.width(40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}
