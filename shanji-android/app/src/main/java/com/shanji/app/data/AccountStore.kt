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

/* 账户存储：Room + Compose 状态，与 TxStore 同模式 */
class AccountStore(context: Context) {
    private val dao = AppDatabase.getInstance(context).accountDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val list = mutableStateListOf<Account>()

    init {
        scope.launch {
            dao.observeAll().collect { entities ->
                withContext(Dispatchers.Main) {
                    list.clear()
                    list.addAll(entities.map { it.toDomain() })
                }
            }
        }
        // 全新安装（直接建 v2 库，不走迁移）时补种 4 条默认账户
        scope.launch {
            if (dao.count() == 0) seedDefaults()
        }
    }

    private fun seedDefaults() {
        val now = System.currentTimeMillis()
        val defaults = listOf(
            Account("acc_wechat", "微信", "wechat", 0.0, false, 0),
            Account("acc_alipay", "支付宝", "alipay", 0.0, false, 1),
            Account("acc_cash", "现金", "cash", 0.0, true, 2),
            Account("acc_bank", "银行卡", "bank", 0.0, false, 3)
        )
        runBlocking { dao.insertAll(defaults.map { it.toEntity(now) }) }
    }

    fun defaultId(): String =
        list.firstOrNull { it.isDefault }?.id ?: list.firstOrNull()?.id ?: "acc_cash"

    fun nameOf(id: String): String = list.firstOrNull { it.id == id }?.name ?: "现金"

    fun of(id: String): Account? = list.firstOrNull { it.id == id }

    fun add(name: String, type: String, initialBalance: Double) {
        val acc = Account(
            id = "acc_" + UUID.randomUUID().toString().take(8),
            name = name.trim().take(12), type = type,
            initialBalance = initialBalance, isDefault = false,
            sortOrder = list.size
        )
        runBlocking { dao.insert(acc.toEntity()) }
    }

    fun update(account: Account) {
        runBlocking { dao.update(account.copy(name = account.name.trim().take(12)).toEntity()) }
    }

    fun remove(id: String) {
        runBlocking { dao.softDelete(id, System.currentTimeMillis()) }
    }

    fun setDefault(id: String) {
        runBlocking {
            dao.clearDefault()
            dao.setDefault(id, System.currentTimeMillis())
        }
    }
}
