package com.shanji.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.ui.Alignment
import com.shanji.app.data.AiClient
import com.shanji.app.data.AppStore
import com.shanji.app.data.Categories
import com.shanji.app.data.Tx
import com.shanji.app.data.fmtAmount
import com.shanji.app.data.newTx
import com.shanji.app.data.toLocalDateTime
import com.shanji.app.ui.theme.ChipGray
import com.shanji.app.ui.theme.Primary
import com.shanji.app.ui.theme.Red
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 记账表单（新增 + 编辑共用）。
 * editing == null 为新增；否则编辑现有账单（含删除入口）。
 * presetAmount / presetDateTime：AI 识别预填（仅新增时生效）。
 * source: manual=手动记账；share=分享截图进来
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecordSheet(
    editing: Tx?,
    onDismiss: () -> Unit,
    source: String = "manual",
    presetMerchant: String = "",
    presetAmount: Double? = null,
    presetDateTime: LocalDateTime? = null,
    presetCategory: String = "",
    initialType: String = "expense"
) {
    val store = AppStore.store
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 自然语言记账：一句话 → AI 解析 → 回填下方字段（不直接入库）
    var nlText by remember { mutableStateOf("") }
    var nlParsing by remember { mutableStateOf(false) }
    var nlError by remember { mutableStateOf<String?>(null) }

    var type by remember { mutableStateOf(editing?.type ?: initialType) }
    var amountText by remember {
        mutableStateOf(
            editing?.let { fmtAmount(it.amount) }
                ?: presetAmount?.takeIf { it > 0 }?.let { fmtAmount(it) }
                ?: ""
        )
    }
    var merchant by remember { mutableStateOf(editing?.merchant ?: presetMerchant) }
    var category by remember {
        mutableStateOf(
            editing?.category
                ?: presetCategory.takeIf { Categories.all.any { c -> c.key == it } }
                ?: "dining"
        )
    }
    var note by remember { mutableStateOf(editing?.note?.removePrefix("备注：") ?: "") }
    var dateTime by remember {
        mutableStateOf(
            editing?.let { toLocalDateTime(it.ts) }
                ?: presetDateTime
                ?: LocalDateTime.now()
        )
    }
    var showTimeSheet by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val accountList = com.shanji.app.data.AppStore.accounts.list
    var accountId by remember {
        mutableStateOf(editing?.accountId ?: com.shanji.app.data.AppStore.accounts.defaultId())
    }
    var toAccountId by remember {
        mutableStateOf(
            editing?.toAccountId?.takeIf { it.isNotEmpty() }
                ?: accountList.firstOrNull { it.id != accountId }?.id ?: ""
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (editing == null) "记一笔" else "编辑账单",
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                )
                if (editing != null) {
                    Text(
                        "删除", fontSize = 14.sp, color = Red,
                        modifier = Modifier.clickable {
                            store.remove(editing.id); onDismiss()
                        }
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            // 一句话记账：AI 解析后回填下方字段
            if (editing == null) {
                OutlinedTextField(
                    value = nlText,
                    onValueChange = { nlText = it.take(100); nlError = null },
                    placeholder = { Text("一句话记账，如：午饭 15 块") },
                    singleLine = true,
                    enabled = !nlParsing,
                    leadingIcon = {
                        IconButton(
                            onClick = {
                                if (nlText.isNotBlank() && !nlParsing) {
                                    nlParsing = true; nlError = null
                                    val raw = nlText.trim()
                                    scope.launch {
                                        runCatching { AiClient.parse(ctx, raw) }
                                            .onSuccess { p ->
                                                amountText = fmtAmount(p.amount)
                                                if (p.merchant.isNotBlank()) merchant = p.merchant
                                                // 学过的分类优先于 AI 建议（越用越准）；非法 key 兜底 dining
                                                val learned = p.merchant.takeIf { it.isNotBlank() }
                                                    ?.let { com.shanji.app.data.CategoryLearner.predict(ctx, it) }
                                                val aiCat = p.category.takeIf { c -> Categories.all.any { it.key == c } }
                                                category = learned ?: aiCat ?: category
                                                if (p.note.isNotBlank()) note = p.note
                                                if (p.type == "income") type = "income"
                                                nlText = ""; nlParsing = false
                                            }
                                            .onFailure { nlError = it.message ?: "没听懂这句话，换个说法试试"; nlParsing = false }
                                    }
                                }
                            }
                        ) {
                            if (nlParsing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = Primary
                                )
                            } else {
                                Icon(Icons.AutoMirrored.Filled.Send, "AI 解析", tint = Primary)
                            }
                        }
                    },
                    isError = nlError != null,
                    supportingText = nlError?.let { { Text(it, color = Red) } },
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Primary),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
            }

            OutlinedTextField(
                value = amountText,
                onValueChange = { v -> amountText = v.filter { it.isDigit() || it == '.' }.take(12) },
                prefix = { Text("¥", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
                placeholder = { Text("0.00", fontFamily = FontFamily.Monospace) },
                textStyle = MaterialTheme.typography.headlineSmall.copy(
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                ),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Primary),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            // 商户
            OutlinedTextField(
                value = merchant,
                onValueChange = { merchant = it.take(30) },
                label = { Text("商户 / 事项") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Primary),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            // 时间：快捷日期 chips 一步到位，具体时分点右侧值单页选
            Text("时间", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val today = LocalDate.now()
                val selDate = dateTime.toLocalDate()
                listOf(
                    "前天" to today.minusDays(2),
                    "昨天" to today.minusDays(1),
                    "今天" to today
                ).forEach { (label, date) ->
                    val selected = selDate == date
                    Text(
                        label, fontSize = 13.sp,
                        color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .background(
                                if (selected) Primary else MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(999.dp)
                            )
                            .clickable { dateTime = date.atTime(dateTime.hour, dateTime.minute) }
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Spacer(Modifier.weight(1f))
                // 时间值：点开 日历+时分 单页弹层
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(999.dp))
                        .clickable { showTimeSheet = true }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Icon(
                        Icons.Filled.Schedule, "修改时间",
                        tint = Primary, modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    val quick = when (selDate) {
                        today -> null
                        today.minusDays(1) -> "昨天 "
                        today.minusDays(2) -> "前天 "
                        else -> "${selDate.monthValue}月${selDate.dayOfMonth}日 "
                    }
                    Text(
                        (quick ?: "") + String.format("%02d:%02d", dateTime.hour, dateTime.minute),
                        fontSize = 14.sp, fontWeight = FontWeight.Medium
                    )
                }
            }
            Spacer(Modifier.height(14.dp))

            // 分类（转账不选分类）
            if (type != "transfer") {
                Text("分类", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Categories.all.forEach { c ->
                        val selected = c.key == category
                        Text(
                            c.label, fontSize = 13.sp,
                            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .background(if (selected) Primary else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(999.dp))
                                .clickable { category = c.key }
                                .padding(horizontal = 14.dp, vertical = 7.dp)
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            // 备注
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(100) },
                label = { Text("备注") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Primary),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(14.dp))

            // 支出/收入/转账
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = type == "expense",
                    onClick = { type = "expense" },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                    label = { Text("支出") }
                )
                SegmentedButton(
                    selected = type == "income",
                    onClick = { type = "income" },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                    label = { Text("收入") }
                )
                SegmentedButton(
                    selected = type == "transfer",
                    onClick = { type = "transfer" },
                    shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                    label = { Text("转账") }
                )
            }
            Spacer(Modifier.height(14.dp))

            // 账户选择
            if (type == "transfer") {
                AccountPicker("转出账户", accountList, accountId) { accountId = it }
                Spacer(Modifier.height(10.dp))
                AccountPicker("转入账户", accountList, toAccountId) { toAccountId = it }
            } else {
                AccountPicker(if (type == "income") "入账账户" else "付款账户", accountList, accountId) { accountId = it }
            }
            Spacer(Modifier.height(16.dp))

            error?.let {
                Text(it, fontSize = 13.sp, color = Red)
                Spacer(Modifier.height(8.dp))
            }

            Button(
                onClick = {
                    val amount = amountText.toDoubleOrNull()
                    when {
                        amount == null || amount <= 0 -> error = "请填写正确的金额"
                        type != "transfer" && merchant.isBlank() -> error = "请填写商户或事项"
                        type == "transfer" && accountId == toAccountId -> error = "转出和转入账户不能相同"
                        else -> {
                            val ts = dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                            val finalMerchant = if (type == "transfer") "转账" else merchant
                            val finalCat = if (type == "transfer") "other" else category
                            // 学习：记住这次的 商户→分类（下次自动带出）
                            if (type != "transfer") {
                                com.shanji.app.data.CategoryLearner.learn(ctx, finalMerchant, finalCat)
                            }
                            // 超支提醒：计算记账前的已花额
                            val ym = java.time.YearMonth.of(dateTime.year, dateTime.monthValue)
                            val oldTotal = com.shanji.app.data.BudgetCalc.spentTotal(store.txs, ym)
                            val oldCat = com.shanji.app.data.BudgetCalc.spentOf(store.txs, ym, finalCat)
                            val addAmt = if (type == "expense") amount else 0.0
                            if (editing == null) {
                                store.add(
                                    newTx(
                                        type, amount, finalMerchant, finalCat, note, ts, source,
                                        accountId = accountId,
                                        toAccountId = if (type == "transfer") toAccountId else ""
                                    )
                                )
                            } else {
                                store.update(
                                    editing.copy(
                                        type = type, amount = amount, merchant = finalMerchant,
                                        category = finalCat, note = note, ts = ts,
                                        accountId = accountId,
                                        toAccountId = if (type == "transfer") toAccountId else ""
                                    )
                                )
                            }
                            // 刚越过预算 → Toast 提醒
                            val totalBudget = com.shanji.app.data.AppStore.budgets.total()
                            if (addAmt > 0) {
                                val catBudget = com.shanji.app.data.AppStore.budgets.ofCategory(finalCat)
                                if (com.shanji.app.data.BudgetCalc.justCrossed(oldCat, oldCat + addAmt, catBudget)) {
                                    android.widget.Toast.makeText(
                                        ctx, "⚠ 已超出「${com.shanji.app.data.Categories.of(finalCat).label}」预算",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                } else if (com.shanji.app.data.BudgetCalc.justCrossed(oldTotal, oldTotal + addAmt, totalBudget)) {
                                    android.widget.Toast.makeText(
                                        ctx, "⚠ 已超出本月总预算", android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                            onDismiss()
                        }
                    }
                },
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text(if (editing == null) "保存这笔账单" else "保存修改", fontSize = 16.sp)
            }
        }
    }

    // 时间单页选择：日历 + 时分同屏，一次确定（替代旧的 日期→下一步→时分 两连击）
    if (showTimeSheet) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = dateTime.toLocalDate()
                .atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        )
        val timeState = rememberTimePickerState(
            initialHour = dateTime.hour, initialMinute = dateTime.minute, is24Hour = true
        )
        ModalBottomSheet(
            onDismissRequest = { showTimeSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                Modifier.padding(horizontal = 16.dp).padding(bottom = 20.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "选择时间", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
                DatePicker(state = dateState)
                Spacer(Modifier.height(8.dp))
                TimePicker(state = timeState)
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { showTimeSheet = false }) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        val d = dateState.selectedDateMillis?.let {
                            Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate()
                        } ?: dateTime.toLocalDate()
                        dateTime = d.atTime(timeState.hour, timeState.minute)
                        showTimeSheet = false
                    }) { Text("确定", color = Primary, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

/** 账户选择行：标签 + 账户 chips */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountPicker(
    label: String,
    accounts: List<com.shanji.app.data.Account>,
    selectedId: String,
    onSelect: (String) -> Unit
) {
    Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        accounts.forEach { acc ->
            val selected = acc.id == selectedId
            Text(
                acc.name, fontSize = 13.sp,
                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .background(
                        if (selected) Primary else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(999.dp)
                    )
                    .clickable { onSelect(acc.id) }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            )
        }
    }
}
