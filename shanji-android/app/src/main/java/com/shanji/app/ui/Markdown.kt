package com.shanji.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 轻量 Markdown 渲染（AI 回复专用）。
 * 覆盖聊天场景常用语法：标题 / **加粗** / *斜体* / `行内代码` / 围栏代码块 /
 * 无序与有序列表 / 引用 / 分割线 / 表格 / 链接。
 * 纯手写无三方依赖：按行分块 + 行内正则，流式半截文本也能安全解析
 * （未闭合的 ** 或 ``` 按普通文本/未关代码块渲染，收到后续增量后自动恢复）。
 */

// ---------- 行内片段 ----------
private sealed interface Seg {
    data class T(val s: String) : Seg
    data class B(val segs: List<Seg>) : Seg
    data class I(val segs: List<Seg>) : Seg
    data class C(val s: String) : Seg
    data class L(val text: String, val url: String) : Seg
}

private val INLINE = Regex("""\*\*(.+?)\*\*|\*([^*\n]+)\*|`([^`\n]+)`|\[([^\]]+)\]\(([^)\s]+)\)""")

private fun parseInline(s: String): List<Seg> {
    if (s.isEmpty()) return emptyList()
    val out = mutableListOf<Seg>()
    var last = 0
    for (m in INLINE.findAll(s)) {
        if (m.range.first > last) out += Seg.T(s.substring(last, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() -> out += Seg.B(parseInline(g[1]))
            g[2].isNotEmpty() -> out += Seg.I(parseInline(g[2]))
            g[3].isNotEmpty() -> out += Seg.C(g[3])
            g[4].isNotEmpty() -> out += Seg.L(g[4], g[5])
            else -> out += Seg.T(m.value)
        }
        last = m.range.last + 1
    }
    if (last < s.length) out += Seg.T(s.substring(last))
    return out
}

// ---------- 块模型 ----------
private sealed interface Block {
    data class Para(val segs: List<Seg>) : Block
    data class Heading(val level: Int, val segs: List<Seg>) : Block
    data class ListBlock(val ordered: Boolean, val items: List<List<Seg>>) : Block
    data class Code(val text: String) : Block
    data class Quote(val segs: List<Seg>) : Block
    data class Table(val header: List<List<Seg>>, val rows: List<List<List<Seg>>>) : Block
    data object Rule : Block
}

private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
private val BULLET = Regex("""^[-*+]\s+(.*)$""")
private val ORDERED = Regex("""^\d+[.)]\s+(.*)$""")
private val TABLE_SEP = Regex("""^\|[\s:|-]+\|$""")

private fun splitRow(line: String): List<String> =
    line.trim().trim('|').split('|').map { it.trim() }

private fun parseBlocks(text: String): List<Block> {
    val lines = text.split('\n')
    val out = mutableListOf<Block>()
    var i = 0
    while (i < lines.size) {
        val t = lines[i].trim()
        when {
            // 围栏代码块（到 ``` 或文末；流式中未闭合也先按代码渲染）
            t.startsWith("```") -> {
                val sb = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    sb.append(lines[i]).append('\n'); i++
                }
                if (i < lines.size) i++ // 跳过关闭围栏
                out += Block.Code(sb.toString().trimEnd('\n'))
            }
            HEADING.matches(t) -> {
                val m = HEADING.matchEntire(t)!!
                out += Block.Heading(m.groupValues[1].length, parseInline(m.groupValues[2]))
                i++
            }
            t == "---" || t == "***" || t == "___" -> { out += Block.Rule; i++ }
            t.startsWith(">") -> {
                val sb = StringBuilder()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    val q = lines[i].trim().removePrefix(">").trim()
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(q); i++
                }
                out += Block.Quote(parseInline(sb.toString()))
            }
            BULLET.matches(t) -> {
                val items = mutableListOf<List<Seg>>()
                while (i < lines.size && BULLET.matches(lines[i].trim())) {
                    items += parseInline(BULLET.matchEntire(lines[i].trim())!!.groupValues[1])
                    i++
                }
                out += Block.ListBlock(false, items)
            }
            ORDERED.matches(t) -> {
                val items = mutableListOf<List<Seg>>()
                while (i < lines.size && ORDERED.matches(lines[i].trim())) {
                    items += parseInline(ORDERED.matchEntire(lines[i].trim())!!.groupValues[1])
                    i++
                }
                out += Block.ListBlock(true, items)
            }
            // 表格：表头行 + 分隔行（|---|---|）
            t.startsWith("|") && i + 1 < lines.size &&
                TABLE_SEP.matches(lines[i + 1].trim()) -> {
                val header = splitRow(t).map { parseInline(it) }
                i += 2
                val rows = mutableListOf<List<List<Seg>>>()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    rows += splitRow(lines[i]).map { parseInline(it) }
                    i++
                }
                out += Block.Table(header, rows)
            }
            t.isBlank() -> i++
            else -> {
                // 连续普通行合并成一段（保留模型自己的换行）
                val sb = StringBuilder(t)
                i++
                while (i < lines.size) {
                    val n = lines[i].trim()
                    if (n.isBlank() || HEADING.matches(n) || BULLET.matches(n) ||
                        ORDERED.matches(n) || n.startsWith(">") || n.startsWith("```") ||
                        n.startsWith("|") || n == "---" || n == "***" || n == "___"
                    ) break
                    sb.append('\n').append(n); i++
                }
                out += Block.Para(parseInline(sb.toString()))
            }
        }
    }
    return out
}

// ---------- 渲染 ----------
private fun AnnotatedString.Builder.appendSegs(
    segs: List<Seg>,
    codeBg: Color,
    linkColor: Color,
    onLink: ((String) -> Unit)?
) {
    segs.forEach { s ->
        when (s) {
            is Seg.T -> append(s.s)
            is Seg.B -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold)); appendSegs(s.segs, codeBg, linkColor, onLink); pop()
            }
            is Seg.I -> {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic)); appendSegs(s.segs, codeBg, linkColor, onLink); pop()
            }
            is Seg.C -> {
                pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg))
                append(s.s); pop()
            }
            is Seg.L -> {
                pushStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                // 溯源链接（shanji-trace:）交给调用方跳明细；普通链接走系统浏览器
                if (onLink != null && s.url.startsWith("shanji-trace:")) {
                    pushLink(androidx.compose.ui.text.LinkAnnotation.Clickable(s.url) { onLink(s.url) })
                } else {
                    pushLink(androidx.compose.ui.text.LinkAnnotation.Url(s.url))
                }
                append(s.text)
                pop() // link annotation
                pop() // span style
            }
        }
    }
}

@Composable
private fun SegsText(
    segs: List<Seg>,
    fontSize: Int,
    bold: Boolean = false,
    color: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
    onLink: ((String) -> Unit)? = null
) {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    Text(
        text = buildAnnotatedString { appendSegs(segs, codeBg, linkColor, onLink) },
        fontSize = fontSize.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        color = color,
        lineHeight = (fontSize + 7).sp,
        modifier = modifier
    )
}

/** AI 回复气泡正文：Markdown 排版渲染。[onLink] 点击 shanji-trace: 溯源链接时回调（参数为完整 URL） */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, onLink: ((String) -> Unit)? = null) {
    val blocks = remember(text) { parseBlocks(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { b ->
            when (b) {
                is Block.Para -> SegsText(b.segs, 14, onLink = onLink)
                is Block.Heading -> SegsText(
                    b.segs, when (b.level) { 1 -> 18; 2 -> 16; else -> 15 }, bold = true
                )
                is Block.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    b.items.forEachIndexed { idx, segs ->
                        Row {
                            Text(
                                if (b.ordered) "${idx + 1}." else "•",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 6.dp)
                            )
                            SegsText(segs, 14, onLink = onLink)
                        }
                    }
                }
                is Block.Code -> Text(
                    b.text,
                    fontSize = 12.5.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
                is Block.Quote -> Row {
                    Text(
                        "▍",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    SegsText(b.segs, 14, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is Block.Table -> Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth()) {
                        b.header.forEach { cell ->
                            SegsText(cell, 13, bold = true, modifier = Modifier.weight(1f).padding(end = 4.dp, bottom = 4.dp))
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)
                    b.rows.forEach { row ->
                        Row(Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 5.dp)) {
                            row.forEach { cell ->
                                SegsText(cell, 13, modifier = Modifier.weight(1f).padding(end = 4.dp))
                            }
                            // 行列数不足时补齐，防止权重错位
                            repeat((b.header.size - row.size).coerceAtLeast(0)) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
                is Block.Rule -> HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline, thickness = 1.dp,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}
