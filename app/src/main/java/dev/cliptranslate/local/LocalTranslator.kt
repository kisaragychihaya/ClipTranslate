package dev.cliptranslate.local

import android.app.ActivityManager
import android.content.Context
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 本地推理失败；message 一律是可以直接展示给用户的中文说明。 */
class LocalTranslateException(message: String) : Exception(message)

/**
 * TranslateGemma 4B（GGUF）的本地推理器。
 *
 * 懒加载：第一次翻译时才 `ensureLoaded()` → `nativeLoad()`，之后复用 handle；
 * 用户在设置里改了加速方式时，[reloadForBackendChange] 会在 [Mutex] 内立即卸掉旧 handle
 * 并按新后端重载（[ensureHandle] 保留同样的兜底逻辑，防止设置不是从设置页写进来的）。
 * llama.cpp 的 context 不能并发使用，所以整条调用链用 [Mutex] 串行化。
 * 装载失败（APK 没内置 `.so`、内存不足、模型文件缺失）一律抛 [LocalTranslateException]，
 * 由上层降级成错误提示，不会影响在线翻译。
 */
class LocalTranslator private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val mutex = Mutex()

    /**
     * native handle；0 表示尚未加载。只在 [mutex] 内读写。
     *
     * 只有「加速方式被用户改过」时才主动 [LlamaBridge.nativeUnload]（见 [reloadForBackendChange]
     * 与 [ensureHandle]）：
     * 卸载时若还有生成在跑会让 native 侧出现 use-after-free，而所有读写都在 [mutex] 内，
     * 这里卸载时不可能有生成在跑。其余情况 handle 的生命周期跟着进程走，对用户没有可见影响：
     * 模型被删除又重新下载后 handle 仍指向已删除的旧文件，但内容同一个 SHA256，等价于同一个模型。
     */
    private var handle: Long = 0L

    /** [handle] 是用哪个加速方式加载的；[handle] 为 0 时为 null。只在 [mutex] 内读写。 */
    private var handleBackend: GpuBackend? = null

    /** 阻塞式翻译；内部已切到 [Dispatchers.IO]，UI 层直接 await 即可。 */
    suspend fun translate(text: String, targetCode: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val sourceCode = detectSourceCode(text)
            val prompt = buildLocalPrompt(text.trim(), sourceCode, targetCode)
            val loaded = ensureHandle()
            val raw = try {
                LlamaBridge.nativeGenerate(loaded, prompt, MAX_TOKENS)
            } catch (e: RuntimeException) {
                // native 层的 message 已经带错误码/原因，原样向上传（ERR_TOO_LONG 翻译成中文）。
                throw LocalTranslateException(mapNativeError(e.message))
            }
            raw.trim()
        }
    }

    // --- 加速方式切换 -------------------------------------------------------

    /**
     * 加速方式变更后立即卸掉旧模型并按新后端重载，让用户当场知道所选后端能不能用。
     *
     * 只有模型本来就装载着才重载；没装载时保持懒加载（仅改设置不该白占约 3GB 常驻内存），
     * 下一次翻译会走 [ensureHandle] 按新设置加载。
     * 幂等：handle 已经是当前设置的后端时直接返回成功，快速连点只会真正重载一次。
     * 失败返回 [Result.failure]，此时 [handle] 已归零，不会留下半成品。
     */
    suspend fun reloadForBackendChange(): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            LocalSettings.attach(appContext)
            val backend = LocalSettings.gpuBackend.value
            if (handle == 0L || handleBackend == backend) return@withLock Result.success(Unit)
            // 先卸再装：峰值内存只驻留一份模型。
            unloadLocked()
            try {
                loadLocked(backend)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    // --- 加载 ---------------------------------------------------------------

    private fun ensureHandle(): Long {
        LocalSettings.attach(appContext)
        val backend = LocalSettings.gpuBackend.value
        if (handle != 0L) {
            if (handleBackend == backend) return handle
            // 加速方式改了：旧 handle 仍按老后端工作，必须在 mutex 内卸掉再按新设置重载。
            unloadLocked()
        }
        return loadLocked(backend)
    }

    /** 卸掉当前 handle 并归零；只在 [mutex] 内调用。 */
    private fun unloadLocked() {
        if (handle == 0L) return
        LlamaBridge.nativeUnload(handle)
        handle = 0L
        handleBackend = null
    }

    /**
     * 按 [backend] 装一份新 handle；只在 [mutex] 内调用。
     *
     * 失败抛 [LocalTranslateException]；[handle] 只在 nativeLoad 成功后才写入，
     * 所以失败时不会留下半成品（旧 handle 由调用方先行 [unloadLocked]）。
     */
    private fun loadLocked(backend: GpuBackend): Long {
        if (!LlamaBridge.ensureLoaded()) {
            val detail = LlamaBridge.lastLoadError?.let { "（$it）" } ?: ""
            throw LocalTranslateException(
                "本地推理库加载失败$detail，请安装最新 CI 构建的版本",
            )
        }
        checkAvailableMemory()
        val model = ModelManager.get(appContext).modelFile
        if (!model.exists()) {
            throw LocalTranslateException("本地模型文件不存在，请先在设置中下载")
        }
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6)
        // NPU 需要 skel 库在真实文件路径上（fastrpc 按路径加载），先解压 assets 里的副本。
        // auto 也可能选中 NPU，同样先备好。
        val skelDir = if (backend == GpuBackend.NPU || backend == GpuBackend.AUTO) extractSkels() else ""
        val loaded = LlamaBridge.nativeLoad(model.absolutePath, threads, CONTEXT_SIZE, backend.value, skelDir)
        if (loaded == 0L) {
            throw LocalTranslateException(loadFailureMessage(backend))
        }
        handle = loaded
        handleBackend = backend
        return loaded
    }

    /**
     * 把 assets/htp-skel/ 里的 5 个 DSP skel 库解压到 filesDir/htp-skel/（仅在选 NPU 时做）。
     * 已存在且大小一致就跳过，返回目录绝对路径。
     */
    private fun extractSkels(): String {
        val dir = java.io.File(appContext.filesDir, "htp-skel")
        dir.mkdirs()
        val names = appContext.assets.list("htp-skel").orEmpty()
        if (names.isEmpty()) {
            throw LocalTranslateException("安装包未内置 NPU skel 库（assets/htp-skel 缺失）")
        }
        for (name in names) {
            val target = java.io.File(dir, name)
            appContext.assets.open("htp-skel/$name").use { input ->
                val bytes = input.readBytes()
                if (!target.exists() || target.length() != bytes.size.toLong()) {
                    target.writeBytes(bytes)
                }
            }
        }
        return dir.absolutePath
    }

    /** 显式选了 GPU/NPU 后端还加载失败，多半是该后端在这台机器上不可用，提示换一种方式。 */
    private fun loadFailureMessage(backend: GpuBackend): String = when (backend) {
        GpuBackend.OPENCL, GpuBackend.NPU ->
            "所选后端不可用，请切换加速方式或改用 CPU"

        else -> "本地模型加载失败（内存不足或模型文件损坏）"
    }

    /**
     * 4B Q4_K_M 常驻约 3GB，加载前先看可用内存，不够就给可读提示而不是被系统杀掉。
     *
     * 阈值默认 4GB，开发者模式下可在 2.5~4.5GB 之间调整（[DevSettings.memoryThresholdGb]）。
     * 刚卸载旧后端会话时，释放的页要过一两秒才被内核回收计进 availMem，第一次读数会
     * 假性偏低（16GB 机型实测：卸载 NPU 后立即读 4.2GB、约 1 秒后恢复到 4.5GB+），
     * 所以按 1 秒间隔重试取最高值，全部不达标才报错。
     */
    private fun checkAvailableMemory() {
        val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return
        DevSettings.attach(appContext)
        val thresholdGb = DevSettings.memoryThresholdGb.value.coerceIn(
            DevSettings.MIN_MEMORY_THRESHOLD_GB,
            DevSettings.MAX_MEMORY_THRESHOLD_GB,
        )
        val thresholdBytes = (thresholdGb * 1024f * 1024f * 1024f).toLong()
        val info = ActivityManager.MemoryInfo()
        var best = 0L
        repeat(MEMORY_CHECK_RETRIES) {
            manager.getMemoryInfo(info)
            best = maxOf(best, info.availMem)
            if (best >= thresholdBytes) return
            Thread.sleep(MEMORY_CHECK_INTERVAL_MS)
        }
        throw LocalTranslateException(
            "可用内存不足（需要约 ${formatThreshold(thresholdGb)} GB，当前 ${formatGigabytes(best)} GB）",
        )
    }

    companion object {
        private const val CONTEXT_SIZE = 4096
        private const val MAX_TOKENS = 1024

        /** 内存预检重试次数与间隔，见 [checkAvailableMemory]。 */
        private const val MEMORY_CHECK_RETRIES = 5
        private const val MEMORY_CHECK_INTERVAL_MS = 1_000L

        private const val ERR_TOO_LONG = "ERR_TOO_LONG"

        @Volatile private var instance: LocalTranslator? = null

        /** 进程内单例：native handle 只能有一份，重复 nativeLoad 会白吃一倍内存。 */
        fun get(context: Context): LocalTranslator = instance ?: synchronized(this) {
            instance ?: LocalTranslator(context.applicationContext).also { instance = it }
        }
    }

    private fun mapNativeError(message: String?): String {
        val text = message?.trim().orEmpty()
        if (text.startsWith(ERR_TOO_LONG)) return "文本过长，本地模型上下文不足"
        return text.ifBlank { "本地推理失败" }
    }

    private fun formatGigabytes(bytes: Long): String =
        String.format(Locale.US, "%.1f", bytes / 1024.0 / 1024.0 / 1024.0)

    /** 4.0f → "4"；2.5f → "2.5"，错误信息里不带无意义的小数尾巴。 */
    private fun formatThreshold(gb: Float): String =
        String.format(Locale.US, "%.1f", gb).trimEnd('0').trimEnd('.')
}

