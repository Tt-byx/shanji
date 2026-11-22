package com.shanji.app.data

import android.content.Context

/**
 * 自动分类学习：记住用户手动改过的 商户→分类。
 * 下次同一商户记账时优先用学到的分类（覆盖 AI 建议），越用越准。
 * 纯本地 SharedPreferences，无网络。
 */
object CategoryLearner {

    private const val PREFS = "shanji_category_learner"

    /** 商户归一化：去空格、转小写，避免「瑞幸」「瑞 幸」学成两条 */
    private fun norm(merchant: String): String =
        merchant.trim().lowercase().replace(Regex("\\s+"), "")

    fun predict(context: Context, merchant: String): String? {
        val key = norm(merchant)
        if (key.isEmpty()) return null
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key, null)
    }

    /** 保存账单时学习（用户可能手动改过分类，以最终保存的为准） */
    fun learn(context: Context, merchant: String, category: String) {
        val key = norm(merchant)
        if (key.isEmpty() || category.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(key, category).apply()
    }
}
