package com.shanji.app.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 规则学习：记住 用户最终保存的 完整规则（商户 + 金额区间 + 时段 + 账户）→ 分类。
 * 下次命中同条件时优先用学到的分类（覆盖 AI 建议），命中次数越多置信度越高，越用越准。
 * 纯本地 SharedPreferences（JSON），无网络；规则摘要会随 /api/ai/parse 一起发给 AI。
 */
@Serializable
data class CatRule(
    val m: String,          // 商户归一化（小写去空格）
    val aLo: Double,        // 金额下限（元）
    val aHi: Double,        // 金额上限（元）
    val hLo: Int,           // 时段下限（0~23 点）
    val hHi: Int,           // 时段上限（0~23 点）
    val acc: String,        // 账户 id；"" 表示不限账户
    val c: String,          // 分类 key
    val n: Int              // 命中次数（置信度）
)

object CategoryLearner {

    private const val PREFS = "shanji_category_learner"
    private const val KEY = "rules"
    private const val MAX_RULES = 300
    private val json = Json { ignoreUnknownKeys = true }

    private fun norm(merchant: String): String =
        merchant.trim().lowercase().replace(Regex("\\s+"), "")

    private fun load(context: Context): List<CatRule> {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY, null)
        if (raw == null) {
            // 迁移旧版「商户 → 分类」扁平存法（key=商户，value=分类）
            val legacy = sp.all.mapNotNull { (k, v) ->
                if (k == KEY || v !is String) null
                else CatRule(k, 0.0, 999999.0, 0, 23, "", v, 1)
            }
            if (legacy.isEmpty()) return emptyList()
            sp.edit().clear().putString(KEY, json.encodeToString(legacy)).apply()
            return legacy
        }
        return runCatching { json.decodeFromString<List<CatRule>>(raw) }.getOrDefault(emptyList())
    }

    private fun save(context: Context, rules: List<CatRule>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, json.encodeToString(rules.sortedByDescending { it.n }.take(MAX_RULES)))
            .apply()
    }

    /**
     * 预测分类：商户 → 命中金额区间 → 命中时段 → 命中账户，逐级收敛；
     * 某一级没命中就退回上一级，最后按命中次数取最稳的那条。
     */
    fun predict(
        context: Context,
        merchant: String,
        amount: Double = 0.0,
        hour: Int = -1,
        accountId: String = ""
    ): String? {
        val m = norm(merchant)
        if (m.isEmpty()) return null
        val same = load(context).filter { it.m == m }
        if (same.isEmpty()) return null

        val byAmount = same.filter { amount <= 0 || (amount >= it.aLo && amount <= it.aHi) }
            .ifEmpty { same }
        val byHour = if (hour in 0..23) {
            byAmount.filter { hour >= it.hLo && hour <= it.hHi }.ifEmpty { byAmount }
        } else byAmount
        val byAcc = if (accountId.isNotEmpty()) {
            byHour.filter { it.acc.isEmpty() || it.acc == accountId }.ifEmpty { byHour }
        } else byHour

        return byAcc.maxByOrNull { it.n }?.c
    }

    /** 保存账单时学习（以用户最终保存的为准）。同商户同分类合并，区间取并集，置信度累加 */
    fun learn(
        context: Context,
        merchant: String,
        category: String,
        amount: Double = 0.0,
        hour: Int = -1,
        accountId: String = ""
    ) {
        val m = norm(merchant)
        if (m.isEmpty() || category.isBlank()) return
        val rules = load(context).toMutableList()
        val same = rules.filter { it.m == m && it.c == category }
        val rest = rules.filterNot { it.m == m && it.c == category }

        val merged = same.reduceOrNull { a, b ->
            CatRule(
                m = m, aLo = minOf(a.aLo, b.aLo), aHi = maxOf(a.aHi, b.aHi),
                hLo = minOf(a.hLo, b.hLo), hHi = maxOf(a.hHi, b.hHi),
                acc = if (a.acc == b.acc) a.acc else "", c = category, n = a.n + b.n
            )
        }
        val updated = if (merged == null) {
            CatRule(
                m = m,
                aLo = if (amount > 0) amount else 0.0,
                aHi = if (amount > 0) amount else 999999.0,
                hLo = if (hour in 0..23) hour else 0,
                hHi = if (hour in 0..23) hour else 23,
                acc = accountId, c = category, n = 1
            )
        } else merged.copy(
            n = merged.n + 1,
            aLo = if (amount > 0) minOf(merged.aLo, amount) else merged.aLo,
            aHi = if (amount > 0) maxOf(merged.aHi, amount) else merged.aHi,
            hLo = if (hour in 0..23) minOf(merged.hLo, hour) else merged.hLo,
            hHi = if (hour in 0..23) maxOf(merged.hHi, hour) else merged.hHi,
            acc = when {
                merged.acc.isEmpty() || merged.acc == accountId -> merged.acc
                else -> ""   // 同规则出现过不同账户 → 放宽为不限账户
            }
        )
        save(context, rest + updated)
    }

    /**
     * 给 AI 的规则摘要（随 /api/ai/parse 一起发，让 AI 知道用户的历史习惯）。
     * 只发命中 2 次以上的、按置信度取前 N 条，控制请求体积。
     */
    fun aiRules(context: Context, limit: Int = 12): List<String> =
        load(context)
            .filter { it.n >= 2 && Categories.all.any { c -> c.key == it.c } }
            .sortedByDescending { it.n }
            .take(limit)
            .map { r ->
                val cond = buildList {
                    // 区间收窄过才发：全宽区间（999999）等于没条件，发了只会误导
                    if (r.aHi - r.aLo <= 200.0 && r.aHi < 999999.0) {
                        add("¥${fmtAmount(r.aLo)}-${fmtAmount(r.aHi)}")
                    }
                    if (r.hHi - r.hLo <= 8 && (r.hLo > 0 || r.hHi < 23)) {
                        add("${r.hLo}-${r.hHi}点")
                    }
                }
                val head = r.m + if (cond.isEmpty()) "" else "（${cond.joinToString("，")}）"
                head + " → ${r.c}"
            }
}
