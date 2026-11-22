package com.shanji.app.data

import android.content.Context

/**
 * 月度报告缓存：按月存 AI 生成的报告文本，重复查看不再调 AI。
 */
object MonthlyReportStore {

    private const val PREFS = "shanji_monthly_report"

    fun get(context: Context, ym: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ym, null)

    fun put(context: Context, ym: String, text: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(ym, text).apply()
    }
}
