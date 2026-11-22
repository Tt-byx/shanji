package com.shanji.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shanji.app.data.AppStore
import com.shanji.app.data.Categories
import com.shanji.app.data.Recurring
import com.shanji.app.data.fmtAmount
import com.shanji.app.ui.theme.Primary
import com.shanji.app.ui.theme.Red

/* 周期记账：每月固定日期自动生成账单（App 启动时补记） */
@Composable
fun RecurringScreen(onBack: () -> Unit) {
    val store = AppStore.recurring
    val rules = store.list
    var editing by remember { mutableStateOf<Recurring?>(null) }
    var showAdd by remember { mutableStateOf(false) }

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
        Spacer(Modifier.height(60.dp))
    }

    if (showAdd) RecurringDialog(
        title = "新增周期规则",
        onConfirm = { type, amount, merchant, cat, day ->
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
            onConfirm = { type, amount, merchant, cat, day ->
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
    onConfirm: (type: String, amount: Double, merchant: String, category: String, day: Int) -> Unit,
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var type by remember { mutableStateOf(initial?.type ?: "expense") }
    var amountText by remember { mutableStateOf(initial?.let { fmtAmount(it.amount) } ?: "") }
    var merchant by remember { mutableStateOf(initial?.merchant ?: "") }
    var category by remember { mutableStateOf(initial?.category ?: "daily") }
    var day by remember { mutableStateOf((initial?.dayOfMonth ?: 1).toString()) }

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
                    label = { Text("每月几号（1~28）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
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
                        (day.toIntOrNull() in 1..28),
                onClick = {
                    onConfirm(
                        type, amountText.toDoubleOrNull() ?: 0.0, merchant, category,
                        (day.toIntOrNull() ?: 1).coerceIn(1, 28)
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
