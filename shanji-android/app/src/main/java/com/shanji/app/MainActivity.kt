package com.shanji.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.shanji.app.data.AppStore
import com.shanji.app.data.LockManager
import com.shanji.app.ui.AccountsScreen
import com.shanji.app.ui.CalendarScreen
import com.shanji.app.ui.ChatScreen
import com.shanji.app.ui.HomeScreen
import com.shanji.app.ui.LockScreen
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
                var unlocked by remember {
                    mutableStateOf(!LockManager.isEnabled(this) || LockManager.unlocked)
                }
                if (unlocked) MainApp()
                else LockScreen(onUnlocked = { unlocked = true })
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
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
        }
    ) { padding ->
        androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
            when (screen) {
                "stats" -> StatsScreen(onCalendar = { screen = "calendar" })
                "account" -> AccountsScreen(onTransfer = {
                    editingId = null; recordType = "transfer"; showRecord = true
                })
                "search" -> SearchScreen(onBack = { screen = "home" })
                "chat" -> ChatScreen(
                    onAddTx = { amount, merchant, cat, type ->
                        // 对话里说「记一笔」→ 打开记账弹层预填
                        chatPresetAmount = amount
                        chatPresetMerchant = merchant
                        chatPresetCategory = cat
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
                    onAdd = { editingId = null; recordType = "expense"; showRecord = true },
                    onCapture = { editingId = null; recordType = "expense"; showRecord = true }
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
            onDismiss = {
                showRecord = false; editingId = null; recordType = "expense"
                chatPresetAmount = null; chatPresetMerchant = ""; chatPresetCategory = ""
            }
        )
    }
}
