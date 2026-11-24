package com.shanji.app.data

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.shanji.app.MainActivity
import com.shanji.app.R
import com.shanji.app.data.db.AppDatabase
import com.shanji.app.data.db.toDomain
import kotlinx.coroutines.withTimeoutOrNull
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * 主动式 AI 推送：不等用户问，到点主动给结论。
 *  - 每周一 9:00 周报（本周花了多少 / 比上周 / 哪类超了）
 *  - 每天 8:30 检查：超支预警（带 AI 给的省钱方案）、待归类账单、订阅到期
 *
 * 实现取舍（与 RecurringScheduler 一致）：不引 WorkManager，用 AlarmManager 的
 * setAndAllowWhileIdle——闹钟在系统里，关掉 App 也会按时触发；只在重新开机后丢失，
 * 下次打开 App 会自动重排。
 */
object ProactiveAi {

    private const val CHANNEL_ID = "shanji_ai"
    private const val PREFS = "shanji_proactive"
    private const val REQ_WEEKLY = 4101
    private const val REQ_DAILY = 4102

    private const val ID_WEEKLY = 5101
    private const val ID_OVER_TOTAL = 5102
    private const val ID_UNCAT = 5103
    private const val ID_CAT_BASE = 5200   // + 分类序号
    private const val ID_DUE_BASE = 5300   // + 订阅 id hash

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean("enabled", true)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("enabled", on).apply()
        if (on) schedule(context) else cancel(context)
    }

    /* ---------------- 闹钟 ---------------- */

    /** 下一个满足条件、且时间还没到的时刻 */
    private fun nextAt(hour: Int, minute: Int, match: (LocalDate) -> Boolean): Long {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val today = LocalDate.now(zone)
        for (i in 0..14) {
            val d = today.plusDays(i.toLong())
            if (!match(d)) continue
            val t = d.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
            if (t > now) return t
        }
        return now + 24 * 3600_000L
    }

    private fun pending(context: Context, kind: String, req: Int): PendingIntent {
        val intent = Intent(context, AiAlarmReceiver::class.java).putExtra("kind", kind)
        return PendingIntent.getBroadcast(
            context, req, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 每次 App 启动都会调：把周报和每日检查重排到下一次 */
    fun schedule(context: Context) {
        if (!enabled(context)) { cancel(context); return }
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, nextAt(9, 0) { it.dayOfWeek == DayOfWeek.MONDAY },
            pending(context, "weekly", REQ_WEEKLY)
        )
        am.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, nextAt(8, 30) { true },
            pending(context, "daily", REQ_DAILY)
        )
    }

    private fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(context, "weekly", REQ_WEEKLY))
        am.cancel(pending(context, "daily", REQ_DAILY))
    }

    /* ---------------- 闹钟回调入口（由 AiAlarmReceiver 调，已切到 IO） ---------------- */

    suspend fun handle(context: Context, kind: String) {
        try {
            AppStore.init(context)   // 进程可能刚被闹钟拉起，存储还没初始化
            when (kind) {
                "weekly" -> weeklyReport(context)
                "daily" -> dailyChecks(context)
            }
        } finally {
            runCatching { schedule(context) }   // 排下一次
        }
    }

    /* ---------------- 每周一：周报 ---------------- */

    private suspend fun weeklyReport(context: Context) {
        val p = prefs(context)
        val today = LocalDate.now().toString()
        if (p.getString("lastWeekly", "") == today) return

        val db = AppDatabase.getInstance(context)
        val txs = db.txDao().listAll().map { it.toDomain() }
        val (cur, prev, diff) = Ledger.weekOverWeek(txs)
        if (cur <= 0 && prev <= 0) return   // 没数据不打扰

        val ym = YearMonth.now()
        val monthSpent = BudgetCalc.spentTotal(txs, ym)
        val totalBudget = db.budgetDao().listAll().map { it.toDomain() }
            .firstOrNull { it.scope == "total" }?.amount ?: 0.0
        val top = Ledger.categoryBreakdown(Ledger.monthTx(txs, ym)).firstOrNull()

        val stats = buildString {
            append("本周支出¥").append(fmtAmount(cur))
            append("，上周¥").append(fmtAmount(prev))
            append(if (diff >= 0) "，多花¥" else "，少花¥").append(fmtAmount(kotlin.math.abs(diff)))
            append('\n')
            if (top != null) {
                append("本月最大头：").append(top.cat.label).append('¥')
                append(fmtAmount(top.amount)).append("（占").append(top.pct).append("%）\n")
            }
            if (totalBudget > 0) {
                append("本月已花¥").append(fmtAmount(monthSpent))
                append("/总预算¥").append(fmtAmount(totalBudget))
            }
        }
        val ai = askAi(context, "weekly_report", stats)
        p.edit().putString("lastWeekly", today).apply()
        notify(
            context, ID_WEEKLY, "本周账单周报",
            (ai ?: fallbackWeekly(cur, prev, diff, top)).trim(),
            nav = "chat"
        )
    }

    private fun fallbackWeekly(cur: Double, prev: Double, diff: Double, top: CatShare?): String {
        val trend = when {
            prev <= 0 -> "上周没有支出记录可比"
            diff > 0 -> "比上周多花 ¥${fmtAmount(diff)}"
            else -> "比上周少花 ¥${fmtAmount(-diff)}"
        }
        val head = top?.let { "，最大头是「${it.cat.label}」¥${fmtAmount(it.amount)}" } ?: ""
        return "本周花了 ¥${fmtAmount(cur)}，$trend$head。"
    }

    /* ---------------- 每天：超支 / 待归类 / 订阅到期 ---------------- */

    private suspend fun dailyChecks(context: Context) {
        val db = AppDatabase.getInstance(context)
        val txs = db.txDao().listAll().map { it.toDomain() }
        val p = prefs(context)
        val ym = YearMonth.now()
        val ymKey = ym.toString()

        val budgets = db.budgetDao().listAll().map { it.toDomain() }

        // 1) 总预算超支 10% → 每月提醒一次，带一条能落地的省钱建议
        val totalBudget = budgets.firstOrNull { it.scope == "total" }?.amount ?: 0.0
        val monthSpent = BudgetCalc.spentTotal(txs, ym)
        if (totalBudget > 0 && monthSpent > totalBudget * 1.1 && p.getString("over_total", "") != ymKey) {
            p.edit().putString("over_total", ymKey).apply()
            val over = monthSpent - totalBudget
            val stats = "本月总预算¥${fmtAmount(totalBudget)}，已花¥${fmtAmount(monthSpent)}，超¥${fmtAmount(over)}。" +
                "本月分类支出：" + Ledger.categoryBreakdown(Ledger.monthTx(txs, ym))
                    .joinToString("、") { "${it.cat.label}¥${fmtAmount(it.amount)}" }
            val ai = askAi(context, "budget_over", stats)
            notify(
                context, ID_OVER_TOTAL, "⚠ 本月总预算已超 ¥${fmtAmount(over)}",
                (ai ?: "已花 ¥${fmtAmount(monthSpent)} / 预算 ¥${fmtAmount(totalBudget)}，先停一停非必要支出。").trim()
            )
        }

        // 2) 分类预算超支 10% → 每月每类一次
        budgets.filter { it.scope == "category" && it.amount > 0 }.forEachIndexed { i, b ->
            val spent = BudgetCalc.spentOf(txs, ym, b.category)
            if (spent <= b.amount * 1.1) return@forEachIndexed
            val key = "over_${b.category}"
            if (p.getString(key, "") == ymKey) return@forEachIndexed
            p.edit().putString(key, ymKey).apply()
            val label = Categories.of(b.category).label
            val over = spent - b.amount
            val ai = askAi(
                context, "budget_over",
                "${label}预算¥${fmtAmount(b.amount)}，本月已花¥${fmtAmount(spent)}，超¥${fmtAmount(over)}。" +
                    "给出一条最能省下这笔的建议，20字以内。"
            )
            notify(
                context, ID_CAT_BASE + i, "⚠ ${label}超支 ¥${fmtAmount(over)}",
                (ai ?: "已花 ¥${fmtAmount(spent)} / 预算 ¥${fmtAmount(b.amount)}，${label}这月得收着点。").trim()
            )
        }

        // 3) 没分类的账单攒多了 → 提醒用 AI 批量归类
        val uncat = Ledger.monthTx(txs, ym).count { it.type == "expense" && it.category == "other" }
        if (uncat >= 3 && p.getString("uncat", "") != ymKey) {
            p.edit().putString("uncat", ymKey).apply()
            notify(
                context, ID_UNCAT, "本月有 $uncat 笔账单归到了「其他」",
                "打开 AI 问账本，让它按你的习惯重新分分类。", nav = "chat"
            )
        }

        // 4) 订阅明天/今天扣款 → 提醒
        AppStore.subscriptions.dueWithin(1).forEach { (sub, date) ->
            val marker = date.toString()
            val key = "due_${sub.id}"
            if (p.getString(key, "") == marker) return@forEach
            p.edit().putString(key, marker).apply()
            val whenText = if (date == LocalDate.now()) "今天" else "明天"
            notify(
                context,
                ID_DUE_BASE + (sub.id.hashCode().and(0x3ff)),
                "「${sub.name}」${whenText}扣款 ¥${fmtAmount(sub.amount)}",
                "每月${sub.dayOfMonth}号 · ${Categories.of(sub.category).label} · 上次识别约¥${fmtAmount(sub.amount)}/月"
            )
        }
    }

    /* ---------------- AI 文案（超时就退回本地规则文案） ---------------- */

    private suspend fun askAi(context: Context, kind: String, stats: String): String? =
        withTimeoutOrNull(8_000) {
            runCatching { AiClient.insight(context, kind, emptyList(), stats) }.getOrNull()
        }?.takeIf { it.isNotBlank() }

    /* ---------------- 通知 ---------------- */

    private fun notify(context: Context, id: Int, title: String, text: String, nav: String? = null) {
        if (!enabled(context)) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "AI 提醒", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            nav?.let { putExtra("nav", it) }
        }
        val pi = PendingIntent.getActivity(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shanji)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(id, n) }
    }
}
