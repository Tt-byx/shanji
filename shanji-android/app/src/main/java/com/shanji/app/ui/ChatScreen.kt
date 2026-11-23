package com.shanji.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shanji.app.data.AppStore
import com.shanji.app.data.ChatStore
import com.shanji.app.data.ChatStreamManager
import com.shanji.app.data.Ledger
import com.shanji.app.data.fmtAmount
import com.shanji.app.data.net.AiAction
import com.shanji.app.data.net.AiChatMsg
import com.shanji.app.data.net.AiChatReq
import com.shanji.app.ui.theme.Primary
import com.shanji.app.ui.theme.Red
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
@Composable
fun ChatScreen(
    onAddTx: (amount: Double, merchant: String, category: String, type: String) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<ChatItem>() }
    var convId by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var startPending by remember { mutableStateOf(false) } // POST /chat/task 进行中
    var showHistory by remember { mutableStateOf(false) }
    var conversations by remember { mutableStateOf<List<com.shanji.app.data.db.ConversationEntity>>(emptyList()) }
    var renameTarget by remember { mutableStateOf<com.shanji.app.data.db.ConversationEntity?>(null) }
    var renameText by remember { mutableStateOf("") }
    val persona = remember {
        ctx.getSharedPreferences("shanji_prefs", android.content.Context.MODE_PRIVATE)
            .getString("ai_persona", null)
    }
    val listState = rememberLazyListState()
    // 流式显示进度：bubbleId → 已流出的字符数（Agent 式逐字流出的本地节拍进度）。
    // 放在页面级（而非气泡内 remember），滚动/重组导致 item 重建也不丢进度。
    val typedChars = remember { androidx.compose.runtime.snapshots.SnapshotStateMap<String, Int>() }

    // ===== 吸底滚动状态（规则1：贴底判定 = 末项底边距视口底边 ≤ 阈值）=====
    val density = LocalDensity.current
    val bottomThresholdPx = with(density) { 72.dp.toPx() } // 规则建议 50~80px
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

    /** 本月统计上下文（给 AI 的数字必须来自本机，禁止编造） */
    fun monthContext(): String {
        val ym = YearMonth.now()
        val s = Ledger.monthSummary(AppStore.store.txs, ym)
        val cats = Ledger.categoryBreakdown(Ledger.monthTx(AppStore.store.txs, ym)).take(5)
        val catLine = cats.joinToString("、") { "${it.cat.label}¥${fmtAmount(it.amount)}" }
        return "本月支出¥${fmtAmount(s.expense)} 收入¥${fmtAmount(s.income)} 结余¥${fmtAmount(s.balance)}；分类：$catLine"
    }

    /** 本轮查账结果回传 AI 出第二轮（第二轮也走后台任务，allowAct=false 不递归） */
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
    }

    /** 切换会话：只改 convId，加载与订阅由 LaunchedEffect(convId) 统一处理 */
    fun openConversation(id: String) {
        if (id == convId) { showHistory = false; return }
        convId = id
        showHistory = false
    }

    fun send() {
        val q = input.trim()
        if (q.isEmpty() || busy) return
        val cid = convId ?: return
        input = ""; startPending = true
        android.util.Log.d("SHANJI", "send: 发送 q=${q.take(30)}")
        items.add(ChatItem("u${System.nanoTime()}", "user", q))
        // 规则5：用户发送 → 强制平滑滚到底（即使此前在上面翻历史）
        isAtBottom = true
        scope.launch { listState.animateScrollToBottom() }
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
        typedChars.clear() // 新会话：流式进度清零（历史消息按全文初始化，不重播）
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
    ) {
        // 页头：与其它 tab 一致的大标题 + 历史/新对话
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("问账本", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {
                scope.launch {
                    conversations = withContext(Dispatchers.IO) { ChatStore.list(ctx) }
                    showHistory = true
                }
            }) {
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
                                .padding(horizontal = 16.dp, vertical = 13.dp)
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
                    items(items, key = { it.id }) { m -> ChatBubble(m, typedChars) }
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
                                listState.animateScrollToBottom()
                            }
                        }
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
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
                    .size(44.dp)
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

    // 历史会话列表
    if (showHistory) {
        AlertDialog(
            onDismissRequest = { showHistory = false },
            title = { Text("历史对话") },
            text = {
                Column(Modifier.height(320.dp).verticalScroll(rememberScrollState())) {
                    if (conversations.isEmpty()) {
                        Text("还没有对话", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    conversations.forEach { c ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                Modifier.weight(1f).clickable {
                                    showHistory = false
                                    openConversation(c.id)
                                }
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
                }
            },
            confirmButton = {
                TextButton(onClick = { showHistory = false }) { Text("关闭") }
            }
        )
    }

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
    typedChars: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Int>
) {
    val isUser = m.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        if (isUser) {
            // 用户消息：浅色气泡贴合文字——限宽换行，文字多长气泡多长，整体靠右
            Text(
                m.text,
                fontSize = 14.sp,
                color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .background(Primary, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            )
        } else {
            // ===== Agent 式逐字流出（参考 C:\Users\28182\Desktop\Agent 前端效果）=====
            // 数据到达节奏无关紧要：本地节拍器按 token 流出感连续推进字符（渐快出、渐慢收），
            // 尾部 streaming 期间显示闪烁光标 ▍（1s blink，同 Agent 的 CSS 效果）。
            val currentText by rememberUpdatedState(m.text)
            if (m.id !in typedChars) {
                // 空缓冲的新任务 → 从 0 逐字流出；已有缓冲（切回/历史）→ 立即全文渲染（规则4）
                typedChars[m.id] = if (m.streaming && m.text.isEmpty()) 0 else m.text.length
                android.util.Log.d(
                    "SHANJI", "reveal init id=${m.id.take(6)} streaming=${m.streaming} textLen=${m.text.length}"
                )
            }
            val shown = typedChars[m.id] ?: 0
            LaunchedEffect(m.id) {
                var tick = 0
                while (true) {
                    val len = currentText.length
                    val cur = typedChars[m.id] ?: 0
                    if (cur < len) {
                        // 连续小步流出：剩余多时快（最多4字/25ms≈160字/秒），收尾渐慢到1字
                        typedChars[m.id] = minOf(len, cur + (len - cur).coerceIn(1, 4))
                        if (++tick % 20 == 0) {
                            android.util.Log.d("SHANJI", "reveal id=${m.id.take(6)} ${(typedChars[m.id] ?: 0)}/$len")
                        }
                    }
                    delay(25)
                }
            }
            // 闪烁光标（Agent 同款 step-end blink，500ms 亮灭）
            var blink by remember(m.id) { mutableStateOf(true) }
            LaunchedEffect(m.id, m.streaming) {
                if (!m.streaming) return@LaunchedEffect
                while (true) { delay(500); blink = !blink }
            }
            val len = currentText.length
            val display = currentText.substring(0, minOf(shown, len))
            val cursor = if (m.streaming && blink) "▍" else ""
            val text = display + cursor
            if (text.isEmpty()) {
                Text("▍", fontSize = 14.sp, color = Primary)
            } else {
                MarkdownText(text, Modifier.fillMaxWidth())
            }
        }
    }
}
