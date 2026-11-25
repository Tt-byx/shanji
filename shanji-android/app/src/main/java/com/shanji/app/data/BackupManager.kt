package com.shanji.app.data

import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/* 备份/恢复：JSON（与旧版 tx.json 格式兼容）与 CSV（Excel 可直接打开，支持导回） */
object BackupManager {

    private val json = Json { ignoreUnknownKeys = true }
    private val timeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** 解析备份 JSON，格式非法抛异常 */
    fun parseJson(text: String): List<Tx> = json.decodeFromString<List<Tx>>(text)

    /** CSV 导出：utf-8-sig（BOM）由调用方写入，保证 Excel 中文不乱码 */
    fun toCsv(txs: List<Tx>): String {
        val sb = StringBuilder()
        sb.appendLine("ID,类型,金额,商户,分类,备注,时间,来源")
        for (t in txs) {
            sb.appendLine(
                listOf(
                    t.id,
                    if (t.type == "income") "收入" else "支出",
                    t.amount.toString(),
                    esc(t.merchant),
                    Categories.of(t.category).label,
                    esc(t.note),
                    timeFmt.format(Instant.ofEpochMilli(t.ts).atZone(ZoneId.systemDefault())),
                    if (t.source == "share") "分享" else if (t.source == "recurring") "周期" else "手动"
                ).joinToString(",")
            )
        }
        return sb.toString()
    }

    /** CSV 解析结果：total=数据行数，txs=成功行，failed=失败样例「第N行：原因」（最多 5 条） */
    data class CsvParseResult(val txs: List<Tx>, val total: Int, val failed: List<String>) {
        val failedCount: Int get() = total - txs.size
    }

    /** 表头无法识别时抛出，message 直接给用户看 */
    class CsvFormatException(message: String) : Exception(message)

    private val timeParsers = listOf(
        "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd",
        "yyyy/MM/dd HH:mm:ss", "yyyy/MM/dd HH:mm", "yyyy/MM/dd"
    ).map { DateTimeFormatter.ofPattern(it) }

    /**
     * CSV 导入：按表头名映射列（支持闪记自家 / 支付宝 / 微信账单），
     * 金额/时间解析失败的行计入 failed 并给原因，不再静默丢弃。
     * 表头不认识时抛 CsvFormatException（message 含实测表头与支持范围提示）。
     */
    fun parseCsv(text: String): CsvParseResult {
        val rows = splitCsv(text.removePrefix("\uFEFF"))
        if (rows.isEmpty()) return CsvParseResult(emptyList(), 0, emptyList())
        val header = rows.first().map { it.trim() }
        val col = resolveColumns(header)   // null：不像表头行 → 按自家固定列序兜底
        val dataRows = if (col != null) rows.drop(1) else rows
        if (dataRows.isEmpty()) return CsvParseResult(emptyList(), 0, emptyList())

        val catByKey = Categories.all.associateBy({ it.label }, { it.key })
        val txs = mutableListOf<Tx>()
        val failed = mutableListOf<String>()
        // D9：同文件内 fp 指纹出现次数——同秒同商户同金额的两笔真实交易要各占一个 id
        val fpSeen = mutableMapOf<String, Int>()
        dataRows.forEachIndexed { i, cols ->
            val lineNo = i + (if (col != null) 2 else 1)
            val reason = parseRow(cols, col, catByKey, txs, fpSeen)
            if (reason != null && failed.size < 5) failed.add("第${lineNo}行：$reason")
        }
        return CsvParseResult(txs, dataRows.size, failed)
    }

    /**
     * 按表头名找必需列；完全不认识表头时抛 CsvFormatException；
     * 表头缺失但数据像自家格式（>=7 列）时返回 null 走固定列序兜底。
     * 模糊（contains）匹配只给时间/金额用——ID/收支等必须精确命中，
     * 否则「商品ID」会被当成交易 ID、「收/付款方式」被当成收支列。
     */
    private fun resolveColumns(header: List<String>): Map<String, Int>? {
        fun find(fuzzy: Boolean, vararg names: String): Int? {
            for (n in names) {
                header.indexOfFirst { it.equals(n, ignoreCase = true) }.let { if (it >= 0) return it }
            }
            if (fuzzy) {
                for (n in names) {
                    header.indexOfFirst { it.contains(n, ignoreCase = true) }.let { if (it >= 0) return it }
                }
            }
            return null
        }
        val looksLikeHeader = header.any {
            it.contains("金额") || it.equals("amount", true) || it.contains("时间") || it.contains("日期")
        }
        if (!looksLikeHeader) return null
        val time = find(fuzzy = true, "交易时间", "时间", "交易日期", "日期", "date", "time")
        val amount = find(fuzzy = true, "金额(元)", "金额", "交易金额", "amount")
        if (time == null || amount == null) {
            val preview = header.take(6).joinToString(",")
            throw CsvFormatException(
                "这个 CSV 的表头是「${preview}」，闪记目前支持支付宝/微信账单导出，或闪记自己导出的表格"
            )
        }
        return mapOf(
            "time" to time,
            "amount" to amount,
            "id" to (find(fuzzy = false, "ID", "交易订单号", "交易单号", "订单号") ?: -1),
            "type" to (find(fuzzy = false, "收/支", "收支", "收入/支出", "交易方向", "类型") ?: -1),
            "merchant" to (find(fuzzy = false, "交易对方", "商户", "交易方", "商家", "merchant") ?: -1),
            "category" to (find(fuzzy = false, "交易分类", "交易类型", "分类") ?: -1),
            "note" to (find(fuzzy = false, "商品说明", "商品", "备注", "note") ?: -1),
            "source" to (find(fuzzy = false, "来源") ?: -1),
            "status" to (find(fuzzy = false, "交易状态", "当前状态", "状态") ?: -1)
        )
    }

