package dev.cliptranslate.local

import android.content.Context
import android.content.SharedPreferences
import dev.cliptranslate.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 开发者相关的持久化项：「开发者模式」是否解锁、调试日志是否开启。
 *
 * 与 [LocalSettings]、TranslateViewModel 的记忆项共用同一个 SharedPreferences 文件
 * [PREFS_NAME]，保持项目现有习惯（SharedPreferences，无 DataStore 依赖）。
 *
 * 日志默认值用 [BuildConfig.DEBUG]：它是编译期常量，debug 包里恒为 true、
 * release 包里恒为 false，不需要运行时再判构建类型（R8 也会直接内联）。
 */
object DevSettings {

    private const val PREFS_NAME = "app_prefs"
    private const val KEY_LOGGING_ENABLED = "debug_logging_enabled"
    private const val KEY_DEV_MODE = "dev_mode_enabled"
    private const val KEY_MEMORY_THRESHOLD_GB = "memory_check_threshold_gb"

    /** 本地模型加载前「可用内存预检」的默认值与可调范围（GB），见 [LocalTranslator]。 */
    const val DEFAULT_MEMORY_THRESHOLD_GB = 4.0f
    const val MIN_MEMORY_THRESHOLD_GB = 2.5f
    const val MAX_MEMORY_THRESHOLD_GB = 4.5f

    private val _loggingEnabled = MutableStateFlow(BuildConfig.DEBUG)
    val loggingEnabled: StateFlow<Boolean> = _loggingEnabled.asStateFlow()

    private val _devModeEnabled = MutableStateFlow(false)
    val devModeEnabled: StateFlow<Boolean> = _devModeEnabled.asStateFlow()

    private val _memoryThresholdGb = MutableStateFlow(DEFAULT_MEMORY_THRESHOLD_GB)
    val memoryThresholdGb: StateFlow<Float> = _memoryThresholdGb.asStateFlow()

    @Volatile private var prefs: SharedPreferences? = null

    /** 首次拿到 Context 时读取磁盘上的设置值；重复调用无副作用。 */
    @Synchronized
    fun attach(context: Context) {
        if (prefs != null) return
        val loaded = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = loaded
        _loggingEnabled.value = loaded.getBoolean(KEY_LOGGING_ENABLED, BuildConfig.DEBUG)
        _devModeEnabled.value = loaded.getBoolean(KEY_DEV_MODE, false)
        _memoryThresholdGb.value = loaded.getFloat(KEY_MEMORY_THRESHOLD_GB, DEFAULT_MEMORY_THRESHOLD_GB)
            .coerceIn(MIN_MEMORY_THRESHOLD_GB, MAX_MEMORY_THRESHOLD_GB)
    }

    fun setLoggingEnabled(context: Context, value: Boolean) {
        attach(context)
        _loggingEnabled.value = value
        prefs?.edit()?.putBoolean(KEY_LOGGING_ENABLED, value)?.apply()
    }

    fun setDevModeEnabled(context: Context, value: Boolean) {
        attach(context)
        _devModeEnabled.value = value
        prefs?.edit()?.putBoolean(KEY_DEV_MODE, value)?.apply()
    }

    /** 设置内存预检阈值（GB），越界会被夹到 [MIN_MEMORY_THRESHOLD_GB]..[MAX_MEMORY_THRESHOLD_GB]。 */
    fun setMemoryThresholdGb(context: Context, value: Float) {
        attach(context)
        val clamped = value.coerceIn(MIN_MEMORY_THRESHOLD_GB, MAX_MEMORY_THRESHOLD_GB)
        _memoryThresholdGb.value = clamped
        prefs?.edit()?.putFloat(KEY_MEMORY_THRESHOLD_GB, clamped)?.apply()
    }
}
