package dev.cliptranslate.local

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「启用本地离线翻译」开关的持久化。
 *
 * 和 TranslateViewModel 的记忆项共用同一个 SharedPreferences 文件 [PREFS_NAME]，
 * 与项目现有持久化方式保持一致（SharedPreferences，无 DataStore 依赖）。
 * 用 StateFlow 暴露，设置页与翻译页都能即时响应开关变化。
 */
object LocalSettings {

    private const val PREFS_NAME = "app_prefs"
    private const val KEY_LOCAL_ENABLED = "local_offline_enabled"

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    @Volatile private var prefs: SharedPreferences? = null

    /** 首次拿到 Context 时读取磁盘上的开关值；重复调用无副作用。 */
    @Synchronized
    fun attach(context: Context) {
        if (prefs != null) return
        val loaded = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = loaded
        _enabled.value = loaded.getBoolean(KEY_LOCAL_ENABLED, false)
    }

    fun setEnabled(context: Context, value: Boolean) {
        attach(context)
        _enabled.value = value
        prefs?.edit()?.putBoolean(KEY_LOCAL_ENABLED, value)?.apply()
    }
}
