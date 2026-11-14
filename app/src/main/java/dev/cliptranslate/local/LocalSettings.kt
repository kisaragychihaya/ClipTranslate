package dev.cliptranslate.local

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 本地离线翻译的持久化项：「启用开关」与「加速方式」。
 *
 * 和 TranslateViewModel 的记忆项共用同一个 SharedPreferences 文件 [PREFS_NAME]，
 * 与项目现有持久化方式保持一致（SharedPreferences，无 DataStore 依赖）。
 * 用 StateFlow 暴露，设置页与翻译页都能即时响应设置变化。
 */
object LocalSettings {

    private const val PREFS_NAME = "app_prefs"
    private const val KEY_LOCAL_ENABLED = "local_offline_enabled"
    private const val KEY_GPU_BACKEND = "gpu_backend"

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _gpuBackend = MutableStateFlow(GpuBackend.AUTO)
    val gpuBackend: StateFlow<GpuBackend> = _gpuBackend.asStateFlow()

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
}
