package dev.cliptranslate.core

import java.io.PrintStream
import java.nio.charset.StandardCharsets

// Windows 下 stdout.encoding 常为 GBK，而 QC 门禁在 Git Bash / CI 里按 UTF-8 读日志，
// 这里固定用 UTF-8 输出，保证中文成功/失败信息始终可读。
private val out = PrintStream(System.out, true, StandardCharsets.UTF_8)
private val err = PrintStream(System.err, true, StandardCharsets.UTF_8)

/** core 层自测入口：kotlinc 编成 jar 后直接运行，断言失败即非零退出。 */
fun main() {
    try {
        runChecks()
    } catch (t: Throwable) {
        err.println("core 自测失败：" + (t.message ?: t.toString()))
        err.flush()
        kotlin.system.exitProcess(1)
    }
    out.println(
        "core 自测全部通过：Languages 共 ${Languages.SUPPORTED.size} 种目标语言（首选 ${Languages.SUPPORTED.first().second}），" +
            "Prompts 与 TextUtil 行为符合契约。"
    )
}

private fun runChecks() {
    // a) displayName
    check(Languages.displayName("zh-CN") == "简体中文") {
        "displayName(\"zh-CN\") 期望 \"简体中文\"，实际 \"${Languages.displayName("zh-CN")}\""
    }
    check(Languages.displayName("xx") == "xx") {
        "未知 code 应原样返回，实际 \"${Languages.displayName("xx")}\""
    }

    // b) SUPPORTED 清单
    check(Languages.SUPPORTED.isNotEmpty()) { "SUPPORTED 不能为空" }
    check(Languages.SUPPORTED.size >= 16) { "SUPPORTED 至少 16 种语言，实际 ${Languages.SUPPORTED.size}" }
    check(Languages.SUPPORTED.first().first == "zh-CN") {
        "SUPPORTED 第一项应为 zh-CN，实际 \"${Languages.SUPPORTED.first().first}\""
    }
    val codes = Languages.SUPPORTED.map { it.first }
    check(codes.size == codes.toSet().size) {
        "SUPPORTED 存在重复 code：${codes.groupingBy { it }.eachCount().filter { it.value > 1 }.keys}"
    }
    check(codes.all { it.isNotBlank() } && Languages.SUPPORTED.all { it.second.isNotBlank() }) {
        "SUPPORTED 存在空 code 或空显示名"
    }

    // c) 提示词体现目标语言与原文
    val system = Prompts.systemPrompt("ja")
    val user = Prompts.userPrompt("hello", "ja")
    check(system.contains("日")) { "systemPrompt(\"ja\") 未包含目标语言显示名：\n$system" }
    check(user.contains("日")) { "userPrompt(\"hello\", \"ja\") 未包含目标语言显示名：\n$user" }
    check(user.contains("hello")) { "userPrompt 未包含原文：\n$user" }

    // d) 空白处理与截断
    check(TextUtil.clipForRequest("  abc  ") == "abc") {
        "clipForRequest(\"  abc  \") 期望 \"abc\"，实际 \"${TextUtil.clipForRequest("  abc  ")}\""
    }
    check(TextUtil.clipForRequest("   ") == "") { "纯空白应返回空串" }
    check(TextUtil.clipForRequest("abc", maxChars = 10) == "abc") { "未超长时不应改动内容" }
    check(TextUtil.clipForRequest("abcdef", maxChars = 4) == "abc…") {
        "短上限截断应保留 maxChars-1 个字符再加 \"…\"，实际 \"${TextUtil.clipForRequest("abcdef", maxChars = 4)}\""
    }

    val longText = "a".repeat(7000)
    val clipped = TextUtil.clipForRequest(longText)
    check(clipped.length <= 6000) { "截断后长度 ${clipped.length} 超过 6000" }
    check(clipped.endsWith("…")) { "截断后应以 \"…\" 结尾" }

    // 跨代理对（emoji）截断不应切坏字符
    val emojiClipped = TextUtil.clipForRequest("ab😀cd", maxChars = 3)
    check(emojiClipped.length <= 3) { "emoji 截断后长度 ${emojiClipped.length} 超过 3" }
    check(emojiClipped.endsWith("…")) { "emoji 截断后应以 \"…\" 结尾" }
    check(emojiClipped.none { Character.isHighSurrogate(it) || Character.isLowSurrogate(it) }) {
        "截断后留下了半个代理对：$emojiClipped"
    }
}
