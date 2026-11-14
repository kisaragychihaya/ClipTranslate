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

/** 内置「本地离线翻译」Provider 的固定 id。它不写进 SharedPreferences，由 [ProviderRepository] 每次注入到列表首位。 */
const val LOCAL_PROVIDER_ID = "local-translategemma"

/** 内置本地模型的 id，对应 ModelManager 下载的那个 GGUF。 */
const val LOCAL_MODEL_ID = "translategemma-4b-it"

/** 内置 Provider 不可编辑、不可删除；UI 据此隐藏编辑/删除入口。 */
val Provider.isBuiltin: Boolean
    get() = id == LOCAL_PROVIDER_ID
