package dev.cliptranslate.core

object Languages {
    /** (语言代码, 中文显示名)，zh-CN 固定排第一（应用默认目标语言）。 */
    val SUPPORTED: List<Pair<String, String>> = listOf(
        "zh-CN" to "简体中文",
        "zh-TW" to "繁体中文",
        "en" to "英语",
        "ja" to "日语",
        "ko" to "韩语",
        "fr" to "法语",
        "de" to "德语",
        "es" to "西班牙语",
        "ru" to "俄语",
        "pt" to "葡萄牙语",
        "it" to "意大利语",
        "ar" to "阿拉伯语",
        "th" to "泰语",
        "vi" to "越南语",
        "id" to "印尼语",
        "tr" to "土耳其语",
        "pl" to "波兰语",
        "nl" to "荷兰语",
        "hi" to "印地语",
        "uk" to "乌克兰语",
    )

    private val namesByCode: Map<String, String> = SUPPORTED.toMap()

    fun displayName(code: String): String = namesByCode[code] ?: code
}
