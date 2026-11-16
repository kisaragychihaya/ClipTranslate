package dev.cliptranslate.ui.translate

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cliptranslate.core.Languages
import dev.cliptranslate.core.Prompts
import dev.cliptranslate.core.TextUtil
import dev.cliptranslate.data.LOCAL_PROVIDER_ID
import dev.cliptranslate.data.Provider
import dev.cliptranslate.data.ProviderRepository
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
                _state.update { current -> current.copy(localModelReady = isLocalModelReady(current)) }
                val readyIds = states.filterValues { it is ModelState.Ready }.keys.toSet()
                if (readyIds != lastReadyIds) {
                    lastReadyIds = readyIds
                    refreshProviders()
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
            persistSelection()
        }
    }

    // --- 翻译主链路 ---------------------------------------------------------

    fun translate() {
        val snapshot = _state.value
        if (snapshot.isLoading) return

        val text = TextUtil.clipForRequest(snapshot.input)
        if (text.isBlank()) {
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
        } else if (model.isBlank()) {
            _state.update { it.copy(error = "请先在设置中选择模型") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val result = withContext(Dispatchers.IO) {
                    if (isLocal) {
                        LocalTranslator.get(app).translate(text, targetCode, localSpec.id)
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
