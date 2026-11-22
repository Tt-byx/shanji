package com.shanji.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.material3.CircularProgressIndicator
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
    var showTheme by remember { mutableStateOf(false) }
    var showBudget by remember { mutableStateOf(false) }
    // 登录态变化时触发重组（logout/login 后立即刷新卡片）
    var authVersion by remember { mutableStateOf(0) }

    var pendingImport by remember { mutableStateOf<List<Tx>?>(null) }

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
                MenuItem("周期记账") { onRecurring() }
                HorizontalDivider(color = MaterialTheme.colorScheme.background)
                MenuItem("AI 识别设置") { showAiSettings = true }
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
                MenuItem("恢复备份") {
                    importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.background)
                MenuItem("清空全部数据", danger = true) { confirmClear = true }
            }
        }
    }

    if (showBudget) {
        val budgets = AppStore.budgets
        var totalText by remember {
            mutableStateOf(budgets.total().takeIf { it > 0 }?.let { fmtAmount(it) } ?: "")
        }
        val catTexts = remember {
            androidx.compose.runtime.mutableStateMapOf<String, String>().apply {
                com.shanji.app.data.Categories.all.forEach { c ->
                    val v = budgets.ofCategory(c.key)
                    if (v > 0) put(c.key, fmtAmount(v))
                }
            }
        }
        AlertDialog(
            onDismissRequest = { showBudget = false },
            title = { Text("预算设置") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = totalText,
                        onValueChange = { totalText = it.filter { c -> c.isDigit() || c == '.' }.take(10) },
                        label = { Text("本月总预算（元）") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    com.shanji.app.data.Categories.all.forEach { c ->
                        OutlinedTextField(
                            value = catTexts[c.key] ?: "",
                            onValueChange = { catTexts[c.key] = it.filter { ch -> ch.isDigit() || ch == '.' }.take(10) },
                            label = { Text("${c.label}预算（元）") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    budgets.set("total", "", totalText.toDoubleOrNull() ?: 0.0)
                    com.shanji.app.data.Categories.all.forEach { c ->
                        budgets.set("category", c.key, (catTexts[c.key] ?: "").toDoubleOrNull() ?: 0.0)
                    }
                    android.widget.Toast.makeText(context, "预算已保存", android.widget.Toast.LENGTH_SHORT).show()
                    showBudget = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showBudget = false }) { Text("取消") } }
        )
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

/** AI 识别设置：OpenAI 兼容多模态接口（智谱 GLM-4V / 通义 qwen-vl / Kimi 等），支持一键测试连接 */
@androidx.compose.runtime.Composable
private fun AiSettingsDialog(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val cfg = com.shanji.app.data.AiSettings.load(context)
    var baseUrl by remember { mutableStateOf(cfg.baseUrl) }
    var apiKey by remember { mutableStateOf(cfg.apiKey) }
    var model by remember { mutableStateOf(cfg.model) }
    var testing by remember { mutableStateOf(false) }
    var testOk by remember { mutableStateOf<Boolean?>(null) }
    var testMsg by remember { mutableStateOf<String?>(null) }
    // AI 人设（作用于对话/洞察/月报）
    var persona by remember {
        mutableStateOf(
            context.getSharedPreferences("shanji_prefs", android.content.Context.MODE_PRIVATE)
                .getString("ai_persona", null) ?: ""
        )
    }

    AlertDialog(
        onDismissRequest = { if (!testing) onDismiss() },
        title = { Text("AI 识别设置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
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
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it; testMsg = null },
                    label = { Text("接口地址") },
                    placeholder = { Text("https://open.bigmodel.cn/api/paas/v4") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; testMsg = null },
                    label = { Text("API Key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it; testMsg = null },
                    label = { Text("模型名") },
                    placeholder = { Text("glm-4v-flash") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))

                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            if (testing) return@TextButton
                            // 先保存再测试，保证测试的就是用户当前填写的内容
                            com.shanji.app.data.AiSettings.save(context, baseUrl, apiKey, model)
                            testing = true; testMsg = null; testOk = null
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                val report = com.shanji.app.data.AiRecognizer.testConnection(baseUrl.trim(), apiKey.trim(), model.trim())
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    testing = false; testOk = report.ok; testMsg = report.message
                                }
                            }
                        }
                    ) {
                        Text(if (testing) "测试中…" else "测试连接", color = com.shanji.app.ui.theme.Primary)
                    }
                    if (testing) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = com.shanji.app.ui.theme.Primary)
                    }
                }
                testMsg?.let { msg ->
                    Text(
                        msg, fontSize = 12.sp, lineHeight = 17.sp,
                        color = if (testOk == true) com.shanji.app.ui.theme.Green
                                else com.shanji.app.ui.theme.Red,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !testing,
                onClick = {
                    com.shanji.app.data.AiSettings.save(context, baseUrl, apiKey, model)
                    android.widget.Toast.makeText(context, "已保存 AI 识别配置", android.widget.Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(enabled = !testing, onClick = onDismiss) { Text("取消") }
        }
    )
}
