package com.shanji.app.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import com.shanji.app.data.db.AppDatabase
import com.shanji.app.data.db.toDomain
import com.shanji.app.data.db.toEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.random.Random

/* ---------------- 数据模型 ---------------- */
@Serializable
data class Tx(
    val id: String,
    val type: String,          // expense | income | transfer
    val amount: Double,        // 元，两位小数
    val merchant: String,
    val category: String,
    val note: String = "",
    val ts: Long,              // 毫秒时间戳
    val source: String = "manual",  // manual | share
    val accountId: String = "acc_cash",   // 支出付款/收入进账/转出 账户
    val toAccountId: String = ""          // 转入账户（仅 transfer）
)

/** 账户：微信/支付宝/现金/银行卡 */
data class Account(
    val id: String,
    val name: String,
    val type: String,          // wechat | alipay | cash | bank
    val initialBalance: Double,
    val isDefault: Boolean,
    val sortOrder: Int = 0
) {
    companion object {
        val TYPE_LABELS = mapOf(
            "wechat" to "微信", "alipay" to "支付宝", "cash" to "现金", "bank" to "银行卡"
        )
    }
}

fun typeLabel(type: String): String = Account.TYPE_LABELS[type] ?: type

data class Cat(
    val key: String, val label: String, val color: Long, val bg: Long
)

object Categories {
    // iOS 系统色板：低饱和、成对浅底，配合 grouped background 分层
    val all = listOf(
        Cat("dining", "餐饮", 0xFFFF9500, 0xFFFFF3E6),
        Cat("transport", "交通", 0xFF30B0C7, 0xFFE5F6F9),
        Cat("shopping", "购物", 0xFFFF2D55, 0xFFFDE8EE),
        Cat("daily", "日用", 0xFF5856D6, 0xFFEBEAFB),
        Cat("entertainment", "娱乐", 0xFFAF52DE, 0xFFF5E9FB),
        Cat("health", "医疗", 0xFFFF3B30, 0xFFFDEAE9),
        Cat("education", "学习", 0xFF34C759, 0xFFE8F8EE),
        Cat("other", "其他", 0xFF8E8E93, 0xFFF2F2F7)
    )
    fun of(key: String): Cat = all.firstOrNull { it.key == key }
        ?: all.first { it.key == "other" }
}

fun round2(n: Double): Double = Math.round(n * 100) / 100.0

fun newTx(
    type: String, amount: Double, merchant: String, category: String,
    note: String = "", ts: Long = System.currentTimeMillis(), source: String = "manual",
    accountId: String = "acc_cash", toAccountId: String = ""
): Tx = Tx(
    id = "tx_" + UUID.randomUUID().toString().take(8),
    type = type, amount = round2(amount), merchant = merchant.trim().take(30),
    category = category, note = note.trim().take(100), ts = ts, source = source,
    accountId = accountId, toAccountId = toAccountId
)

/* ---------------- 存储层：Room 数据库 + Compose 状态 ----------------
 * 对外 API（txs/add/update/remove/clear/replaceAll/exportJson/seedDemo）保持不变，
 * 内部由 tx.json 文件改为 Room；UI 调用点无需改动。 */
