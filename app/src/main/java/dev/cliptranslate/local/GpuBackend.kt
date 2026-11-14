package dev.cliptranslate.local

/**
 * 推理加速方式，[value] 就是 JNI `nativeLoad(..., gpuBackend)` 约定的整型取值。
 *
 * 持久化时存 Int（`gpu_backend`），读取时用 [fromValue] 兜底成 [AUTO]，
 * 这样以后重命名常量或出现脏数据都不会让设置页崩掉。
 */
enum class GpuBackend(val value: Int) {
    /** 0：交给 native 侧决定，优先 GPU，失败自动回落 CPU。 */
    AUTO(0),

    /** 1：只用 CPU，兼容性最好。 */
    CPU(1),

    /** 2：强制 OpenCL（Adreno）。 */
    OPENCL(2),
    ;

    companion object {
        fun fromValue(value: Int): GpuBackend = entries.firstOrNull { it.value == value } ?: AUTO
    }
}
