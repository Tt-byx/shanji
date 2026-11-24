package com.shanji.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shanji.app.data.AppStore
import com.shanji.app.data.Categories
import com.shanji.app.data.Recurring
import com.shanji.app.data.ScanResult
import com.shanji.app.data.SubscriptionDetector
import com.shanji.app.data.fmtAmount
import com.shanji.app.ui.theme.Green
import com.shanji.app.ui.theme.Primary
import com.shanji.app.ui.theme.Red
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 固定支出（F23 合并页）：周期记账与订阅一张表管理，卡片 tag 区分
 * 「自动记账」（到日子自动生成账单）与「到期提醒」（只提醒不扣款）。
 * AI 识别（F7）：账单摘要交服务端 AI 语义判断，失败降级本地规则。
 */
@Composable
fun RecurringScreen(onBack: () -> Unit) {
    val store = AppStore.recurring
    val rules = store.list
    var editing by remember { mutableStateOf<Recurring?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var detecting by remember { mutableStateOf(false) }
    var scan by remember { mutableStateOf<ScanResult?>(null) }
    var showDetect by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = MaterialTheme.colorScheme.onSurface)
            }
            Text("固定支出", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "每月 / 每年固定要花的钱，一张表管：\n" +
                "· 自动记账——到日子自动生成账单（房租 / 通勤）\n" +
                "· 到期提醒——只在扣款前提醒你（会员 / 话费）",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp
        )
        Spacer(Modifier.height(14.dp))

        if (rules.isEmpty()) {
            Text("还没有固定支出", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Text(
                "房租 / 话费 / 会员 / 通勤这类固定支出，加进来后：自动记账的每月到日子生成账单，" +
                    "订阅类到期前提醒你。\n示例：房租 ¥1500 每月1号 · 视频会员 ¥25 每月15号",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, lineHeight = 18.sp
            )
            Spacer(Modifier.height(6.dp))
        }

        rules.forEach { r ->
            FixedCard(
                r = r,
                onEdit = { editing = r },
                onToggle = { store.setEnabled(r, it) }
            )
        }

        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(
                Modifier.weight(1f).clickable { showAdd = true }.padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("+ 新增", fontSize = 15.sp, color = Primary, fontWeight = FontWeight.Medium)
            }
            Row(
                Modifier.weight(1f).clickable(enabled = !detecting) {
                    if (detecting) return@clickable
                    detecting = true
                    val existing = store.list.map { it.merchant }
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            SubscriptionDetector.scan(
                                ctx,
                                AppStore.store.txs,
                                existing,
                                AppStore.accounts.defaultId()
                            )
                        }
                        scan = result
                        detecting = false
                        showDetect = true
                    }
                }.padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (detecting) "AI 识别中…" else "AI 识别订阅",
                    fontSize = 15.sp, color = Primary, fontWeight = FontWeight.Medium
                )
            }
        }
        Spacer(Modifier.height(60.dp))
    }

    // AI 识别结果确认（来源标注：AI / 本地规则；AI 失败会附原因）
    if (showDetect) {
        val result = scan
        val found = result?.items ?: emptyList()
        AlertDialog(
            onDismissRequest = { showDetect = false },
            title = {
                Text(
                    when {
                        found.isEmpty() && result?.usedAi == true -> "AI 没认出新的订阅"
                        found.isEmpty() -> "没认出新的订阅"
                        else -> "认出 ${found.size} 个订阅"
                    }
                )
            },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (found.isEmpty()) {
                        Text(
                            "没有找到「连续 3 个月以上、金额稳定」的固定支出。" +
                                "可能是数据还不够，或者这些支出本来就不固定。",
                            fontSize = 13.sp
                        )
                    } else {
                        Text(
                            "识别方式：" + (if (result?.usedAi == true) "AI 分析账单" else "本地规则") +
                                "，确认后加进列表，到期前会提醒你：",
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        found.forEach {
                            Text(
                                "· ${it.merchant}  ¥${fmtAmount(it.amount)}/月  每月${it.dayOfMonth}号",
                                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (result != null && !result.usedAi && !result.error.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(result.error, fontSize = 12.sp, color = Red)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = found.isNotEmpty(),
                    onClick = {
                        store.addAll(found)
                        showDetect = false
                    }
                ) { Text("全部添加") }
            },
            dismissButton = { TextButton(onClick = { showDetect = false }) { Text("取消") } }
        )
    }

    // 新增：先选类型（自动记账 / 到期提醒）
    if (showAdd) RecurringDialog(
        title = "新增固定支出",
        showKind = true,
        onConfirm = { type, amount, merchant, cat, day, cycle, dueMonth, autoRecord ->
            store.add(
                type, amount, merchant, cat, AppStore.accounts.defaultId(), day,
                cycle = cycle, dueMonth = dueMonth, autoRecord = autoRecord
            )
            showAdd = false
        },
        onDismiss = { showAdd = false }
    )

    // 编辑（点卡片）：类型与来源沿用原记录，可改可删
    editing?.let { r ->
        RecurringDialog(
            title = if (r.autoRecord) "编辑周期规则" else "编辑订阅",
            initial = r,
            showDelete = true,
            showCycle = !r.autoRecord,
            initialCycle = r.cycle,
            initialDueMonth = r.dueMonth,
            onConfirm = { _, amount, merchant, cat, day, cycle, dueMonth, _ ->
                store.update(
                    r.copy(
                        amount = amount, merchant = merchant, category = cat, dayOfMonth = day,
                        cycle = if (r.autoRecord) r.cycle else cycle,
                        dueMonth = if (r.autoRecord) r.dueMonth else if (cycle == "yearly") dueMonth else 0
                    )
                )
                editing = null
            },
            onDelete = { store.remove(r.id); editing = null },
            onDismiss = { editing = null }
        )
    }
}

/** 固定支出卡片：tag 标明 自动记账/到期提醒，开关 = 启用（生成 / 提醒） */
@Composable
private fun FixedCard(r: Recurring, onEdit: () -> Unit, onToggle: (Boolean) -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Column(Modifier.weight(1f).clickable(onClick = onEdit)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.merchant, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(8.dp))
                    val tagAuto = r.autoRecord
                    Text(
                        if (tagAuto) "自动记账" else "到期提醒",
                        fontSize = 10.sp,
                        color = if (tagAuto) Green else Primary,
                        modifier = Modifier
                            .background(
                                (if (tagAuto) Green else Primary).copy(alpha = 0.12f),
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    (if (r.cycle == "yearly") "每年${r.dueMonth}月${r.dayOfMonth}日" else "每月 ${r.dayOfMonth} 日") +
                        " · " + (if (r.type == "income") "+" else "-") + "¥" + fmtAmount(r.amount) +
                        " · ${Categories.of(r.category).label}" +
                        when (r.source) {
                            "ai" -> " · AI 识别"
                            "local" -> " · 规则识别"
                            else -> " · 手动"
                        } +
                        (if (r.autoRecord && r.lastRunDate.isNotEmpty()) " · 上次 ${r.lastRunDate}" else ""),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = r.enabled,
                onCheckedChange = onToggle
            )
        }
    }
}

/**
 * 固定支出新增/编辑对话框。
 * showKind：新增时选「自动记账 / 到期提醒」；选到期提醒（或 showCycle）时出现每月扣/每年扣。
 */
@Composable
private fun RecurringDialog(
    title: String,
    initial: Recurring? = null,
    showDelete: Boolean = false,
    showKind: Boolean = false,
    initialAutoRecord: Boolean = true,
    showCycle: Boolean = false,
    initialCycle: String = "monthly",
    initialDueMonth: Int = 0,
    onConfirm: (
        type: String, amount: Double, merchant: String, category: String,
        day: Int, cycle: String, dueMonth: Int, autoRecord: Boolean
    ) -> Unit,
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var type by remember { mutableStateOf(initial?.type ?: "expense") }
    var autoRecord by remember { mutableStateOf(initial?.autoRecord ?: initialAutoRecord) }
    var amountText by remember { mutableStateOf(initial?.let { fmtAmount(it.amount) } ?: "") }
    var merchant by remember { mutableStateOf(initial?.merchant ?: "") }
    var category by remember { mutableStateOf(initial?.category ?: "daily") }
    var day by remember { mutableStateOf((initial?.dayOfMonth ?: 1).toString()) }
    var cycle by remember { mutableStateOf(initialCycle) }
    var dueMonthText by remember { mutableStateOf(if (initialDueMonth in 1..12) initialDueMonth.toString() else "") }
    // 自动记账规则只有按月生成；到期提醒才需要 每月扣/每年扣
    val cycleVisible = if (showKind) !autoRecord else showCycle
    val isYearly = cycleVisible && cycle == "yearly"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // 类型：新增时选；订阅固定支出
                if (showKind || initial == null) {
                    if (autoRecord) {
                        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                            listOf("expense" to "支出", "income" to "收入").forEach { (t, label) ->
                                val selected = t == type
                                Chip(label, selected) { type = t }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }
                // 类型（固定支出）：自动记账=生成账单  到期提醒=只提醒
                if (showKind) {
                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                        listOf(true to "自动记账", false to "到期提醒").forEach { (v, label) ->
                            Chip(label, autoRecord == v) { autoRecord = v }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                if (cycleVisible) {
                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                        listOf("monthly" to "每月扣", "yearly" to "每年扣").forEach { (c, label) ->
                            Chip(label, cycle == c) { cycle = c }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' }.take(10) },
                    label = { Text("金额（元）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it.take(30) },
                    label = { Text("商户 / 事项") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = day,
                    onValueChange = { day = it.filter { c -> c.isDigit() }.take(2) },
                    label = { Text(if (isYearly) "到期几号（1~31）" else "每月几号（1~31）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (isYearly) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = dueMonthText,
                        onValueChange = { dueMonthText = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("到期月份（1~12）") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text("分类", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    Categories.all.take(4).forEach { c ->
                        Chip(c.label, c.key == category) { category = c.key }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = (amountText.toDoubleOrNull() ?: 0.0) > 0 &&
                        merchant.isNotBlank() &&
                        (day.toIntOrNull() in 1..31) &&
                        (!isYearly || dueMonthText.toIntOrNull() in 1..12),
                onClick = {
                    onConfirm(
                        type, amountText.toDoubleOrNull() ?: 0.0, merchant, category,
                        (day.toIntOrNull() ?: 1).coerceIn(1, 31),
                        cycle,
                        (dueMonthText.toIntOrNull() ?: 1).coerceIn(1, 12),
                        autoRecord
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (showDelete && onDelete != null) {
                    TextButton(onClick = onDelete) { Text("删除", color = Red) }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

/** 单选小圆片（选中 = 主题色底白字） */
@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label, fontSize = 13.sp,
        color = if (selected) androidx.compose.ui.graphics.Color.White
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(
                if (selected) Primary else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(999.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    )
}