class TxStore(context: Context) {
    private val prefs = context.getSharedPreferences("shanji_flags", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val dao = AppDatabase.getInstance(context).txDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val txs = mutableStateListOf<Tx>()

    init {
        // 首次安装的一次性工作（文件迁移 + 演示数据 80 行入库）放 IO 且顺序执行，
        // 避免主线程 runBlocking 卡启动/掉帧，也避免迁移与播种竞态
        scope.launch {
            migrateLegacyFile(context)
            if (!prefs.getBoolean("seeded", false)) seedDemo()
        }
        // 数据库 -> 状态列表（单向数据流，所有写操作经由 Room 触发刷新）
        scope.launch {
            dao.observeAll().collect { list ->
                withContext(Dispatchers.Main) {
                    txs.clear()
                    txs.addAll(list.map { it.toDomain() })
                    // 数据真正到达才 bump：统计页 remember(version) 据此重算
                    AppStore.version.value++
                }
            }
        }
    }

    /** tx.json 存量数据一次性导入 Room，原文件改名 .bak 保留作回滚保险 */
    private fun migrateLegacyFile(context: Context) {
        if (prefs.getBoolean("migrated_to_room", false)) return
        val file = context.getFileStreamPath("tx.json")
        if (file.exists()) {
            runBlocking {
                val list = runCatching {
                    json.decodeFromString<List<Tx>>(file.readText())
                }.getOrDefault(emptyList())
                if (list.isNotEmpty() && dao.count() == 0) {
                    dao.insertAll(list.map { it.toEntity() })
                }
            }
            file.renameTo(context.getFileStreamPath("tx.json.bak"))
            // 已有存量数据的用户不再被播种演示数据
            prefs.edit().putBoolean("seeded", true).apply()
        }
        prefs.edit().putBoolean("migrated_to_room", true).apply()
    }

    // 写操作全部走 IO 协程：主线程只改 UI，Room 写入由 observeAll 流回刷
    fun add(tx: Tx) { scope.launch { dao.insert(tx.toEntity()) }; AppStore.notifyChanged() }
    fun update(tx: Tx) { scope.launch { dao.update(tx.toEntity()) }; AppStore.notifyChanged() }
    fun remove(id: String) {
        scope.launch { dao.delete(id, System.currentTimeMillis()) }
        AppStore.notifyChanged()
    }
    fun clear() {
        scope.launch { dao.softClear(System.currentTimeMillis()) }
        AppStore.notifyChanged()
    }
    fun replaceAll(list: List<Tx>) {
        scope.launch {
            // 软清空（墓碑）→ 恢复的行覆盖墓碑，未包含的行保持删除态并可同步传播
            dao.softClear(System.currentTimeMillis())
            dao.insertAll(list.map { it.toEntity() })
        }
        AppStore.notifyChanged()
    }
    fun exportJson(): String = json.encodeToString(txs.toList())

    /** 演示数据：近 42 天，每天 1~3 笔，固定种子可复现；并标记已初始化 */
    fun seedDemo() {
        prefs.edit().putBoolean("seeded", true).apply()
        val rnd = Random(20261008)
        val list = mutableListOf<Tx>()
        val today = LocalDate.now()
        val now = LocalDateTime.now()
        val food = listOf("瑞幸咖啡", "美团外卖", "学校二食堂", "蜜雪冰城", "兰州拉面")
        val foodNote = listOf("下午提神", "加班晚餐", "午饭", "和室友拼单", "晚饭")
        val maxHour = if (now.hour <= 8) 8 else now.hour - 1
        for (d in 0 until 42) {
            val n = 1 + rnd.nextInt(3)
            repeat(n) {
                val date = today.minusDays(d.toLong())
                var hour = 8 + rnd.nextInt(13)
                if (d == 0 && hour > maxHour) hour = maxHour
                val ts = date.atTime(hour, rnd.nextInt(60))
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val r = rnd.nextDouble()
                val merchant: String
                val cat: String
                val amount: Double
                val note: String
                when {
                    r < 0.42 -> { merchant = food.random(rnd); cat = "dining"; amount = 6.0 + rnd.nextDouble() * 40; note = foodNote.random(rnd) }
                    r < 0.62 -> { merchant = listOf("地铁通勤", "公交", "共享单车").random(rnd); cat = "transport"; amount = 1.5 + rnd.nextDouble() * 6.5; note = "" }
                    r < 0.78 -> { merchant = listOf("美团外卖", "学校二食堂").random(rnd); cat = "dining"; amount = 12.0 + rnd.nextDouble() * 28; note = "备注：外卖" }
                    r < 0.88 -> { merchant = listOf("屈臣氏", "淘宝", "拼多多").random(rnd); cat = "shopping"; amount = 19.0 + rnd.nextDouble() * 149; note = "备注：日用品" }
                    r < 0.95 -> { merchant = listOf("腾讯视频会员", "网易云黑胶", "Steam").random(rnd); cat = "entertainment"; amount = 15.0 + rnd.nextDouble() * 83; note = "" }
                    else -> { merchant = listOf("校园超市", "水果店").random(rnd); cat = "daily"; amount = 9.0 + rnd.nextDouble() * 46; note = "" }
                }
                val src = if (rnd.nextDouble() > 0.5) "share" else "manual"
                list.add(newTx("expense", amount, merchant, cat, note, ts, src))
            }
        }
        // 每月 1 号生活费收入
        for (m in 0..1) {
            val date = today.withDayOfMonth(1).minusMonths(m.toLong())
            val ts = date.atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            list.add(newTx("income", 2500.0, "生活费", "other", "爸妈打的生活费", ts))
        }
        replaceAll(list)
    }
}

/** 全局存储单例：MainActivity 与 ShareCaptureActivity 都可能先启动，各自 onCreate 时初始化 */
object AppStore {
    lateinit var store: TxStore
        private set
    lateinit var accounts: AccountStore
        private set
    lateinit var budgets: BudgetStore
        private set
    lateinit var recurring: RecurringStore
        private set
    private lateinit var appContext: android.content.Context

    /** 账单数据版本：txs 每次从 Room 刷新 +1，统计页 remember(它) 避免无关重组重算 */
    val version = androidx.compose.runtime.mutableStateOf(0)

    fun init(context: android.content.Context) {
        appContext = context.applicationContext
        if (!this::store.isInitialized) store = TxStore(context.applicationContext)
        if (!this::accounts.isInitialized) accounts = AccountStore(context.applicationContext)
        if (!this::budgets.isInitialized) budgets = BudgetStore(context.applicationContext)
        if (!this::recurring.isInitialized) {
            recurring = RecurringStore(context.applicationContext)
            // 启动时补记周期账单：涉及写库，放后台线程避免拖慢首帧
            Thread {
                runCatching { RecurringScheduler.checkAndRun(store, accounts, recurring) }
            }.start()
        }
        notifyChanged()
    }

    /** 数据变化后刷新桌面小组件快照 */
    fun notifyChanged() {
        if (::appContext.isInitialized) WidgetUpdater.update(appContext)
    }
}
