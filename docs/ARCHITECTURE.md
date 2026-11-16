# ClipTranslate 架构设计

剪贴板翻译安卓应用：读取（或自动检测）剪贴板文本 → 调用 OpenAI 兼容接口的外部大模型 → 输出目标语言译文。

## 1. 技术选型与约束

| 项 | 决策 | 理由 |
|---|---|---|
| 语言 | Kotlin 2.1.21 | 与 kotlinc 本地语法检查一致 |
| UI | Jetpack Compose (BOM 2025.06.00) | 声明式、代码量少 |
| 架构 | 单模块 MVVM + Repository | 功能聚焦，无需多模块 |
| 网络 | `HttpURLConnection` + `org.json`（系统内置） | 零第三方依赖，CI 构建快、稳 |
| 持久化 | `SharedPreferences` + `org.json` 序列化 | 零依赖，Provider 配置足够 |
| 构建 | AGP 8.13.0 / Gradle 8.14.3 / JDK 21 | 参考仓库同款组合 |
| SDK | minSdk 26, targetSdk 36, compileSdk 36 | 剪贴板前台读取无需权限 |
| 本地环境 | 只装 JDK 21 + kotlinc 2.1.21，**不装 Android SDK**；APK 由 GitHub Actions 构建 | 用户硬性约束 |
| 包名 | `dev.cliptranslate` | 应用 ID 同包名 |

## 2. 目录结构

```
app/src/main/java/dev/cliptranslate/
├── MainActivity.kt            # 入口，NavHost(translate / settings)
├── core/                      # 纯 Kotlin，无 Android 依赖（可 JVM 编译+运行做 QC）
│   ├── Languages.kt           # 目标语言清单 + 显示名
│   ├── Prompts.kt             # 系统/用户提示词构造
│   └── TextUtil.kt            # 文本截断等纯函数
├── data/                      # Android 依赖
│   ├── Models.kt              # Provider / ModelInfo 数据类
│   └── ProviderRepository.kt  # SharedPreferences CRUD
├── local/                     # 本地 GGUF 推理（Android 依赖 + JNI）
│   ├── LocalModelCatalog.kt   # 模型目录：id/地址/SHA256/大小/内存门槛/提示词风格/可选视觉投影附件
│   ├── ModelManager.kt        # 按模型 id 的下载、断点续传、SHA256 校验、删除（主文件与附件各一套）
│   ├── LocalModelRequirements.kt # 机型门槛：总内存够哪些模型
│   ├── LocalTranslator.kt     # llama.cpp 推理：提示词展开、换模型/换后端/切视觉重载、加载前内存预检
│   ├── LlamaBridge.kt         # JNI 声明与 native 库加载（只做裸补全；含图片生成入口）
│   ├── GpuBackend.kt          # 加速方式枚举（对应 nativeLoad 的整型参数）
│   ├── LocalSettings.kt       # 启用开关与加速方式（SharedPreferences）
│   └── DevSettings.kt         # 开发者模式、调试日志、内存预检阈值
├── net/                       # Android 依赖（org.json 为系统库）
│   ├── OpenAiClient.kt        # chat / fetchModels（阻塞式，IO 线程调用）
│   ├── OpenAiException.kt
│   └── JsonUtil.kt            # org.json 小工具
├── platform/
│   ├── ClipboardHelper.kt     # 读剪贴板文本
│   └── PhotoInput.kt          # 拍照落盘（FileProvider）、相册取图、解码降采样成 RGB
└── ui/
    ├── AppNav.kt
    ├── theme/Theme.kt
    ├── about/AboutScreen.kt   # 版本、开源组件与许可证；长按 logo 解锁开发者模式
    ├── translate/TranslateScreen.kt + TranslateViewModel.kt
    └── settings/SettingsScreen.kt + SettingsViewModel.kt
        + ProviderEditorSheet.kt + ModelPickerSheet.kt
```

## 3. 模块间契约（所有子代理必须严格遵守）

### core（纯 Kotlin，package `dev.cliptranslate.core`）
```kotlin
object Languages {
    val SUPPORTED: List<Pair<String, String>>  // (code, 显示名)，zh-CN 排第一
    fun displayName(code: String): String      // 未知 code 原样返回
}
object Prompts {
    fun systemPrompt(targetCode: String): String
    fun userPrompt(text: String, targetCode: String): String
}
object TextUtil { fun clipForRequest(text: String, maxChars: Int = 6000): String }
```
- Prompts 职责：构造"把给定文本翻译成目标语言、只输出译文"的系统提示与用户提示。

### data（package `dev.cliptranslate.data`）
```kotlin
data class ModelInfo(val id: String, val ownedBy: String? = null)
data class Provider(
    val id: String,            // UUID
    val name: String,
    val baseUrl: String,       // 形如 https://api.openai.com/v1；归一化由 net 层做
    val apiKey: String,
    val models: List<ModelInfo> = emptyList(),
    val defaultModelId: String? = null,
)
class ProviderRepository(context: Context) {
    fun load(): List<Provider>
    fun upsert(provider: Provider)      // 同 id 覆盖
    fun delete(id: String)
    fun get(id: String): Provider?
}
```

