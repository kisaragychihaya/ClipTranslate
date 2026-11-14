package dev.cliptranslate.local

/**
 * llama.cpp 的 JNI 桥。native 侧由 CI 编译（app/src/main/cpp），本地没有 `.so`。
 *
 * 关键约束：[ensureLoaded] 失败只返回 false，绝不抛 —— 未内置 native 库的安装包必须能正常
 * 启动、正常用在线翻译，只是选中本地模型时给出「推理库未内置」这种可读提示。
 */
object LlamaBridge {
    @Volatile private var loaded = false

    /** 最近一次 loadLibrary 失败的原因（dlopen 报错原文），用于界面诊断展示。 */
    @Volatile var lastLoadError: String? = null
        private set

    /** 尝试加载 native 库；APK 未内置 .so 或依赖解析失败时返回 false 而不是崩溃。 */
    @Synchronized
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        preloadVendorOpenCL()
        return try {
            System.loadLibrary("cliptranslate_jni")
            loaded = true
            true
        } catch (t: Throwable) {
            lastLoadError = t.message
            false
        }
    }

    /**
     * libcliptranslate_jni.so 对 libOpenCL.so 是 DT_NEEDED 硬依赖（链接桩的 SONAME）。
     * 高通设备的 libOpenCL.so 在 /vendor/lib64，应用命名空间按名字搜不到；
     * 它登记在 /vendor/etc/public.libraries.txt，允许应用按绝对路径加载。
     * 预先 System.load 进来后，linker 会复用已加载的同名库，DT_NEEDED 得以解析。
     * 非高通设备预载全部失败也无妨：后续 loadLibrary 失败会走 ensureLoaded=false 的可读提示。
     */
    private fun preloadVendorOpenCL() {
        val candidates = listOf(
            "/vendor/lib64/libOpenCL.so",
            "/system_ext/lib64/libOpenCL.so",
            "/system/vendor/lib64/libOpenCL.so",
            "/vendor/lib64/egl/libOpenCL.so",
        )
        for (path in candidates) {
            try {
                System.load(path)
                return
            } catch (t: Throwable) {
                // 试下一个候选路径
            }
        }
    }

    /**
     * @param gpuBackend 0=自动（GPU 优先）/ 1=仅 CPU / 2=OpenCL / 3=Vulkan，见 [GpuBackend]。
     *   返回 0 表示加载失败（含所选 GPU 后端不可用）。
     */
    external fun nativeLoad(modelPath: String, nThreads: Int, nCtx: Int, gpuBackend: Int): Long
    external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int): String
    external fun nativeUnload(handle: Long)
}
