package com.shanji.app.data

/* ============================================================
 * 截图识别已迁移至服务器（POST /api/ai/vision，key 在服务端）。
 * 原端侧 AiSettings（baseUrl/apiKey/model）与直连多模态模型的
 * AiRecognizer 已废弃删除——见优化建议 2.1：普通用户不知道 API
 * Key 是什么，端侧配置填完也只会在文本 AI 上得到一次 401。
 * ============================================================ */

/** 识别结果：只预填，用户确认后入库 */
data class AiResult(
    val amount: Double?,
    val merchant: String?,
    val time: String?   // "yyyy-MM-dd HH:mm"，解析失败为 null
)
