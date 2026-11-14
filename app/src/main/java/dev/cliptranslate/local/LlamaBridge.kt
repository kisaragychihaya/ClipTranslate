package dev.cliptranslate.local

/**
 * llama.cpp 的 JNI 桥。native 侧由 CI 编译（app/src/main/cpp），本地没有 `.so`。
 *
 * 关键约束：[ensureLoaded] 失败只返回 false，绝不抛 —— 未内置 native 库的安装包必须能正常
 * 启动、正常用在线翻译，只是选中本地模型时给出「推理库未内置」这种可读提示。
 */
object LlamaBridge {
    @Volatile private var loaded = false

    /** 尝试加载 native 库；APK 未内置 .so 时返回 false 而不是崩溃。 */
    @Synchronized
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("cliptranslate_jni")
            loaded = true
            true
        } catch (t: Throwable) { false }
    }

    external fun nativeLoad(modelPath: String, nThreads: Int, nCtx: Int): Long
    external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int): String
    external fun nativeUnload(handle: Long)
}
