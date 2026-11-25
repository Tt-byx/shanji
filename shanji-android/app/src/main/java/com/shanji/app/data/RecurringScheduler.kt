package com.shanji.app.data

import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.ZoneId

/**
 * 周期记账执行器：每次 App 启动时检查并补记。
 * 局限（个人项目取舍）：不依赖后台任务，不打开 App 不会生成。
 */
object RecurringScheduler {

    fun checkAndRun(store: TxStore, accounts: AccountStore, recurring: RecurringStore) {
        runBlocking {
            val rules = recurring.listEnabled()
            val today = LocalDate.now()
            for (r in rules) {
                if (!r.enabled) continue
                // 先夹取再比较（D1）：原守卫用未夹取的 dayOfMonth 比较，29/30/31 号规则
                // 在 4/6/9/11 月与 2 月永远到不了 → 整月漏记，夹取分支不可达
                val runDate = today.withDayOfMonth(r.dayOfMonth.coerceAtMost(today.lengthOfMonth()))
                if (today.isBefore(runDate)) continue
                // 本月已生成则跳过
                if (r.lastRunDate >= runDate.toString()) continue

                val ts = runDate.atTime(12, 0)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val accountId = if (accounts.list.any { it.id == r.accountId }) r.accountId
                    else accounts.defaultId()
                store.add(
                    newTx(
                        r.type, r.amount, r.merchant, r.category,
                        note = "周期自动记账", ts = ts, source = "recurring",
                        accountId = accountId
                    )
                )
                recurring.markRun(r.id, runDate.toString())
            }
        }
    }
}
