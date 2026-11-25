package com.shanji.app.data

import android.content.Context

/**
 * 月度报告缓存：按月存 AI 生成的报告文本，重复查看不再调 AI。
 */
object MonthlyReportStore {

    private const val PREFS = "shanji_monthly_report"

    fun get(context: Context, ym: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ym, null)

    /**
     * @param clearPrefix 传入时先删掉所有以它开头的旧 key——洞察按数据指纹存，
     * 指纹随每次改账漂移，不清会按版本无限堆积。
     */
    fun put(context: Context, ym: String, text: String, clearPrefix: String? = null) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val e = sp.edit().putString(ym, text)
        if (clearPrefix != null) {
            sp.all.keys.filter { it.startsWith(clearPrefix) && it != ym }.forEach { e.remove(it) }
        }
        e.apply()
    }

    /**
     * U6：v1.17 及更早的 insight_ 前缀旧缓存换前缀后「不再被读但永不删除」，
     * 启动时一次性清掉（insight2_ 是新前缀，startsWith("insight_") 不会误伤）。
     */
    fun clearLegacyInsight(context: Context) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val keys = sp.all.keys.filter { it.startsWith("insight_") }
        if (keys.isEmpty()) return
        val e = sp.edit()
        keys.forEach { e.remove(it) }
        e.apply()
    }
}
