package com.shanji.app.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/* ============================================================
 * AI 识别：通用 OpenAI 兼容多模态接口（vision chat/completions）
 * 智谱 GLM-4V / 通义 qwen-vl / Kimi / DeepSeek-VL / OpenAI 均兼容。
 * 用户在「我的 → AI 识别设置」里自行填 baseUrl / apiKey / model。
 * ============================================================ */

object AiSettings {
    private const val PREFS = "shanji_ai"

    data class Config(val baseUrl: String, val apiKey: String, val model: String) {
        val ready: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
    }

    fun load(ctx: Context): Config {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            baseUrl = p.getString("baseUrl", "") ?: "",
            apiKey = p.getString("apiKey", "") ?: "",
            model = p.getString("model", "") ?: ""
        )
    }

    fun save(ctx: Context, baseUrl: String, apiKey: String, model: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("baseUrl", baseUrl.trim())
            .putString("apiKey", apiKey.trim())
            .putString("model", model.trim())
            .apply()
    }
}

/** 识别结果：只预填，用户确认后入库 */
data class AiResult(
    val amount: Double?,
    val merchant: String?,
    val time: String?   // "yyyy-MM-dd HH:mm"，解析失败为 null
)

/** 连接测试报告：区分域名不通 / Key 无效 / 地址 404 / 其他 */
data class TestReport(val ok: Boolean, val message: String)

object AiRecognizer {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class ChatResponse(val choices: List<Choice> = emptyList())

    @Serializable
    private data class Choice(val message: Message? = null)

    @Serializable
    private data class Message(val content: String? = null)

    /**
     * 调用多模态模型识别付款截图（同步阻塞，请在 IO 线程调用）。
     * @param jpegBytes 已压缩的 JPEG 截图
     * @throws Exception 网络/HTTP/解析错误，调用方降级为手动
     */
    fun recognize(baseUrl: String, apiKey: String, model: String, jpegBytes: ByteArray): AiResult {
        val b64 = Base64.getEncoder().encodeToString(jpegBytes)
        val body = buildJsonObject {
            put("model", model)
            put("temperature", 0.1)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", "你是支付账单识别助手。从付款截图中提取信息，只返回一个 JSON 对象，格式：" +
                            "{\"amount\": 数字(支出金额，正数), \"merchant\": \"商户或事项名称\", \"time\": \"yyyy-MM-dd HH:mm\"(付款时间,未知则为 null)}。" +
                            "不要返回任何其他文字、解释或 markdown 代码块标记。")
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", "识别这张付款截图的金额、商户和付款时间")
                        })
                        add(buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject {
                                put("url", "data:image/jpeg;base64,$b64")
                            })
                        })
                    })
                })
            })
        }.toString()

        val conn = (URL(baseUrl.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20000
            readTimeout = 60000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code: ${text.take(200)}")
            }
            val resp = json.decodeFromString<ChatResponse>(text)
            val content = resp.choices.firstOrNull()?.message?.content
                ?: throw IllegalStateException("模型返回为空")
            return parseResult(content)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 连接测试：发一条极小的文本请求（几乎不耗 token），
     * 分别验证 Base URL 可达、API Key 有效、模型名正确。
     * 同步阻塞，请在 IO 线程调用。
     */
    fun testConnection(baseUrl: String, apiKey: String, model: String): TestReport {
        if (baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) {
            return TestReport(false, "请先填写完整的接口地址、API Key 和模型名")
        }
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 16)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "请只回复两个字：正常")
                })
            })
        }.toString()

        val conn = try {
            (URL(baseUrl.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10000
                readTimeout = 20000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
        } catch (e: Exception) {
            return TestReport(false, "接口地址格式不正确：" + (e.message ?: e.javaClass.simpleName))
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val errText = (if (code in 200..299) null else conn.errorStream)
                ?.bufferedReader()?.readText()?.take(300) ?: ""
            return when {
                code in 200..299 -> {
                    val ok = runCatching { json.decodeFromString<ChatResponse>(errText.ifEmpty { conn.inputStream.bufferedReader().readText() }) }
                        .getOrNull()?.choices?.isNotEmpty() == true
                    if (ok) TestReport(true, "连接成功：服务可达、Key 有效、模型 $model 可用")
                    else TestReport(true, "服务已连通（HTTP $code），模型响应格式可能不是 OpenAI 兼容，可尝试实际识别一次")
                }
                code == 401 || code == 403 -> TestReport(false, "API Key 无效或无权限（HTTP $code）" + errDetail(errText))
                code == 404 -> TestReport(false, "接口地址不存在（HTTP 404）。常见原因：Base URL 需要填到 /v1 或 /v4 这一级，而不是域名根地址" + errDetail(errText))
                code == 429 -> TestReport(false, "请求频率/额度受限（HTTP 429），Key 有效但配额不足" + errDetail(errText))
                else -> TestReport(false, "服务返回 HTTP $code" + errDetail(errText))
            }
        } catch (e: java.net.UnknownHostException) {
            return TestReport(false, "无法解析域名「${baseUrl.host()}」，请检查接口地址拼写")
        } catch (e: java.net.ConnectException) {
            return TestReport(false, "连接被拒绝，请检查接口地址和端口")
        } catch (e: java.net.SocketTimeoutException) {
            return TestReport(false, "连接超时，请检查网络或稍后再试")
        } catch (e: Exception) {
            return TestReport(false, "测试失败：" + (e.message ?: e.javaClass.simpleName))
        } finally {
            conn.disconnect()
        }
    }

    private fun errDetail(errText: String): String =
        if (errText.isBlank()) "" else " · " + errText.replace(Regex("\\s+"), " ").take(160)

    private fun String.host(): String = runCatching { URL(this).host }.getOrDefault(this)

    /** 从模型回复里抠出 JSON（容忍 ```json 包裹与前后废话） */
    internal fun parseResult(content: String): AiResult {
        val raw = Regex("\\{[^{}]*\\}", RegexOption.DOT_MATCHES_ALL)
            .findAll(content).lastOrNull()?.value
            ?: throw IllegalStateException("回复中没有 JSON")
        val obj: JsonObject = json.parseToJsonElement(raw).jsonObject
        val amount = obj["amount"]?.jsonPrimitive?.content?.toDoubleOrNull()
        val merchant = obj["merchant"]?.jsonPrimitive?.content?.take(30)
        val time = obj["time"]?.jsonPrimitive?.content
            ?.takeIf { Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}").matches(it) }
        return AiResult(amount, merchant, time)
    }
}
