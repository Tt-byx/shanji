package com.shanji.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.shanji.app.data.Account
import kotlinx.serialization.Serializable

/* 账户表：微信/支付宝/现金/银行卡等（兼作同步 DTO） */
@Serializable
@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,            // wechat | alipay | cash | bank
    val initialBalance: Double,
    val isDefault: Int = 0,
    val sortOrder: Int = 0,
    val updatedAt: Long,
    val deleted: Int = 0
)

fun AccountEntity.toDomain(): Account = Account(
    id = id, name = name, type = type,
    initialBalance = initialBalance, isDefault = isDefault == 1
)

fun Account.toEntity(updatedAt: Long = System.currentTimeMillis()): AccountEntity =
    AccountEntity(
        id = id, name = name, type = type,
        initialBalance = initialBalance,
        isDefault = if (isDefault) 1 else 0,
        sortOrder = sortOrder, updatedAt = updatedAt, deleted = 0
    )
