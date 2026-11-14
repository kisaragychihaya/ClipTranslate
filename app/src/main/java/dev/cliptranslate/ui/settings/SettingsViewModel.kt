package dev.cliptranslate.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cliptranslate.data.ModelInfo
import dev.cliptranslate.data.Provider
import dev.cliptranslate.data.ProviderRepository
import dev.cliptranslate.data.isBuiltin
import dev.cliptranslate.local.LocalSettings
import dev.cliptranslate.local.ModelManager
import dev.cliptranslate.local.ModelState
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
 * 设置页状态：本地模型、Provider 列表、拉取模型流程、编辑弹窗。
 * 所有 [OpenAiClient] 调用都跑在 [Dispatchers.IO]，UI 只收集 StateFlow。
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val repository = ProviderRepository(application)

    /** 本地模型下载/校验状态；与翻译页共享同一个 [ModelManager] 单例，进度不会两边不一致。 */
    private val modelManager = ModelManager.get(application)

    private val _providers = MutableStateFlow<List<Provider>>(emptyList())
    val providers: StateFlow<List<Provider>> = _providers.asStateFlow()

    val modelState: StateFlow<ModelState> = modelManager.state

    /** 是否有 `.part` 残档 —— 按钮文案在「下载」和「继续下载」之间切换。 */
    val hasPartialModel: StateFlow<Boolean> = modelManager.hasPartial

    /** 「启用本地离线翻译」开关（持久化在 app_prefs，与翻译页共享）。 */
    val localEnabled: StateFlow<Boolean> = LocalSettings.enabled

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
        LocalSettings.attach(application)
        reload()
    }

    fun reload() {
        // 内置本地 Provider 由「本地模型」卡片单独展示，列表里不再重复出现。
        _providers.value = repository.load().filterNot { it.isBuiltin }
    }

    // --- 本地模型 -----------------------------------------------------------

    fun downloadModel() {
        modelManager.download()
    }

    fun cancelModelDownload() {
        modelManager.cancelDownload()
    }

    fun deleteModel() {
        modelManager.deleteModel()
    }

    fun setLocalEnabled(enabled: Boolean) {
        LocalSettings.setEnabled(app, enabled)
    }

    /** 页面回到前台时对一遍磁盘，避免下载过程中进程被杀导致状态卡在「下载中」。 */
    fun refreshModelState() {
        modelManager.refreshFromDisk()
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
