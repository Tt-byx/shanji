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

    // G13：写库走 scope（IO），不再 runBlocking 卡主线程
    private suspend fun seedDefaults() {
        // D10：种子数据 updatedAt=0 —— 只是出厂默认值，不应在同步 LWW 里
        // 被当成"最新变更"去覆盖其它设备/服务端已有的同 id 账户
        val defaults = listOf(
            Account("acc_wechat", "微信", "wechat", 0.0, false, 0),
            Account("acc_alipay", "支付宝", "alipay", 0.0, false, 1),
            Account("acc_cash", "现金", "cash", 0.0, true, 2),
            Account("acc_bank", "银行卡", "bank", 0.0, false, 3)
        )
        dao.insertAll(defaults.map { it.toEntity(0L) })
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
            // D11：取现有最大 sortOrder+1（list.size 在冷启动未回流时是 0，会撞第一条）
            sortOrder = (list.maxOfOrNull { it.sortOrder } ?: -1) + 1
        )
        scope.launch { dao.insert(acc.toEntity()) }
    }

    fun update(account: Account) {
        scope.launch { dao.update(account.copy(name = account.name.trim().take(12)).toEntity()) }
    }

    fun remove(id: String) {
        scope.launch { dao.softDelete(id, System.currentTimeMillis()) }
    }

    fun setDefault(id: String) {
        scope.launch {
            dao.clearDefault()
            dao.setDefault(id, System.currentTimeMillis())
        }
    }
}
