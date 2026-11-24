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
}
