package dev.cliptranslate

import android.app.Application
import dev.cliptranslate.local.DevSettings
import dev.cliptranslate.local.LlamaBridge

/**
 * 进程级入口。现在只做一件事：把持久化的调试日志开关在启动时同步给 native 层，
 * 保证 debug 默认开、release 默认关（默认值见 [DevSettings]）。
 *
 * native 库是懒加载的（首次本地翻译才 dlopen），这里调 [LlamaBridge.applyLogEnabled]
 * 只记下当前值，真正的 nativeSetLogEnabled 由 ensureLoaded() 加载成功后补调。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DevSettings.attach(this)
        LlamaBridge.applyLogEnabled(DevSettings.loggingEnabled.value)
    }
}
