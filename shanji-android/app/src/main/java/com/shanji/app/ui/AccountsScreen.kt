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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
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
import com.shanji.app.data.Account
import com.shanji.app.data.AppStore
import com.shanji.app.data.Ledger
import com.shanji.app.data.fmtAmount
import com.shanji.app.data.typeLabel
import com.shanji.app.ui.theme.Primary
import com.shanji.app.ui.theme.Red
import java.time.YearMonth

/* 账户页：余额总览、账户列表、转账入口、账户增删改 */
@Composable
fun AccountsScreen(onTransfer: () -> Unit = {}) {
    val accounts = AppStore.accounts.list
    val txs = AppStore.store.txs
    val ym = YearMonth.now()

    var showAdd by remember { mutableStateOf(false) }
    var editingAcc by remember { mutableStateOf<Account?>(null) }
    // U4：删除账户先确认，并写清后果（不改数据语义，只加确认+文案）
    var confirmDelAcc by remember { mutableStateOf<Account?>(null) }

    val total = accounts.sumOf { Ledger.accountBalance(it, txs) }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("账户", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button(
                onClick = onTransfer,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) { Text("转账", fontSize = 14.sp) }
        }

        // 总资产卡
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text("总资产", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text(
                    "¥" + fmtAmount(total), fontSize = 28.sp, fontWeight = FontWeight.Bold,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        // 账户列表
        accounts.forEach { acc ->
            val balance = Ledger.accountBalance(acc, txs)
            val (expense, income) = Ledger.accountMonthFlow(acc, txs, ym)
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).clickable { editingAcc = acc }
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(acc.name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            if (acc.isDefault) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "默认", fontSize = 10.sp, color = Primary,
                                    modifier = Modifier
                                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            typeLabel(acc.type) + " · 本月支出 ¥" + fmtAmount(expense) + " · 收入 ¥" + fmtAmount(income),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "¥" + fmtAmount(balance), fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // 新增账户入口
        Row(
            Modifier.fillMaxWidth().clickable { showAdd = true }.padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("+ 新增账户", fontSize = 15.sp, color = Primary, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(80.dp))
    }

    if (showAdd) AccountDialog(
        title = "新增账户",
        onConfirm = { name, type, balance ->
            AppStore.accounts.add(name, type, balance); showAdd = false
        },
        onDismiss = { showAdd = false }
    )

    editingAcc?.let { acc ->
        AccountDialog(
            title = "编辑账户",
            initialName = acc.name, initialType = acc.type, initialBalance = acc.initialBalance,
            showDelete = true, isDefault = acc.isDefault,
            onConfirm = { name, type, balance ->
                AppStore.accounts.update(acc.copy(name = name, type = type, initialBalance = balance))
                editingAcc = null
            },
            onDelete = { confirmDelAcc = acc; editingAcc = null },
            onSetDefault = { AppStore.accounts.setDefault(acc.id) },
            onDismiss = { editingAcc = null }
        )
    }

    // U4：删除账户二次确认（后果写明：历史账单保留、账户名显示回退）
    confirmDelAcc?.let { acc ->
        ConfirmDeleteDialog(
            title = "删除账户「${acc.name}」？",
            message = "不可恢复。该账户的历史账单会保留，但账户名将显示为「现金」；" +
                "涉及该账户的余额统计会减少 ¥${fmtAmount(Ledger.accountBalance(acc, txs))}。",
            onConfirm = {
                AppStore.accounts.remove(acc.id)
                confirmDelAcc = null
            },
            onDismiss = { confirmDelAcc = null }
        )
    }
}

@Composable
private fun AccountDialog(
    title: String,
    initialName: String = "",
    initialType: String = "wechat",
    initialBalance: Double = 0.0,
    showDelete: Boolean = false,
    isDefault: Boolean = false,
    onConfirm: (name: String, type: String, balance: Double) -> Unit,
    onDelete: (() -> Unit)? = null,
    onSetDefault: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var type by remember { mutableStateOf(initialType) }
    var balanceText by remember { mutableStateOf(if (initialBalance == 0.0) "" else fmtAmount(initialBalance)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(12) },
                    label = { Text("账户名称") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Text("类型", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    listOf("wechat", "alipay", "cash", "bank").forEach { t ->
                        val selected = t == type
                        Text(
                            typeLabel(t), fontSize = 13.sp,
                            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .background(
                                    if (selected) Primary else MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(999.dp)
                                )
                                .clickable { type = t }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = balanceText,
                    onValueChange = { balanceText = it.filter { c -> c.isDigit() || c == '.' }.take(12) },
                    label = { Text("初始余额（可留空）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (showDelete && !isDefault && onSetDefault != null) {
                    TextButton(onClick = onSetDefault) { Text("设为默认账户", color = Primary) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name, type, balanceText.toDoubleOrNull() ?: 0.0) }
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (showDelete && onDelete != null && !isDefault) {
                    TextButton(onClick = onDelete) { Text("删除", color = Red) }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}
