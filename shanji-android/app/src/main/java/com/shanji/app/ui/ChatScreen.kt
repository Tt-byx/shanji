package com.shanji.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shanji.app.data.AppStore
import com.shanji.app.data.Categories
import com.shanji.app.data.ChatStore
import com.shanji.app.data.ChatStreamManager
import com.shanji.app.data.Ledger
import com.shanji.app.data.fmtAmount
import com.shanji.app.data.toLocalDateTime
import com.shanji.app.data.net.AiAction
import com.shanji.app.data.net.AiChatMsg
import com.shanji.app.data.net.AiChatReq
import com.shanji.app.ui.theme.Primary
import com.shanji.app.ui.theme.PrimaryDeep
import com.shanji.app.ui.theme.Red
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import java.time.YearMonth

private data class ChatItem(
    val id: String,
    val role: String,       // user | assistant
    val text: String,
    val streaming: Boolean = false  // 生成中：尾部显示闪烁光标 ▍
)

/** DeepSeek 偶发把换行转成字面 &#10;（可能跨 chunk），显示/入库前统一还原 */
private fun cleanText(s: String): String =
    s.replace("&#10;", "\n").replace("&amp;", "&")

/** [系统] 开头是查账回传的内部消息：只进 AI 上下文，不上屏 */
private fun visible(msgs: List<com.shanji.app.data.db.ChatMessageEntity>): List<com.shanji.app.data.db.ChatMessageEntity> =
    msgs.filterNot { it.role == "user" && it.content.startsWith("[系统]") }

// ===== 流式段落批次揭示：安全切割点（绝不腰斩代码块/表格）=====

private const val REVEAL_MIN = 120 // 批次最小字符数（避免碎批；短回复由流结束 flush 兜底）
private val RE_HEAD = Regex("""^#{1,6}\s+.*$""")
private val RE_BULLET = Regex("""^[-*+]\s+.*$""")
private val RE_ORDERED = Regex("""^\d+[.)]\s+.*$""")

private fun lineStartAt(text: String, pos: Int): Int =
    if (pos <= 0) 0 else text.lastIndexOf('\n', pos - 1) + 1

/** [pos] 所在位置是否处于未闭合的 ``` 围栏内（按行首围栏标记计数） */
private fun fenceOpenAt(text: String, pos: Int): Boolean {
    var depth = 0
    var p = 0
    val limit = lineStartAt(text, pos)
    while (p < limit) {
        val e = text.indexOf('\n', p).let { if (it == -1 || it > limit) limit else it }
        if (text.substring(p, e).trimStart().startsWith("```")) depth++
        if (e >= limit) break
        p = e + 1
    }
    return depth % 2 == 1
}

/** 行类型（空行0/围栏1/标题2/分割线3/引用4/无序5/有序6/表格7/段落8） */
private fun lineKind(t: String): Int = when {
    t.isEmpty() -> 0
    t.startsWith("```") -> 1
    t.startsWith("|") -> 7
    t.startsWith(">") -> 4
    RE_BULLET.matches(t) -> 5
    RE_ORDERED.matches(t) -> 6
    RE_HEAD.matches(t) -> 2
    t == "---" || t == "***" || t == "___" -> 3
    else -> 8
}

/** cur 行是否相对 prev 行开启新解析块（同类延续的列表/引用/表格/段落不切） */
private fun isBlockBoundary(prev: Int, cur: Int): Boolean {
    if (cur == 0 || prev < 0) return false
    if (prev == 0) return true // 空行后 → 新块
    if (prev == cur && cur in intArrayOf(4, 5, 6, 7, 8)) return false
    return true
}

/** 句末位置：返回 [from,end) 内第一个句末标点（含后随右引号）之后的下标；无 → -1 */
private fun sentenceEndIn(text: String, from: Int, end: Int): Int {
    var i = from
    while (i < end) {
        val c = text[i]
        val cjk = c == '。' || c == '！' || c == '？' || c == '…' || c == '‼'
        val ascii = c == '.' || c == '!' || c == '?'
        if (cjk || ascii) {
            if (ascii) {
                val nx = if (i + 1 < end) text[i + 1] else '\n'
                if (!nx.isWhitespace() && nx != '"' && nx != '\'' && nx != '”' && nx != '’') { i++; continue }
            }
            var j = i + 1
            while (j < end && (text[j] == '"' || text[j] == '\'' || text[j] == '”' || text[j] == '’' ||
                        text[j] == '）' || text[j] == ')' || text[j] == '』' || text[j] == '」')) j++
            return j
        }
        i++
    }
    return -1
}

/** 句末切割的行内构造平衡检查：不切开 `code`、**bold**、[link](...) */
private fun inlineBalanced(text: String, from: Int, cut: Int): Boolean {
    if (cut <= from) return true
    val seg = text.substring(from, cut)
    if (seg.count { it == '`' } % 2 != 0) return false
    if (seg.count { it == '*' } % 2 != 0) return false
    if (seg.split("**").size % 2 == 0) return false // "**" 出现奇数次
    val lb = seg.lastIndexOf('[')
    if (lb >= 0) {
        val close = seg.indexOf(']', lb)
        if (close < 0) return false           // [ 未闭合
        if (close == seg.length - 1 || seg[close + 1] != '(') return false // ] 后无 ( → 链接跨切点
        if (seg.indexOf(')', close) < 0) return false // (url 未闭合
    }
    return true
}

/**
 * 流式批次切割点：从 [from]（行首或句末）起找第一个安全边界。
 * 规则：绝不切开 ``` 围栏与表格（整块揭示）；优先块边界（空行/构造行），句末兜底；
 * 批次不小于 [REVEAL_MIN]；流结束时无边界则 flush 全量。null = 暂无边界（继续攒批）。
 */
