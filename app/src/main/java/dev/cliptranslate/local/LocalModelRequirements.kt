package dev.cliptranslate.local

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import java.util.Locale

/**
 * 本地模型（离线翻译）的硬件门槛：总内存达到某个模型自己的 [LocalModelSpec.minTotalMemBytes]
 * 才提供该模型。
 *
 * 门槛按模型分档而不是 8GiB 一刀切：TranslateGemma 4B 常驻约 3GB，8GiB 以下的机器跑起来会被
 * 系统反复杀进程；Index-Translate 2B 常驻约 1.5GB，4GiB 机型就能用。
 * 设置页的本地模型卡片与翻译页的服务商列表只看 [isSupported]（任一模型可用），
 * 具体列出哪些模型由 [supportedModels] 决定。
 */
object LocalModelRequirements {

    private const val TAG = "CliptranslateLocal"

    /** 设备总内存不会变，检测一次即可；null 表示尚未检测过。 */
    @Volatile private var cachedTotalMemBytes: Long? = null

    /** 本机是否跑得动 [spec]（总内存 ≥ 该模型的 [LocalModelSpec.minTotalMemBytes]）。 */
    fun isSupported(context: Context, spec: LocalModelSpec): Boolean =
        totalMemBytes(context) >= spec.minTotalMemBytes

    /** 本机能跑的模型，保持 [LocalModelCatalog.models] 的顺序。 */
    fun supportedModels(context: Context): List<LocalModelSpec> {
        val total = totalMemBytes(context)
        return LocalModelCatalog.models.filter { total >= it.minTotalMemBytes }
    }

    /** 本机是否至少能跑一个本地模型 —— 决定本地模型入口（设置页卡片、翻译页服务商）是否出现。 */
    fun isSupported(context: Context): Boolean = supportedModels(context).isNotEmpty()

    /** 读一次总内存并缓存：循环里逐个模型判断时不要反复过 ActivityManager。 */
    private fun totalMemBytes(context: Context): Long {
        cachedTotalMemBytes?.let { return it }
        val manager = context.applicationContext
            .getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        // 拿不到 ActivityManager 时 totalMem 保持 0，按不支持处理：宁可少一个入口，也别让本地推理把机器拖死。
        manager?.getMemoryInfo(info)
        Log.i(TAG, "本地模型支持检测: 总内存 ${formatGigabytes(info.totalMem)} GB")
        cachedTotalMemBytes = info.totalMem
        return info.totalMem
    }

    /** 8589934592 → "8.0"（GB，保留一位小数）。 */
    private fun formatGigabytes(bytes: Long): String =
        String.format(Locale.US, "%.1f", bytes / 1024.0 / 1024.0 / 1024.0)
}
