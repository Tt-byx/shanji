package com.shanji.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.shanji.app.data.Recurring

/* 周期记账规则：每月固定日期自动生成一笔账单 */
@Entity(tableName = "recurring")
data class RecurringEntity(
    @PrimaryKey val id: String,
    val type: String,            // expense | income
    val amount: Double,
    val merchant: String,
    val category: String,
    val accountId: String,
    val dayOfMonth: Int,         // 1~28
    val enabled: Int = 1,
    val lastRunDate: String = "", // yyyy-MM-dd，上次生成日期
    val updatedAt: Long,
    val deleted: Int = 0
)

fun RecurringEntity.toDomain(): Recurring = Recurring(
    id = id, type = type, amount = amount, merchant = merchant,
    category = category, accountId = accountId, dayOfMonth = dayOfMonth,
    enabled = enabled == 1, lastRunDate = lastRunDate
)

fun Recurring.toEntity(updatedAt: Long = System.currentTimeMillis()): RecurringEntity =
    RecurringEntity(
        id = id, type = type, amount = amount, merchant = merchant,
        category = category, accountId = accountId, dayOfMonth = dayOfMonth,
        enabled = if (enabled) 1 else 0, lastRunDate = lastRunDate,
        updatedAt = updatedAt, deleted = 0
    )
