package dev.cliptranslate.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cliptranslate.data.ModelInfo
import dev.cliptranslate.data.Provider
import dev.cliptranslate.data.ProviderRepository
import dev.cliptranslate.net.OpenAiClient
import dev.cliptranslate.net.OpenAiException
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.cliptranslate.net.ModelInfo as NetModelInfo

/**
 * 设置页状态：Provider 列表、拉取模型流程、编辑弹窗。
 * 所有 [OpenAiClient] 调用都跑在 [Dispatchers.IO]，UI 只收集 StateFlow。
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ProviderRepository(application)

    private val _providers = MutableStateFlow<List<Provider>>(emptyList())
    val providers: StateFlow<List<Provider>> = _providers.asStateFlow()

    private val _isFetching = MutableStateFlow(false)
    val isFetching: StateFlow<Boolean> = _isFetching.asStateFlow()

    private val _fetchError = MutableStateFlow<String?>(null)
    val fetchError: StateFlow<String?> = _fetchError.asStateFlow()

    /** 拉取到的模型列表；null 表示当前不在拉取流程中。 */
    private val _fetchResult = MutableStateFlow<List<NetModelInfo>?>(null)
    val fetchResult: StateFlow<List<NetModelInfo>?> = _fetchResult.asStateFlow()

    /** 正在编辑的 Provider；null 表示编辑弹窗关闭。新建时 id 为新 UUID，其余字段为空。 */
    private val _editing = MutableStateFlow<Provider?>(null)
    val editing: StateFlow<Provider?> = _editing.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        _providers.value = repository.load()
    }

    fun startCreate() {
        _fetchError.value = null
        _fetchResult.value = null
        _editing.value = Provider(
            id = UUID.randomUUID().toString(),
            name = "",
            baseUrl = "",
            apiKey = "",
        )
    }

    fun startEdit(provider: Provider) {
        _fetchError.value = null
        _fetchResult.value = null
        _editing.value = provider
    }

    /** 编辑弹窗里每敲一下输入框都会把草稿同步到这里，避免切到模型选择弹窗后丢输入。 */
    fun updateEditing(provider: Provider) {
        if (_editing.value?.id == provider.id) {
            _editing.value = provider
        }
    }

    fun dismissEditor() {
        _editing.value = null
        _fetchError.value = null
        _fetchResult.value = null
    }

    /** 关闭模型选择弹窗，回到编辑弹窗（草稿保留）。 */
    fun dismissModelPicker() {
        _fetchResult.value = null
        _fetchError.value = null
    }

    fun save(provider: Provider) {
        repository.upsert(provider)
        reload()
        _editing.value = null
        _fetchResult.value = null
        _fetchError.value = null
    }

    fun delete(id: String) {
        repository.delete(id)
        if (_editing.value?.id == id) {
            _editing.value = null
        }
        if (_fetchResult.value != null) {
            _fetchResult.value = null
        }
        reload()
    }

    fun fetchModels(provider: Provider) {
        if (_isFetching.value) return

        val baseUrl = provider.baseUrl.trim()
        val apiKey = provider.apiKey.trim()
        if (baseUrl.isEmpty() || !baseUrl.contains("://")) {
            _fetchError.value = "拉取失败：Base URL 不能为空且需要包含 \"://\"，例如 https://api.openai.com/v1"
            return
        }

        _fetchError.value = null
        _fetchResult.value = null
        _isFetching.value = true

        viewModelScope.launch {
            try {
                val models = withContext(Dispatchers.IO) {
                    OpenAiClient(baseUrl, apiKey).fetchModels()
                }
                if (models.isEmpty()) {
                    _fetchResult.value = null
                    _fetchError.value = "拉取成功，但服务端没有返回任何模型"
                } else {
                    _fetchResult.value = models
                    _fetchError.value = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _fetchResult.value = null
                _fetchError.value = describeFailure(e)
            } finally {
                _isFetching.value = false
            }
        }
    }

    /**
     * 把拉取结果里勾选的模型并入 provider.models（按 id 去重），defaultModelId 为空时取第一个，
     * 保存后清空拉取状态（模型选择弹窗与编辑弹窗一起关闭）。
     */
    fun applyFetchedModels(provider: Provider, selectedIds: Set<String>) {
        val fetched = _fetchResult.value ?: return

        val merged = provider.models.toMutableList()
        val seen = provider.models.map { it.id }.toMutableSet()
        fetched.forEach { model ->
            if (model.id in selectedIds && seen.add(model.id)) {
                merged.add(ModelInfo(id = model.id, ownedBy = model.ownedBy))
            }
        }

        val defaultId = provider.defaultModelId?.takeIf { id -> merged.any { it.id == id } }
            ?: merged.firstOrNull()?.id

        save(provider.copy(models = merged, defaultModelId = defaultId))
    }

    private fun describeFailure(e: Exception): String {
        val code = (e as? OpenAiException)?.httpCode
        val reason = e.message?.takeIf { it.isNotBlank() }
            ?: when (e) {
                is OpenAiException -> "服务端返回错误"
                is IOException -> "网络连接失败"
                else -> e.javaClass.simpleName
            }
        return buildString {
            append("拉取模型失败：")
            append(reason)
            if (code != null && !reason.contains(code.toString())) {
                append("（HTTP ").append(code).append("）")
            }
        }
    }
}
