package dev.cliptranslate.net

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets

/**
 * OpenAI 兼容接口的最小客户端：只用 JDK 的 [HttpURLConnection] + org.json，零第三方依赖。
 *
 * 所有方法都是阻塞式的 —— 调用方必须自己在 Dispatchers.IO 上调用。
 * 失败一律抛 [OpenAiException]，message 是可展示的中文说明。
 */
class OpenAiClient(
    baseUrl: String,
    private val apiKey: String,
    private val connectTimeoutMs: Int = 15000,
    private val readTimeoutMs: Int = 60000,
) {
    private val base: String = normalizeBaseUrl(baseUrl)

    /** POST {base}/chat/completions，返回 choices[0].message.content（已 trim）。 */
    fun chat(model: String, system: String, user: String): String {
        val payload = JSONObject().apply {
            put("model", model)
            put(
                "messages",
                JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", system)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", user)
                    })
                },
            )
            put("temperature", 0.2)
        }
        val raw = execute("POST", "$base/chat/completions", payload.toString())
        val json = parseJsonObjectOrNull(raw)
            ?: throw OpenAiException("接口返回的不是合法 JSON：${raw.truncateForMessage()}")
        val choices = json.optArrayOrNull("choices")
            ?: throw OpenAiException("响应缺少 choices 字段")
        val message = choices.optJSONObject(0)?.optObjectOrNull("message")
            ?: throw OpenAiException("响应缺少 choices[0].message")
        val content = message.optStringOrNull("content")?.trim()
        if (content.isNullOrEmpty()) throw OpenAiException("响应缺少译文内容")
        return content
    }

    /** GET {base}/models，解析 data[] 里每项的 id 与 owned_by；没有 id 的条目跳过。 */
    fun fetchModels(): List<ModelInfo> {
        val raw = execute("GET", "$base/models", null)
        val json = parseJsonObjectOrNull(raw)
            ?: throw OpenAiException("接口返回的不是合法 JSON：${raw.truncateForMessage()}")
        val data = json.optArrayOrNull("data")
            ?: throw OpenAiException("响应缺少 data 数组")
        val models = ArrayList<ModelInfo>(data.length())
        for (index in 0 until data.length()) {
            val item = data.optJSONObject(index) ?: continue
            val id = item.optStringOrNull("id")?.trim().orEmpty()
            if (id.isEmpty()) continue
            val ownedBy = item.optStringOrNull("owned_by")?.trim()?.takeIf { it.isNotEmpty() }
            models.add(ModelInfo(id = id, ownedBy = ownedBy))
        }
        return models
    }

    /** 发一次请求并返回响应体文本；非 2xx 抛带状态码与响应节选的 [OpenAiException]。 */
    private fun execute(method: String, url: String, body: String?): String {
        val connection = openConnection(url)
        try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.useCaches = false
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "ClipTranslate")
            if (body != null) {
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            val text = readBody(connection, code)
            if (code !in 200..299) {
                val detail = text.trim().ifEmpty { connection.responseMessage?.trim().orEmpty() }
                throw OpenAiException("HTTP 错误 $code：${detail.truncateForMessage()}", code)
            }
            return text
        } catch (e: OpenAiException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw OpenAiException("请求超时（连接 ${connectTimeoutMs} ms / 读取 ${readTimeoutMs} ms）：$url")
        } catch (e: UnknownHostException) {
            throw OpenAiException("无法解析服务器地址：${e.message ?: url}")
        } catch (e: IOException) {
            throw OpenAiException("网络错误：${e.message ?: e.javaClass.simpleName}")
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        val opened = try {
            URL(url).openConnection()
        } catch (e: IOException) {
            throw OpenAiException("服务地址无效：$url（请在设置里填写完整的 http(s):// 地址）")
        }
        return opened as? HttpURLConnection
            ?: throw OpenAiException("只支持 http/https 地址：$url")
    }

    private fun readBody(connection: HttpURLConnection, code: Int): String {
        val stream: InputStream? = if (code in 200..299) connection.inputStream else connection.errorStream
        return stream?.use { it.readBytes().toString(StandardCharsets.UTF_8) } ?: ""
    }
}

/** 异常消息里的响应节选：最多保留前 [max] 个字符，避免把整页 HTML 塞进对话框。 */
private fun String.truncateForMessage(max: Int = 300): String {
    val text = trim()
    return if (text.length <= max) text else text.substring(0, max) + "…"
}

/**
 * baseUrl 归一化（纯函数，便于测试）：
 * 1. 去掉首尾空白；
 * 2. 去掉尾部所有 `/`；
 * 3. 若 URL 的 path 部分为空（如 `http://host:7863`、`http://host:7863/`），补上 `/v1`；
 * 4. path 非空则原样保留 —— 带不带 `/v1` 由用户自己决定（`https://api.openai.com/v1/` → `https://api.openai.com/v1`）。
 *
 * 边界：空串/纯空白返回空串（真正发请求时会以「服务地址无效」报错）；
 * baseUrl 里不应带 query 或 fragment，本函数不处理它们。
 *
 * 例：
 * - `http://debian:7863/`        → `http://debian:7863/v1`
 * - `http://debian:7863`         → `http://debian:7863/v1`
 * - `http://x/v1/`               → `http://x/v1`
 * - `  https://api.openai.com  ` → `https://api.openai.com/v1`
 * - `http://host:7863/v1/chat`   → `http://host:7863/v1/chat`（path 非空，保持原样）
 */
internal fun normalizeBaseUrl(raw: String): String {
    val base = raw.trim().trimEnd('/')
    if (base.isEmpty()) return ""
    val authorityStart = base.indexOf("://").let { if (it < 0) 0 else it + 3 }
    val hasPath = base.indexOf('/', authorityStart) >= 0
    return if (hasPath) base else "$base/v1"
}
