package com.shanji.app.data

import android.content.Context
import com.shanji.app.data.db.AppDatabase
import com.shanji.app.data.net.ApiFactory
import com.shanji.app.data.net.AuthRequest
import com.shanji.app.data.net.PullData
import com.shanji.app.data.net.PushRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SyncReport(val pushed: Int, val pulled: Int)

/**
 * 登录 + 云同步：
 * 同步顺序固定 push(dirty 行 + 账户 + 预算) → pull(since=lastSyncAt) → LWW 合并 → dirty 清零。
 * 合并规则：远端 updatedAt 严格大于本地才覆盖（本地更新则保留）。
 */
object SyncManager {

    private const val PREFS = "shanji_auth"

    /**
     * 把技术错误翻译成用户能懂的话（UX：绝不暴露状态码/英文术语）。
     * 单点映射，登录/注册/同步统一走这里（DX：新增错误只需改这一处）。
     */
    fun friendlyError(t: Throwable): String = when {
        // Retrofit 非 2xx（服务端用 HTTP 状态码表达业务错误，如登录 401）：
        // 优先用服务端已有的人话 message，取不到再按状态码兜底
        t is retrofit2.HttpException -> {
            val serverMsg = runCatching {
                org.json.JSONObject(t.response()?.errorBody()?.string() ?: "").optString("message")
            }.getOrNull().orEmpty()
            val technical = listOf("size", "blank", "must", "Exception", "http", "valid")
            when {
                serverMsg.isBlank() -> when (t.code()) {
                    401 -> "账号或密码不对，再检查一下"
                    409 -> "这个用户名已被注册了，换一个试试"
                    400 -> "输入的内容不符合要求，检查一下"
                    429 -> "操作太频繁了，歇一会儿再试"
                    in 500..599 -> "服务器开了小差，稍后再试"
                    else -> "操作没成功，稍后再试"
                }
                // 校验类英文/术语 message 翻译成人话
                technical.any { serverMsg.contains(it, ignoreCase = true) } &&
                serverMsg.contains("username", ignoreCase = true) ->
                    "用户名至少 3 个字符"
                technical.any { serverMsg.contains(it, ignoreCase = true) } &&
                serverMsg.contains("password", ignoreCase = true) ->
                    "密码至少 6 位"
                technical.any { serverMsg.contains(it, ignoreCase = true) } ->
                    "输入的内容不符合要求，检查一下"
                else -> serverMsg
            }
        }
        // 服务端业务 message（Bean Validation / BizException）
        t.message?.contains("username size") == true ||
        t.message?.contains("password size") == true ||
        t.message?.contains("must not be blank") == true ->
            "用户名至少 3 个字符，密码至少 6 位"
        t.message?.contains("已被注册") == true || t.message?.contains("已存在") == true ->
            "这个用户名已被注册了，换一个试试"
        t.message?.contains("用户名或密码") == true ->
            "账号或密码不对，再检查一下"
        t.message?.contains("尚未登录") == true ->
            "请先登录"
        // 网络层
        t is java.net.UnknownHostException || t.message?.contains("Unable to resolve") == true ->
            "连不上服务器，检查网络后重试"
        t is java.net.ConnectException || t.message?.contains("Failed to connect") == true ||
        t.message?.contains("timed out") == true || t is java.net.SocketTimeoutException ->
            "连不上服务器，稍后再试"
        // 服务端 5xx（ApiResponse code!=0 时 message 为空）
        t.message?.isEmpty() == true || t.message == "服务器内部错误" ->
            "服务器开了小差，稍后再试"
        else -> t.message?.takeIf { it.isNotBlank() && !it.contains("Exception") && !it.contains("http")
            } ?: "操作没成功，稍后再试"
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun token(context: Context): String? = prefs(context).getString("token", null)

    fun username(context: Context): String = prefs(context).getString("username", "") ?: ""

    fun isLoggedIn(context: Context): Boolean = token(context) != null

    fun lastSyncAt(context: Context): Long = prefs(context).getLong("lastSyncAt", 0)

    private fun saveSession(context: Context, token: String, username: String) {
        prefs(context).edit()
            .putString("token", token)
            .putString("username", username)
            .apply()
    }

    fun logout(context: Context) {
        prefs(context).edit()
            .remove("token")
            .remove("username")
            .remove("lastSyncAt")
            .apply()
    }

    /** 注册并登录；失败抛出的 message 已是人话（见 friendlyError） */
    suspend fun register(context: Context, username: String, password: String): Unit =
        withContext(Dispatchers.IO) {
            runCatching {
                val resp = ApiFactory.auth(context)
                    .register(AuthRequest(username, password))
                if (resp.code != 0 || resp.data == null) {
                    error(resp.message.ifEmpty { "" })
                }
                saveSession(context, resp.data.token, resp.data.username)
            }.getOrElse { throw Exception(friendlyError(it)) }
        }

    suspend fun login(context: Context, username: String, password: String): Unit =
        withContext(Dispatchers.IO) {
            runCatching {
                val resp = ApiFactory.auth(context)
                    .login(AuthRequest(username, password))
                if (resp.code != 0 || resp.data == null) {
                    error(resp.message.ifEmpty { "" })
                }
                saveSession(context, resp.data.token, resp.data.username)
            }.getOrElse { throw Exception(friendlyError(it)) }
        }

    /** 连通性测试（登录页按钮用）；失败 message 已是人话 */
    suspend fun testConnection(context: Context): String = withContext(Dispatchers.IO) {
        runCatching {
            val health = ApiFactory.sync(context).health()
            if (health.status == "UP") "连接成功：${health.service}" else "连接失败，稍后再试"
        }.getOrElse { friendlyError(it) }
    }

    suspend fun syncNow(context: Context): Result<SyncReport> = withContext(Dispatchers.IO) {
        runCatching {
            val bearer = "Bearer " + (token(context) ?: error("尚未登录"))
            val db = AppDatabase.getInstance(context)
            val api = ApiFactory.sync(context)

            // 1) push：脏账单 + 全量账户/预算/固定支出（列表很小，全量推由服务端 LWW 裁决）
            val dirty = db.txDao().listDirty()
            val pushResp = api.push(
                bearer,
                PushRequest(
                    txs = dirty,
                    accounts = db.accountDao().listAll(),
                    budgets = db.budgetDao().listAll(),
                    fixeds = db.recurringDao().listAllWithDeleted()   // 含墓碑：本地删除要能传播到其它设备
                )
            )
            if (pushResp.code != 0) error(pushResp.message.ifEmpty { "推送失败" })

            // 2) pull：取 since 之后的远端变更（含软删行）
            val pullResp = api.pull(bearer, lastSyncAt(context))
            if (pullResp.code != 0 || pullResp.data == null) {
                error(pullResp.message.ifEmpty { "拉取失败" })
            }
            val data: PullData = pullResp.data

            // 3) LWW 合并
            for (r in data.txs) {
                val local = db.txDao().get(r.id)
                if (local == null || r.updatedAt > local.updatedAt) {
                    db.txDao().insert(r.copy(dirty = 0))
                }
            }
            for (r in data.accounts) {
                val local = db.accountDao().get(r.id)
                if (local == null || r.updatedAt > local.updatedAt) {
                    db.accountDao().insert(r)
                }
            }
            for (r in data.budgets) {
                val local = db.budgetDao().get(r.id)
                if (local == null || r.updatedAt > local.updatedAt) {
                    db.budgetDao().upsert(r)
                }
            }
            for (r in data.fixeds) {
                val local = db.recurringDao().get(r.id)
                if (local == null || r.updatedAt > local.updatedAt) {
                    db.recurringDao().upsert(r)   // 含远端软删墓碑（deleted 原样落库）
                }
            }

            // 4) 本地脏标记清零 + 记录同步水位
            if (dirty.isNotEmpty()) db.txDao().clearDirty(dirty.map { it.id })
            prefs(context).edit().putLong("lastSyncAt", data.serverTime).apply()

            AppStore.notifyChanged()
            SyncReport(dirty.size, data.txs.size)
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(Exception(friendlyError(it))) }
        )
    }
}
