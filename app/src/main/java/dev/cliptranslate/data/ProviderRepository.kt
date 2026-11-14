package dev.cliptranslate.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Provider 配置的持久化层。
 *
 * 存储：SharedPreferences("providers") 的 key "json" 里放一个 JSON 数组字符串，
 * 每个元素形如 {id, name, baseUrl, apiKey, defaultModelId, models:[{id, ownedBy}]}。
 * 读取一律容错：空串、坏 JSON、类型不符都退化成空列表，绝不因为历史脏数据崩溃。
 */
class ProviderRepository(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 全量读取。首次运行（"json" 这个 key 压根不存在）时先写入内置种子。 */
    @Synchronized
    fun load(): List<Provider> {
        ensureSeeded()
        return readAll()
    }

    /** 同 id 覆盖，否则追加到末尾。 */
    @Synchronized
    fun upsert(provider: Provider) {
        val current = load()
        val index = current.indexOfFirst { it.id == provider.id }
        val next = if (index >= 0) {
            current.toMutableList().also { it[index] = provider }
        } else {
            current + provider
        }
        writeAll(next)
    }

    /** 删除指定 id 并写回；删掉最后一个时 key 变成 "[]"，种子因此不会复活。 */
    @Synchronized
    fun delete(id: String) {
        writeAll(load().filterNot { it.id == id })
    }

    @Synchronized
    fun get(id: String): Provider? = load().firstOrNull { it.id == id }

    // --- 种子 ---------------------------------------------------------------
    // 只在 key 完全不存在时写一次；用户删掉后存储里至少剩 "[]"，key 已存在，因此不会复活。
    private fun ensureSeeded() {
        if (prefs.contains(KEY_JSON)) return
        writeAll(listOf(SEED_PROVIDER))
    }

    // --- 读写 ---------------------------------------------------------------
    private fun readAll(): List<Provider> {
        val raw = prefs.getString(KEY_JSON, null)
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    parseProvider(item)?.let { add(it) }
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeAll(providers: List<Provider>) {
        prefs.edit().putString(KEY_JSON, encodeProviders(providers)).apply()
    }

    private fun parseProvider(json: JSONObject): Provider? {
        val id = json.stringOrNull("id") ?: return null
        return Provider(
            id = id,
            name = json.stringOrEmpty("name"),
            baseUrl = json.stringOrEmpty("baseUrl"),
            apiKey = json.stringOrEmpty("apiKey"),
            models = json.optJSONArray("models").toModelInfos(),
            defaultModelId = json.stringOrNull("defaultModelId"),
        )
    }

    private companion object {
        const val PREFS_NAME = "providers"
        const val KEY_JSON = "json"

        val SEED_PROVIDER = Provider(
            id = "local-test",
            name = "本地测试服务",
            baseUrl = "http://debian:7863/v1",
            apiKey = "",
            models = listOf(ModelInfo("cn:hy4free")),
            defaultModelId = "cn:hy4free",
        )
    }
}

// --- org.json 小工具（文件内私有） -------------------------------------------

private fun encodeProviders(providers: List<Provider>): String {
    val array = JSONArray()
    providers.forEach { array.put(it.toJson()) }
    return array.toString()
}

private fun Provider.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("baseUrl", baseUrl)
    put("apiKey", apiKey)
    put("defaultModelId", defaultModelId ?: JSONObject.NULL)
    put("models", JSONArray().apply {
        models.forEach { model ->
            put(JSONObject().apply {
                put("id", model.id)
                put("ownedBy", model.ownedBy ?: JSONObject.NULL)
            })
        }
    })
}

/** 缺失、显式 null、空串统一当作 null。 */
private fun JSONObject.stringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).ifEmpty { null }
}

private fun JSONObject.stringOrEmpty(key: String): String = stringOrNull(key) ?: ""

private fun JSONArray?.toModelInfos(): List<ModelInfo> {
    if (this == null) return emptyList()
    return buildList {
        for (i in 0 until length()) {
            val item = optJSONObject(i) ?: continue
            val modelId = item.stringOrNull("id") ?: continue
            add(ModelInfo(id = modelId, ownedBy = item.stringOrNull("ownedBy")))
        }
    }
}
