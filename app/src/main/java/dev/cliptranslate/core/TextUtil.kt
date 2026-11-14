package dev.cliptranslate.core

object TextUtil {
    /** 去掉首尾空白；超过 [maxChars] 时截断并以 "…" 结尾，结果总长度不超过 [maxChars]。 */
    fun clipForRequest(text: String, maxChars: Int = 6000): String {
        if (maxChars <= 0) return ""
        val trimmed = text.trim()
        if (trimmed.length <= maxChars) return trimmed
        var end = maxChars - 1
        if (end > 0 && Character.isHighSurrogate(trimmed[end - 1])) end--
        return trimmed.substring(0, end) + "…"
    }
}