### local（package `dev.cliptranslate.local`）
```kotlin
enum class LocalModelPromptStyle { GEMMA, INDEX_TRANSLATE }

data class AttachmentSpec(                   // 可选附件（目前只有 Index-Translate 2B 的视觉投影 mmproj）
    val fileName: String, val downloadUrl: String,
    val sha256: String, val sizeBytes: Long,
)

data class LocalModelSpec(
    val id: String, val displayName: String, val fileName: String,
    val downloadUrl: String, val sha256: String, val sizeBytes: Long,
    val minTotalMemBytes: Long,             // 机型门槛：设备总内存
    val defaultMemoryThresholdGb: Float,    // 加载前可用内存预检的默认阈值
    val promptStyle: LocalModelPromptStyle,
    val mmproj: AttachmentSpec? = null,     // 非 null 表示该模型可（另行下载附件后）翻译图片
)

object LocalModelCatalog {
    val models: List<LocalModelSpec>        // 顺序即优先级，第一个是默认模型
    fun byId(id: String?): LocalModelSpec   // 找不到/为空回退第一个，永不抛
}

class ModelManager {
    val statesMap: StateFlow<Map<String, ModelState>>   // 只含主文件，不含附件
    fun stateOf(modelId: String): StateFlow<ModelState>
    fun hasPartialOf(modelId: String): StateFlow<Boolean>
    fun modelFile(modelId: String): File
    fun download(modelId: String) / cancelDownload(modelId: String) / deleteModel(modelId: String)
    fun refreshFromDisk()                              // 主文件与附件一起对磁盘
    // 附件：没有 mmproj 的模型这些返回 null / 是空操作
    fun attachmentStateOf(modelId: String): StateFlow<ModelState>?
    fun hasAttachmentPartialOf(modelId: String): StateFlow<Boolean>?
    fun attachmentFile(modelId: String): File?
    fun downloadAttachment(modelId: String) / cancelAttachmentDownload(modelId: String)
    fun deleteAttachment(modelId: String)              // 只删附件，主文件保留
}

object LocalModelRequirements {
    fun isSupported(context: Context): Boolean                   // 任一模型可用
    fun isSupported(context: Context, spec: LocalModelSpec): Boolean
    fun supportedModels(context: Context): List<LocalModelSpec>
}

object LocalSettings {
    val enabled: StateFlow<Boolean>
    val gpuBackend: StateFlow<GpuBackend>
    val selectedModelId: StateFlow<String>                       // 两个页面共享的"当前用哪个本地模型"
    fun setSelectedModelId(context: Context, id: String)         // 归一化后写盘 + 推 StateFlow
}

data class DecodedImage(val rgb: ByteArray, val width: Int, val height: Int)  // RGB 3 字节/像素

class LocalTranslator {
    suspend fun translate(
        text: String, targetCode: String, modelId: String,
        image: DecodedImage? = null,        // 非 null 走图片翻译；此时 text 是可选附加要求
    ): String
}
```
- 加模型只改 `LocalModelCatalog`：下载、校验、机型过滤、设置页下拉、提示词分派全按目录走，不再有散落的硬编码常量。
- native 侧只做裸 prompt 补全，chat template 由 Kotlin 侧展开（`GEMMA` / `INDEX_TRANSLATE` 两套，必须逐字一致）；图片走 `INDEX_TRANSLATE` 专用模板，prompt 里用 `__IMAGE__` 占位符表示图片位置。
- 内存判定分两层：`minTotalMemBytes` 决定机型能否列出该模型，`defaultMemoryThresholdGb` 决定加载前的可用内存预检阈值（用户自定义过阈值时以用户值为准）。
- 「用哪个本地模型」只有 `LocalSettings.selectedModelId` 一个事实源：设置页下拉与翻译页服务商默认模型都读它，任一处改另一处立即跟着变。
- 内置本地 Provider 的 `models` 只列「本机跑得动 且 `ModelState.Ready`」的模型（`ProviderRepository.builtinProviders`）；一个都没就绪时列表为空、`defaultModelId` 为 null，翻译页表现为下拉禁用 + 未选择模型 + 去设置下载提示。
- **视觉投影附件（图片翻译）**：`LocalModelSpec.mmproj` 是可选项，只有多模态模型才有。附件与主文件由 `ModelManager` 用同一套下载/续传/SHA256 逻辑管理（各自 `.part`、各自状态流），但附件状态不进 `statesMap` —— 聚合表始终只表达「模型文件好没好」。删除模型连带删除附件；`refreshFromDisk()` 两者都覆盖。
- **图片推理的加载维度**：`LlamaBridge.nativeLoad(modelPath, nThreads, nCtx, gpuBackend, skelDir, mmprojPath)` 的 `mmprojPath` 为空串表示不带视觉能力。`LocalTranslator` 在 mutex 内额外记住 `handleHasVision`：纯文本 handle 收到图片请求时先卸再按带 mmproj 的路径重载（与换模型同一模式）；附件不在磁盘上则抛「图片翻译需要先在设置中下载视觉投影」。纯文本路径的行为与加图片能力之前完全一致。
- **图片生成契约**：`LlamaBridge.nativeGenerateImage(handle, prompt, rgb, width, height, maxTokens)`，`rgb` 为紧密排列的 RGB 三字节/像素；Kotlin 侧负责取材、解码、降采样（`platform/PhotoInput.kt`）与摊平，native 侧只做 marker 替换与解码循环。

