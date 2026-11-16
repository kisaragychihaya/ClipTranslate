package dev.cliptranslate.ui.translate

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cliptranslate.core.Languages
import dev.cliptranslate.core.Prompts
import dev.cliptranslate.core.TextUtil
import dev.cliptranslate.data.LOCAL_PROVIDER_ID
import dev.cliptranslate.data.Provider
import dev.cliptranslate.data.ProviderRepository
import dev.cliptranslate.local.DecodedImage
import dev.cliptranslate.local.LocalModelCatalog
import dev.cliptranslate.local.LocalModelRequirements
import dev.cliptranslate.local.LocalSettings
import dev.cliptranslate.local.LocalTranslateException
import dev.cliptranslate.local.LocalTranslator
import dev.cliptranslate.local.ModelManager
import dev.cliptranslate.local.ModelState
import dev.cliptranslate.net.OpenAiClient
import dev.cliptranslate.net.OpenAiException
import dev.cliptranslate.platform.ClipboardHelper
import dev.cliptranslate.platform.PhotoInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TranslateUiState(
    val input: String = "",
    val output: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val targetCode: String = DEFAULT_TARGET_CODE,
    val providerId: String? = null,
    val modelId: String? = null,
    val providers: List<Provider> = emptyList(),
    /** 当前选中的本地模型是否已就绪（选中本地 Provider 但没下载时，主界面据此提示去设置下载）。 */
    val localModelReady: Boolean = false,
    /**
     * 能否翻译图片：本地 Provider + 选中模型带视觉投影附件 + 主文件与附件都已就绪。
     * 只有它为 true 时「拍照 / 打开图片」才出现 —— 附件没下载时引导去设置页，不在主界面堆提示。
     */
    val canImageTranslate: Boolean = false,
    /** 当前选中的图片（RGB 像素）；非 null 时 [TranslateViewModel.translate] 走图片推理。 */
    val image: DecodedImage? = null,
    /** 输入框下方的小缩略图，与 [image] 同时设置/清除。 */
    val imageThumbnail: Bitmap? = null,
) {
    val currentProvider: Provider?
        get() = providers.firstOrNull { it.id == providerId }

    /** 真正用于请求的模型：优先用户手选的 modelId，其次服务商的默认模型。 */
    val effectiveModelId: String?
        get() = modelId?.takeIf { it.isNotBlank() }
            ?: currentProvider?.defaultModelId?.takeIf { it.isNotBlank() }
}

class TranslateViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val repository = ProviderRepository(application)
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val modelManager = ModelManager.get(application)

    private val _state = MutableStateFlow(TranslateUiState())
    val state: StateFlow<TranslateUiState> = _state.asStateFlow()

    init {
        LocalSettings.attach(application)
        _state.update { current ->
            val next = current.copy(
                targetCode = restoreTargetCode(),
                providerId = prefs.getString(KEY_PROVIDER_ID, null),
                modelId = prefs.getString(KEY_MODEL_ID, null),
            )
            next.copy(localModelReady = isLocalModelReady(next))
        }
        refreshProviders()

        // 模型就绪状态变了：既要更新提示，也要重建服务商列表 —— 翻译页只列「已下载就绪」的本地模型。
        // 只在「就绪集合」真的变化时刷新：下载进度每 256KB 推一次 statesMap，逐次重建会白白刷 UI。
        viewModelScope.launch {
            var lastReadyIds: Set<String>? = null
            modelManager.statesMap.collect { states ->
                refreshLocalReadiness()
                val readyIds = states.filterValues { it is ModelState.Ready }.keys.toSet()
                if (readyIds != lastReadyIds) {
                    lastReadyIds = readyIds
                    refreshProviders()
                }
            }
        }
        // 视觉投影附件不在 statesMap 里（聚合表只表达「模型文件好没好」），所以单独跟着选中的
        // 本地模型切流：附件下载完成 / 被删除时，图片入口要立刻跟着出现或消失。
        viewModelScope.launch {
            LocalSettings.selectedModelId.collectLatest { selectedId ->
                val attachment = modelManager.attachmentStateOf(selectedId)
                if (attachment == null) {
                    refreshImageTranslation()
                } else {
                    attachment.collect { refreshImageTranslation() }
                }
            }
        }
        // 设置页换选本地模型后同步到翻译页（反向由 selectModel 写 LocalSettings 完成）。
        // 刻意不检查「该模型是否已在 provider.models 里」：用户可能就是刚选了一个还没下载的模型，
        // 这时翻译页如实显示它（配「还没下载」提示），下载完成后 refreshProviders 会把列表补上。
        viewModelScope.launch {
            LocalSettings.selectedModelId.drop(1).collect { selectedId ->
                val current = _state.value
                if (current.currentProvider?.id != LOCAL_PROVIDER_ID) return@collect
                if (current.modelId == selectedId) return@collect
                _state.update { it.copy(modelId = selectedId, output = "", error = null) }
                refreshLocalReadiness()
                persistSelection()
            }
        }
        // 设置页切换「启用本地离线翻译」后，服务商列表要重新过滤（首次值由 refreshProviders 处理）。
        viewModelScope.launch {
            LocalSettings.enabled.drop(1).collect { refreshProviders() }
        }
    }

    // --- 输入与选择 ---------------------------------------------------------

    fun onInputChange(value: String) {
        _state.update { it.copy(input = value, error = null) }
    }

    fun clearInput() {
        _state.update { it.copy(input = "", output = "", error = null) }
    }

    fun selectTarget(code: String) {
        _state.update { it.copy(targetCode = code, output = "", error = null) }
        persistSelection()
    }

    fun selectProvider(providerId: String) {
        val provider = _state.value.providers.firstOrNull { it.id == providerId } ?: return
        val model = provider.defaultModelId?.takeIf { it.isNotBlank() }
            ?: provider.models.firstOrNull()?.id
        _state.update { it.copy(providerId = provider.id, modelId = model, output = "", error = null) }
        refreshLocalReadiness()
        persistSelection()
    }

    fun selectModel(modelId: String) {
        _state.update { it.copy(modelId = modelId, output = "", error = null) }
        // 本地 Provider 的模型选择与设置页共用 [LocalSettings] 这一个持久化源，这里顺手写回去，
        // 设置页的下拉与下载状态会立即跟着切到这个模型。
        if (_state.value.currentProvider?.id == LOCAL_PROVIDER_ID) {
            LocalSettings.setSelectedModelId(app, modelId)
        }
        // 换模型可能从「已下载」切到「还没下载」，提示要立刻跟着变（statesMap 不会因此推送）。
        refreshLocalReadiness()
        persistSelection()
    }

    // --- 剪贴板 -------------------------------------------------------------

    /**
     * 自动使用剪贴板：有文本、且与当前输入不同时填入输入框。
     * 与当前译文相同时也跳过，避免"复制译文后切回来"把原文覆盖掉。
     */
    fun refreshClipboard(context: Context) {
        val text = ClipboardHelper.readText(context) ?: return
        val current = _state.value
        if (text == current.input || text == current.output) return
        _state.update { it.copy(input = text, output = "", error = null) }
    }

    /** 「粘贴」按钮：强制再读一次剪贴板，剪贴板为空时给出提示。 */
    fun pasteFromClipboard(context: Context) {
        val text = ClipboardHelper.readText(context)
        if (text == null) {
            _state.update { it.copy(error = "剪贴板里没有可翻译的文本") }
            return
        }
        _state.update { it.copy(input = text, output = "", error = null) }
    }

    // --- 图片输入 -----------------------------------------------------------

    /**
     * 选好图后解码：BitmapFactory 两遍降采样 + RGB 摊平都在 [Dispatchers.IO]，
     * 一张大图要几十毫秒到几百毫秒，放主线程会卡住输入框。
     *
     * 解码失败只清掉旧图并给可读提示，不动已有译文 —— 用户可能只是想换一张图。
     */
    fun setImage(uri: Uri) {
        viewModelScope.launch {
            val photo = withContext(Dispatchers.IO) { PhotoInput.decode(app, uri) }
            if (photo == null) {
                _state.update { it.copy(error = "图片读取失败，请换一张试试") }
                return@launch
            }
            _state.update {
                it.copy(
                    image = photo.image,
                    imageThumbnail = photo.thumbnail,
                    output = "",
                    error = null,
                )
            }
        }
    }

    /** 点缩略图移除已选图片，回到纯文本翻译。 */
    fun clearImage() {
        _state.update { it.copy(image = null, imageThumbnail = null, output = "", error = null) }
    }

    // --- 服务商 / 模型 ------------------------------------------------------

    fun refreshProviders() {
        val localEnabled = LocalSettings.enabled.value
        val localSupported = LocalModelRequirements.isSupported(app)
        viewModelScope.launch {
            val providers = withContext(Dispatchers.IO) {
                runCatching { repository.load() }.getOrDefault(emptyList())
                    // 关掉「启用本地离线翻译」或本机内存不够时，主界面的服务商列表里不出现内置本地 Provider。
                    .filter { it.id != LOCAL_PROVIDER_ID || (localEnabled && localSupported) }
            }
            _state.update { current ->
                val provider = providers.firstOrNull { it.id == current.providerId }
                    ?: providers.firstOrNull()
                // 本地 Provider 的模型选择以 LocalSettings.selectedModelId 为唯一事实源：
                // 不保留旧 modelId，一律回落到 defaultModelId（仓库层已让它跟随 selectedModelId）。
                // 在线 Provider 的 models 可能还没拉取过，保留旧 modelId 更友好。
                val keepModel = provider != null &&
                    provider.id == current.providerId &&
                    provider.id != LOCAL_PROVIDER_ID &&
                    !current.modelId.isNullOrBlank() &&
                    (provider.models.isEmpty() || provider.models.any { it.id == current.modelId })
                val model = when {
                    provider == null -> null
                    keepModel -> current.modelId
                    else -> provider.defaultModelId?.takeIf { it.isNotBlank() }
                        ?: provider.models.firstOrNull()?.id
                }
                current.copy(
                    providers = providers,
                    providerId = provider?.id,
                    modelId = model,
                )
            }
            _state.update { it.copy(localModelReady = isLocalModelReady(it)) }
            refreshImageTranslation()
            persistSelection()
        }
    }

    // --- 翻译主链路 ---------------------------------------------------------

    fun translate() {
        val snapshot = _state.value
        if (snapshot.isLoading) return

        val image = snapshot.image
        val text = TextUtil.clipForRequest(snapshot.input)
        // 图片模式下输入框里写的是「附加要求」，可以为空；纯文本模式仍然必须有正文。
        if (image == null && text.isBlank()) {
            _state.update { it.copy(error = "请输入要翻译的文本") }
            return
        }
        val provider = snapshot.currentProvider
        if (provider == null) {
            _state.update { it.copy(error = "未配置服务商，请先在设置中添加") }
            return
        }
        val targetCode = snapshot.targetCode
        val isLocal = provider.id == LOCAL_PROVIDER_ID
        val model = snapshot.effectiveModelId.orEmpty()
        // 本地链路的「当前模型」= 服务商里选中的那个模型 id；为空/失效时退回目录默认。
        val localSpec = LocalModelCatalog.byId(snapshot.effectiveModelId)
        if (isLocal) {
            // 本地链路要有下载好且校验通过的模型文件 —— 只检查选中的那一个，别的模型下没下无关。
            if (!LocalModelRequirements.isSupported(app)) {
                _state.update { it.copy(error = "本机内存不足，无法使用本地模型") }
                return
            }
            if (modelManager.statesMap.value[localSpec.id] !is ModelState.Ready) {
                _state.update { it.copy(error = "请先在设置中下载本地模型") }
                return
            }
            // 图片入口只在附件就绪时出现，这里再拦一次：入口出现后用户可能去设置里把附件删了。
            if (image != null &&
                (localSpec.mmproj == null ||
                    modelManager.attachmentStateOf(localSpec.id)?.value !is ModelState.Ready)
            ) {
                _state.update { it.copy(error = "图片翻译需要先在设置中下载视觉投影") }
                return
            }
        } else if (model.isBlank()) {
            _state.update { it.copy(error = "请先在设置中选择模型") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val result = withContext(Dispatchers.IO) {
                    if (isLocal) {
                        LocalTranslator.get(app).translate(text, targetCode, localSpec.id, image)
                    } else {
                        OpenAiClient(provider.baseUrl, provider.apiKey).chat(
                            model,
                            Prompts.systemPrompt(targetCode),
                            Prompts.userPrompt(text, targetCode),
                        )
                    }
                }
                _state.update {
                    it.copy(
                        isLoading = false,
                        output = result,
                        error = if (result.isBlank()) "模型没有返回任何内容" else null,
                    )
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(isLoading = false) }
                throw e
            } catch (e: LocalTranslateException) {
                // 本地推理的失败信息本身就是给人看的，不再加「请求失败」前缀。
                _state.update { it.copy(isLoading = false, error = e.message ?: "本地翻译失败") }
            } catch (e: OpenAiException) {
                _state.update { it.copy(isLoading = false, error = e.message ?: "请求失败") }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = "请求失败：${e.message ?: e.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    // --- 本地模型就绪判断 ---------------------------------------------------

    /**
     * 本地模型是否就绪：只看「当前选中的那个模型」。
     *
     * 用户可能在设置里下过两个模型却只用一个，所以不能再用「有任意一个 Ready 就算就绪」。
     * 选中模型是服务商的模型 id；没选/失效时按目录默认，与 [translate] 的兜底保持一致。
     */
    private fun isLocalModelReady(snapshot: TranslateUiState): Boolean {
        val modelId = if (snapshot.currentProvider?.id == LOCAL_PROVIDER_ID) {
            LocalModelCatalog.byId(snapshot.effectiveModelId).id
        } else {
            LocalModelCatalog.DEFAULT_MODEL_ID
        }
        return modelManager.statesMap.value[modelId] is ModelState.Ready
    }

    /** 选择（服务商/模型）变了就重算一次就绪标记 —— 这类变化不会让 statesMap 推送。 */
    private fun refreshLocalReadiness() {
        _state.update { it.copy(localModelReady = isLocalModelReady(it)) }
        refreshImageTranslation()
    }

    /**
     * 重算「能否翻译图片」：本地 Provider + 选中模型带视觉投影附件 + 主文件与附件都已就绪。
     *
     * 能力不成立时顺手清掉已选图片：否则用户在图片翻译可用时选好图、再把模型换成不支持图片的，
     * 点「翻译」只会静默丢掉图片，比直接收起图片更让人困惑。
     */
    private fun refreshImageTranslation() {
        _state.update { current ->
            if (canImageTranslate(current)) {
                current.copy(canImageTranslate = true)
            } else {
                current.copy(canImageTranslate = false, image = null, imageThumbnail = null)
            }
        }
    }

    private fun canImageTranslate(snapshot: TranslateUiState): Boolean {
        if (snapshot.currentProvider?.id != LOCAL_PROVIDER_ID) return false
        val spec = LocalModelCatalog.byId(snapshot.effectiveModelId)
        // 没有附件的模型（如 TranslateGemma）就没有视觉能力，连按钮都不出现。
        if (spec.mmproj == null) return false
        if (modelManager.statesMap.value[spec.id] !is ModelState.Ready) return false
        return modelManager.attachmentStateOf(spec.id)?.value is ModelState.Ready
    }

    // --- 记忆项 -------------------------------------------------------------

    private fun restoreTargetCode(): String {
        val saved = prefs.getString(KEY_TARGET_CODE, null)
        return saved?.takeIf { code -> Languages.SUPPORTED.any { it.first == code } }
            ?: DEFAULT_TARGET_CODE
    }

    private fun persistSelection() {
        val snapshot = _state.value
        prefs.edit()
            .putString(KEY_PROVIDER_ID, snapshot.providerId)
            .putString(KEY_MODEL_ID, snapshot.modelId)
            .putString(KEY_TARGET_CODE, snapshot.targetCode)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "app_prefs"
        const val KEY_PROVIDER_ID = "last_provider_id"
        const val KEY_MODEL_ID = "last_model_id"
        const val KEY_TARGET_CODE = "last_target_code"
    }
}

private const val DEFAULT_TARGET_CODE = "zh-CN"
