package dev.cliptranslate.net

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/*
 * org.json 小工具：只做「安全取值」，不抛异常、不关心业务语义。
 * internal 可见性 —— 仅限 net/ 包内复用，不对外暴露成 API。
 */

/** 解析 JSON 对象文本；不是合法 JSON 对象时返回 null，由调用方决定怎么报错。 */
internal fun parseJsonObjectOrNull(text: String): JSONObject? =
    try {
        JSONObject(text)
    } catch (e: JSONException) {
        null
    }

/**
 * 取字符串字段：字段缺失、显式 null（org.json 会存成 JSONObject.NULL）、
 * 或者值本身是 null 都返回 null；非字符串值退化为 toString()，避免 ClassCastException。
 */
internal fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val value = opt(key) ?: return null
    return if (value is String) value else value.toString()
}

/** 取嵌套对象；缺失或类型不符返回 null。 */
internal fun JSONObject.optObjectOrNull(key: String): JSONObject? = optJSONObject(key)

/** 取数组；缺失或类型不符返回 null。 */
internal fun JSONObject.optArrayOrNull(key: String): JSONArray? = optJSONArray(key)
