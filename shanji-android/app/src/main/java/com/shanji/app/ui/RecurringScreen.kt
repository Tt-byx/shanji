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
import androidx.compose.material3.HorizontalDivider
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
import com.shanji.app.data.Subscription
import com.shanji.app.data.SubscriptionDetector
import com.shanji.app.data.fmtAmount
import com.shanji.app.ui.theme.Primary
import com.shanji.app.ui.theme.Red
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* 周期记账：每月固定日期自动生成账单（App 启动时补记） */
@Composable
fun RecurringScreen(onBack: () -> Unit) {
    val store = AppStore.recurring
    val rules = store.list
    var editing by remember { mutableStateOf<Recurring?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    // 订阅管理
    val subs = AppStore.subscriptions.list
    val scope = rememberCoroutineScope()
    var detecting by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf<List<Subscription>?>(null) }
    var showDetect by remember { mutableStateOf(false) }
    var showAddSub by remember { mutableStateOf(false) }
    var editingSub by remember { mutableStateOf<Subscription?>(null) }

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
            Text("周期记账", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))

        if (rules.isEmpty()) {
            Text("还没有周期规则", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Text(
                "房租 / 话费 / 会员 / 通勤这类固定支出，建个规则后每月到日子自动生成账单，不用再手动记。\n" +
                    "示例：房租 ¥1500 每月1号 · 话费 ¥50 每月10号",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, lineHeight = 18.sp
            )
        }

        rules.forEach { r ->
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Column(Modifier.weight(1f).clickable { editing = r }) {
                        Text(r.merchant, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "每月 ${r.dayOfMonth} 日 · " +
                                    (if (r.type == "income") "+" else "-") + "¥" + fmtAmount(r.amount) +
                                    " · ${Categories.of(r.category).label}" +
                                    (if (r.lastRunDate.isNotEmpty()) " · 上次 ${r.lastRunDate}" else ""),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = r.enabled,
                        onCheckedChange = { store.setEnabled(r, it) }
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth().clickable { showAdd = true }.padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("+ 新增周期规则", fontSize = 15.sp, color = Primary, fontWeight = FontWeight.Medium)
        }

        // ===== 订阅管理：从历史账单里认出每月都在扣的钱 + 到期提醒 =====
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        Spacer(Modifier.height(16.dp))
        Text("订阅管理", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "认出每月都在扣的会员 / 话费，到期前提醒你，别忘续也别多扣",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        if (subs.isEmpty()) {
            Text(
                "还没有订阅。点下面「自动识别订阅」，扫一遍历史账单就能认出来。",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
        }

        subs.forEach { s ->
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Column(
                        Modifier.weight(1f).clickable { editingSub = s }
                    ) {
                        Text(s.name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            (if (s.cycle == "yearly") "每年${s.dueMonth}月${s.dayOfMonth}日"
                             else "每月 ${s.dayOfMonth} 日") +
                                " · ¥${fmtAmount(s.amount)} · " +
                                Categories.of(s.category).label +
                                // 实际是本地规则检测，文案不能承诺 AI（同「AI 识别设置」改名的教训）
                                (if (s.source == "ai") " · 自动识别" else " · 手动"),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = s.enabled,
                        onCheckedChange = { AppStore.subscriptions.setEnabled(s.id, it) }
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.weight(1f).clickable(enabled = !detecting) {
                    if (detecting) return@clickable
                    detecting = true
                    val existing = AppStore.subscriptions.list.map { it.name }
                    scope.launch {
                        val found = withContext(Dispatchers.IO) {
                            SubscriptionDetector.detect(AppStore.store.txs, existing)
                        }
                        candidates = found
                        detecting = false
                        showDetect = true
                    }
                }.padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (detecting) "识别中…" else "+ 自动识别订阅",
                    fontSize = 15.sp, color = Primary, fontWeight = FontWeight.Medium
                )
            }
            Row(
                Modifier.weight(1f).clickable { showAddSub = true }.padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("+ 手动添加", fontSize = 15.sp, color = Primary, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.height(60.dp))
    }

    // AI 识别结果确认
    if (showDetect) {
        val found = candidates ?: emptyList()
        AlertDialog(
            onDismissRequest = { showDetect = false },
            title = { Text(if (found.isEmpty()) "没认出新的订阅" else "认出 ${found.size} 个订阅") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (found.isEmpty()) {
                        Text(
                            "没有找到「连续 3 个月以上、金额稳定」的固定支出。" +
                                "可能是数据还不够，或者这些支出本来就不固定。",
                            fontSize = 13.sp
                        )
                    } else {
                        Text("确认后会加进订阅列表，到期前会提醒你：", fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        found.forEach {
                            Text(
                                "· ${it.name}  ¥${fmtAmount(it.amount)}/月  每月${it.dayOfMonth}号",
                                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = found.isNotEmpty(),
                    onClick = {
                        AppStore.subscriptions.addAll(found)
                        showDetect = false
                    }
                ) { Text("全部添加") }
            },
            dismissButton = { TextButton(onClick = { showDetect = false }) { Text("取消") } }
        )
    }

    // 手动添加订阅（可选每月/每年）
    if (showAddSub) RecurringDialog(
        title = "新增订阅",
        showCycle = true,
        onConfirm = { _, amount, merchant, cat, day, cycle, dueMonth ->
            AppStore.subscriptions.add(
                Subscription(
                    id = "sub_" + java.util.UUID.randomUUID().toString().take(8),
                    name = merchant, amount = amount, category = cat,
                    accountId = AppStore.accounts.defaultId(),
                    cycle = cycle,
                    dayOfMonth = day,
                    dueMonth = if (cycle == "yearly") dueMonth else 0,
                    source = "manual"
                )
            )
            showAddSub = false
        },
        onDismiss = { showAddSub = false }
    )

    // 编辑订阅（点卡片）：改完或删除
    editingSub?.let { s ->
        RecurringDialog(
            title = "编辑订阅",
            initial = Recurring(
                id = s.id, type = "expense", amount = s.amount, merchant = s.name,
                category = s.category, accountId = s.accountId, dayOfMonth = s.dayOfMonth,
                enabled = s.enabled, lastRunDate = ""
            ),
            showCycle = true,
            initialCycle = s.cycle,
            initialDueMonth = s.dueMonth,
            showDelete = true,
            onConfirm = { _, amount, merchant, cat, day, cycle, dueMonth ->
                AppStore.subscriptions.remove(s.id)
                AppStore.subscriptions.add(
                    s.copy(
                        name = merchant, amount = amount, category = cat, dayOfMonth = day,
                        cycle = cycle, dueMonth = if (cycle == "yearly") dueMonth else 0
                    )
                )
                editingSub = null
            },
            onDelete = { AppStore.subscriptions.remove(s.id); editingSub = null },
            onDismiss = { editingSub = null }
        )
    }

    if (showAdd) RecurringDialog(
        title = "新增周期规则",
        onConfirm = { type, amount, merchant, cat, day, _, _ ->
            store.add(type, amount, merchant, cat, AppStore.accounts.defaultId(), day)
            showAdd = false
        },
        onDismiss = { showAdd = false }
    )

    editing?.let { r ->
        RecurringDialog(
            title = "编辑周期规则",
            initial = r,
            showDelete = true,
            onConfirm = { type, amount, merchant, cat, day, _, _ ->
                store.update(r.copy(type = type, amount = amount, merchant = merchant, category = cat, dayOfMonth = day))
                editing = null
            },
            onDelete = { store.remove(r.id); editing = null },
            onDismiss = { editing = null }
        )
    }
}

@Composable
private fun RecurringDialog(
    title: String,
    initial: Recurring? = null,
    showDelete: Boolean = false,
    showCycle: Boolean = false,
    initialCycle: String = "monthly",
    initialDueMonth: Int = 0,
    onConfirm: (type: String, amount: Double, merchant: String, category: String, day: Int, cycle: String, dueMonth: Int) -> Unit,
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var type by remember { mutableStateOf(initial?.type ?: "expense") }
    var amountText by remember { mutableStateOf(initial?.let { fmtAmount(it.amount) } ?: "") }
    var merchant by remember { mutableStateOf(initial?.merchant ?: "") }
    var category by remember { mutableStateOf(initial?.category ?: "daily") }
    var day by remember { mutableStateOf((initial?.dayOfMonth ?: 1).toString()) }
    var cycle by remember { mutableStateOf(initialCycle) }
    var dueMonthText by remember { mutableStateOf(if (initialDueMonth in 1..12) initialDueMonth.toString() else "") }
    val isYearly = showCycle && cycle == "yearly"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    listOf("expense" to "支出", "income" to "收入").forEach { (t, label) ->
                        val selected = t == type
                        Text(
                            label, fontSize = 13.sp,
                            color = if (selected) androidx.compose.ui.graphics.Color.White
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .background(
                                    if (selected) Primary else MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(999.dp)
                                )
                                .clickable { type = t }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                }
                // 订阅支持按年扣（年费会员/保险），原来只有 monthly，yearly 分支是死代码
                if (showCycle) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                        listOf("monthly" to "每月扣", "yearly" to "每年扣").forEach { (c, label) ->
                            val selected = c == cycle
                            Text(
                                label, fontSize = 13.sp,
                                color = if (selected) androidx.compose.ui.graphics.Color.White
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .background(
                                        if (selected) Primary else MaterialTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(999.dp)
                                    )
                                    .clickable { cycle = c }
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
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
                        val selected = c.key == category
                        Text(
                            c.label, fontSize = 12.sp,
                            color = if (selected) androidx.compose.ui.graphics.Color.White
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .background(
                                    if (selected) Primary else MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(999.dp)
                                )
                                .clickable { category = c.key }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        )
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
                        (dueMonthText.toIntOrNull() ?: 1).coerceIn(1, 12)
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
