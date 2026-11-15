package dev.cliptranslate.local

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import java.util.Locale

/**
 * 本地模型（离线翻译）的硬件门槛：总内存不足 [MIN_TOTAL_MEM_BYTES] 的机型不提供该功能。
 *
 * 4B Q4_K_M 模型常驻约 3GB，8GiB 以下的机器跑起来会被系统反复杀进程，
 * 所以设置页的本地模型卡片与翻译页的服务商列表都直接对这类机型隐藏。
 */
object LocalModelRequirements {

    /** 支持本地模型所需的最小总内存：8GiB。 */
    const val MIN_TOTAL_MEM_BYTES = 8L * 1024 * 1024 * 1024

    private const val TAG = "CliptranslateLocal"

    /** 设备总内存不会变，检测一次即可；null 表示尚未检测过。 */
    @Volatile private var cachedSupported: Boolean? = null

    /** 本机是否支持本地模型（总内存 ≥ [MIN_TOTAL_MEM_BYTES]），首次调用后缓存结果。 */
    fun isSupported(context: Context): Boolean {
        cachedSupported?.let { return it }
        val manager = context.applicationContext
            .getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        // 拿不到 ActivityManager 时 totalMem 保持 0，按不支持处理：宁可少一个入口，也别让本地推理把机器拖死。
        manager?.getMemoryInfo(info)
        val supported = info.totalMem >= MIN_TOTAL_MEM_BYTES
        Log.i(
            TAG,
            "本地模型支持检测: 总内存 ${formatGigabytes(info.totalMem)} GB, 支持=$supported",
        )
        cachedSupported = supported
        return supported
    }

    /** 8589934592 → "8.0"（GB，保留一位小数）。 */
    private fun formatGigabytes(bytes: Long): String =
        String.format(Locale.US, "%.1f", bytes / 1024.0 / 1024.0 / 1024.0)
}
