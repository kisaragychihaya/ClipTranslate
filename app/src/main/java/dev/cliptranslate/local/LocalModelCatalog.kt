package dev.cliptranslate.local

/**
 * 本地模型的提示词族。不同模型训练时用的 chat template 与语言名写法不同，
 * native 侧只做裸补全（[LlamaBridge.nativeGenerate]），模板要在 Kotlin 侧手工展开。
 */
enum class LocalModelPromptStyle {
    /** TranslateGemma：`<start_of_turn>` 模板 + 英文语言全名，见 [buildLocalPrompt]。 */
    GEMMA,

    /** Index-Translate：Qwen3 风格 `<|im_start|>` 模板 + 中文语言全名，见 [buildIndexTranslatePrompt]。 */
    INDEX_TRANSLATE,
}

/**
 * 模型的可选附件（目前只有 Index-Translate 2B 的视觉投影 mmproj）。
 *
 * 附件不是模型本体的一部分：主文件照样能跑纯文本翻译，下载附件只是给这个模型解锁
 * 「翻译图片里的文本」这一种额外能力。三要素与 [LocalModelSpec] 同义（大小判完整、
 * SHA256 判内容），下载/校验/续传/删除完全复用 [ModelManager] 同一套逻辑。
 */
data class AttachmentSpec(
    val fileName: String,
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long,
)

/**
 * 一个可下载的本地 GGUF 模型。
 *
 * 下载所需的三要素（[downloadUrl]、[sha256]、[sizeBytes]）必须与远端文件严格一致：
 * 大小用来判断磁盘上的文件是否完整，SHA256 用来判断内容是否被替换或损坏。
 *
 * [minTotalMemBytes] 是机型门槛（总内存），[defaultMemoryThresholdGb] 是加载前的可用内存预检默认值，
 * 两者刻意分开：小模型的机型门槛可以低到 4GiB，而加载时仍需要留出余量给系统和其余应用。
 *
 * [mmproj] 非空表示这个模型带一个可选的视觉投影附件 —— 下载后该模型解锁图片翻译，
 * 不下载则只有纯文本能力（见 [LocalTranslator]）。
 */
data class LocalModelSpec(
    val id: String,
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val minTotalMemBytes: Long,
    val defaultMemoryThresholdGb: Float,
    val promptStyle: LocalModelPromptStyle,
    val mmproj: AttachmentSpec? = null,
)

/**
 * 本地模型目录：新增模型只需要在这里加一条 [LocalModelSpec]，
 * 下载、校验、机型过滤、设置页下拉、提示词分派都按目录走，不再有散落各处的硬编码常量。
 *
 * 顺序即优先级：[models] 的第一个是默认模型，也是 [byId] 的兜底结果 ——
 * 让 TranslateGemma 保持在前，升级用户的既有选择（modelId 为空/失效）行为不变。
 */
object LocalModelCatalog {

    /** 默认本地模型 id；与 data 层的 `LOCAL_MODEL_ID` 同值，两处都不能随意改。 */
    const val DEFAULT_MODEL_ID = "translategemma-4b-it"

    private const val GIB = 1024L * 1024L * 1024L

    val models: List<LocalModelSpec> = listOf(
        LocalModelSpec(
            id = DEFAULT_MODEL_ID,
            displayName = "TranslateGemma 4B（Q4_K_M）",
            fileName = "translategemma-4b-it-Q4_K_M.gguf",
            downloadUrl = "https://www.modelscope.cn/models/42ailab/TranslateGemma-4B-GGUF/" +
                "resolve/master/translategemma-4b-it-Q4_K_M.gguf",
            sha256 = "7f7357c14abd9da4eb200b38b05da502cd6e10d7e1d403fbc9f78c19f3209b72",
            sizeBytes = 2489909312L,
            // 4B Q4_K_M 常驻约 3GB，8GiB 以下的机器跑起来会被系统反复杀进程。
            minTotalMemBytes = 8L * GIB,
            defaultMemoryThresholdGb = 4.0f,
            promptStyle = LocalModelPromptStyle.GEMMA,
        ),
        LocalModelSpec(
            id = "index-translate-2b",
            displayName = "Index-Translate 2B（Q4_K_M）",
            fileName = "Index-Translate-2B.Q4_K_M.gguf",
            downloadUrl = "https://www.modelscope.cn/models/IndexTeam/Index-Translate-2B-GGUF/" +
                "resolve/master/Index-Translate-2B.Q4_K_M.gguf",
            sha256 = "044b313d29342bd3b2c77cbb64023ca0d209bd9b3247763f9d162767ef2d746a",
            sizeBytes = 1312164352L,
            // 2B Q4_K_M 常驻约 1.5GB，4GiB 机型可用，是内存紧张设备唯一的本地选项。
            minTotalMemBytes = 4L * GIB,
            defaultMemoryThresholdGb = 2.0f,
            promptStyle = LocalModelPromptStyle.INDEX_TRANSLATE,
            // Q8_0 量化的视觉投影（约 345MB）：主模型本身不带网关，必须随 mmproj 一起加载
            // 才能把图片编码成模型看得懂的向量。它是可选项，纯文本翻译不需要它。
            mmproj = AttachmentSpec(
                fileName = "Index-Translate-2B.mmproj-Q8_0.gguf",
                downloadUrl = "https://www.modelscope.cn/models/IndexTeam/Index-Translate-2B-GGUF/" +
                    "resolve/master/Index-Translate-2B.mmproj-Q8_0.gguf",
                sha256 = "b100ca29aaf437461d0b22ada7492d5d2dfeaa15959e329c4267598ddebe446d",
                sizeBytes = 361518144L,
            ),
        ),
    )

    /**
     * 按 id 取模型；找不到或 id 为空时回退 [models] 的第一个。
     *
     * 永不抛异常也不返回 null：调用点遍布下载、加载、UI，任何一处拿不到模型都只会
     * 退化成「用默认模型」，不会因为历史脏数据（改过 id 的旧配置）崩掉。
     */
    fun byId(id: String?): LocalModelSpec =
        models.firstOrNull { it.id == id } ?: models.first()
}