### net（package `dev.cliptranslate.net`）
```kotlin
class OpenAiException(message: String, val httpCode: Int? = null) : Exception(message)

class OpenAiClient(
    baseUrl: String,             // 归一化：去尾斜杠；若 path 为空自动补 "/v1"
    private val apiKey: String,
    private val connectTimeoutMs: Int = 15000,
    private val readTimeoutMs: Int = 60000,
) {
    fun chat(model: String, system: String, user: String): String   // 阻塞；返回译文纯文本
    fun fetchModels(): List<ModelInfo>                               // 阻塞
}
```
- 请求头：`Authorization: Bearer <apiKey>`；HTTP 非 2xx 时抛 `OpenAiException`（含状态码与响应节选）。
- `chat` 只取 `choices[0].message.content` 字符串。

### platform（package `dev.cliptranslate.platform`）
```kotlin
object ClipboardHelper {
    fun readText(context: Context): String?   // 剪贴板为空或非文本返回 null
}
```

### ui 关键行为
- **翻译页**：进入页面与 `ON_RESUME` 时调用 `ClipboardHelper.readText`；剪贴板有文本且与当前输入不同 → 自动填入输入框（用户新需求：剪贴板有内容时自动使用）。输入框始终可手动编辑。
- 状态（`TranslateViewModel`）：`input / output / isLoading / error / targetCode / providerId / modelId / localModelReady / canImageTranslate / image / imageThumbnail`。
- 记忆项（`SharedPreferences` 单独文件 `app_prefs`）：最近使用的 providerId、modelId、targetCode。已选图片不持久化（进程被杀就重选，缓存目录里的照片由系统回收）。
- 无已配置 Provider 时翻译页显示"去设置添加"引导按钮。
- **图片翻译入口**：仅当「当前 Provider 是本地 + 选中模型带 `mmproj` 附件 + 主文件与附件都是 `Ready`」时，输入框下方出现「拍照」「打开图片」。附件存在但未就绪时按钮不出现（引导下载由设置页的附件行承担），已选图片会在能力消失时自动清掉。
  - 拍照：`ActivityResultContracts.TakePicture` + `FileProvider`（`${applicationId}.fileprovider` → `cacheDir/images/`）；打开图片：`ActivityResultContracts.PickVisualMedia`（系统照片选择器）。两者都不申请任何运行时权限。
  - 解码：`platform/PhotoInput.kt` 用 BitmapFactory 两遍法降采样（最长边 ≤ 1024）、按 EXIF 转正朝向、摊平成 RGB 三字节/像素；缩略图独立成一份供界面显示，点缩略图移除。
  - 图片模式下输入框内容变成「附加要求」（可空），提示词见 `buildIndexTranslateImagePrompt`。
- **设置页**：Provider 列表（名称、baseUrl、模型数）；「+」新建 / 点击编辑（底部弹窗编辑 name/baseUrl/apiKey）;「拉取模型」按钮 → `fetchModels()` → 弹出模型多选列表 → 确认后一键合并保存到该 Provider。
- **本地模型**：设置页卡片顶部可选模型（列出本机内存跑得动的全部模型，不管有没有下载），每个模型独立下载/删除、各自 `.part` 可断点续传；「启用本地离线翻译」开关与「加速方式」是全局设置。选中带附件的模型时，主状态行下方多一行「视觉投影（图片翻译）」：状态与按钮与主文件同款，但下载/取消/删除只作用于附件。翻译页的本地 Provider 只列「跑得动且已下载就绪」的模型，两处的"当前选中模型"都来自 `LocalSettings.selectedModelId`，任一处切换另一处立即同步。
- 线程纪律：所有 `OpenAiClient` 调用在 `Dispatchers.IO`；StateFlow 暴露状态。

## 4. CI（GitHub Actions）

`.github/workflows/build-apk.yml`：ubuntu-latest → checkout → setup-java 21 (temurin) → android-actions/setup-android@v4（`platforms;android-36 build-tools;36.0.0 platform-tools`，cmdline-tools 14742923）→ chmod gradlew → `./gradlew :app:assembleRelease --stacktrace` → upload-artifact（7 天）。触发：push main + workflow_dispatch。Release 用 debug 签名（与参考仓库一致，仓库不含私钥）。

## 5. 本地 QC 门禁

`tools/qc/syntax-check.sh`（Git Bash 可跑）：
1. `kotlinc` 全量解析 `app/src/main/java/**`，**语法类错误**（expecting/unexpected/unclosed 等）必须为 0；Android/Compose 未解析引用属预期噪音，过滤掉。
2. `kotlinc core/ -include-runtime -d build/qc/core.jar && java -jar core.jar` 跑 core 自测 main，断言 Prompts/Languages 行为。

两个门禁全绿才算子代理任务完成。
