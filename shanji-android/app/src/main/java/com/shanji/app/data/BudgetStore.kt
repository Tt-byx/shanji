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

/** 预算（月度循环）：scope=total 总预算；scope=category 分类预算 */
data class Budget(
    val scope: String,
    val category: String,
    val amount: Double
)

class BudgetStore(context: Context) {
    private val dao = AppDatabase.getInstance(context).budgetDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val list = mutableStateListOf<Budget>()

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

    fun total(): Double = list.firstOrNull { it.scope == "total" }?.amount ?: 0.0

    fun ofCategory(cat: String): Double =
        list.firstOrNull { it.scope == "category" && it.category == cat }?.amount ?: 0.0

    /** amount <= 0 表示删除该预算 */
    fun set(scope: String, category: String, amount: Double) {
        val budget = Budget(scope, category, amount)
        // G13：写库走 scope（IO），不再 runBlocking 卡主线程（注意：scope 参数遮蔽了属性，用 this@）
        // D2：notifyChanged 放到写库完成之后，否则小组件读到旧预算
        this@BudgetStore.scope.launch {
            if (amount <= 0) {
                dao.softDelete(
                    if (scope == "total") "total" else "cat_$category",
                    System.currentTimeMillis()
                )
            } else {
                dao.upsert(budget.toEntity())
            }
            AppStore.notifyChanged()
        }
    }
}