    /** 解析单行成功返回 null 并加入 txs；失败返回原因（计入失败计数） */
    private fun parseRow(
        cols: List<String>,
        col: Map<String, Int>?,
        catByKey: Map<String, String>,
        txs: MutableList<Tx>,
        fpSeen: MutableMap<String, Int>
    ): String? {
        fun cell(key: String): String =
            (col?.get(key)?.takeIf { it in cols.indices } ?: -1).let { if (it >= 0) cols[it].trim() else "" }

        // 未完成交易（微信「当前状态」/支付宝「交易状态」）
        val status = cell("status")
        if (status.contains("关闭") || status.contains("失败")) return "交易未完成"

        val amountRaw = if (col != null) cell("amount")
        else cols.getOrNull(2)?.trim() ?: ""
        val amount = amountRaw.replace("[¥￥,\\s]".toRegex(), "").toDoubleOrNull()
            ?: return "金额无法识别（$amountRaw）"
        if (kotlin.math.abs(amount) > 1_000_000) return "金额超出范围（$amountRaw）"

        // 收/支：支付宝「不计收支」（转账/余额变动）跳过；认不出时按金额正负判断
        val typeRaw = if (col != null) cell("type") else cols.getOrNull(1)?.trim() ?: ""
        if (typeRaw.contains("不计收支") || typeRaw.contains("转账")) return "不计收支/转账"
        // 负数金额 = 退款/转入 → 记收入，不丢行（不因「金额<=0」整行丢弃）
        val type = when {
            amount < 0 -> "income"
            typeRaw.contains("收入") -> "income"
            else -> "expense"
        }

        val timeRaw = if (col != null) cell("time") else cols.getOrNull(6)?.trim() ?: ""
        val ts = timeParsers.firstNotNullOfOrNull { p ->
            runCatching {
                java.time.LocalDateTime.parse(timeRaw, p)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrNull()
        } ?: return "时间无法识别（$timeRaw）"

        val merchantRaw = if (col != null) cell("merchant") else cols.getOrNull(3)?.trim() ?: ""
        val note = if (col != null) cell("note") else cols.getOrNull(5)?.trim() ?: ""
        val merchant = merchantRaw.ifBlank { note.ifBlank { "未知商户" } }
        val catLabel = if (col != null) cell("category") else cols.getOrNull(4)?.trim() ?: ""
        // 三级瀑布：① 账单自带分类列（支付宝）→ ② 商户名/商品名词（微信没有分类列）
        // → ③ 平台词（先扫商品名词的品类词，认不出才回退平台默认）→ 其他（D7）。
        // 商户名是平台词时跳过其关键词级（「美团-药房」里的「外卖」不能抢在商品名「阿莫西林」前面）
        val plat = platformCategory(merchant)
        // 注意：if 不能直接嵌进 Elvis 链（else 分支会吞掉后续 ?:），先落到局部变量
        val merchantCat = if (plat == null) keywordCategory(merchant) else null
        val category = catByKey[catLabel]
            ?: Categories.all.firstOrNull { it.key == catLabel }?.key
            ?: keywordCategory(catLabel)
            ?: merchantCat
            ?: keywordCategory(note)
            ?: plat
            ?: "other"
        // ID：有订单号列用它（重导入可去重）；没有则用 商户+金额+时间 指纹，
        // 否则每次导入都是随机 UUID → 重复导入会重复记账
        val idRaw = if (col != null) cell("id") else cols.getOrNull(0)?.trim() ?: ""
        val id = idRaw.ifBlank {
            // D9：同文件内同指纹的第 2 笔缀 -2 消歧，避免同秒拆单被去重吃掉
            val fp = fingerprint("$merchant|${round2(kotlin.math.abs(amount))}|$ts")
            val n = fpSeen.merge(fp, 1, Int::plus) ?: 1
            if (n == 1) "fp_$fp" else "fp_$fp-$n"
        }
        val source = when (if (col != null) cell("source") else cols.getOrNull(7)?.trim() ?: "") {
            "分享" -> "share"
            "周期" -> "recurring"
            else -> "manual"
        }
        txs.add(Tx(id, type, round2(kotlin.math.abs(amount)), merchant, category, note, ts, source))
        return null
    }

    /** MD5 前 6 字节：无稳定 ID 时的确定性指纹（同商户同额同分钟 → 同 ID，重导入自动去重） */
    private fun fingerprint(s: String): String =
        java.security.MessageDigest.getInstance("MD5")
            .digest(s.toByteArray()).take(6)
            .joinToString("") { "%02x".format(it) }

    /**
     * 关键词分类瀑布（2.3 修法）：微信账单没有消费分类列，「交易类型」是支付类别，
     * 只能靠商户名/商品名兜底。返回 null 表示认不出，交给下一级。
     * D7：平台词（美团/饿了么/淘宝/抖音…）不在这里——平台名不表达品类，
     * 「美团买药」会被误判成餐饮、「抖音购物」误判成娱乐，改由 platformCategory 第 3 级处理。
     */
    private fun keywordCategory(s: String): String? = when {
        s.isBlank() -> null
        listOf(
            "餐饮", "美食", "外卖", "咖啡", "奶茶", "餐厅", "快餐", "火锅", "茶饮",
            "星巴克", "瑞幸", "麦当劳", "肯德基", "海底捞", "喜茶", "蜜雪", "沪上阿姨",
            "华莱士", "老乡鸡", "真功夫"
        ).any { s.contains(it) } -> "dining"
        listOf(
            "交通", "出行", "骑行", "加油", "地铁", "公交", "打车", "停车",
            "滴滴", "高德", "中石化", "中石油", "铁路", "12306", "航空"
        ).any { s.contains(it) } -> "transport"
        listOf(
            "购物", "服饰", "数码", "美妆", "百货",
            "优衣库", "耐克", "阿迪", "小米", "华为", "名创", "屈臣氏"
        ).any { s.contains(it) } -> "shopping"
        listOf(
            "日用", "生活缴费", "水电", "话费", "充值", "便利店", "盒马", "永辉",
            "沃尔玛", "山姆", "全家", "罗森", "电费", "燃气"
        ).any { s.contains(it) } -> "daily"
        listOf(
            "娱乐", "休闲", "文化", "影视", "游戏", "会员", "爱奇艺", "腾讯视频",
            "网易云", "哔哩哔哩", "B站", "优酷"
        ).any { s.contains(it) } -> "entertainment"
        listOf("医疗", "健康", "医药", "药房", "医院", "诊所").any { s.contains(it) } -> "health"
        listOf("学习", "教育", "图书", "书店", "网课", "培训").any { s.contains(it) } -> "education"
        else -> null
    }

    /**
     * D7 第 3 级：平台词判别。命中平台名时先在商品名词里扫品类词（note 已在上一级扫过，
     * 走到这里说明商品名词也没有品类词），按平台性质回退：外卖平台→餐饮、购物平台→购物。
     * 「苹果」已从商户词表移除（水果苹果/Apple 支付都容易误判）。
     */
    private fun platformCategory(s: String): String? = when {
        s.isBlank() -> null
        listOf("美团", "饿了么").any { s.contains(it) } -> "dining"
        listOf("淘宝", "京东", "拼多多", "天猫", "抖音", "快手").any { s.contains(it) } -> "shopping"
        else -> null
    }

    /** RFC4180 风格拆分：引号内可含逗号/换行/转义双引号 */
    private fun splitCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' -> {
                    if (i + 1 < text.length && text[i + 1] == '"') { sb.append('"'); i++ }
                    else inQuotes = false
                }
                !inQuotes && c == '"' -> inQuotes = true
                !inQuotes && c == ',' -> { row.add(sb.toString()); sb.setLength(0) }
                !inQuotes && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row.add(sb.toString()); sb.setLength(0)
                    if (row.any { it.isNotBlank() }) rows.add(row.toList())
                    row.clear()
                }
                else -> sb.append(c)
            }
            i++
        }
        row.add(sb.toString())
        if (row.any { it.isNotBlank() }) rows.add(row.toList())
        return rows
    }

    private fun esc(s: String): String =
        if (s.contains(',') || s.contains('"') || s.contains('\n'))
            "\"" + s.replace("\"", "\"\"") + "\""
        else s
}
