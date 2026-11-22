package com.shanji.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shanji.app.data.AiClient
import com.shanji.app.data.AppStore
import com.shanji.app.data.ChatStore
import com.shanji.app.data.Ledger
import com.shanji.app.data.fmtAmount
import com.shanji.app.data.net.AiAction
import com.shanji.app.data.net.AiChatMsg
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
    val text: String
)

/**
 * 伪造打字机的切段器：按句子边界把回复切成"蹦字单位"。
 * 句末标点（。！？；…!?; 及换行，含其后的右引号右括号）后切一段；
 * 超过 40 字没有标点强制切一段兜底。返回各段末字符位置（exclusive）。
 */
private fun segmentEnds(s: String): IntArray {
    if (s.isEmpty()) return IntArray(0)
    val ends = ArrayList<Int>()
    val terminal = "。！？；…!?;\n"
    val tail = "」』\"'）)]"
    var i = 0
    var last = 0
    while (i < s.length) {
        val c = s[i]
        var cut = -1
        if (c in terminal) {
            var j = i + 1
            while (j < s.length && s[j] in tail) j++
            cut = j
        } else if (c == '.' && (i + 1 >= s.length || s[i + 1] == ' ' || s[i + 1] == '\n')) {
            // 英文句点仅在句尾位置切（避免 3.14 这类小数）
            var j = i + 1
            while (j < s.length && s[j] in tail) j++
            cut = j
        }
        if (cut > 0) {
            ends.add(cut); last = cut; i = cut; continue
        }
        if (i + 1 - last >= 40) {
            ends.add(i + 1); last = i + 1
        }
        i++
    }
    return ends.toIntArray()
}

/** DeepSeek 偶发把换行转成字面 &#10;（可能跨 chunk），显示/入库前统一还原 */
private fun cleanText(s: String): String =
    s.replace("&#10;", "\n").replace("&amp;", "&")

/** [系统] 开头是查账回传的内部消息：只进 AI 上下文，不上屏 */
private fun visible(msgs: List<com.shanji.app.data.db.ChatMessageEntity>): List<com.shanji.app.data.db.ChatMessageEntity> =
    msgs.filterNot { it.role == "user" && it.content.startsWith("[系统]") }

