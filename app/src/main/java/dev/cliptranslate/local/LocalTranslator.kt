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
 * 已解码、已降采样的图片，供图片翻译使用。
 *
 * [rgb] 是紧密排列的 RGB 三字节/像素（无 alpha、无行填充）：native 侧直接按这个布局
 * 喂给视觉投影，Kotlin 侧在这里一次性把 Bitmap 的 ARGB 摊平成它要的形状，
 * 避免 JNI 边界上来回转换。
 */
data class DecodedImage(val rgb: ByteArray, val width: Int, val height: Int)

/**
 * 本地 GGUF 模型的推理器，模型由 [LocalModelCatalog] 决定（当前是 TranslateGemma 4B / Index-Translate 2B）。
 *
 * 懒加载：第一次翻译时才 `ensureLoaded()` → `nativeLoad()`，之后复用 handle；
 * 用户在设置里改了加速方式时，[reloadForBackendChange] 会在 [Mutex] 内立即卸掉旧 handle
 * 并按新后端重载；换了模型则同样先卸后装（同一把锁、同一套兜底逻辑，见 [ensureHandle]）。
 * 图片翻译多一个维度：handle 是否带视觉投影（mmproj）也要匹配 —— 纯文本 handle 收到图片
 * 请求时同样先卸再按带 mmproj 的路径重载一次。
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
     * 只有「加速方式或模型被用户改过」时才主动 [LlamaBridge.nativeUnload]（见 [reloadForBackendChange]
     * 与 [ensureHandle]）：
     * 卸载时若还有生成在跑会让 native 侧出现 use-after-free，而所有读写都在 [mutex] 内，
     * 这里卸载时不可能有生成在跑。其余情况 handle 的生命周期跟着进程走，对用户没有可见影响：
     * 模型被删除又重新下载后 handle 仍指向已删除的旧文件，但内容同一个 SHA256，等价于同一个模型。
     */
    private var handle: Long = 0L

    /** [handle] 是用哪个加速方式加载的；[handle] 为 0 时为 null。只在 [mutex] 内读写。 */
    private var handleBackend: GpuBackend? = null

    /** [handle] 里装的是哪个模型（目录 id）；[handle] 为 0 时为 null。只在 [mutex] 内读写。 */
    private var handleModelId: String? = null

    /** [handle] 是否带了视觉投影（mmproj）；[handle] 为 0 时为 false。只在 [mutex] 内读写。 */
    private var handleHasVision: Boolean = false

    /**
     * 阻塞式翻译；内部已切到 [Dispatchers.IO]，UI 层直接 await 即可。
     *
     * [image] 非 null 时走图片翻译：此时 [text] 不再是被翻译的内容，而是可选的「附加要求」
     * （为空就不加），提示词见 [buildIndexTranslateImagePrompt]。纯文本路径与之前逐字一致。
     */
    suspend fun translate(
        text: String,
        targetCode: String,
        modelId: String,
        image: DecodedImage? = null,
    ): String =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val spec = LocalModelCatalog.byId(modelId)
                if (image == null) {
                    val prompt = when (spec.promptStyle) {
                        LocalModelPromptStyle.GEMMA ->
                            buildLocalPrompt(text.trim(), detectSourceCode(text), targetCode)

                        LocalModelPromptStyle.INDEX_TRANSLATE ->
                            buildIndexTranslatePrompt(text.trim(), targetCode)
                    }
                    val loaded = ensureHandle(spec, withVision = false)
                    val raw = try {
                        LlamaBridge.nativeGenerate(loaded, prompt, MAX_TOKENS)
                    } catch (e: RuntimeException) {
                        // native 层的 message 已经带错误码/原因，原样向上传（ERR_TOO_LONG 翻译成中文）。
                        throw LocalTranslateException(mapNativeError(e.message))
                    }
                    raw.trim()
                } else {
                    // 只有多模态的 Index-Translate 有图片模板；别的模型给图只会得到胡说八道，
                    // 干脆按能力不足拒掉（UI 也不会显示图片入口）。
                    if (spec.promptStyle != LocalModelPromptStyle.INDEX_TRANSLATE) {
                        throw LocalTranslateException("当前模型不支持图片翻译")
                    }
                    val prompt = buildIndexTranslateImagePrompt(
                        targetCode,
                        extraInstruction = text.trim().ifBlank { null },
                    )
                    val loaded = ensureHandle(spec, withVision = true)
                    val raw = try {
                        LlamaBridge.nativeGenerateImage(
                            loaded,
                            prompt,
                            image.rgb,
                            image.width,
                            image.height,
                            MAX_TOKENS,
                        )
                    } catch (e: RuntimeException) {
                        throw LocalTranslateException(mapNativeError(e.message))
                    }
                    raw.trim()
                }
            }
        }

    // --- 加速方式切换 -------------------------------------------------------

    /**
     * 加速方式变更后立即卸掉旧模型并按新后端重载，让用户当场知道所选后端能不能用。
     *
     * 只有模型本来就装载着才重载；没装载时保持懒加载（仅改设置不该白占常驻内存），
     * 下一次翻译会走 [ensureHandle] 按新设置加载。
     * 幂等：handle 已经是当前设置的后端时直接返回成功，快速连点只会真正重载一次。
     * 失败返回 [Result.failure]，此时 [handle] 已归零，不会留下半成品。
     */
    suspend fun reloadForBackendChange(): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            LocalSettings.attach(appContext)
            val backend = LocalSettings.gpuBackend.value
            if (handle == 0L || handleBackend == backend) return@withLock Result.success(Unit)
            // 重载用当前已装的那个模型；handleModelId 为 null 时才退化成目录默认。
            val spec = LocalModelCatalog.byId(handleModelId)
            // 保持当前 handle 的视觉能力：改成 CPU 不该悄悄把图片翻译弄没了。
            // 但附件已经不在磁盘上时不再强行带 mmproj，否则一次改后端会被翻译成「请先下载视觉投影」。
            val withVision = handleHasVision && attachmentPathOrNull(spec) != null
            // 先卸再装：峰值内存只驻留一份模型。
            unloadLocked()
            try {
                loadLocked(backend, spec, withVision)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    // --- 加载 ---------------------------------------------------------------

    /**
     * 拿到可用的 handle；[withVision] 表示调用方要用视觉能力（图片翻译）。
     *
     * 匹配条件不只是「模型 + 加速方式」，还包含「有没有装视觉投影」：纯文本 handle 收到图片请求时
     * 必须重载一次，否则 native 侧根本没有图片编码器。
     */
    private fun ensureHandle(spec: LocalModelSpec, withVision: Boolean): Long {
        LocalSettings.attach(appContext)
        val backend = LocalSettings.gpuBackend.value
        if (handle != 0L) {
            val matches = handleBackend == backend &&
                handleModelId == spec.id &&
                (!withVision || handleHasVision)
            if (matches) return handle
            // 加速方式/模型/视觉能力改了：旧 handle 必须先在 mutex 内卸掉，再按新设置重载。
            unloadLocked()
        }
        return loadLocked(backend, spec, withVision)
    }

    /** 卸掉当前 handle 并归零；只在 [mutex] 内调用。 */
    private fun unloadLocked() {
        if (handle == 0L) return
        LlamaBridge.nativeUnload(handle)
        handle = 0L
        handleBackend = null
        handleModelId = null
        handleHasVision = false
    }

    /**
     * 按 [backend] 装一份 [spec] 的新 handle；只在 [mutex] 内调用。
     *
     * [withVision] 为 true 时把视觉投影附件一并交给 native；附件没下载就抛可读提示，
     * 不会退化成「加载了一个没视觉的模型然后拿图片去生成」。
     *
     * 失败抛 [LocalTranslateException]；[handle] 只在 nativeLoad 成功后才写入，
     * 所以失败时不会留下半成品（旧 handle 由调用方先行 [unloadLocked]）。
     */
    private fun loadLocked(backend: GpuBackend, spec: LocalModelSpec, withVision: Boolean): Long {
        if (!LlamaBridge.ensureLoaded()) {
            val detail = LlamaBridge.lastLoadError?.let { "（$it）" } ?: ""
            throw LocalTranslateException(
                "本地推理库加载失败$detail，请安装最新 CI 构建的版本",
            )
        }
        checkAvailableMemory(spec)
        val model = ModelManager.get(appContext).modelFile(spec.id)
        if (!model.exists()) {
            throw LocalTranslateException("本地模型文件不存在，请先在设置中下载")
        }
        val mmprojPath = if (withVision) {
            attachmentPathOrNull(spec)
                ?: throw LocalTranslateException("图片翻译需要先在设置中下载视觉投影")
        } else {
            ""
        }
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6)
        // NPU 需要 skel 库在真实文件路径上（fastrpc 按路径加载），先解压 assets 里的副本。
        // auto 也可能选中 NPU，同样先备好。
        val skelDir = if (backend == GpuBackend.NPU || backend == GpuBackend.AUTO) extractSkels() else ""
        val loaded = LlamaBridge.nativeLoad(
            model.absolutePath,
            threads,
            CONTEXT_SIZE,
            backend.value,
            skelDir,
            mmprojPath,
        )
        if (loaded == 0L) {
            throw LocalTranslateException(loadFailureMessage(backend))
        }
        handle = loaded
        handleBackend = backend
        handleModelId = spec.id
        handleHasVision = mmprojPath.isNotEmpty()
        return loaded
    }

    /**
     * 视觉投影附件的绝对路径；模型没有附件、附件不在磁盘上或大小不符（下载没完成/被删了一半）
     * 时返回 null，调用方据此把它当成「未就绪」。
     *
     * 这里只查大小不重算 SHA256：下载链路已经校验过，每次加载都重算 345MB 会让首次翻译卡几十秒。
     */
    private fun attachmentPathOrNull(spec: LocalModelSpec): String? {
        val attachment = spec.mmproj ?: return null
        val file = ModelManager.get(appContext).attachmentFile(spec.id) ?: return null
        if (!file.exists() || file.length() != attachment.sizeBytes) return null
        return file.absolutePath
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
     * 加载前先看可用内存，不够就给可读提示而不是被系统杀掉。
     *
     * 阈值：开发者模式下用户手动设过就用那个值（2.5~4.5GB），没设过就用模型自带的
     * [LocalModelSpec.defaultMemoryThresholdGb] —— 4B 模型约 3GB、2B 模型约 1.5GB 常驻，
     * 统一阈值会让小模型在低内存机型上被无谓地拒绝。
     * 刚卸载旧后端会话时，释放的页要过一两秒才被内核回收计进 availMem，第一次读数会
     * 假性偏低（16GB 机型实测：卸载 NPU 后立即读 4.2GB、约 1 秒后恢复到 4.5GB+），
     * 所以按 1 秒间隔重试取最高值，全部不达标才报错。
     */
    private fun checkAvailableMemory(spec: LocalModelSpec) {
        val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return
        DevSettings.attach(appContext)
        val thresholdGb = if (DevSettings.hasCustomMemoryThreshold()) {
            DevSettings.memoryThresholdGb.value.coerceIn(
                DevSettings.MIN_MEMORY_THRESHOLD_GB,
                DevSettings.MAX_MEMORY_THRESHOLD_GB,
            )
        } else {
            spec.defaultMemoryThresholdGb
        }
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
 * App 语言代码 → Index-Translate 提示词里用的中文语言名。
 *
 * 该模型训练时用的是中文语言全名，写英文名会明显掉质量；繁简在提示词里也要区分，
 * 否则 zh-TW 会被译成简体。未列出的代码原样使用。
 */
private val INDEX_LANGUAGE_NAMES: Map<String, String> = mapOf(
    "zh-CN" to "中文",
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

internal fun indexLanguageName(code: String): String = INDEX_LANGUAGE_NAMES[code] ?: code

/**
 * 源语言启发式检测：TranslateGemma 要求必须给出源语言，而 App 不要求用户选源语言。
 *
 * 顺序即优先级：假名最高（日文里混着大量汉字，先认假名才不会误判成中文），
 * 之后依次 Hangul / 泰文 / 天城文 / 阿拉伯文 / 西里尔 / 汉字，全不匹配按英语处理。
 * Index-Translate 的提示词不需要源语言，这套检测只服务 GEMMA 风格的模型。
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

/**
 * Index-Translate（Qwen3 风格 chat template）在 `enable_thinking=false` 时的展开形式，
 * 必须逐字一致：提示词里已经带上了空的 `<think>` 段，模型才会直接开始输出译文。
 *
 * 官方提示词用中文语言全名；结尾的两个换行对应模板的 `<think>\n\n</think>\n\n`，
 * 少一个都会改变生成起点。
 */
internal fun buildIndexTranslatePrompt(text: String, targetCode: String): String =
    "<|im_start|>user\n" +
        "请将以下文本翻译为${indexLanguageName(targetCode)}，直接输出翻译结果，不要进行任何解释：\n\n" +
        "$text<|im_end|>\n" +
        "<|im_start|>assistant\n" +
        "<think>\n\n</think>\n\n"

/** 图片在提示词里的占位符；native 侧会把它替换成真正的图片 marker（见 [LlamaBridge.nativeGenerateImage]）。 */
internal const val IMAGE_PROMPT_PLACEHOLDER = "__IMAGE__"

/**
 * Index-Translate 的图片翻译提示词：图片占位符放在 user 内容的开头，后面才是指令。
 *
 * [extraInstruction] 是用户在输入框里写的附加要求（例如「保留原文分行」），为空时整段不加 ——
 * 图片模式下输入框不再是被翻译的正文，所以空输入是正常情况，不能塞进一个空行。
 * 结尾的 `<think>` 段与 [buildIndexTranslatePrompt] 同款，模型才会直接开始输出译文。
 */
internal fun buildIndexTranslateImagePrompt(
    targetCode: String,
    extraInstruction: String?,
): String {
    val extra = extraInstruction?.trim().orEmpty()
    return buildString {
        append("<|im_start|>user\n")
        append(IMAGE_PROMPT_PLACEHOLDER).append('\n')
        append("请将图片中的文本翻译为${indexLanguageName(targetCode)}，直接输出翻译结果，不要进行任何解释。")
        if (extra.isNotEmpty()) append('\n').append(extra)
        append("<|im_end|>\n")
        append("<|im_start|>assistant\n")
        append("<think>\n\n</think>\n\n")
    }
}