// --- 语言与提示词（纯函数，方便单独验证） -----------------------------------

/** App 语言代码 → 模型提示词里用的英文语言名。原文要求的映射表，未列出的代码原样使用。 */
private val LANGUAGE_NAMES: Map<String, String> = mapOf(
    "zh-CN" to "Chinese",
    "zh-TW" to "Chinese",
    "en" to "English",
    "ja" to "Japanese",
    "ko" to "Korean",
    "fr" to "French",
    "de" to "German",
    "es" to "Spanish",
    "ru" to "Russian",
    "pt" to "Portuguese",
    "it" to "Italian",
    "ar" to "Arabic",
    "th" to "Thai",
    "vi" to "Vietnamese",
    "id" to "Indonesian",
    "tr" to "Turkish",
    "pl" to "Polish",
    "nl" to "Dutch",
    "hi" to "Hindi",
    "uk" to "Ukrainian",
)

internal fun englishLanguageName(code: String): String = LANGUAGE_NAMES[code] ?: code

/**
 * 源语言启发式检测：TranslateGemma 要求必须给出源语言，而 App 不要求用户选源语言。
 *
 * 顺序即优先级：假名最高（日文里混着大量汉字，先认假名才不会误判成中文），
 * 之后依次 Hangul / 泰文 / 天城文 / 阿拉伯文 / 西里尔 / 汉字，全不匹配按英语处理。
 */
