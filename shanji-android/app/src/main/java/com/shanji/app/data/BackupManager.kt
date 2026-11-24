package com.shanji.app.data

import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/* 备份/恢复：JSON（与旧版 tx.json 格式兼容）与 CSV（Excel 可直接打开） */
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

    private fun esc(s: String): String =
        if (s.contains(',') || s.contains('"') || s.contains('\n'))
            "\"" + s.replace("\"", "\"\"") + "\""
        else s
}
