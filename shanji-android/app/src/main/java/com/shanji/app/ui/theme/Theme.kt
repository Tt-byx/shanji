package com.shanji.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Apple HIG 语义色板：灰阶分层（grouped background + separator）+ iOS 强调蓝，
// 语义色用系统色（systemRed/Green/Orange），分类色见 Model.kt Categories
val Primary = Color(0xFF007AFF)          // systemBlue：强调/可点元素统一色
val PrimaryDeep = Color(0xFF0071E3)      // 深一档（按下/图表深色）
val PrimarySoft = Color(0xFFE5F1FF)      // 蓝的浅底（图表/高亮）
val Green = Color(0xFF34C759)            // systemGreen：收入/正向
val GreenSoft = Color(0xFFE9F9EE)
val GreenDeep = Color(0xFF248A3D)
val Amber = Color(0xFFFF9500)            // systemOrange：提醒/预警
val AmberSoft = Color(0xFFFFF3E6)
val Red = Color(0xFFFF3B30)              // systemRed：支出/危险
val Text1 = Color(0xFF000000)            // label
val Text2 = Color(0xFF8E8E93)            // secondaryLabel / systemGray
val Text3 = Color(0xFFC7C7CC)            // systemGray2
val Bg = Color(0xFFF2F2F7)               // systemGroupedBackground
val CardBg = Color(0xFFFFFFFF)           // systemBackground（卡片纯白，分层靠灰阶）
val BorderSubtle = Color(0xFFE5E5EA)     // separator 分割线
val ChipGray = Color(0xFFE9E9EB)         // fill（未选中 chip/输入底）

private val LightScheme = lightColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    secondary = Green,
    tertiary = Amber,
    background = Bg,
    onBackground = Text1,
    surface = CardBg,
    onSurface = Text1,
    surfaceVariant = ChipGray,
    onSurfaceVariant = Text2,
    outline = BorderSubtle,
    error = Red
)

private val DarkScheme = darkColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    secondary = Green,
    tertiary = Amber,
    background = Color(0xFF000000),      // iOS dark grouped background
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF1C1C1E),         // systemGroupedBackground dark
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFF98989D), // secondaryLabel dark
    outline = Color(0xFF38383A),
    error = Color(0xFFFF453A)
)

/** 外观三态：跟随系统 / 浅色 / 深色，存 SharedPreferences（Snapshot state 驱动重组） */
object ThemeMode {
    const val PREFS = "shanji_theme"
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    private val state = androidx.compose.runtime.mutableStateOf<String?>(null)

    fun get(context: android.content.Context): String {
        state.value?.let { return it }
        val saved = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getString("mode", SYSTEM) ?: SYSTEM
        state.value = saved
        return saved
    }

    fun set(context: android.content.Context, mode: String) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putString("mode", mode).apply()
        state.value = mode
    }
}

@Composable
fun ShanJiTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val mode = ThemeMode.get(context)
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
