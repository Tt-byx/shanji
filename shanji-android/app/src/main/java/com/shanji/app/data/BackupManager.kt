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

    /**
     * CSV 导入（与 toCsv 互逆）：BOM/引号/字段内逗号换行都能解析；
     * 非法行（金额/时间解析不出）静默丢弃，返回可导入的账单列表。
     */
    fun parseCsv(text: String): List<Tx> {
        val rows = splitCsv(text.removePrefix("\uFEFF"))
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map { it.trim() }
        // 支持表头行顺序与导出一致；找不到必需列则按固定顺序解释
        val hasHeader = header.any { it.equals("金额", ignoreCase = true) || it.equals("amount", true) }
        val dataRows = if (hasHeader) rows.drop(1) else rows

        val timeParser = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val catByKey = Categories.all.associateBy({ it.label }, { it.key })
        return dataRows.mapNotNull { cols ->
            if (cols.size < 7) return@mapNotNull null   // 至少到「时间」列
            // 导出列序：ID,类型,金额,商户,分类,备注,时间,来源
            val id = cols[0].trim().ifBlank { "tx_" + java.util.UUID.randomUUID().toString().take(8) }
            val type = if (cols[1].trim() == "收入") "income" else "expense"
            val amount = cols[2].trim().toDoubleOrNull() ?: return@mapNotNull null
            if (amount <= 0 || amount > 1_000_000) return@mapNotNull null
            val merchant = cols[3].trim()
            val catLabel = cols[4].trim()
            val category = catByKey[catLabel] ?: Categories.all.firstOrNull { it.key == catLabel }?.key ?: "other"
            val note = cols.getOrNull(5)?.trim() ?: ""
            val ts = runCatching {
                java.time.LocalDateTime.parse(cols[6].trim(), timeParser)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrElse { return@mapNotNull null }
            val source = when (cols.getOrNull(7)?.trim()) {
                "分享" -> "share"
                "周期" -> "recurring"
                else -> "manual"
            }
            Tx(id, type, round2(amount), merchant, category, note, ts, source)
        }
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
