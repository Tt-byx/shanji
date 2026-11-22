package com.shanji.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.shanji.app.data.Tx
import kotlinx.serialization.Serializable

/* 账单表：字段与领域模型 Tx 一一对应，另加同步预留列（M6 云同步用）
 * @Serializable：兼作云同步 DTO，与后端 user_tx 字段对齐 */
@Serializable
@Entity(tableName = "txs")
data class TxEntity(
    @PrimaryKey val id: String,
    val type: String,          // expense | income | transfer
    val amount: Double,
    val merchant: String,
    val category: String,
    val note: String,
    val ts: Long,
    val source: String,
    @ColumnInfo(defaultValue = "acc_cash") val accountId: String = "acc_cash",
    @ColumnInfo(defaultValue = "") val toAccountId: String = "",
    val updatedAt: Long,       // 本地最后修改时间（毫秒），云同步 LWW 依据
    val deleted: Int = 0,      // 软删标记：0 正常 1 已删
    val dirty: Int = 1         // 待同步标记：1 未同步 0 已同步
)

fun TxEntity.toDomain(): Tx = Tx(
    id = id, type = type, amount = amount, merchant = merchant,
    category = category, note = note, ts = ts, source = source,
    accountId = accountId, toAccountId = toAccountId
)

fun Tx.toEntity(updatedAt: Long = System.currentTimeMillis(), dirty: Int = 1): TxEntity =
    TxEntity(
        id = id, type = type, amount = amount, merchant = merchant,
        category = category, note = note, ts = ts, source = source,
        accountId = accountId, toAccountId = toAccountId,
        updatedAt = updatedAt, deleted = 0, dirty = dirty
    )
