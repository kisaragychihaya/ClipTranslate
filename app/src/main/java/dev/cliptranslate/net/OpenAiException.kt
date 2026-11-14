package dev.cliptranslate.net

/**
 * 网络层统一异常。
 *
 * [message] 是可以直接展示给用户的中文说明（不含堆栈细节）；
 * [httpCode] 仅在服务端真的返回了状态码时非空，网络/解析类失败为 null。
 */
class OpenAiException(
    message: String,
    val httpCode: Int? = null,
) : Exception(message)