internal fun detectSourceCode(text: String): String {
    var hangul = false
    var thai = false
    var devanagari = false
    var arabic = false
    var cyrillic = false
    var han = false

    for (ch in text) {
        val code = ch.code
        when {
            code in 0x3040..0x30FF || code in 0x31F0..0x31FF || code in 0xFF66..0xFF9D -> return "ja"
            code in 0xAC00..0xD7AF || code in 0x1100..0x11FF || code in 0x3130..0x318F -> hangul = true
            code in 0x0E00..0x0E7F -> thai = true
            code in 0x0900..0x097F -> devanagari = true
            code in 0x0600..0x06FF || code in 0x0750..0x077F -> arabic = true
            code in 0x0400..0x04FF -> cyrillic = true
            code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF || code in 0xF900..0xFAFF -> han = true
        }
    }

    return when {
        hangul -> "ko"
        thai -> "th"
        devanagari -> "hi"
        arabic -> "ar"
        cyrillic -> "ru"
        han -> "zh-CN"
        else -> "en"
    }
}

/**
 * TranslateGemma 官方 chat template 的展开形式，必须逐字一致 ——
 * 少一个换行、多一个空格都会影响译文质量，改动前请对照官方模板。
 * 结尾的换行让模型从 `<start_of_turn>model` 的下一行开始生成。
 */
internal fun buildLocalPrompt(text: String, sourceCode: String, targetCode: String): String {
    val src = englishLanguageName(sourceCode)
    val tgt = englishLanguageName(targetCode)
    return buildString {
        append("<start_of_turn>user\n")
        append(
            "You are a professional $src ($sourceCode) to $tgt ($targetCode) translator. " +
                "Your goal is to accurately convey the meaning and nuances of the original $src text " +
                "while adhering to $tgt grammar, vocabulary, and cultural sensitivities.\n",
        )
        append(
            "Produce only the $tgt translation, without any additional explanations or commentary. " +
                "Please translate the following $src text into $tgt:\n",
        )
        append("\n")
        append("\n")
        append(text)
        append("<end_of_turn>\n")
        append("<start_of_turn>model\n")
    }
}
