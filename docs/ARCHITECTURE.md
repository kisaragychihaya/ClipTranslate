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
├── net/                       # Android 依赖（org.json 为系统库）
│   ├── OpenAiClient.kt        # chat / fetchModels（阻塞式，IO 线程调用）
│   ├── OpenAiException.kt
│   └── JsonUtil.kt            # org.json 小工具
├── platform/
│   └── ClipboardHelper.kt     # 读剪贴板文本
└── ui/
    ├── AppNav.kt
    ├── theme/Theme.kt
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
- 状态（`TranslateViewModel`）：`input / output / isLoading / error / targetCode / providerId / modelId`。
- 记忆项（`SharedPreferences` 单独文件 `app_prefs`）：最近使用的 providerId、modelId、targetCode。
- 无已配置 Provider 时翻译页显示"去设置添加"引导按钮。
- **设置页**：Provider 列表（名称、baseUrl、模型数）；「+」新建 / 点击编辑（底部弹窗编辑 name/baseUrl/apiKey）;「拉取模型」按钮 → `fetchModels()` → 弹出模型多选列表 → 确认后一键合并保存到该 Provider。
- 线程纪律：所有 `OpenAiClient` 调用在 `Dispatchers.IO`；StateFlow 暴露状态。

## 4. CI（GitHub Actions）

`.github/workflows/build-apk.yml`：ubuntu-latest → checkout → setup-java 21 (temurin) → android-actions/setup-android@v4（`platforms;android-36 build-tools;36.0.0 platform-tools`，cmdline-tools 14742923）→ chmod gradlew → `./gradlew :app:assembleRelease --stacktrace` → upload-artifact（7 天）。触发：push main + workflow_dispatch。Release 用 debug 签名（与参考仓库一致，仓库不含私钥）。

## 5. 本地 QC 门禁

`tools/qc/syntax-check.sh`（Git Bash 可跑）：
1. `kotlinc` 全量解析 `app/src/main/java/**`，**语法类错误**（expecting/unexpected/unclosed 等）必须为 0；Android/Compose 未解析引用属预期噪音，过滤掉。
2. `kotlinc core/ -include-runtime -d build/qc/core.jar && java -jar core.jar` 跑 core 自测 main，断言 Prompts/Languages 行为。

两个门禁全绿才算子代理任务完成。
