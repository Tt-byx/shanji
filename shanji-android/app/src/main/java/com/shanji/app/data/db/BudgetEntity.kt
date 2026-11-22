package com.shanji.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.shanji.app.data.Budget
import kotlinx.serialization.Serializable

/* 预算表：一行一个预算（id 字面量：total / cat_dining），月度循环，不做按月快照（兼作同步 DTO） */
@Serializable
@Entity(tableName = "budgets")
data class BudgetEntity(
    @PrimaryKey val id: String,
    val scope: String,          // total | category
    val category: String = "",
    val amount: Double,
    val updatedAt: Long,
    val deleted: Int = 0
)

fun BudgetEntity.toDomain(): Budget =
    Budget(scope = scope, category = category, amount = amount)

fun Budget.toEntity(updatedAt: Long = System.currentTimeMillis()): BudgetEntity =
    BudgetEntity(
        id = if (scope == "total") "total" else "cat_$category",
        scope = scope, category = category, amount = amount,
        updatedAt = updatedAt, deleted = 0
    )