/**
 * AI 问账本 tab：DeepSeek 风格 —— 欢迎态居中大标题+建议 chips，
 * 流式输出 + 本地记忆（滑窗/摘要）+ 会话管理，底部胶囊输入框。
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
    var busy by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var conversations by remember { mutableStateOf<List<com.shanji.app.data.db.ConversationEntity>>(emptyList()) }
    var renameTarget by remember { mutableStateOf<com.shanji.app.data.db.ConversationEntity?>(null) }
    var renameText by remember { mutableStateOf("") }
    val persona = remember {
        ctx.getSharedPreferences("shanji_prefs", android.content.Context.MODE_PRIVATE)
            .getString("ai_persona", null)
    }
    val listState = rememberLazyListState()
    // 伪造打字机状态：bubbleId → 已蹦出的段数。
    // 放在页面级（而非气泡内 remember），滚动/重组导致 item 重建也不丢进度。
    val typedSegs = remember { androidx.compose.runtime.snapshots.SnapshotStateMap<String, Int>() }
    // 流式回调在 IO 线程，统一 post 回主线程改 Compose 状态
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }

    // 打开：加载最近会话及其消息（无消息时显示欢迎态，不放气泡）
    LaunchedEffect(Unit) {
        val conv = withContext(Dispatchers.IO) { ChatStore.latestOrCreate(ctx) }
        convId = conv.id
        val msgs = withContext(Dispatchers.IO) { ChatStore.messages(ctx, conv.id) }
        items.clear()
        visible(msgs).forEach { items.add(ChatItem(it.id, it.role, cleanText(it.content))) }
    }

    LaunchedEffect(items.size) {
        if (items.isNotEmpty()) listState.animateScrollToItem(items.size - 1)
    }

    /** 本月统计上下文（给 AI 的数字必须来自本机，禁止编造） */
    fun monthContext(): String {
        val ym = YearMonth.now()
        val s = Ledger.monthSummary(AppStore.store.txs, ym)
        val cats = Ledger.categoryBreakdown(Ledger.monthTx(AppStore.store.txs, ym)).take(5)
        val catLine = cats.joinToString("、") { "${it.cat.label}¥${fmtAmount(it.amount)}" }
        return "本月支出¥${fmtAmount(s.expense)} 收入¥${fmtAmount(s.income)} 结余¥${fmtAmount(s.balance)}；分类：$catLine"
    }

    /** queryTx：本机查 Room → 结果回传 AI 二轮（二轮也流式） */
    fun runQueryThenReply(params: Map<String, kotlinx.serialization.json.JsonElement>, assistantReply: String) {
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

        scope.launch {
            val cid = convId ?: return@launch
            // 第一段回复入库
            if (assistantReply.isNotBlank()) {
                withContext(Dispatchers.IO) { ChatStore.append(ctx, cid, "assistant", assistantReply) }
            }
            withContext(Dispatchers.IO) { ChatStore.append(ctx, cid, "user", "[系统] $resultText") }
            // 二轮：流式
            val mem = withContext(Dispatchers.IO) { ChatStore.buildMemory(ctx, cid) }
            val msgs = mem.map { AiChatMsg(it.first, it.second) }
            val bubbleId = "b${System.nanoTime()}"
            items.add(ChatItem(bubbleId, "assistant", ""))
            var acc = ""
            runCatching {
                AiClient.chatStream(ctx, msgs, monthContext(), persona,
                    onDelta = { d ->
                        mainHandler.post {
                            acc += d
                            val idx = items.indexOfFirst { it.id == bubbleId }
                            if (idx >= 0) items[idx] = items[idx].copy(text = cleanText(acc))
                        }
                    },
                    onAct = { act -> /* 二轮不再递归动作 */ }
                )
            }.onSuccess {
                android.util.Log.d("SHANJI", "runQuery onSuccess acc=${acc.length}")
                if (acc.isBlank()) {
                    val idx = items.indexOfFirst { it.id == bubbleId }
                    if (idx >= 0) items.removeAt(idx)
                } else {
                    val idx = items.indexOfFirst { it.id == bubbleId }
                    if (idx >= 0) items[idx] = items[idx].copy(text = cleanText(acc))
                    withContext(Dispatchers.IO) { ChatStore.append(ctx, cid, "assistant", cleanText(acc).trim()) }
                }
                busy = false
            }.onFailure {
                android.util.Log.w("SHANJI", "runQuery onFailure ${it.javaClass.simpleName}: ${it.message}")
                val idx = items.indexOfFirst { it.id == bubbleId }
                val msg = it.message ?: "没查成，稍后再试"
                if (idx >= 0) items[idx] = items[idx].copy(text = msg)
                withContext(Dispatchers.IO) { ChatStore.append(ctx, cid, "assistant", msg) }
                busy = false
            }
        }
    }

    /** 处理动作行（#ACT# 里的 actions） */
    fun handleAct(actJson: String, streamedSoFar: String) {
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
            runQueryThenReply(query.params, streamedSoFar.trim())
        }
    }

    fun send() {
        val q = input.trim()
        if (q.isEmpty() || busy) return
        val cid = convId ?: return
        input = ""; busy = true
        android.util.Log.d("SHANJI", "send: 发送 q=${q.take(30)}")
        items.add(ChatItem("u${System.nanoTime()}", "user", q))
        scope.launch {
            withContext(Dispatchers.IO) { ChatStore.append(ctx, cid, "user", q) }
            android.util.Log.d("SHANJI", "send: user 已入库")
            val mem = withContext(Dispatchers.IO) {
                ChatStore.maybeAutoTitle(ctx, cid, q)
                ChatStore.buildMemory(ctx, cid)
            }
            android.util.Log.d("SHANJI", "send: memory 构建完成 msgs=${mem.size}")
            val msgs = mem.map { AiChatMsg(it.first, it.second) }
            val bubbleId = "b${System.nanoTime()}"
            items.add(ChatItem(bubbleId, "assistant", ""))
            var acc = ""
            var actJson: String? = null
            runCatching {
                AiClient.chatStream(ctx, msgs, monthContext(), persona,
                    onDelta = { d ->
                        mainHandler.post {
                            acc += d
                            val idx = items.indexOfFirst { it.id == bubbleId }
                            if (idx >= 0) items[idx] = items[idx].copy(text = cleanText(acc))
                            else android.util.Log.w("SHANJI", "delta 但气泡不存在 idx=-1 acc=${acc.length}")
                        }
                    },
                    onAct = { act -> mainHandler.post { actJson = act } }
                )
            }.onSuccess {
                android.util.Log.d("SHANJI", "send onSuccess acc=${acc.length} act=${actJson != null}")
                val act = actJson
                if (act != null) {
                    // 首轮文本留在原气泡按打字机节奏蹦完——绝不删气泡重建
                    //（重建会以全文初始化，一整段直接蹦出，就是用户看到的"一下子"）。
                    // 首轮入库与二轮回复由 runQueryThenReply 负责，二轮另起新气泡继续蹦。
                    handleAct(act, acc)
                } else if (acc.isBlank()) {
                    val idx = items.indexOfFirst { it.id == bubbleId }
                    if (idx >= 0) items[idx] = items[idx].copy(text = "没听懂，换个说法试试")
                    busy = false
                } else {
                    val idx = items.indexOfFirst { it.id == bubbleId }
                    if (idx >= 0) items[idx] = items[idx].copy(text = cleanText(acc))
                    withContext(Dispatchers.IO) { ChatStore.append(ctx, cid, "assistant", cleanText(acc).trim()) }
                    busy = false
                }
            }.onFailure {
                android.util.Log.w("SHANJI", "send onFailure ${it.javaClass.simpleName}: ${it.message}")
                val idx = items.indexOfFirst { it.id == bubbleId }
                val msg = it.message ?: "没查成，稍后再试"
                if (idx >= 0) items[idx] = items[idx].copy(text = msg)
                withContext(Dispatchers.IO) { ChatStore.append(ctx, cid, "assistant", msg) }
                busy = false
            }
        }
    }

    /** 切换/新建会话 */
    fun openConversation(id: String) {
        scope.launch {
            convId = id
            typedSegs.clear() // 新会话：打字机进度清零（历史消息加载时按全文初始化，不重播）
            val msgs = withContext(Dispatchers.IO) { ChatStore.messages(ctx, id) }
            items.clear()
            visible(msgs).forEach { items.add(ChatItem(it.id, it.role, cleanText(it.content))) }
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

        if (!hasMessages) {
            // 欢迎态：居中大标题 + 建议 chips（DeepSeek 欢迎页模式）
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
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
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(items, key = { it.id }) { m -> ChatBubble(m, typedSegs) }
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
    typedSegs: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Int>
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
            // ===== 伪造分段打字机（不依赖网络流速）=====
            // 数据到了也按"句子段"一截一截蹦：节奏完全由本地节拍器控制。
            // 初始值：新消息创建时是空文本 → 从第0段开蹦；历史消息创建即全文 → 段数=全部，直接显示。
            val currentText by rememberUpdatedState(m.text)
            if (m.id !in typedSegs) {
                val initSegs = segmentEnds(m.text).size
                typedSegs[m.id] = initSegs
                android.util.Log.d("SHANJI", "bubble init id=${m.id.take(6)} textLen=${m.text.length} segs=$initSegs")
            }
            val segs = segmentEnds(currentText)
            val shown = typedSegs[m.id] ?: 0
            LaunchedEffect(m.id) {
                while (true) {
                    val total = segmentEnds(currentText).size
                    val cur = typedSegs[m.id] ?: 0
                    if (cur < total) {
                        typedSegs[m.id] = cur + 1
                        android.util.Log.d("SHANJI", "tick id=${m.id.take(6)} ${cur + 1}/$total len=${currentText.length}")
                        // 段少→慢蹦保证短回复也有节奏感；段多→加速防拖沓
                        delay(when { total <= 3 -> 300; total <= 10 -> 200; total <= 18 -> 140; else -> 70 })
                    } else {
                        delay(140) // 已追平，等流式新内容形成下一段
                    }
                }
            }
            val display = when {
                shown <= 0 -> ""
                shown >= segs.size -> currentText
                else -> currentText.substring(0, segs[shown - 1])
            }
            if (display.isEmpty()) {
                // 段还没蹦出来的占位光标
                Text("▌", fontSize = 14.sp, color = Primary)
            } else {
                MarkdownText(display, Modifier.fillMaxWidth())
            }
        }
    }
}
