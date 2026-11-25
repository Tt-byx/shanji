package com.shanji.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shanji.app.data.AppStore
import com.shanji.app.data.fmtAmount

/**
 * 溯源明细弹层（F22 共享件）：AI 数字的 shanji-trace 链接 / 卡片按钮点开 → 列出对应账单。
 * [query]：category/month/keyword 过滤（null = 关闭）；[onEdit] 可空（统计页没有编辑入口则只读）。
 */
// OptIn 而非 @Experimental 标注：不把实验性 API 传染给调用方（ChatScreen/StatsScreen）
@androidx.compose.runtime.Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun TraceSheet(
    query: Map<String, String>?,
    onDismiss: () -> Unit,
    onEdit: ((String) -> Unit)? = null
) {
    if (query == null) return
    val q = query
    BackHandler { onDismiss() }
    val month = q["month"] ?: ""
    val cat = q["category"] ?: ""
    val keyword = q["keyword"] ?: ""
    // 提示词要求 category 用 key，但 LLM 可能给中文「购物」：label/key 两态都认，认不出保留原值给空态提示用
    val catKey = remember(cat) {
        if (cat.isBlank()) ""
        else com.shanji.app.data.Categories.all.firstOrNull { it.key == cat || it.label == cat }?.key ?: cat
    }
    val hits = remember(q) {
        AppStore.store.txs.filter { t ->
            val inMonth = month.isBlank() || java.time.YearMonth.from(
                java.time.Instant.ofEpochMilli(t.ts).atZone(java.time.ZoneId.systemDefault())
            ).toString() == month
            val inCat = catKey.isBlank() || t.category == catKey
            val inKw = keyword.isBlank() ||
                t.merchant.contains(keyword) || t.note.contains(keyword)
            inMonth && inCat && inKw
        }.sortedByDescending { it.ts }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        val total = hits.filter { it.type == "expense" }.sumOf { it.amount }
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                if (hits.isEmpty()) "没匹配到账单"
                else "AI 提到的账单 · ${hits.size} 笔 · 支出合计 ¥${fmtAmount(total)}",
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            if (hits.isEmpty()) {
                // 空结果说清原因，避免「点了没反应」的困惑
                Text(
                    if (cat.isNotBlank()) "没匹配到账单（AI 给的分类「$cat」，账本里没有这一类）"
                    else "没有匹配的账单",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(hits.size) { i ->
                    TxRow(
                        hits[i],
                        onClick = onEdit?.let { edit ->
                            { onDismiss(); edit(hits[i].id) }
                        } ?: {}
                    )
                }
            }
        }
    }
}
