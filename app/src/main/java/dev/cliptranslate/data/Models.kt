package dev.cliptranslate.data

/** 服务商返回的单个模型。ownedBy 是上游给的归属字段，可能为空。 */
data class ModelInfo(
    val id: String,
    val ownedBy: String? = null,
)

/**
 * 一个 OpenAI 兼容服务商的完整配置。
 * baseUrl 形如 https://api.openai.com/v1，尾部斜杠与 /v1 的归一化由 net 层负责，这里原样保存。
 */
data class Provider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val models: List<ModelInfo> = emptyList(),
    val defaultModelId: String? = null,
)
