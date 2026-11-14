package dev.cliptranslate.ui.translate

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cliptranslate.core.Languages
import dev.cliptranslate.core.Prompts
import dev.cliptranslate.core.TextUtil
import dev.cliptranslate.data.Provider
import dev.cliptranslate.data.ProviderRepository
import dev.cliptranslate.net.OpenAiClient
import dev.cliptranslate.net.OpenAiException
import dev.cliptranslate.platform.ClipboardHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) {
    val currentProvider: Provider?
        get() = providers.firstOrNull { it.id == providerId }

    /** 真正用于请求的模型：优先用户手选的 modelId，其次服务商的默认模型。 */
    val effectiveModelId: String?
        get() = modelId?.takeIf { it.isNotBlank() }
            ?: currentProvider?.defaultModelId?.takeIf { it.isNotBlank() }
}

class TranslateViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ProviderRepository(application)
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(TranslateUiState())
    val state: StateFlow<TranslateUiState> = _state.asStateFlow()

    init {
        _state.update {
            it.copy(
                targetCode = restoreTargetCode(),
                providerId = prefs.getString(KEY_PROVIDER_ID, null),
                modelId = prefs.getString(KEY_MODEL_ID, null),
            )
        }
        refreshProviders()
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
        persistSelection()
    }

    fun selectModel(modelId: String) {
        _state.update { it.copy(modelId = modelId, output = "", error = null) }
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
        viewModelScope.launch {
            val providers = withContext(Dispatchers.IO) {
                runCatching { repository.load() }.getOrDefault(emptyList())
            }
            _state.update { current ->
                val provider = providers.firstOrNull { it.id == current.providerId }
                    ?: providers.firstOrNull()
                val keepModel = provider != null &&
                    provider.id == current.providerId &&
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
        val model = snapshot.effectiveModelId
        if (model == null) {
            _state.update { it.copy(error = "请先在设置中选择模型") }
            return
        }
        val targetCode = snapshot.targetCode

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val result = withContext(Dispatchers.IO) {
                    OpenAiClient(provider.baseUrl, provider.apiKey).chat(
                        model,
                        Prompts.systemPrompt(targetCode),
                        Prompts.userPrompt(text, targetCode),
                    )
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
