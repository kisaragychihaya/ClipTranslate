package dev.cliptranslate.local

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 本地离线翻译的持久化项：「启用开关」「加速方式」「当前选中的本地模型」。
 *
 * 和 TranslateViewModel 的记忆项共用同一个 SharedPreferences 文件 [PREFS_NAME]，
 * 与项目现有持久化方式保持一致（SharedPreferences，无 DataStore 依赖）。
 * 用 StateFlow 暴露，设置页与翻译页都能即时响应设置变化。
 *
 * [selectedModelId] 是「用哪个本地模型」的唯一事实源：设置页的下拉、翻译页服务商的默认模型、
 * 下载按钮的状态行都读它，任一处改另一处立即跟着变，不会出现两边各记一个值的情况。
 */
object LocalSettings {

    private const val PREFS_NAME = "app_prefs"
    private const val KEY_LOCAL_ENABLED = "local_offline_enabled"
    private const val KEY_GPU_BACKEND = "gpu_backend"
    private const val KEY_LOCAL_MODEL_ID = "local_selected_model_id"

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _gpuBackend = MutableStateFlow(GpuBackend.AUTO)
    val gpuBackend: StateFlow<GpuBackend> = _gpuBackend.asStateFlow()

    /** 当前选中的本地模型 id；[attach] 之前读到的就是目录默认值。 */
    private val _selectedModelId = MutableStateFlow(LocalModelCatalog.DEFAULT_MODEL_ID)
    val selectedModelId: StateFlow<String> = _selectedModelId.asStateFlow()

    @Volatile private var prefs: SharedPreferences? = null

    /** 首次拿到 Context 时读取磁盘上的设置值；重复调用无副作用。 */
    @Synchronized
    fun attach(context: Context) {
        if (prefs != null) return
        val loaded = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = loaded
        _enabled.value = loaded.getBoolean(KEY_LOCAL_ENABLED, false)
        _gpuBackend.value = GpuBackend.fromValue(
            loaded.getInt(KEY_GPU_BACKEND, GpuBackend.AUTO.value),
        )
        // 用 byId 归一化：改过目录 id 或清过模型文件后，旧值必须退化成有效模型而不是空指针。
        _selectedModelId.value = LocalModelCatalog.byId(loaded.getString(KEY_LOCAL_MODEL_ID, null)).id
    }

    fun setEnabled(context: Context, value: Boolean) {
        attach(context)
        _enabled.value = value
        prefs?.edit()?.putBoolean(KEY_LOCAL_ENABLED, value)?.apply()
    }

    fun setGpuBackend(context: Context, value: GpuBackend) {
        attach(context)
        _gpuBackend.value = value
        prefs?.edit()?.putInt(KEY_GPU_BACKEND, value.value)?.apply()
    }

    /** 切换选中的本地模型；同样先归一化，写 StateFlow 让两个页面同时收到。 */
    fun setSelectedModelId(context: Context, id: String) {
        attach(context)
        val normalized = LocalModelCatalog.byId(id).id
        _selectedModelId.value = normalized
        prefs?.edit()?.putString(KEY_LOCAL_MODEL_ID, normalized)?.apply()
    }
}
