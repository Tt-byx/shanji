package com.shanji.app.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 闹钟接收器：周报 / 每日检查。
 * onReceive 必须立刻返回，用 goAsync() 撑住进程，再丢到 IO 协程里跑网络+读库；
 * 30s 上限：服务端实测 AI 耗时 3.2~8.7s，原 8s 会掐掉已计费的调用（拿到的是本地模板文案）；
 * 后台广播 ANR 上限 60s，30s 留足余量。超时就放弃本轮，下次闹钟再来。
 */
class AiAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getStringExtra("kind") ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(30_000) { ProactiveAi.handle(context, kind) }
                    ?: android.util.Log.w("SHANJI", "proactive $kind 超时，本轮跳过")
            } catch (t: Throwable) {
                android.util.Log.w("SHANJI", "proactive $kind 失败: $t")
            } finally {
                pending.finish()
            }
        }
    }
}
