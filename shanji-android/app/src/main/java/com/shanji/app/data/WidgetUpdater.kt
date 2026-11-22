package com.shanji.app.data

import android.appwidget.AppWidgetManager
import android.content.Context
import com.shanji.app.data.db.toDomain
import com.shanji.app.ui.widget.ShanjiWidgetProvider
import com.shanji.app.ui.widget.WidgetViews
import java.time.LocalDate
import java.time.YearMonth

/* 桌面小组件数据通道：写库后把摘要快照写 prefs，Provider 读快照渲染（RemoteViews 不能直接查 Room） */
object WidgetUpdater {

    data class Snapshot(
        val monthSpent: Double,
        val todaySpent: Double,
        val budget: Double
    )

    private const val PREFS = "shanji_widget_snapshot"

    /** 数据变化后调用：重算快照并刷新桌面小组件（直接读库，不依赖 Flow 加载时机） */
    fun update(context: Context) {
        runCatching {
            val db = com.shanji.app.data.db.AppDatabase.getInstance(context)
            val txs = kotlinx.coroutines.runBlocking { db.txDao().listAll() }
                .map { it.toDomain() }
            val budget = kotlinx.coroutines.runBlocking { db.budgetDao().listAll() }
                .firstOrNull { it.scope == "total" }?.amount ?: 0.0
            val today = LocalDate.now()
            val ym = YearMonth.now()
            val snap = Snapshot(
                monthSpent = BudgetCalc.spentTotal(txs, ym),
                todaySpent = round2(
                    txs.filter {
                        it.type == "expense" &&
                                java.time.Instant.ofEpochMilli(it.ts)
                                    .atZone(java.time.ZoneId.systemDefault())
                                    .toLocalDate() == today
                    }.sumOf { it.amount }
                ),
                budget = budget
            )
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putFloat("monthSpent", snap.monthSpent.toFloat())
                .putFloat("todaySpent", snap.todaySpent.toFloat())
                .putFloat("budget", snap.budget.toFloat())
                .apply()
            ShanjiWidgetProvider.refresh(context)
        }
    }

    /** Provider 侧：读快照并渲染到全部小组件实例 */
    fun render(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val snap = Snapshot(
            monthSpent = prefs.getFloat("monthSpent", 0f).toDouble(),
            todaySpent = prefs.getFloat("todaySpent", 0f).toDouble(),
            budget = prefs.getFloat("budget", 0f).toDouble()
        )
        val views = WidgetViews.build(context, snap)
        manager.updateAppWidget(ids, views)
    }
}
