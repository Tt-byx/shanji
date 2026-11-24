package com.shanji.app.data.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/* ---- AI 中转接口（服务器持有 GLM key） DTO ---- */

@Serializable
data class AiParseReq(
    val rawText: String,
    val persona: String? = null,
    /** 用户本机学到的分类规则摘要（商户+金额+时段 → 分类），让 AI 越用越准 */
    val rules: List<String>? = null
)

@Serializable
data class AiParseData(
    val amount: Double = 0.0,
    val type: String = "expense",
    val merchant: String = "",
    val category: String = "other",
    val note: String = ""
)

@Serializable
data class AiChatMsg(val role: String, val content: String)

@Serializable
data class AiChatReq(
    val messages: List<AiChatMsg>,
    val context: String? = null,
    val persona: String? = null
)

@Serializable
data class AiAction(
    val fn: String = "",
    // 服务端 params 值可能是数字/字符串，用 JsonElement 兜住
    val params: Map<String, JsonElement> = emptyMap()
)

@Serializable
data class AiChatData(
    val reply: String = "",
    val actions: List<AiAction> = emptyList()
)

@Serializable
data class AiInsightReq(
    val kind: String,
    val ruleHits: List<String> = emptyList(),
    val stats: String? = null,
    val persona: String? = null
)

@Serializable
data class AiInsightData(val text: String = "")

@Serializable
data class AiVisionReq(val image: String) // base64 JPEG（不带 data: 前缀）

@Serializable
data class AiVisionData(
    val amount: Double? = null,
    val merchant: String? = null,
    val time: String? = null    // "yyyy-MM-dd HH:mm"，识别不出为 null
)

/* ---- AI 订阅识别（F7：真调 AI 语义判断，本地规则只作兜底） ---- */

@Serializable
data class SubscribeScanReq(
    /** 近 N 个月账单摘要，每行：yyyy-MM-dd|商户|金额|分类key（服务端不存，只做本次判断） */
    val txSummary: String,
    /** 已有订阅/规则的商户名，识别结果里跳过 */
    val existing: List<String> = emptyList()
)

@Serializable
data class SubscribeScanItem(
    val name: String = "",
    val amount: Double = 0.0,
    val category: String = "other",
    val cycle: String = "monthly",   // monthly | yearly
    val dayOfMonth: Int = 1,
    val dueMonth: Int = 0            // yearly 1~12
)

@Serializable
data class SubscribeScanData(val items: List<SubscribeScanItem> = emptyList())

/* ---- AI 结构化卡片（#ACT# JSON 的 card 字段，随正文 #CARD# 行持久化） ---- */

@Serializable
data class AiCardBtn(
    val label: String = "",
    val fn: String = "",        // queryTx | budget_set | addTx
    val params: Map<String, JsonElement> = emptyMap()
)

@Serializable
data class AiCard(
    val title: String = "",
    val lines: List<String> = emptyList(),
    val buttons: List<AiCardBtn> = emptyList()
)

interface AiApi {
    // AI 接口服务端已放开匿名访问：有 token 就带（Retrofit 对 null 不发该头），没有也能用
    @POST("api/ai/parse")
    suspend fun parse(
        @Header("Authorization") bearer: String?,
        @Body req: AiParseReq
    ): ApiResponse<AiParseData>

    /** 截图识别（多模态）：端上只传压缩后的 base64，key 在服务端 */
    @POST("api/ai/vision")
    suspend fun vision(
        @Header("Authorization") bearer: String?,
        @Body req: AiVisionReq
    ): ApiResponse<AiVisionData>

    @POST("api/ai/chat")
    suspend fun chat(
        @Header("Authorization") bearer: String?,
        @Body req: AiChatReq
    ): ApiResponse<AiChatData>

    /** 启动后台生成任务，立即返回 messageId（生成在服务端执行，不依赖客户端连接） */
    @POST("api/ai/chat/task")
    suspend fun startTask(
        @Header("Authorization") bearer: String?,
        @Body req: AiChatReq
    ): ApiResponse<String>

    /**
     * SSE 断点续传读取：offset = 已收到的正文字符数，服务端只补发缺失部分；
     * 任务已完成则补发完整内容后发 done 关闭。
     * @Streaming 必须有：否则 Retrofit 把整个响应缓冲进内存，流式即失效。
     */
    @Streaming
    @GET("api/ai/chat/stream/{id}")
    suspend fun streamTask(
        @Header("Authorization") bearer: String?,
        @Path("id") id: String,
        @Query("offset") offset: Long
    ): okhttp3.ResponseBody

    @POST("api/ai/insight")
    suspend fun insight(
        @Header("Authorization") bearer: String?,
        @Body req: AiInsightReq
    ): ApiResponse<AiInsightData>

    /** 订阅识别：客户端把账单摘要交给服务端 AI 判断（F7），失败由客户端本地规则兜底 */
    @POST("api/ai/subscribe-scan")
    suspend fun subscribeScan(
        @Header("Authorization") bearer: String?,
        @Body req: SubscribeScanReq
    ): ApiResponse<SubscribeScanData>
}
