package dev.cliptranslate.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cliptranslate.data.ModelInfo
import dev.cliptranslate.data.Provider
import dev.cliptranslate.data.ProviderRepository
import dev.cliptranslate.data.isBuiltin
import dev.cliptranslate.local.GpuBackend
import dev.cliptranslate.local.LocalModelCatalog
import dev.cliptranslate.local.LocalModelRequirements
import dev.cliptranslate.local.LocalModelSpec
import dev.cliptranslate.local.LocalSettings
import dev.cliptranslate.local.LocalTranslateException
import dev.cliptranslate.local.LocalTranslator
import dev.cliptranslate.local.ModelManager
import dev.cliptranslate.local.ModelState
import dev.cliptranslate.net.OpenAiClient
import dev.cliptranslate.net.OpenAiException
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
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

    /**
     * 本机能跑的本地模型（目录顺序）。构造时算一次即可：设备总内存不会变。
     * 只有「一个模型都跑不动」时才会是空列表，此时设置页整张本地模型卡片都不显示。
     */
    val localModels: List<LocalModelSpec> = LocalModelRequirements.supportedModels(application)

    /** 卡片里当前选中的本地模型；下载/删除/状态行都跟着它走。 */
    private val _selectedLocalModelId = MutableStateFlow(
        localModels.firstOrNull()?.id ?: LocalModelCatalog.DEFAULT_MODEL_ID,
    )
    val selectedLocalModelId: StateFlow<String> = _selectedLocalModelId.asStateFlow()

    /**
     * 下载/校验状态跟随选中的模型。
     *
     * 用 flatMapLatest 换状态流而不是让 Screen 按 id 自己收集：切换模型时状态行必须立刻切到
     * 新模型的进度，否则会残留上一个模型的「已就绪」，用户会以为新模型也下载好了。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val modelState: StateFlow<ModelState> = _selectedLocalModelId
        .flatMapLatest { modelManager.stateOf(it) }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            modelManager.stateOf(_selectedLocalModelId.value).value,
        )

    /** 是否有 `.part` 残档 —— 按钮文案在「下载」和「继续下载」之间切换。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val hasPartialModel: StateFlow<Boolean> = _selectedLocalModelId
        .flatMapLatest { modelManager.hasPartialOf(it) }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            modelManager.hasPartialOf(_selectedLocalModelId.value).value,
        )

    /** 「启用本地离线翻译」开关（持久化在 app_prefs，与翻译页共享）。 */
    val localEnabled: StateFlow<Boolean> = LocalSettings.enabled

    /** 「加速方式」（持久化在 app_prefs，与翻译页共享；改动后立即卸载旧模型并按新后端重载）。 */
    val gpuBackend: StateFlow<GpuBackend> = LocalSettings.gpuBackend

    /** 正在按新加速方式卸载/重载本地模型。 */
    private val _backendSwitching = MutableStateFlow(false)
    val backendSwitching: StateFlow<Boolean> = _backendSwitching.asStateFlow()

    /** 切换加速方式失败的原因；null 表示没有错误。 */
    private val _backendSwitchError = MutableStateFlow<String?>(null)
    val backendSwitchError: StateFlow<String?> = _backendSwitchError.asStateFlow()

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

    /** 切换卡片里选中的本地模型；未收录的 id 退回目录默认，避免状态流指向不存在的会话。 */
    fun selectLocalModel(id: String) {
        _selectedLocalModelId.value = LocalModelCatalog.byId(id).id
    }

    fun downloadModel() {
        modelManager.download(_selectedLocalModelId.value)
    }

    fun cancelModelDownload() {
        modelManager.cancelDownload(_selectedLocalModelId.value)
    }

    fun deleteModel() {
        modelManager.deleteModel(_selectedLocalModelId.value)
    }

    fun setLocalEnabled(enabled: Boolean) {
        LocalSettings.setEnabled(app, enabled)
    }

    /**
     * 先落盘再触发卸载+重载：用户改完加速方式当场就能看到该后端可不可用，
     * 而不是等到下一次翻译才在翻译锁里失败。
     *
     * 连续快速切换时两个重载协程会串行在推理锁上，由
     * [LocalTranslator.reloadForBackendChange] 锁内的幂等检查自然去重。
     */
    fun setGpuBackend(backend: GpuBackend) {
        val previous = LocalSettings.gpuBackend.value
        LocalSettings.setGpuBackend(app, backend)
        if (backend == previous) return

        viewModelScope.launch {
            _backendSwitching.value = true
            _backendSwitchError.value = null
            try {
                val failure = LocalTranslator.get(app).reloadForBackendChange().exceptionOrNull()
                if (failure != null) {
                    _backendSwitchError.value = describeBackendFailure(failure)
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                _backendSwitching.value = false
            }
        }
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

    /** 改加速方式失败的提示：[LocalTranslateException] 的 message 本身就是给人看的说明。 */
    private fun describeBackendFailure(e: Throwable): String =
        (e as? LocalTranslateException)?.message ?: "切换加速方式失败，请稍后重试"

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
