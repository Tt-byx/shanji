package com.shanji.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.shanji.app.data.AppStore
import com.shanji.app.ui.AccountsScreen
import com.shanji.app.ui.CalendarScreen
import com.shanji.app.ui.ChatScreen
import com.shanji.app.ui.HomeScreen
import com.shanji.app.ui.LoginScreen
import com.shanji.app.ui.ProfileScreen
import com.shanji.app.ui.RecordSheet
import com.shanji.app.ui.RecurringScreen
import com.shanji.app.ui.SearchScreen
import com.shanji.app.ui.StatsScreen
import com.shanji.app.ui.theme.Primary
import com.shanji.app.ui.theme.ShanJiTheme

class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppStore.init(this)
        setContent {
            ShanJiTheme {
                MainApp()
            }
        }
    }
}

private data class TabItem(val key: String, val label: String, val icon: ImageVector)

@Composable
fun MainApp() {
    var screen by remember { mutableStateOf("home") }
    var showRecord by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var recordType by remember { mutableStateOf("expense") }
    // 对话「记一笔」预填
    var chatPresetAmount by remember { mutableStateOf<Double?>(null) }
    var chatPresetMerchant by remember { mutableStateOf("") }
    var chatPresetCategory by remember { mutableStateOf("") }
    // 首页「记一笔」继承浏览月份的时间预填（当前月 = null → 用现在）
    var chatPresetDateTime by remember { mutableStateOf<java.time.LocalDateTime?>(null) }

    // 系统返回键：子页面返回上级，根页面退出（AI 是 tab，返回回首页）
    androidx.activity.compose.BackHandler(enabled = screen in setOf("search", "calendar", "recurring", "login", "chat")) {
        screen = when (screen) {
            "search" -> "home"
            "calendar" -> "stats"
            "login" -> "profile"
            "chat" -> "home"
            else -> "profile"
        }
    }

    val tabs = listOf(
        TabItem("home", "明细", Icons.Filled.Home),
        TabItem("stats", "统计", Icons.Filled.BarChart),
        TabItem("chat", "AI", Icons.Filled.SmartToy),
        TabItem("account", "账户", Icons.Filled.AccountBalanceWallet),
        TabItem("profile", "我的", Icons.Filled.Person)
    )

    // Android 15+ 强制 edge-to-edge 后 adjustResize 失效：键盘弹出时窗口不缩，
    // 只有 WindowInsets.ime 变化。此时若底部导航栏和 contentWindowInsets 的底部
    // 空间还留着，会在输入框和键盘之间顶出一大段空隙（真机掉帧外的另一真机问题）。
    // 处理：键盘弹出 → 收起底部导航栏、清掉内容区底部预留，让子页面的 imePadding 精确贴键盘。
    val density = LocalDensity.current
    val imeOpen = WindowInsets.ime.getBottom(density) > 0

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (!imeOpen) NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = screen == tab.key,
                        onClick = { screen = tab.key },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White,
                            selectedTextColor = Primary,
                            indicatorColor = Primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        },
        // 键盘弹出时只清底部（顶栏状态栏 inset 保留）
        contentWindowInsets = if (imeOpen) {
            WindowInsets(0.dp, WindowInsets.statusBars.asPaddingValues().calculateTopPadding(), 0.dp, 0.dp)
        } else {
            ScaffoldDefaults.contentWindowInsets
        }
    ) { padding ->
        androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
            when (screen) {
                "stats" -> StatsScreen(onCalendar = { screen = "calendar" })
                "account" -> AccountsScreen(onTransfer = {
                    editingId = null; recordType = "transfer"; showRecord = true
                })
                "search" -> SearchScreen(
                    onBack = { screen = "home" },
                    // 搜索结果可点：直接进编辑弹层（P0-1.3）
                    onEdit = { id -> editingId = id; showRecord = true }
                )
                "chat" -> ChatScreen(
                    onAddTx = { amount, merchant, cat, type ->
                        // 对话里说「记一笔」→ 打开记账弹层预填
                        chatPresetAmount = amount
                        chatPresetMerchant = merchant
                        chatPresetCategory = cat
                        chatPresetDateTime = null // AI 记账按「现在」，不继承浏览月份
                        editingId = null
                        recordType = type
                        showRecord = true
                        screen = "home"
                    }
                )
                "calendar" -> CalendarScreen(onBack = { screen = "stats" })
                "recurring" -> RecurringScreen(onBack = { screen = "profile" })
                "login" -> LoginScreen(
                    onBack = { screen = "profile" },
                    onLoggedIn = { screen = "profile" }
                )
                "profile" -> ProfileScreen(
                    onRecurring = { screen = "recurring" },
                    onLogin = { screen = "login" }
                )
                else -> HomeScreen(
                    onEdit = { id -> editingId = id; showRecord = true },
                    onSearch = { screen = "search" },
                    onAdd = { ym ->
                        // 记一笔继承当前浏览月份：浏览历史月时新账单落到该月（P0-1.4）
                        val now = java.time.LocalDateTime.now()
                        chatPresetDateTime = if (ym == java.time.YearMonth.now()) null
                        else ym.atDay(kotlin.math.min(now.dayOfMonth, ym.lengthOfMonth()))
                            .atTime(now.toLocalTime())
                        editingId = null; recordType = "expense"; showRecord = true
                    }
                )
            }
        }
    }

    if (showRecord) {
        RecordSheet(
            editing = editingId?.let { id -> AppStore.store.txs.firstOrNull { it.id == id } },
            initialType = recordType,
            presetAmount = chatPresetAmount,
            presetMerchant = chatPresetMerchant,
            presetCategory = chatPresetCategory,
            presetDateTime = chatPresetDateTime,
            onDismiss = {
                showRecord = false; editingId = null; recordType = "expense"
                chatPresetAmount = null; chatPresetMerchant = ""; chatPresetCategory = ""
                chatPresetDateTime = null
            }
        )
    }
}
