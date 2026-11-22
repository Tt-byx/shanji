package com.shanji.app.data.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/* ---- AI 中转接口（服务器持有 GLM key） DTO ---- */

@Serializable
data class AiParseReq(val rawText: String, val persona: String? = null)

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

interface AiApi {
    // AI 接口服务端已放开匿名访问：有 token 就带（Retrofit 对 null 不发该头），没有也能用
    @POST("api/ai/parse")
    suspend fun parse(
        @Header("Authorization") bearer: String?,
        @Body req: AiParseReq
    ): ApiResponse<AiParseData>

    @POST("api/ai/chat")
    suspend fun chat(
        @Header("Authorization") bearer: String?,
        @Body req: AiChatReq
    ): ApiResponse<AiChatData>

    /** 流式对话：chunked 文本，调用方逐段读 body.source() */
    @POST("api/ai/chat/stream")
    suspend fun chatStream(
        @Header("Authorization") bearer: String?,
        @Body req: AiChatReq
    ): okhttp3.ResponseBody

    @POST("api/ai/insight")
    suspend fun insight(
        @Header("Authorization") bearer: String?,
        @Body req: AiInsightReq
    ): ApiResponse<AiInsightData>
}
