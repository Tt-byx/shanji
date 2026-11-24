package com.shanji.app.data

import android.content.Context

/**
 * AI 使用统计（端侧口径）：按月记录调用次数与失败次数，我的页「AI 助手」里展示。
 * 只数「真的发出去了的请求」，涵盖 解析/截图/对话/洞察/订阅识别 五个入口。
 */
object AiUsage {

    private const val PREFS = "shanji_ai_usage"

    private fun key(): String = java.time.YearMonth.now().toString()

    /** 记一次调用结果（ok=false 为失败/超时） */
    fun record(context: Context, ok: Boolean) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = key()
        val total = p.getInt("${k}_total", 0)
        val fail = p.getInt("${k}_fail", 0)
        p.edit()
            .putInt("${k}_total", total + 1)
            .putInt("${k}_fail", fail + (if (ok) 0 else 1))
            .apply()
    }

    /** 当前月（total, fail） */
    fun current(context: Context): Pair<Int, Int> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = key()
        return p.getInt("${k}_total", 0) to p.getInt("${k}_fail", 0)
    }
}
