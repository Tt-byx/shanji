package com.shanji.app.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import com.shanji.app.data.db.AppDatabase
import com.shanji.app.data.db.RecurringEntity
import com.shanji.app.data.db.toDomain
import com.shanji.app.data.db.toEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/**
 * 固定支出（F8/F23 合并后的一张表）：每月/每年固定要花的钱。
 * autoRecord=true  → 周期记账：到日子自动生成账单（房租/通勤）
 * autoRecord=false → 订阅：只在到期前提醒，不生成账单（会员/话费）
 */
@Serializable
data class Recurring(
    val id: String,
    val type: String,          // expense | income
    val amount: Double,
    val merchant: String,
    val category: String,
    val accountId: String,
    val dayOfMonth: Int,       // 1~31（按当月实际天数夹取）
    val enabled: Boolean,
    val lastRunDate: String,   // yyyy-MM-dd
    val cycle: String = "monthly",   // monthly | yearly
    val dueMonth: Int = 0,           // yearly 到期月份 1~12
    val autoRecord: Boolean = true,  // true=自动记账 false=到期提醒
    val source: String = "manual"    // manual | ai | local | detected
)

/** 旧版 SharedPreferences 订阅 JSON（一次性迁入 Room 用，字段与 SubscriptionStore 时期一致） */
@Serializable
private data class LegacySub(
    val id: String,
    val name: String,
    val amount: Double,
    val category: String,
    val accountId: String = "acc_cash",
    val cycle: String = "monthly",
    val dayOfMonth: Int = 1,
    val dueMonth: Int = 0,
    val enabled: Boolean = true,
    val source: String = "ai"
)

class RecurringStore(context: Context) {
    private val dao = AppDatabase.getInstance(context).recurringDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val list = mutableStateListOf<Recurring>()

    init {
        scope.launch {
            // 旧版订阅存 SharedPreferences（F8）：首次启动一次性迁进 Room，迁完打标记防重复
            migrateLegacySubscriptions(context)
            dao.observeAll().collect { entities ->
                withContext(Dispatchers.Main) {
                    list.clear()
                    list.addAll(entities.map { it.toDomain() })
                }
            }
        }
    }

    /** shanji_subscriptions SP → recurring 表（autoRecord=0）；只迁一次，SP 保留作回滚保险 */
    private suspend fun migrateLegacySubscriptions(context: Context) {
        val prefs = context.getSharedPreferences("shanji_flags", Context.MODE_PRIVATE)
        if (prefs.getBoolean("migrated_sub_to_room", false)) return
        val sp = context.getSharedPreferences("shanji_subscriptions", Context.MODE_PRIVATE)
        val raw = sp.getString("list", null)
        if (!raw.isNullOrBlank()) {
            val subs = runCatching {
                Json { ignoreUnknownKeys = true }
                    .decodeFromString<List<LegacySub>>(raw)
            }.getOrDefault(emptyList())
            if (subs.isNotEmpty()) {
                dao.upsertAll(subs.map { s ->
                    RecurringEntity(
                        id = s.id, type = "expense", amount = s.amount, merchant = s.name,
                        category = s.category, accountId = s.accountId, dayOfMonth = s.dayOfMonth,
                        enabled = if (s.enabled) 1 else 0, lastRunDate = "",
                        cycle = s.cycle, dueMonth = s.dueMonth, autoRecord = 0,
                        source = s.source, updatedAt = System.currentTimeMillis(), deleted = 0
                    )
                })
                // G10：老版把 29~31 号压成了 28 号（数据不可还原），提示用户核对一次
                prefs.edit().putBoolean("sub_day_check_hint", true).apply()
            }
        }
        prefs.edit().putBoolean("migrated_sub_to_room", true).apply()
    }

    /** 新增固定支出；[autoRecord]=false 为订阅（仅提醒） */
    fun add(
        type: String, amount: Double, merchant: String, category: String,
        accountId: String, dayOfMonth: Int,
        cycle: String = "monthly", dueMonth: Int = 0, autoRecord: Boolean = true,
        source: String = "manual"
    ) {
        val rule = Recurring(
            id = "rc_" + UUID.randomUUID().toString().take(8),
            type = type, amount = round2(amount), merchant = merchant.trim().take(30),
            category = category, accountId = accountId,
            dayOfMonth = dayOfMonth.coerceIn(1, 31),
            enabled = true, lastRunDate = "",
            cycle = cycle, dueMonth = if (cycle == "yearly") dueMonth else 0,
            autoRecord = autoRecord, source = source
        )
        // G13：写库走 scope（IO），不再 runBlocking 卡主线程等 IO（切开关/增删不再掉帧）
        scope.launch { dao.upsert(rule.toEntity()) }
    }

    fun update(rule: Recurring) {
        scope.launch {
            dao.update(rule.copy(dayOfMonth = rule.dayOfMonth.coerceIn(1, 31)).toEntity())
        }
    }

    /** 直接入库给定的固定支出（识别结果确认时用，id 由识别方生成） */
    fun addAll(items: List<Recurring>) {
        if (items.isEmpty()) return
        scope.launch { dao.upsertAll(items.map { it.toEntity() }) }
    }

    fun setEnabled(rule: Recurring, enabled: Boolean) {
        scope.launch { dao.update(rule.copy(enabled = enabled).toEntity()) }
    }

    fun remove(id: String) {
        scope.launch { dao.softDelete(id, System.currentTimeMillis()) }
    }

    fun markRun(id: String, date: String) {
        scope.launch { dao.markRun(id, date, System.currentTimeMillis()) }
    }

    /** 直接读库（不依赖 Flow 加载时机），供调度器使用：只有 autoRecord=1 的规则才生成账单 */
    suspend fun listEnabled(): List<Recurring> = dao.listEnabled().map { it.toDomain() }

    /** 订阅到期提醒：未来 N 天内要扣款的订阅（直接读库，闹钟回调不依赖 UI 状态） */
    suspend fun dueWithin(days: Int, from: LocalDate = LocalDate.now()): List<Pair<Recurring, LocalDate>> {
        val end = from.plusDays(days.toLong())
        return dao.listActive().map { it.toDomain() }
            .filter { !it.autoRecord }
            .mapNotNull { s -> nextDue(s, from)?.let { s to it } }
            .filter { it.second <= end }
            .sortedBy { it.second }
    }

    /** 下次扣款日（今天或之后）；29~31 号按当月实际天数夹取 */
    fun nextDue(sub: Recurring, from: LocalDate = LocalDate.now()): LocalDate? {
        if (!sub.enabled) return null
        return when (sub.cycle) {
            "yearly" -> {
                val month = sub.dueMonth.coerceIn(1, 12)
                val ym = YearMonth.of(from.year, month)
                val thisYear = ym.atDay(sub.dayOfMonth.coerceAtMost(ym.lengthOfMonth()))
                if (thisYear.isBefore(from)) {
                    val nextYm = YearMonth.of(from.year + 1, month)
                    nextYm.atDay(sub.dayOfMonth.coerceAtMost(nextYm.lengthOfMonth()))
                } else thisYear
            }
            else -> {
                val ym = YearMonth.from(from)
                val thisMonth = ym.atDay(sub.dayOfMonth.coerceAtMost(ym.lengthOfMonth()))
                if (thisMonth.isBefore(from)) {
                    val nextYm = ym.plusMonths(1)
                    nextYm.atDay(sub.dayOfMonth.coerceAtMost(nextYm.lengthOfMonth()))
                } else thisMonth
            }
        }
    }
}
