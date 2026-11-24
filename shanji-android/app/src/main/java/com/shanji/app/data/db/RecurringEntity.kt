package com.shanji.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.shanji.app.data.Recurring
import kotlinx.serialization.Serializable

/* 固定支出：每月/每年固定要花的钱，一张表两类（F8/F23 合并）
 * autoRecord=1 → 周期记账，到日子自动生成账单
 * autoRecord=0 → 订阅，只在到期前提醒，不记账
 * @Serializable：兼作云同步 DTO，与后端 user_fixed 字段对齐 */
@Entity(tableName = "recurring")
@Serializable
data class RecurringEntity(
    @PrimaryKey val id: String,
    val type: String,            // expense | income
    val amount: Double,
    val merchant: String,
    val category: String,
    val accountId: String,
    val dayOfMonth: Int,         // 1~31，扣款/生成时按当月实际天数夹取
    val enabled: Int = 1,
    val lastRunDate: String = "", // yyyy-MM-dd，上次生成日期（仅 autoRecord=1 用）
    val cycle: String = "monthly",     // monthly | yearly（订阅支持年付）
    val dueMonth: Int = 0,             // yearly 到期月份 1~12；monthly 为 0
    val autoRecord: Int = 1,           // 1=自动生成账单（周期规则） 0=仅到期提醒（订阅）
    val source: String = "manual",     // manual=手动 | ai=AI识别 | local=规则识别 | detected=历史
    val updatedAt: Long,
    val deleted: Int = 0
)

fun RecurringEntity.toDomain(): Recurring = Recurring(
    id = id, type = type, amount = amount, merchant = merchant,
    category = category, accountId = accountId, dayOfMonth = dayOfMonth,
    enabled = enabled == 1, lastRunDate = lastRunDate,
    cycle = cycle, dueMonth = dueMonth, autoRecord = autoRecord == 1, source = source
)

fun Recurring.toEntity(updatedAt: Long = System.currentTimeMillis()): RecurringEntity =
    RecurringEntity(
        id = id, type = type, amount = amount, merchant = merchant,
        category = category, accountId = accountId, dayOfMonth = dayOfMonth,
        enabled = if (enabled) 1 else 0, lastRunDate = lastRunDate,
        cycle = cycle, dueMonth = dueMonth,
        autoRecord = if (autoRecord) 1 else 0, source = source,
        updatedAt = updatedAt, deleted = 0
    )
