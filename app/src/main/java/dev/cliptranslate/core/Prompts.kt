package dev.cliptranslate.core

object Prompts {
    fun systemPrompt(targetCode: String): String {
        val targetName = Languages.displayName(targetCode)
        return buildString {
            appendLine("你是一名专业翻译，唯一的任务是把用户给出的文本翻译成「$targetName」（语言代码：$targetCode）。")
            appendLine("严格遵守以下要求：")
            appendLine("1. 只输出译文本身，不要输出原文，不要解释、不要注音、不要加评论、不要加任何前后缀。")
            appendLine("2. 保留原文的结构标记：分段、换行、列表符号、代码块标记、占位符与变量名（如 {name}、%s、<tag>、URL、邮箱）原样保留，不翻译、不增删。")
            appendLine("3. 术语与人名保持一致，语气自然，符合目标语言的表达习惯。")
            appendLine("4. 若原文已经是目标语言，或内容只是数字、符号、空白，则原样输出，不要自行扩写或补全。")
            appendLine("5. 除译文内容外，不要输出任何与翻译无关的东西。")
        }
    }

    fun userPrompt(text: String, targetCode: String): String {
        val targetName = Languages.displayName(targetCode)
        return buildString {
            appendLine("请把下面的文本翻译成$targetName，仅输出译文：")
            appendLine()
            appendLine(text)
        }
    }
}
