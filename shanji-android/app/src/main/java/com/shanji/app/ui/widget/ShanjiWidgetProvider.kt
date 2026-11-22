package com.shanji.app.ui.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import com.shanji.app.R
import com.shanji.app.data.WidgetUpdater

/* 桌面小组件：渲染 WidgetUpdater 写入的摘要快照 */
class ShanjiWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        WidgetUpdater.render(context, appWidgetManager, appWidgetIds)
    }

    companion object {
        fun refresh(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(
                ComponentName(context, ShanjiWidgetProvider::class.java)
            )
            if (ids.isNotEmpty()) {
                WidgetUpdater.render(context, mgr, ids)
            }
        }
    }
}

object WidgetViews {
    fun build(context: Context, snapshot: WidgetUpdater.Snapshot): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_summary)
        views.setTextViewText(
            R.id.widget_month_spend,
            "¥" + String.format("%,.2f", snapshot.monthSpent)
        )
        views.setTextViewText(
            R.id.widget_today_spend,
            "今日 ¥" + String.format("%,.2f", snapshot.todaySpent)
        )
        val budgetText = when {
            snapshot.budget <= 0 -> "未设预算"
            snapshot.monthSpent > snapshot.budget ->
                "已超支 ¥" + String.format("%,.2f", snapshot.monthSpent - snapshot.budget)
            else -> "预算 ¥" + String.format("%,.0f", snapshot.budget) +
                    " · 已花 " + (snapshot.monthSpent / snapshot.budget * 100).toInt() + "%"
        }
        views.setTextViewText(R.id.widget_budget, budgetText)
        return views
    }
}