private fun nextRevealCut(text: String, from: Int, streamDone: Boolean): Int? {
    val n = text.length
    if (from >= n) return null
    fun eol(p: Int): Int { val i = text.indexOf('\n', p); return if (i == -1) n else i }

    // A. 围栏内 → 只等闭合行（闭合后跳过尾随空行切到内容行首）
    if (fenceOpenAt(text, from)) {
        var p = lineStartAt(text, from)
        while (p < n) {
            val e = eol(p)
            if (text.substring(p, e).trimStart().startsWith("```")) {
                var q = e + 1
                while (q < n && text.substring(q, eol(q)).trim().isEmpty()) q = eol(q) + 1
                return if (q > from && q < n) q else if (streamDone) n else null
            }
            p = e + 1
        }
        return if (streamDone) n else null
    }

    // B. 表格行中 → 等表格结束（跳到表格外第一个内容行）
    val lsFrom = lineStartAt(text, from)
    if (text.substring(lsFrom, eol(lsFrom)).trimStart().startsWith("|")) {
        var p = lsFrom
        while (p < n && text.substring(p, eol(p)).trimStart().startsWith("|")) p = eol(p) + 1
        val q = run {
            var i = p
            while (i < n && text.substring(i, eol(i)).trim().isEmpty()) i = eol(i) + 1
            i
        }
        return if (q > from && q < n) q else if (streamDone) n else null
    }

    // C. 逐行扫描：围栏/表格整块跳过；块边界优先，句末兜底
    var fromLs = lsFrom
    var fence = fenceOpenAt(text, fromLs)
    if (text.substring(fromLs, eol(fromLs)).trimStart().startsWith("```")) fence = !fence
    var prevKind = lineKind(text.substring(fromLs, eol(fromLs)).trim())
    var blockCut = -1
    var sentCut = -1

    // from 所在行剩余部分的句末（仅普通段落行）
    val fe = eol(fromLs)
    if (prevKind == 8 && fe > from) {
        val s0 = sentenceEndIn(text, from, fe)
        if (s0 in (from + REVEAL_MIN)..fe && inlineBalanced(text, from, s0)) sentCut = s0
    }

    var p = fe + 1
    while (p < n && (blockCut < 0 || sentCut < 0)) {
        val e = eol(p)
        val t = text.substring(p, e).trim()
        val kind = lineKind(t)

        if (fence) {
            if (kind == 1) { fence = false; prevKind = 1 }
            p = e + 1
            continue
        }
        if (kind == 1) { // 围栏开栏行：其行首是安全边界（代码块整体留给下一批）
            if (p > from && blockCut < 0 && isBlockBoundary(prevKind, kind) && p - from >= REVEAL_MIN) {
                blockCut = p
                if (blockCut - from <= 400) return blockCut
            }
            fence = true
            prevKind = kind
            p = e + 1
            continue
        }
        if (kind == 7) { // 表格块：跳到表格外
            var q = p
            while (q < n && text.substring(q, eol(q)).trimStart().startsWith("|")) q = eol(q) + 1
            if (q >= n) { p = n; continue }
            if (q > from && blockCut < 0 && isBlockBoundary(prevKind, 7) && q - from >= REVEAL_MIN) {
                blockCut = q
                if (blockCut - from <= 400) return blockCut
            }
            p = q
            prevKind = 7
            continue
        }

        // 普通/标题/列表/引用/分割线行
        if (p > from && blockCut < 0 && kind != 0 && isBlockBoundary(prevKind, kind) && p - from >= REVEAL_MIN) {
            blockCut = p
            if (blockCut - from <= 400) return blockCut
        }
        // 句末候选（仅段落行）
        if (kind == 8 && sentCut < 0) {
            val scanFrom = maxOf(p, from)
            val s = sentenceEndIn(text, scanFrom, e)
            if (s >= 0 && s - from >= REVEAL_MIN && inlineBalanced(text, from, s)) sentCut = s
        }
        prevKind = kind
        p = e + 1
    }

    if (blockCut >= 0 && blockCut - from <= 400) return blockCut
    if (sentCut >= 0) return sentCut
    if (blockCut >= 0) return blockCut
    return if (streamDone) n else null
}

/** 滚到底（即时）：先等 items 变更完成布局，再定位末项并把高于视口的底边补进来 */
private suspend fun LazyListState.scrollToBottom() {
    delay(32) // 等两帧：items 变更 → 重组 → 布局，避免用到陈旧 totalItemsCount
    val total = layoutInfo.totalItemsCount
    if (total == 0) return
    scrollToItem(total - 1)
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return
    android.util.Log.d(
        "SHANJI",
        "scrollToBottom target=${total - 1} nowLastIdx=${last.index} " +
            "lastEnd=${last.offset + last.size} viewportEnd=${info.viewportEndOffset}"
    )
    val excess = (last.offset + last.size) - info.viewportEndOffset
    if (excess > 0) scrollBy(excess.toFloat())
}

/** 滚到底（平滑）：发送消息 / 点「回到底部」用 */
private suspend fun LazyListState.animateScrollToBottom() {
    delay(32)
    val total = layoutInfo.totalItemsCount
    if (total == 0) return
    animateScrollToItem(total - 1)
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return
    val excess = (last.offset + last.size) - info.viewportEndOffset
    if (excess > 0) animateScrollBy(excess.toFloat())
}

