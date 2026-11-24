package com.shanji.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import com.shanji.app.data.AppStore
import com.shanji.app.data.Categories
import com.shanji.app.data.Tx
import com.shanji.app.data.fmtAmount
import com.shanji.app.data.round2
import com.shanji.app.ui.theme.Primary
import kotlin.math.abs

/**
 * 搜索页：关键词 + 收支类型/分类筛选，结果可点进编辑。
 * 金额按数值精确匹配（搜 8 只命中 ¥8.00，不再误中 ¥48/¥180）。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(onBack: () -> Unit, onEdit: (String) -> Unit = {}) {
    var query by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf("all") } // all | expense | income | transfer
    var catFilter by remember { mutableStateOf("") }     // "" = 全部分类
    val txs = AppStore.store.txs
    // 数据版本做 remember key：改一笔金额后笔数不变，用 txs.size 会让结果停在旧数据
    val dataVersion = AppStore.version.value

    // 进入页面自动聚焦输入框，省一次点击
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val results = remember(query, typeFilter, catFilter, dataVersion) {
        val q = query.trim()
        val amountHit: ((Tx) -> Boolean)? = q.toDoubleOrNull()?.let { v ->
            { tx: Tx -> abs(abs(tx.amount) - v) < 0.005 }
        }
        txs.filter { tx ->
            (typeFilter == "all" || tx.type == typeFilter) &&
                    (catFilter.isEmpty() || tx.category == catFilter) &&
                    (q.isEmpty() ||
                            tx.merchant.contains(q, true) ||
                            tx.note.contains(q, true) ||
                            Categories.of(tx.category).label.contains(q, true) ||
                            amountHit?.invoke(tx) == true)
        }
    }
    // 合计拆成支出/收入两口径：原来收入+支出混加出来的「合计」没有意义；转账单独计数
    val sums = remember(results) {
        val exp = results.filter { it.type == "expense" }.sumOf { abs(it.amount) }
        val inc = results.filter { it.type == "income" }.sumOf { abs(it.amount) }
        val tr = results.count { it.type == "transfer" }
        Triple(round2(exp), round2(inc), tr)
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .imePadding()
            .padding(horizontal = 20.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = MaterialTheme.colorScheme.onSurface)
            }
            Text("搜索账单", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("搜商户、备注、分类或金额") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Primary,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface
            ),
            modifier = Modifier.fillMaxWidth().focusRequester(focus)
        )
        Spacer(Modifier.height(10.dp))

        // 收支类型筛选：加维度标签（两行 chip 原来长得一模一样，分不清哪行筛什么），FlowRow 换行防溢出
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "类型", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(36.dp)
            )
            androidx.compose.foundation.layout.FlowRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip2("全部", typeFilter == "all") { typeFilter = "all" }
                FilterChip2("支出", typeFilter == "expense") { typeFilter = "expense" }
                FilterChip2("收入", typeFilter == "income") { typeFilter = "income" }
                FilterChip2("转账", typeFilter == "transfer") { typeFilter = "transfer" }
            }
        }
        Spacer(Modifier.height(8.dp))
        // 分类筛选
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "分类", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(36.dp)
            )
            androidx.compose.foundation.layout.FlowRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip2("全部分类", catFilter.isEmpty()) { catFilter = "" }
                Categories.all.forEach { c ->
                    FilterChip2(c.label, catFilter == c.key) { catFilter = if (catFilter == c.key) "" else c.key }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // 结果汇总：笔数 + 支出/收入分列（转账只计笔数）；空态给「最近账单」标题，不再静默铺全量
        val showSummary = query.isNotBlank() || typeFilter != "all" || catFilter.isNotEmpty()
        if (showSummary) {
            Text(
                buildString {
                    append("找到 ${results.size} 笔")
                    if (sums.first > 0) append(" · 支出 ¥${fmtAmount(sums.first)}")
                    if (sums.second > 0) append(" · 收入 ¥${fmtAmount(sums.second)}")
                    if (sums.third > 0) append(" · 转账 ${sums.third} 笔")
                },
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        } else {
            Text(
                "最近账单（共 ${results.size} 笔，输入关键词或点筛选可精确查找）",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        if (showSummary && results.isEmpty()) {
            Text(
                "没有匹配的账单",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 32.dp)
            )
        } else {
            LazyColumn {
                items(results.size) { i ->
                    val tx = results[i]
                    TxRow(tx, onClick = { onEdit(tx.id) })
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun FilterChip2(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 13.sp,
        color = if (selected) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .background(
                if (selected) Primary else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(999.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}
