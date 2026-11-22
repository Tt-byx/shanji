package com.shanji.app.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import com.shanji.app.data.db.AppDatabase
import com.shanji.app.data.db.toDomain
import com.shanji.app.data.db.toEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.UUID

/** 周期记账规则（每月固定日期自动生成） */
data class Recurring(
    val id: String,
    val type: String,          // expense | income
    val amount: Double,
    val merchant: String,
    val category: String,
    val accountId: String,
    val dayOfMonth: Int,       // 1~28
    val enabled: Boolean,
    val lastRunDate: String    // yyyy-MM-dd
)

class RecurringStore(context: Context) {
    private val dao = AppDatabase.getInstance(context).recurringDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val list = mutableStateListOf<Recurring>()

    init {
        scope.launch {
            dao.observeAll().collect { entities ->
                withContext(Dispatchers.Main) {
                    list.clear()
                    list.addAll(entities.map { it.toDomain() })
                }
            }
        }
    }

    fun add(
        type: String, amount: Double, merchant: String, category: String,
        accountId: String, dayOfMonth: Int
    ) {
        val rule = Recurring(
            id = "rc_" + UUID.randomUUID().toString().take(8),
            type = type, amount = round2(amount), merchant = merchant.trim().take(30),
            category = category, accountId = accountId,
            dayOfMonth = dayOfMonth.coerceIn(1, 28),
            enabled = true, lastRunDate = ""
        )
        runBlocking { dao.upsert(rule.toEntity()) }
    }

    fun update(rule: Recurring) {
        runBlocking { dao.update(rule.copy(dayOfMonth = rule.dayOfMonth.coerceIn(1, 28)).toEntity()) }
    }

    fun setEnabled(rule: Recurring, enabled: Boolean) {
        runBlocking { dao.update(rule.copy(enabled = enabled).toEntity()) }
    }

    fun remove(id: String) {
        runBlocking { dao.softDelete(id, System.currentTimeMillis()) }
    }

    fun markRun(id: String, date: String) {
        runBlocking { dao.markRun(id, date, System.currentTimeMillis()) }
    }

    /** 直接读库（不依赖 Flow 加载时机），供调度器使用 */
    suspend fun listEnabled(): List<Recurring> = dao.listEnabled().map { it.toDomain() }
}
