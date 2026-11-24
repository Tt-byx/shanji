package com.shanji.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shanji.app.data.AppStore
import com.shanji.app.data.BackupManager
import com.shanji.app.data.Ledger
import com.shanji.app.data.Tx
import kotlinx.coroutines.launch
import com.shanji.app.data.fmtAmount
import com.shanji.app.ui.theme.Bg
import com.shanji.app.ui.theme.BorderSubtle
import com.shanji.app.ui.theme.Red
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight

@Composable
fun ProfileScreen(onRecurring: () -> Unit = {}, onLogin: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val txs = AppStore.store.txs
    val summary = remember(AppStore.version.value) { Ledger.summary(txs) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDemo by remember { mutableStateOf(false) }
    var showAiSettings by remember { mutableStateOf(false) }
    var showNotify by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    var showBudget by remember { mutableStateOf(false) }
    // 登录态变化时触发重组（logout/login 后立即刷新卡片）
    var authVersion by remember { mutableStateOf(0) }

    var pendingImport by remember { mutableStateOf<List<Tx>?>(null) }
    var pendingCsv by remember { mutableStateOf<Pair<Int, List<Tx>>?>(null) }   // CSV 导入：总行数 to 新账单

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(AppStore.store.exportJson().toByteArray(Charsets.UTF_8))
                }
            }.onSuccess { android.widget.Toast.makeText(context, "已导出 ${txs.size} 笔账单", android.widget.Toast.LENGTH_SHORT).show() }
                .onFailure { android.widget.Toast.makeText(context, "导出失败", android.widget.Toast.LENGTH_SHORT).show() }
        }
    }

    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use {
                    // utf-8-sig：写入 BOM，Excel 打开中文不乱码
                    it.write("\uFEFF".toByteArray(Charsets.UTF_8) + BackupManager.toCsv(txs).toByteArray(Charsets.UTF_8))
                }
            }.onSuccess { android.widget.Toast.makeText(context, "已导出 CSV（${txs.size} 笔）", android.widget.Toast.LENGTH_SHORT).show() }
                .onFailure { android.widget.Toast.makeText(context, "导出失败", android.widget.Toast.LENGTH_SHORT).show() }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: error("读取失败")
                BackupManager.parseJson(text)
            }.onSuccess { list -> pendingImport = list }
                .onFailure { android.widget.Toast.makeText(context, "备份文件解析失败", android.widget.Toast.LENGTH_SHORT).show() }
        }
    }

    // CSV 导入（补齐 导出→导入 闭环）：按 ID 去重，只新增不覆盖
    val csvImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: error("读取失败")
                BackupManager.parseCsv(text)
            }.onSuccess { list ->
                val existing = txs.mapTo(HashSet()) { it.id }
                val fresh = list.filter { it.id !in existing }
                if (list.isEmpty()) {
                    android.widget.Toast.makeText(context, "没读到账单，检查是不是闪记导出的 CSV", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    pendingCsv = list.size to fresh
                }
            }.onFailure {
                android.widget.Toast.makeText(context, "CSV 解析失败，检查文件格式", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 16.dp)) {
            Text("我的", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }

        // 云同步状态卡
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                val loggedIn = remember(authVersion) { com.shanji.app.data.SyncManager.isLoggedIn(context) }
                if (!loggedIn) {
                    Text("云同步", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = onLogin,
                        shape = RoundedCornerShape(20.dp),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = com.shanji.app.ui.theme.Primary
                        )
                    ) { Text("登录 / 注册", fontSize = 14.sp) }
                } else {
                    var syncing by remember { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            com.shanji.app.data.SyncManager.username(context),
                            fontSize = 15.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            if (!syncing) {
                                syncing = true
                                scope.launch {
                                    val r = com.shanji.app.data.SyncManager.syncNow(context)
                                    syncing = false
                                    val msg = r.fold(
                                        { "同步完成：推送 ${it.pushed} / 拉取 ${it.pulled}" },
                                        { "同步失败：" + (it.message ?: "网络错误") }
                                    )
                                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        }) {
                            Text(if (syncing) "同步中…" else "立即同步", color = com.shanji.app.ui.theme.Primary)
                        }
                        TextButton(onClick = {
                            com.shanji.app.data.SyncManager.logout(context)
                            authVersion++
                            android.widget.Toast.makeText(context, "已退出登录（本地数据保留）", android.widget.Toast.LENGTH_SHORT).show()
                        }) { Text("退出", color = Red) }
                    }
                    val last = com.shanji.app.data.SyncManager.lastSyncAt(context)
                    Text(
                        if (last > 0) "上次同步：" + java.time.Instant.ofEpochMilli(last)
                            .atZone(java.time.ZoneId.systemDefault())
                            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                        else "尚未同步过",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    StatItem("${txs.size}", "累计笔数", Modifier.weight(1f))
                    StatItem("¥${fmtAmount(summary.expense)}", "累计支出", Modifier.weight(1f))
                    StatItem("¥${fmtAmount(summary.income)}", "累计收入", Modifier.weight(1f))
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column {
                MenuItem("外观") { showTheme = true }
                HorizontalDivider(color = MaterialTheme.colorScheme.background)
                MenuItem("预算设置") { showBudget = true }
                HorizontalDivider(color = MaterialTheme.colorScheme.background)
                MenuItem("固定支出（周期/订阅）") { onRecurring() }
                HorizontalDivider(color = MaterialTheme.colorScheme.background)
                MenuItem("AI 助手") { showAiSettings = true }
                HorizontalDivider(color = MaterialTheme.colorScheme.background)
                MenuItem("AI 主动提醒") { showNotify = true }
                HorizontalDivider(color = MaterialTheme.colorScheme.background)
                MenuItem("载入演示数据") { confirmDemo = true }
                HorizontalDivider(color = MaterialTheme.colorScheme.background)
                MenuItem("导出数据 (JSON)") {
                    exportLauncher.launch("闪记账单_" + java.time.LocalDate.now() + ".json")
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.background)
                MenuItem("导出表格 (CSV)") {
                    csvLauncher.launch("闪记账单_" + java.time.LocalDate.now() + ".csv")
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.background)
                MenuItem("导入表格 (CSV)") {
                    csvImportLauncher.launch(
                        arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*")
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.background)
                MenuItem("恢复备份") {
                    importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.background)
                MenuItem("清空全部数据", danger = true) { confirmClear = true }
            }
        }
    }

    if (showNotify) {
        var notifyOn by remember { mutableStateOf(com.shanji.app.data.ProactiveAi.enabled(context)) }
        AlertDialog(
            onDismissRequest = { showNotify = false },
            title = { Text("AI 主动提醒") },
            text = {
                Column {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("开启主动提醒", fontSize = 15.sp)
                        Switch(
                            checked = notifyOn,
                            onCheckedChange = {
                                notifyOn = it
                                com.shanji.app.data.ProactiveAi.setEnabled(context, it)
                            }
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "每周一早上推一条周报；每天检查预算超支、没分类的账单、订阅到期扣款，" +
                            "到点用通知栏提醒你。关掉后不再推送。",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showNotify = false }) { Text("完成") } }
        )
    }

    if (showBudget) {
        // 与统计页预算卡空态共用同一份实现（BudgetCard.kt）
        BudgetDialog(onDismiss = { showBudget = false })
    }
    if (showAiSettings) {
        AiSettingsDialog(onDismiss = { showAiSettings = false })
    }
    if (showTheme) {
        val current = com.shanji.app.ui.theme.ThemeMode.get(context)
        AlertDialog(
            onDismissRequest = { showTheme = false },
            title = { Text("外观") },
            text = {
                Column {
                    listOf(
                        com.shanji.app.ui.theme.ThemeMode.SYSTEM to "跟随系统",
                        com.shanji.app.ui.theme.ThemeMode.LIGHT to "浅色",
                        com.shanji.app.ui.theme.ThemeMode.DARK to "深色"
                    ).forEach { (mode, label) ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                com.shanji.app.ui.theme.ThemeMode.set(context, mode)
                                showTheme = false
                            }.padding(vertical = 12.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            if (current == mode) Text("✓", color = com.shanji.app.ui.theme.Primary, fontSize = 16.sp)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showTheme = false }) { Text("关闭") } }
        )
    }
    if (confirmDemo) {
        AlertDialog(
            onDismissRequest = { confirmDemo = false },
            title = { Text("载入演示数据") },
            text = { Text("会覆盖当前全部账单，继续？") },
            confirmButton = {
                TextButton(onClick = {
                    AppStore.store.seedDemo(); confirmDemo = false
                }) { Text("继续") }
            },
            dismissButton = { TextButton(onClick = { confirmDemo = false }) { Text("取消") } }
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空全部数据") },
            text = { Text("此操作不可恢复，建议先导出备份。确定清空？") },
            confirmButton = {
                TextButton(onClick = { AppStore.store.clear(); confirmClear = false }) {
                    Text("清空", color = Red)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } }
        )
    }
    pendingImport?.let { list ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("恢复备份") },
            text = { Text("备份含 ${list.size} 笔账单，恢复会覆盖当前全部账单（${txs.size} 笔），继续？") },
            confirmButton = {
                TextButton(onClick = {
                    AppStore.store.replaceAll(list)
                    pendingImport = null
                    android.widget.Toast.makeText(context, "已恢复 ${list.size} 笔账单", android.widget.Toast.LENGTH_SHORT).show()
                }) { Text("恢复") }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("取消") } }
        )
    }
    pendingCsv?.let { (total, fresh) ->
        AlertDialog(
            onDismissRequest = { pendingCsv = null },
            title = { Text("导入 CSV") },
            text = {
                val dup = total - fresh.size
                Text(
                    if (fresh.isEmpty()) "表格里 $total 笔账单都已存在（按 ID 去重），没有新账单可导入。"
                    else "表格含 $total 笔账单，其中 ${fresh.size} 笔是新的。" +
                        "按 ID 去重，已有的 $dup 笔不会重复导入，也不会覆盖现有账单。"
                )
            },
            confirmButton = {
                TextButton(enabled = fresh.isNotEmpty(), onClick = {
                    AppStore.store.addAll(fresh)
                    pendingCsv = null
                    android.widget.Toast.makeText(context, "已导入 ${fresh.size} 笔账单", android.widget.Toast.LENGTH_SHORT).show()
                }) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { pendingCsv = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun StatItem(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MenuItem(label: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            color = if (danger) Red else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Icon(
            androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline
        )
    }
}

/**
 * AI 助手：只保留「说话风格」（作用于对话/洞察/月报，走服务器中转零配置）。
 * 原「接口地址/API Key/模型名」三件套是废弃的端侧识别配置——文本 AI 从不读它，
 * 图片识别也已确定走服务器，留着只会让用户白填一次 401（见优化建议 2.1/3.8）。
 */
@androidx.compose.runtime.Composable
private fun AiSettingsDialog(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // AI 人设（作用于对话/洞察/月报）
    var persona by remember {
        mutableStateOf(
            context.getSharedPreferences("shanji_prefs", android.content.Context.MODE_PRIVATE)
                .getString("ai_persona", null) ?: ""
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI 助手") },
        text = {
            Column {
                Text("说话风格", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("" to "默认", "gentle" to "温和", "savage" to "毒舌").forEach { (key, label) ->
                        val selected = persona == key
                        Text(
                            label, fontSize = 13.sp,
                            color = if (selected) androidx.compose.ui.graphics.Color.White
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .background(
                                    if (selected) com.shanji.app.ui.theme.Primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(999.dp)
                                )
                                .clickable {
                                    persona = key
                                    context.getSharedPreferences("shanji_prefs", android.content.Context.MODE_PRIVATE)
                                        .edit().putString("ai_persona", key.ifBlank { null }).apply()
                                }
                                .padding(horizontal = 14.dp, vertical = 7.dp)
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                // AI 使用统计（本月调用/失败次数，AiClient 全入口计数）
                val (aiTotal, aiFail) = com.shanji.app.data.AiUsage.current(context)
                Text("本月 AI 调用 $aiTotal 次" + (if (aiFail > 0) " · 失败 $aiFail 次" else ""),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text(
                    "对话、统计洞察、月报均由服务器 AI 生成，无需任何配置。",
                    fontSize = 12.sp, lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}
