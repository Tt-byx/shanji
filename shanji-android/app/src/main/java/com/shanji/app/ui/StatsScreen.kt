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
import androidx.compose.ui.draw.alpha
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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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

    // AI 洞察：带缓存（同月同数据版本不重复调用——原来每次进页都重调一次）；
    // 骨架屏 → 淡入，不再规则文案整块跳变成 AI 文案
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var aiInsight by remember { mutableStateOf<String?>(null) }
    var aiLoading by remember { mutableStateOf(true) } // 首帧即骨架，避免规则文案闪现
    var insightTick by remember { mutableStateOf(0) }  // 「重新生成」自增打破缓存
    // F22：洞察数字可点开溯源明细；#CARD# 操作按钮与 AI 对话同一套协议
    var traceQuery by remember { mutableStateOf<Map<String, String>?>(null) }
    val insightBody = remember(aiInsight) {
        aiInsight?.let { it.substringBefore("\n#CARD#").substringBefore("#CARD#").trim() } ?: ""
    }
    val insightCard = remember(aiInsight) {
        aiInsight?.let { text ->
            val idx = text.indexOf("#CARD#")
            if (idx < 0) null else runCatching {
                kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    .decodeFromString<com.shanji.app.data.net.AiCard>(text.substring(idx + 6).trim())
            }.getOrNull()
        }
    }
    /** 洞察卡按钮：与 AI 对话卡片同一分发逻辑（queryTx 弹明细 / budget_set / addTx 本地执行） */
    fun insightBtn(btn: com.shanji.app.data.net.AiCardBtn) {
        when (btn.fn) {
            "queryTx" -> traceQuery = btn.params.mapValues { it.value.toString().trim('"') }
            "budget_set" -> {
                val amount = btn.params["amount"]?.toString()?.trim('"')?.toDoubleOrNull() ?: 0.0
                if (amount > 0) {
                    val scopeName = btn.params["scope"]?.toString()?.trim('"') ?: "total"
                    val cat = btn.params["category"]?.toString()?.trim('"') ?: ""
                    AppStore.budgets.set(if (scopeName == "category") "category" else "total", cat, amount)
                    val label = if (scopeName == "category") "${Categories.of(cat).label}预算" else "总预算"
                    android.widget.Toast.makeText(
                        ctx, "已设置${label} ¥${fmtAmount(amount)}", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
            "addTx" -> {
                val amount = btn.params["amount"]?.toString()?.trim('"')?.toDoubleOrNull() ?: 0.0
                if (amount > 0) {
                    com.shanji.app.data.newTx(
                        btn.params["type"]?.toString()?.trim('"') ?: "expense",
                        amount,
                        btn.params["merchant"]?.toString()?.trim('"') ?: "",
                        btn.params["category"]?.toString()?.trim('"') ?: "other"
                    ).let { AppStore.store.add(it) }
                    android.widget.Toast.makeText(ctx, "已记一笔 ¥${fmtAmount(amount)}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    val persona = remember {
        ctx.getSharedPreferences("shanji_prefs", android.content.Context.MODE_PRIVATE)
            .getString("ai_persona", null)
    }
    androidx.compose.runtime.LaunchedEffect(ym, dataVersion, insightTick) {
        if (insights.isEmpty() || isWeek) { aiLoading = false; return@LaunchedEffect }
        // 缓存 key 必须是数据指纹：进程内 version 重启归零会撞上别的时期的旧缓存（同屏两个矛盾数字），
        // 而且每写一次库 version++ 会让缓存永远不命中、每次进页都白调 AI
        val fp = txs.fold(0) { h, t ->
            31 * h + t.id.hashCode() + 37 * t.amount.hashCode() +
                41 * t.category.hashCode() + 43 * t.ts.hashCode()
        }
        val cacheKey = "insight_${ym}_$fp"
        if (insightTick == 0) {
            com.shanji.app.data.MonthlyReportStore.get(ctx, cacheKey)?.let {
                aiInsight = it; aiLoading = false; return@LaunchedEffect
            }
        }
        aiLoading = true
        val r = runCatching {
            // 分类给 key(label) 两态 + 标明月份：洞察卡的 shanji-trace 链接要靠它拼 category/month（F22）
            val stats = "月份=$ym；本月支出¥${fmtAmount(summary.expense)}、收入¥${fmtAmount(summary.income)}；" +
                "分类：" + cats.take(5).joinToString("、") { "${it.cat.key}(${it.cat.label})¥${fmtAmount(it.amount)}" }
            com.shanji.app.data.AiClient.insight(ctx, "insight", insights, stats, persona)
        }
        r.getOrNull()?.let {
            aiInsight = it
            // 同月旧指纹条目一起清掉，避免按指纹无限堆积
            com.shanji.app.data.MonthlyReportStore.put(ctx, cacheKey, it, clearPrefix = "insight_${ym}_")
        }
        aiLoading = false
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

        // 洞察卡：结论应在数据上面（原在分类占比下方）；标题「✨ 本月洞察」+ 重新生成；
        // 骨架屏加载 → AI 文案淡入，失败降级回规则文案
        if (!isWeek && (insights.isNotEmpty() || aiLoading)) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("✨ 本月洞察", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "重新生成", fontSize = 12.sp, color = Primary,
                            modifier = Modifier
                                .clickable { insightTick++ }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    when {
                        aiLoading -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // 骨架屏：三条占位条，AI 返回后淡入替换
                            repeat(3) { i ->
                                Box(
                                    Modifier
                                        .fillMaxWidth(if (i == 2) 0.6f else 0.9f)
                                        .height(13.dp)
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant,
                                            RoundedCornerShape(6.dp)
                                        )
                                )
                            }
                        }
                        !aiInsight.isNullOrBlank() -> {
                            val fade = remember(aiInsight) { androidx.compose.animation.core.Animatable(0f) }
                            androidx.compose.runtime.LaunchedEffect(aiInsight) { fade.animateTo(1f, tween(400)) }
                            Column(Modifier.alpha(fade.value)) {
                                // 正文走 Markdown：数字后的 shanji-trace 链接可点开明细（F22）
                                MarkdownText(
                                    insightBody,
                                    modifier = Modifier.fillMaxWidth(),
                                    onLink = { url ->
                                        val params = url.substringAfter("shanji-trace:", "")
                                            .split("&").mapNotNull { kv ->
                                                val i = kv.indexOf('=')
                                                if (i > 0) kv.substring(0, i) to kv.substring(i + 1) else null
                                            }.toMap()
                                        if (params.isNotEmpty()) traceQuery = params
                                    }
                                )
                                // 操作卡片：标题/补充行/按钮（queryTx 查明细 · budget_set 设预算 · addTx 记一笔）
                                insightCard?.let { card ->
                                    if (card.title.isNotBlank()) {
                                        Spacer(Modifier.height(8.dp))
                                        Text(card.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp)
                                    }
                                    card.lines.forEach {
                                        Text("· $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)
                                    }
                                    if (card.buttons.isNotEmpty()) {
                                        Spacer(Modifier.height(8.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            card.buttons.forEach { b ->
                                                if (b.label.isBlank()) return@forEach
                                                Text(
                                                    b.label, fontSize = 12.sp, color = Primary,
                                                    modifier = Modifier
                                                        .background(PrimarySoft, RoundedCornerShape(999.dp))
                                                        .clickable { insightBtn(b) }
                                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        else -> insights.forEach {
                            Text("· $it", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, lineHeight = 20.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

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

    // 洞察数字溯源明细（F22 共享弹层；统计页只读，不带编辑入口）
    TraceSheet(query = traceQuery, onDismiss = { traceQuery = null })
}

@Composable
private fun BarChart(points: List<com.shanji.app.data.TrendPoint>) {
    val max = (points.maxOfOrNull { it.expense } ?: 1.0).coerceAtLeast(1.0)
    // 数值/柱子/标签同列（weight 均分）：原来数值行 width(44) 与柱行 width(16) 各自 SpaceEvenly，
    // 首末列中心错位；周视图 7×44dp 也会超出卡片宽度被裁切
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        points.forEach { p ->
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    if (p.expense <= 0) "—"
                    else if (p.expense >= 1000) "¥%,.0f".format(p.expense)
                    else "¥" + fmtAmount(p.expense),
                    fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                    color = if (p.isCurrent) Primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier.fillMaxWidth().height(84.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    // 空月不画小灰条（易被误读成「花了很少」），只留底部 —
                    if (p.expense > 0) {
                        val h = (4 + (p.expense / max * 70)).dp
                        Box(
                            Modifier.width(16.dp).height(h)
                                .background(if (p.isCurrent) Primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(5.dp))
                        )
                    } else {
                        Box(Modifier.width(16.dp).height(4.dp))
                    }
                }
                Text(
                    p.label, fontSize = 10.sp,
                    color = if (p.isCurrent) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}