/**
 * AI 问账本 tab：DeepSeek 风格 —— 欢迎态居中大标题+建议 chips，
 * 后台流式（ChatStreamManager 全局管理，切会话/最小化不断流）+ 本地记忆 + 会话管理，
 * 吸底滚动（贴底跟随、用户上翻不打断、「回到底部」悬浮按钮）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onAddTx: (amount: Double, merchant: String, category: String, type: String) -> Unit,
    onEditTx: (id: String) -> Unit = {}
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<ChatItem>() }
    var convId by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var startPending by remember { mutableStateOf(false) } // POST /chat/task 进行中
    var showHistory by remember { mutableStateOf(false) }   // 历史抽屉：挂载标志（含退场动画期）
    var drawerOpen by remember { mutableStateOf(false) }    // 抽屉动画目标态
    var conversations by remember { mutableStateOf<List<com.shanji.app.data.db.ConversationEntity>>(emptyList()) }
    var renameTarget by remember { mutableStateOf<com.shanji.app.data.db.ConversationEntity?>(null) }
    var renameText by remember { mutableStateOf("") }
    // 长按气泡 → 底部操作表（复制 / 删除）
    var sheetTarget by remember { mutableStateOf<ChatItem?>(null) }
    // 溯源/卡片「看明细」→ 底部弹层（category/month/keyword 过滤，null = 关闭）
    var traceQuery by remember { mutableStateOf<Map<String, String>?>(null) }
    val haptics = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    // reduced-motion：开发者选项「动画时长缩放=关闭」≈ 系统级减弱动态效果
    val reducedMotion = remember(ctx) {
        runCatching {
            android.provider.Settings.Global.getFloat(
                ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
            ) == 0f || android.provider.Settings.Global.getFloat(
                ctx.contentResolver, android.provider.Settings.Global.TRANSITION_ANIMATION_SCALE, 1f
            ) == 0f
        }.getOrDefault(false)
    }
    val persona = remember {
        ctx.getSharedPreferences("shanji_prefs", android.content.Context.MODE_PRIVATE)
            .getString("ai_persona", null)
    }
    val listState = rememberLazyListState()
    // 流式段落批次揭示：bubbleId → 批次切割点列表（各批文本互不合并，旧批永不重排 → 零跳动）。
    // 放在页面级（而非气泡内 remember），滚动/重组导致 item 重建也不丢进度。
    val batchCuts = remember { androidx.compose.runtime.snapshots.SnapshotStateMap<String, List<Int>>() }
    // 已定稿批次数（淡入完成）；最后一段 [settled, cuts.size) 为淡入中
    val batchSettled = remember { androidx.compose.runtime.snapshots.SnapshotStateMap<String, Int>() }
    // 已删除消息 id：防止流快照把长按删除的消息重新加回列表
    val deletedIds = remember { mutableSetOf<String>() }

    // ===== 吸底滚动状态（规则1：贴底判定 = 末项底边距视口底边 ≤ 阈值）=====
    val density = LocalDensity.current
    val bottomThresholdPx = with(density) { 72.dp.toPx() } // 规则建议 50~80px
    val dragThresholdPx = with(density) { 48.dp.toPx() }   // 抽屉手势阈值（48dp）
    var isAtBottom by remember { mutableStateOf(true) }

    // 快照订阅（规则2：流管理器与视图解耦，UI 只是订阅者之一）
    val snapshots by ChatStreamManager.snapshots.collectAsState()
    val busy = startPending || snapshots.values.any {
        it.conversationId == convId && it.status == ChatStreamManager.Status.STREAMING
    }

    /** 规则1：当前是否贴底。最后一项可见且其底边距视口底边 ≤ 阈值（含内容不足一屏） */
    fun atBottomNow(): Boolean {
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull() ?: return true
        if (last.index != info.totalItemsCount - 1) return false
        return (last.offset + last.size) - info.viewportEndOffset <= bottomThresholdPx
    }

    // 规则1+6：只在「滚动停止」瞬间评估贴底（防抖：内容增长不触发滚动 → 不会把
    // 跟随中的用户误判为上翻；也不逐帧计算）。用户拖拽/惯性结束都会走到这里。
    LaunchedEffect(listState) {
        var wasScrolling = false
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (wasScrolling && !scrolling) isAtBottom = atBottomNow()
            wasScrolling = scrolling
        }
    }

    // 规则2/3/6：贴底跟随轮询（100ms 节流，不逐帧强制 layout；贴底才跟随，
    // 用户上翻后 isAtBottom=false 即停，手势/动画进行中绝不打断）
    LaunchedEffect(listState) {
        while (true) {
            delay(100)
            if (!isAtBottom || items.isEmpty()) continue
            if (listState.isScrollInProgress) continue
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            val needScroll = last == null || last.index < info.totalItemsCount - 1 ||
                (last.offset + last.size) > info.viewportEndOffset + 1
            if (needScroll) listState.scrollToBottom()
        }
    }

    /** 近 6 个月 + 最近 20 笔 + 预算/周期状态（给 AI 的数字必须来自本机，禁止编造） */
    fun monthContext(): String {
        val txs = AppStore.store.txs
        val now = YearMonth.now()
        val sb = StringBuilder()
        sb.append("[近6个月]\n")
        for (i in 5 downTo 0) {
            val ym = now.minusMonths(i.toLong())
            val s = Ledger.monthSummary(txs, ym)
            sb.append("${ym}: 支出¥${fmtAmount(s.expense)} 收入¥${fmtAmount(s.income)}\n")
        }
        val cats = Ledger.categoryBreakdown(Ledger.monthTx(txs, now)).take(5)
        if (cats.isNotEmpty()) {
            sb.append("本月分类：")
                .append(cats.joinToString("、") { "${it.cat.label}¥${fmtAmount(it.amount)}" }).append('\n')
        }
        val recent = txs.sortedByDescending { it.ts }.take(20)
        if (recent.isNotEmpty()) {
            sb.append("[最近20笔]\n")
            recent.forEach { t ->
                val d = toLocalDateTime(t.ts)
                val sign = if (t.type == "expense") "-" else if (t.type == "income") "+" else "±"
                sb.append("${d.monthValue}-${d.dayOfMonth} ${t.merchant} ")
                    .append("${Categories.of(t.category).label} $sign¥${fmtAmount(t.amount)}\n")
            }
        }
        val budgets = AppStore.budgets
        if (budgets.list.isNotEmpty()) {
            sb.append("[预算] 总预算¥${fmtAmount(budgets.total())}")
            budgets.list.filter { it.scope == "category" }.forEach {
                sb.append(" ${Categories.of(it.category).label}¥${fmtAmount(it.amount)}")
            }
            val used = Ledger.monthSummary(txs, now).expense
            sb.append("，本月已花¥${fmtAmount(used)}\n")
        }
        val recurring = AppStore.recurring.list.filter { it.enabled }
        if (recurring.isNotEmpty()) {
            sb.append("[周期账单] ").append(recurring.joinToString("；") {
                "每月${it.dayOfMonth}号 ${it.merchant}¥${fmtAmount(it.amount)}"
            }).append('\n')
        }
        return sb.toString()
    }

    /** 把本轮查询/动作结果回传 AI 出第二轮（第二轮走后台任务，allowAct=false 不递归） */
    fun runFollowUp(resultText: String, cid: String) {
        startPending = true // 同步置位：首轮 DONE 到二轮任务创建之间 busy 不出现空窗
        scope.launch {
            // 首轮回复已由 ChatStreamManager 以 messageId 幂等落库，这里只补查询结果
            withContext(Dispatchers.IO) { ChatStore.append(ctx, cid, "user", "[系统] $resultText") }
            val mem = withContext(Dispatchers.IO) { ChatStore.buildMemory(ctx, cid) }
            val req = AiChatReq(mem.map { AiChatMsg(it.first, it.second) }, monthContext(), persona)
            runCatching { ChatStreamManager.start(ctx, cid, req, allowAct = false) }
                .onSuccess { startPending = false }
                .onFailure {
                    startPending = false
                    android.util.Log.w("SHANJI", "round2 start fail: ${it.message}")
                    val msg = it.message ?: "没查成，稍后再试"
                    items.add(ChatItem("e${System.nanoTime()}", "assistant", msg))
                    withContext(Dispatchers.IO) { ChatStore.append(ctx, cid, "assistant", msg) }
                }
        }
    }

    /** 本轮查账结果回传 AI 出第二轮 */
    fun runQueryThenReply(params: Map<String, kotlinx.serialization.json.JsonElement>, cid: String) {
        val keyword = params["keyword"]?.toString()?.trim('"') ?: ""
        val cat = params["category"]?.toString()?.trim('"') ?: ""
        val month = params["month"]?.toString()?.trim('"') ?: YearMonth.now().toString()
        val ym = runCatching { YearMonth.parse(month) }.getOrDefault(YearMonth.now())
        val hits = AppStore.store.txs.filter { t ->
            val inMonth = YearMonth.from(
                java.time.Instant.ofEpochMilli(t.ts).atZone(java.time.ZoneId.systemDefault())
            ) == ym
            val matchKw = keyword.isBlank() || t.merchant.contains(keyword) || t.note.contains(keyword)
            val matchCat = cat.isBlank() || t.category == cat
            inMonth && matchKw && matchCat
        }
        val total = hits.sumOf { if (it.type == "expense") it.amount else 0.0 }
        val detail = hits.take(10).joinToString("；") { "${it.merchant}¥${fmtAmount(it.amount)}" }
        val resultText = "查询结果：${hits.size} 笔，支出合计¥${fmtAmount(total)}" +
            (if (detail.isNotEmpty()) "。明细：$detail" else "")
        runFollowUp(resultText, cid)
    }

    /** trend 动作：近 N 月（可选分类）支出序列回传 AI */
    fun runTrendThenReply(params: Map<String, kotlinx.serialization.json.JsonElement>, cid: String) {
        val months = params["months"]?.toString()?.trim('"')?.toIntOrNull() ?: 6
        val cat = params["category"]?.toString()?.trim('"') ?: ""
        val now = YearMonth.now()
        val series = (months - 1 downTo 0).map { i ->
            val ym = now.minusMonths(i.toLong())
            val sum = Ledger.monthTx(AppStore.store.txs, ym)
                .filter { it.type == "expense" && (cat.isEmpty() || it.category == cat) }
                .sumOf { it.amount }
            "$ym ¥${fmtAmount(sum)}"
        }
        runFollowUp("近${months}个月支出趋势：" + series.joinToString("，"), cid)
    }

    /** budget_set 动作：直接写预算库，再回传 AI 出确认语 */
    fun runBudgetSetThenReply(params: Map<String, kotlinx.serialization.json.JsonElement>, cid: String) {
        val amount = params["amount"]?.toString()?.trim('"')?.toDoubleOrNull() ?: 0.0
        if (amount <= 0) { runFollowUp("预算设置失败：金额无效", cid); return }
        val scope = params["scope"]?.toString()?.trim('"') ?: "total"
        val cat = params["category"]?.toString()?.trim('"') ?: ""
        if (scope == "category") {
            // G11：AI 可能传中文/未知分类，非法 key 会存出「其他」预算并撞通知 id → 拒绝并告知
            val catKey = com.shanji.app.data.normalizeCatKey(cat)
            if (catKey == null) { runFollowUp("分类「$cat」不认识，预算没有设置", cid); return }
            AppStore.budgets.set("category", catKey, amount)
            runFollowUp("已设置${Categories.of(catKey).label}预算 ¥${fmtAmount(amount)}（月度循环）", cid)
        } else {
            AppStore.budgets.set("total", "", amount)
            runFollowUp("已设置总预算 ¥${fmtAmount(amount)}（月度循环）", cid)
        }
    }

    /** subscribe 动作：账单摘要交服务端 AI 判断订阅（F7），AI 不可用降级本地规则，结果回传 AI 出第二轮 */
    fun runSubscribeThenReply(cid: String) {
        scope.launch {
            val scan = withContext(Dispatchers.IO) {
                com.shanji.app.data.SubscriptionDetector.scan(
                    ctx,
                    AppStore.store.txs,
                    AppStore.recurring.list.map { it.merchant },
                    AppStore.accounts.defaultId()
                )
            }
            val suffix = if (scan.usedAi) "" else "（AI 识别不可用，已改用本地规则）"
            runFollowUp(com.shanji.app.data.SubscriptionDetector.summarize(scan.items) + suffix, cid)
        }
    }

    /** 处理动作行（#ACT# 里的 actions）。cid = 动作所属会话（不依赖当前选中会话） */
    fun handleAct(actJson: String, cid: String) {
        val act = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(actJson)
                .let { it as? kotlinx.serialization.json.JsonObject ?: return@runCatching null }
        }.getOrNull() ?: return
        val actions = (act["actions"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { el ->
            val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val fn = (o["fn"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
            val params = o["params"] as? kotlinx.serialization.json.JsonObject
            AiAction(fn, params?.mapValues { it.value } ?: emptyMap())
        } ?: emptyList()

        val add = actions.firstOrNull { it.fn == "addTx" }
        if (add != null) {
            val amount = add.params["amount"]?.toString()?.trim('"')?.toDoubleOrNull() ?: 0.0
            if (amount > 0) {
                onAddTx(
                    amount,
                    add.params["merchant"]?.toString()?.trim('"') ?: "",
                    add.params["category"]?.toString()?.trim('"') ?: "other",
                    add.params["type"]?.toString()?.trim('"') ?: "expense"
                )
            }
        }
        val query = actions.firstOrNull { it.fn == "queryTx" }
        if (query != null) {
            runQueryThenReply(query.params, cid)
        }
        val trend = actions.firstOrNull { it.fn == "trend" }
        if (trend != null) {
            runTrendThenReply(trend.params, cid)
        }
        val budget = actions.firstOrNull { it.fn == "budget_set" }
        if (budget != null) {
            runBudgetSetThenReply(budget.params, cid)
        }
        if (actions.any { it.fn == "subscribe" }) {
            runSubscribeThenReply(cid)
        }
    }

    /** 卡片按钮：queryTx 直接弹明细（结论落到操作，不再绕 AI 二轮）；budget_set/addTx 本地执行 */
    fun cardAction(btn: com.shanji.app.data.net.AiCardBtn) {
        when (btn.fn) {
            "queryTx" -> traceQuery = btn.params.mapValues { it.value.toString().trim('"') }
            "budget_set" -> {
                val amount = btn.params["amount"]?.toString()?.trim('"')?.toDoubleOrNull() ?: 0.0
                if (amount > 0) {
                    val scope = btn.params["scope"]?.toString()?.trim('"') ?: "total"
                    val cat = btn.params["category"]?.toString()?.trim('"') ?: ""
                    if (scope == "category") {
                        // G11：分类 key 合法性校验，非法则忽略并提示
                        val catKey = com.shanji.app.data.normalizeCatKey(cat)
                        if (catKey == null) {
                            android.widget.Toast.makeText(
                                ctx, "分类「$cat」不认识，预算没设置", android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            AppStore.budgets.set("category", catKey, amount)
                            android.widget.Toast.makeText(
                                ctx, "已设置${Categories.of(catKey).label}预算 ¥${fmtAmount(amount)}",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    } else {
                        AppStore.budgets.set("total", "", amount)
                        android.widget.Toast.makeText(
                            ctx, "已设置总预算 ¥${fmtAmount(amount)}", android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            "addTx" -> {
                val amount = btn.params["amount"]?.toString()?.trim('"')?.toDoubleOrNull() ?: 0.0
                if (amount > 0) {
                    onAddTx(
                        amount,
                        btn.params["merchant"]?.toString()?.trim('"') ?: "",
                        btn.params["category"]?.toString()?.trim('"') ?: "other",
                        btn.params["type"]?.toString()?.trim('"') ?: "expense"
                    )
                }
            }
        }
    }

    /** 溯源链接 shanji-trace:category=dining&month=2026-11 → 弹出对应账单明细 */
    fun traceLink(url: String) {
        val q = url.substringAfter("shanji-trace:", "")
        val params = q.split("&").mapNotNull { kv ->
            val i = kv.indexOf('=')
            if (i > 0) kv.substring(0, i) to kv.substring(i + 1) else null
        }.toMap()
        if (params.isNotEmpty()) traceQuery = params
    }

    // ===== 历史抽屉（覆盖层，left/right 位移，禁 transform）=====
    var drawerJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    /** 打开抽屉：先加载会话列表；挂载后下一帧再翻目标态产生滑入动画 */
    fun openDrawer() {
        drawerJob?.cancel()
        drawerJob = scope.launch {
            conversations = withContext(Dispatchers.IO) { ChatStore.list(ctx) }
            showHistory = true
            delay(32) // 等首帧以 -width 初始位挂载
            drawerOpen = true
        }
    }

    /** 关闭抽屉：先滑出，动画结束再卸载（与 openDrawer 互斥，防止半开半关竞态） */
    fun closeDrawer() {
        drawerJob?.cancel()
        drawerJob = scope.launch {
            drawerOpen = false
            delay(320)
            showHistory = false
        }
    }

    /** 切换会话：只改 convId，加载与订阅由 LaunchedEffect(convId) 统一处理 */
    fun openConversation(id: String) {
        if (id == convId) { closeDrawer(); return }
        convId = id
        closeDrawer()
    }

    // 返回键：先关抽屉（重命名对话框是独立窗口，系统返回先关它），绝不直接退出 App
    BackHandler(enabled = showHistory) { closeDrawer() }

    fun send() {
        val q = input.trim()
        if (q.isEmpty() || busy) return
        val cid = convId ?: return
        input = ""; startPending = true
        android.util.Log.d("SHANJI", "send: 发送 q=${q.take(30)}")
        items.add(ChatItem("u${System.nanoTime()}", "user", q))
        // 规则5：用户发送 → 强制滚到底（即使此前在上面翻历史）；reduced-motion 跳过平滑
        isAtBottom = true
        scope.launch {
            if (reducedMotion) listState.scrollToBottom()
            else listState.animateScrollToBottom()
        }
        scope.launch {
            withContext(Dispatchers.IO) { ChatStore.append(ctx, cid, "user", q) }
            android.util.Log.d("SHANJI", "send: user 已入库")
            val mem = withContext(Dispatchers.IO) {
                ChatStore.maybeAutoTitle(ctx, cid, q)
                ChatStore.buildMemory(ctx, cid)
            }
            android.util.Log.d("SHANJI", "send: memory 构建完成 msgs=${mem.size}")
            val req = AiChatReq(mem.map { AiChatMsg(it.first, it.second) }, monthContext(), persona)
            runCatching { ChatStreamManager.start(ctx, cid, req) }
                .onSuccess { startPending = false } // 气泡与增量由快照同步负责
                .onFailure {
                    startPending = false
                    android.util.Log.w("SHANJI", "send start fail: ${it.message}")
                    val msg = it.message ?: "没查成，稍后再试"
                    items.add(ChatItem("e${System.nanoTime()}", "assistant", msg))
                    withContext(Dispatchers.IO) { ChatStore.append(ctx, cid, "assistant", msg) }
                }
        }
    }

    // ===== 初始：恢复未完成任务（规则5：杀进程/断线回来续传），再打开最近会话 =====
    LaunchedEffect(Unit) {
        ChatStreamManager.resumePending(ctx)
        val conv = withContext(Dispatchers.IO) { ChatStore.latestOrCreate(ctx) }
        convId = conv.id
    }

    // ===== 会话加载 + 订阅该会话的流（规则3：切走即取消订阅，管理器连接与缓冲继续）=====
    LaunchedEffect(convId) {
        val cid = convId ?: return@LaunchedEffect
        batchCuts.clear()   // 新会话：批次揭示进度清零（历史消息按全文初始化，不重播）
        batchSettled.clear()
        val msgs = withContext(Dispatchers.IO) { ChatStore.messages(ctx, cid) }
        android.util.Log.d(
            "SHANJI",
            "conv load id=${cid.take(6)} count=${msgs.size} lastId=${msgs.lastOrNull()?.id?.take(8)} " +
                "lastHead=${msgs.lastOrNull()?.content?.take(30)}"
        )
        items.clear()
        visible(msgs).forEach { items.add(ChatItem(it.id, it.role, cleanText(it.content))) }
        android.util.Log.d(
            "SHANJI",
            "conv items.size=${items.size} detail=" + items.mapIndexed { i, c ->
                "$i:${c.id.take(6)}:${c.text.length}"
            }.joinToString(",")
        )
        isAtBottom = true
        // 打开会话定位到底部（不阻塞订阅：另起子协程）
        launch {
            if (items.isEmpty()) return@launch
            listState.scrollToBottom()
        }
        // 订阅：StateFlow replay 立即渲染已有缓冲（规则4 切回立即显示），之后增量实时追加
        ChatStreamManager.snapshots.collect { map ->
            val tasks = map.values.filter { it.conversationId == cid }.sortedBy { it.order }
            for (t in tasks) {
                if (t.messageId in deletedIds) continue // 已长按删除：不再回加
                val streaming = t.status == ChatStreamManager.Status.STREAMING
                val text = cleanText(t.text)
                val idx = items.indexOfFirst { it.id == t.messageId }
                if (idx >= 0) {
                    val cur = items[idx]
                    if (cur.text != text || cur.streaming != streaming) {
                        items[idx] = cur.copy(text = text, streaming = streaming)
                    }
                } else {
                    // 不在列表（Room 里没有 =尚未落库的进行中任务）→ 追加气泡
                    items.add(ChatItem(t.messageId, "assistant", text, streaming))
                }
            }
            // 首轮动作行：DONE 后一次性消费（幂等），由所属会话执行
            for (t in tasks) {
                if (t.status == ChatStreamManager.Status.DONE && t.act != null) {
                    val act = ChatStreamManager.consumeAct(t.messageId)
                    if (act != null) {
                        android.util.Log.d("SHANJI", "act consumed id=${t.messageId.take(8)}")
                        handleAct(act, cid)
                    }
                }
            }
        }
    }

    val hasMessages = items.isNotEmpty()

    Column(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding() // 键盘弹出时输入框顶到键盘上方
            // 右滑打开历史抽屉（抽屉挂载时其遮罩在上层，本 Column 不在命中路径，不会误触发）
            .pointerInput(Unit) {
                var totalDx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalDx = 0f },
                    onDragEnd = { if (totalDx > dragThresholdPx) openDrawer() },
                    onHorizontalDrag = { _, d -> totalDx += d }
                )
            }
    ) {
        // 页头：与其它 tab 一致的大标题 + 历史/新对话
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("问账本", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { openDrawer() }) {
                Icon(Icons.Filled.History, "历史对话", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = {
                scope.launch {
                    val c = withContext(Dispatchers.IO) { ChatStore.newConversation(ctx) }
                    openConversation(c.id)
                }
            }) {
                Icon(Icons.Filled.Add, "新对话", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // 列表区包进 Box：回到底部按钮悬浮其上（规则4）
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (!hasMessages) {
                // 欢迎态：居中大标题 + 建议 chips（DeepSeek 欢迎页模式）
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("问账本", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "问花费，也管记账",
                        fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(28.dp))
                    listOf(
                        "这个月花了多少",
                        "外卖花了几笔",
                        "记一笔打车 20",
                        "哪类花得最多"
                    ).forEach { q ->
                        Text(
                            q, fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                                .clickable { input = q; send() }
                                // 14sp 文本 + 上下 15dp ≈ 49dp ≥ 48dp 触摸目标
                                .padding(horizontal = 16.dp, vertical = 15.dp)
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(items, key = { it.id }) { m ->
                        ChatBubble(
                            m = m,
                            batchCuts = batchCuts,
                            batchSettled = batchSettled,
                            reducedMotion = reducedMotion,
                            onLongPress = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                sheetTarget = m
                            },
                            onCardAction = { cardAction(it) },
                            onTrace = { traceLink(it) }
                        )
                    }
                    if (busy && items.lastOrNull()?.text?.isEmpty() != true) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.height(16.dp), strokeWidth = 2.dp, color = Primary)
                                Spacer(Modifier.width(8.dp))
                                Text("想一下…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            // 规则4：不在底部时显示「回到底部」，点击平滑滚到底并隐藏
            if (hasMessages && !isAtBottom) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 14.dp)
                        .clickable {
                            isAtBottom = true
                            scope.launch {
                                // reduced-motion：跳过滚动平滑，直接定位到底部
                                if (reducedMotion) listState.scrollToBottom()
                                else listState.animateScrollToBottom()
                            }
                        }
                ) {
                    Row(
                        Modifier
                            .defaultMinSize(minHeight = 48.dp) // 触摸目标 ≥48dp
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.ArrowDownward, null, tint = Primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("回到底部", fontSize = 13.sp, color = Primary)
                    }
                }
            }
        }

        // 胶囊输入框（DeepSeek 风格）
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.take(200) },
                placeholder = { Text("问点什么，或说「记一笔打车 20」") },
                singleLine = true,
                enabled = !busy,
                // 输入字体必须 16sp：低于 16px 时 iOS/WebView 聚焦会自动放大且不回退
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                shape = RoundedCornerShape(999.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                ),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            // 圆形发送钮：有内容才点亮
            val canSend = input.isNotBlank() && !busy
            IconButton(
                onClick = { send() },
                enabled = canSend,
                modifier = Modifier
                    .size(48.dp) // Android 触摸目标 ≥48dp
                    .background(
                        if (canSend) Primary else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(999.dp)
                    )
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send, "发送",
                    tint = if (canSend) androidx.compose.ui.graphics.Color.White
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // ===== 历史抽屉：覆盖层滑入（left/right 位移，禁 transform；宽 = 80vw 动态上限）=====
    if (showHistory) {
        val drawerWidth = with(LocalConfiguration.current) { screenWidthDp.dp * 0.8f }
        val offsetX by animateDpAsState(
            if (drawerOpen) 0.dp else -drawerWidth,
            if (reducedMotion) snap() else tween(280)
        )
        val scrimAlpha by animateFloatAsState(
            if (drawerOpen) 0.4f else 0f,
            if (reducedMotion) snap() else tween(280)
        )
        Box(
            Modifier.fillMaxSize()
                // 左滑关闭（遮罩层统一手势）
                .pointerInput(Unit) {
                    var totalDx = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { totalDx = 0f },
                        onDragEnd = { if (totalDx < -dragThresholdPx) closeDrawer() },
                        onHorizontalDrag = { _, d -> totalDx += d }
                    )
                }
        ) {
            // 遮罩：点按关闭
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = scrimAlpha))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { closeDrawer() }
            )
            // 抽屉面板：Modifier.offset 是布局位移（left/right），非 transform
            Column(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = offsetX)
                    .fillMaxHeight()
                    .fillMaxWidth(0.8f)
                    .background(MaterialTheme.colorScheme.surface)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("历史对话", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { closeDrawer() }) {
                        Icon(Icons.Filled.Close, "关闭", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (conversations.isEmpty()) {
                    Text(
                        "还没有对话", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                }
                conversations.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            Modifier.weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .clickable { openConversation(c.id) }
                                .padding(vertical = 4.dp),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(c.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                            Text(
                                java.time.Instant.ofEpochMilli(c.updatedAt)
                                    .atZone(java.time.ZoneId.systemDefault())
                                    .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")),
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = {
                            renameTarget = c; renameText = c.title
                        }) {
                            Text("改名", fontSize = 12.sp, color = Primary)
                        }
                        IconButton(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { ChatStore.delete(ctx, c.id) }
                                conversations = withContext(Dispatchers.IO) { ChatStore.list(ctx) }
                                if (c.id == convId) {
                                    val next = withContext(Dispatchers.IO) { ChatStore.latestOrCreate(ctx) }
                                    openConversation(next.id)
                                }
                            }
                        }) {
                            Icon(Icons.Filled.Delete, "删除", tint = Red, modifier = Modifier.height(18.dp))
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // ===== 长按气泡 → 底部操作表（Material3 ModalBottomSheet + 轻触觉）=====
    sheetTarget?.let { target ->
        // Back 键只关操作表，不穿透到 MainActivity 的页面导航（后组合者优先）
        BackHandler { sheetTarget = null }
        ModalBottomSheet(
            onDismissRequest = { sheetTarget = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(Modifier.padding(bottom = 24.dp)) {
                SheetActionRow(Icons.Filled.ContentCopy, "复制") {
                    clipboard.setText(AnnotatedString(target.text))
                    sheetTarget = null
                    android.widget.Toast.makeText(ctx, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                }
                if (!target.streaming) {
                    SheetActionRow(Icons.Filled.Delete, "删除", danger = true) {
                        deletedIds.add(target.id)
                        items.removeAll { it.id == target.id }
                        sheetTarget = null
                        scope.launch {
                            withContext(Dispatchers.IO) { ChatStore.deleteMessage(ctx, target.id) }
                        }
                    }
                }
            }
        }
    }

    // ===== 溯源明细：AI 数字/卡片按钮点开 → 底部弹层列出对应账单（共享件，与统计页同一实现）=====
    TraceSheet(
        query = traceQuery,
        onDismiss = { traceQuery = null },
        onEdit = { id -> onEditTx(id) }
    )

    // 重命名
    if (renameTarget != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it.take(20) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = renameTarget ?: return@TextButton
                    scope.launch {
                        withContext(Dispatchers.IO) { ChatStore.rename(ctx, t.id, renameText) }
                        conversations = withContext(Dispatchers.IO) { ChatStore.list(ctx) }
                    }
                    renameTarget = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ChatBubble(
    m: ChatItem,
    batchCuts: androidx.compose.runtime.snapshots.SnapshotStateMap<String, List<Int>>,
    batchSettled: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Int>,
    reducedMotion: Boolean,
    onLongPress: () -> Unit,
    onCardAction: (com.shanji.app.data.net.AiCardBtn) -> Unit = {},
    onTrace: (String) -> Unit = {}
) {
    val isUser = m.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        if (isUser) {
            // 用户消息：强调色气泡贴合文字——限宽80%屏宽，PrimaryDeep 底白字对比度4.7:1 ≥4.5
            val maxBubble = with(LocalConfiguration.current) { (screenWidthDp * 0.8f).dp }
            Text(
                m.text,
                fontSize = 14.sp,
                color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier
                    .pointerInput(m.id) { detectTapGestures(onLongPress = { onLongPress() }) }
                    .widthIn(max = maxBubble)
                    .background(PrimaryDeep, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            )
        } else {
            // #CARD# 结构化卡片行：从正文剥离，正文走批次揭示，卡片渲染在气泡下方
            val bodyText = m.text.substringBefore("\n#CARD#").substringBefore("#CARD#")
            val card = remember(m.id, m.text) {
                val idx = m.text.indexOf("#CARD#")
                if (idx < 0) null else runCatching {
                    kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                        .decodeFromString<com.shanji.app.data.net.AiCard>(
                            m.text.substring(idx + 6).trim()
                        )
                }.getOrNull()
            }
            // ===== 段落批次揭示：本地播放队列，攒到安全边界整批 ~520ms 淡入 =====
            val currentText by rememberUpdatedState(bodyText)
            val currentStreaming by rememberUpdatedState(m.streaming)
            if (m.id !in batchCuts) {
                // 空缓冲新任务 → 从零攒批；已有缓冲（切回/历史）→ 立即全文渲染（规则4）
                val instant = !(m.streaming && bodyText.isEmpty())
                batchCuts[m.id] = if (instant) listOf(bodyText.length) else emptyList()
                batchSettled[m.id] = if (instant) 1 else 0
                android.util.Log.d(
                    "SHANJI", "batch init id=${m.id.take(6)} instant=$instant len=${bodyText.length}"
                )
            }
            val cuts = batchCuts[m.id] ?: emptyList()
            val settledCount = batchSettled[m.id] ?: 0
            val full = bodyText

            // 播放驱动：按安全边界逐批揭示；上一批淡入完成才切下一批（有界节奏）
            LaunchedEffect(m.id) {
                while (true) {
                    delay(80)
                    val text = currentText
                    val streaming = currentStreaming
                    val cs = batchCuts[m.id] ?: break
                    val settled = batchSettled[m.id] ?: 0
                    if (cs.size > settled) continue          // 上一批还在淡入
                    val revealed = cs.lastOrNull() ?: 0
                    if (revealed >= text.length) {
                        if (!streaming) break                // 完成且已全部揭示
                        continue                             // 等更多文本
                    }
                    val cut = nextRevealCut(text, revealed, streamDone = !streaming) ?: continue
                    if (cut <= revealed) continue
                    batchCuts[m.id] = cs + cut
                    android.util.Log.d(
                        "SHANJI", "batch id=${m.id.take(6)} [${revealed},$cut)/${text.length} streaming=$streaming"
                    )
                }
            }

            // 闪烁光标（streaming 尾部；reduced-motion 下不闪常显）
            var blink by remember(m.id) { mutableStateOf(true) }
            LaunchedEffect(m.id, m.streaming, reducedMotion) {
                if (!m.streaming || reducedMotion) return@LaunchedEffect
                while (true) { delay(500); blink = !blink }
            }

            Column(
                Modifier.fillMaxWidth()
                    .pointerInput(m.id) { detectTapGestures(onLongPress = { onLongPress() }) },
                verticalArrangement = Arrangement.spacedBy(6.dp) // 与 MarkdownText 块间距一致，分批=整块 → 合并视图等价
            ) {
                if (cuts.isEmpty()) {
                    // 预缓冲期：尚无批次 → 独立活动光标
                    if (m.streaming) Text("▍", fontSize = 14.sp, color = Primary)
                } else {
                    var start = 0
                    cuts.forEachIndexed { i, rawEnd ->
                        val s = start.coerceAtMost(full.length)
                        val e = rawEnd.coerceAtMost(full.length)
                        if (e > s) {
                            val seg = full.substring(s, e)
                            val isLast = i == cuts.lastIndex
                            // 尾批光标：trim 尾部换行再接，避免光标自成一块；旧批不持光标 → 不重排
                            val body = seg.dropLastWhile { it == '\n' }
                            val tailNl = seg.length - body.length
                            val cursor = if (isLast && m.streaming && (blink || reducedMotion)) "▍" else ""
                            val cursorPart = when {
                                cursor.isEmpty() -> ""
                                body.endsWith("```") || body.endsWith("|") -> "\n$cursor" // 围栏/表格行后换行，防破坏行结构
                                else -> cursor
                            }
                            val segText = body + cursorPart + "\n".repeat(tailNl)
                            key(i) {
                                val alpha = remember(m.id, i, cuts.size) {
                                    Animatable(
                                        if (i < (batchSettled[m.id] ?: 0)) 1f else 0f
                                    )
                                }
                                LaunchedEffect(m.id, i, cuts.size) {
                                    if (i >= (batchSettled[m.id] ?: 0)) {
                                        // 新批次：~520ms 淡入后定稿
                                        if (reducedMotion) alpha.snapTo(1f)
                                        else {
                                            alpha.snapTo(0f)
                                            alpha.animateTo(1f, tween(520))
                                        }
                                        batchSettled[m.id] = i + 1
                                        android.util.Log.d("SHANJI", "batch settle id=${m.id.take(6)} idx=$i")
                                    }
                                }
                                MarkdownText(
                                    segText,
                                    Modifier.fillMaxWidth().alpha(alpha.value),
                                    onLink = { onTrace(it) }
                                )
                            }
                        }
                        start = rawEnd
                    }
                }
                // 结构化卡片：标题 + 要点行 + 可点按钮（AI 结论落到操作）
                if (card != null && cuts.isNotEmpty()) {
                    AiCardView(card, onClick = { onCardAction(it) })
                }
            }
        }
    }
}

/** AI 结构化卡片：灰底圆角，标题/要点/操作按钮 */
@Composable
private fun AiCardView(card: com.shanji.app.data.net.AiCard, onClick: (com.shanji.app.data.net.AiCardBtn) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(card.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        card.lines.forEach {
            Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)
        }
        if (card.buttons.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                card.buttons.forEach { btn ->
                    Text(
                        btn.label, fontSize = 13.sp, color = Primary,
                        modifier = Modifier
                            .background(Primary.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
                            .clickable { onClick(btn) }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }
            }
        }
    }
}

/** 长按操作表单行：48dp 触摸目标 */
@Composable
private fun SheetActionRow(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon, null,
            tint = if (danger) Red else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(14.dp))
        Text(label, fontSize = 15.sp, color = if (danger) Red else MaterialTheme.colorScheme.onSurface)
    }
}
