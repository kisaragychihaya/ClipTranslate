package dev.cliptranslate.local

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 本地模型文件的几种状态。UI 直接按类型分支渲染。 */
sealed interface ModelState {
    data object NotDownloaded : ModelState

    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : ModelState

    data object Verifying : ModelState

    data object Ready : ModelState

    data class Error(val message: String) : ModelState
}

/**
 * 本地 GGUF 模型的下载 / 校验 / 删除。
 *
 * 存放位置：`filesDir/models/`，即应用私有内部存储 —— 卸载应用时由系统自动删除，
 * 不需要任何存储权限，也不会污染用户的相册/下载目录。
 * 下载先写同目录的 `.part` 临时文件，SHA256 校验通过后才 rename 成正式文件名；
 * 取消或失败都保留 `.part`，下次点「继续下载」用 Range 从断点续传（服务器实测支持 206）。
 *
 * 全进程只有一个实例（[get]），设置页与翻译页共享同一份进度 StateFlow。
 */
class ModelManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val modelsDir = File(appContext.filesDir, DIR_MODELS)

    /** 正式模型文件；大小与 [MODEL_SIZE_BYTES] 一致且校验通过后才会存在。 */
    val modelFile = File(modelsDir, FILE_NAME)
    private val partFile = File(modelsDir, "$FILE_NAME.part")

    private val _state = MutableStateFlow<ModelState>(ModelState.NotDownloaded)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    /** 是否有可续传的 `.part` 残档 —— 决定按钮文案是「下载」还是「继续下载」。 */
    private val _hasPartial = MutableStateFlow(false)
    val hasPartial: StateFlow<Boolean> = _hasPartial.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var job: Job? = null

    init {
        refreshFromDisk()
    }

    // --- 状态 ---------------------------------------------------------------

    /**
     * 按磁盘现状推断状态：正式文件大小对上才算 Ready；大小不符的坏文件直接删掉，退化为「未下载」。
     * 下载进行中时不做任何事，避免把 Downloading 覆盖掉。
     */
    fun refreshFromDisk() {
        if (job?.isActive == true) return
        when {
            !modelFile.exists() -> publish(ModelState.NotDownloaded)
            modelFile.length() == MODEL_SIZE_BYTES -> publish(ModelState.Ready)
            else -> {
                modelFile.delete()
                publish(ModelState.NotDownloaded)
            }
        }
    }

    // --- 下载 ---------------------------------------------------------------

    /** 开始（或继续）下载；已经在下载中或已经就绪则忽略。失败/取消都保留 `.part` 供续传。 */
    fun download() {
        if (job?.isActive == true) return
        refreshFromDisk()
        // 已经就绪就别再拉一遍 2.4GB；UI 也不会显示下载按钮，这里是防御。
        if (_state.value is ModelState.Ready) return
        job = scope.launch {
            try {
                downloadInternal()
            } catch (e: CancellationException) {
                publish(ModelState.NotDownloaded)
                throw e
            } catch (e: Exception) {
                // 已被取消（例如删除模型时连带取消）就不再覆盖用户刚设置的状态。
                if (isActive) publish(ModelState.Error(describeFailure(e)))
            }
        }
    }

    /** 取消下载：保留 `.part`，下次「继续下载」从断点续传。 */
    fun cancelDownload() {
        job?.cancel()
    }

    /** 删除正式文件与 `.part` 残档，回到「未下载」。 */
    fun deleteModel() {
        job?.cancel()
        job = null
        modelFile.delete()
        partFile.delete()
        publish(ModelState.NotDownloaded)
    }

    private suspend fun downloadInternal() {
        modelsDir.mkdirs()

        var resumeFrom = if (partFile.exists()) partFile.length() else 0L
        if (resumeFrom > MODEL_SIZE_BYTES) {
            // 残档比目标还大，肯定是坏的，从头来过。
            partFile.delete()
            resumeFrom = 0L
        }
        if (resumeFrom == MODEL_SIZE_BYTES) {
            // 残档已经下载完整（上次校验前被打断），直接进校验。
            verifyAndPromote()
            return
        }

        publish(ModelState.Downloading(resumeFrom, MODEL_SIZE_BYTES))

        val connection = openConnection(resumeFrom)
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK && code != HTTP_PARTIAL_CONTENT) {
                val detail = connection.responseMessage?.trim().orEmpty()
                throw IOException("HTTP 错误 $code${if (detail.isEmpty()) "" else "：$detail"}")
            }
            // 只有服务器明确回 206 才能续写；回 200 说明它忽略了 Range，从 0 重来。
            val append = code == HTTP_PARTIAL_CONTENT && resumeFrom > 0
            var written = if (append) resumeFrom else 0L

            FileOutputStream(partFile, append).use { out ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var pendingBytes = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        pendingBytes += read
                        // 每 256KB 刷新一次进度，避免每个 8KB 块都惊动 UI。
                        if (pendingBytes >= PROGRESS_STEP_BYTES) {
                            pendingBytes = 0L
                            publish(ModelState.Downloading(written, MODEL_SIZE_BYTES))
                        }
                    }
                }
            }

            publish(ModelState.Downloading(written, MODEL_SIZE_BYTES))
            if (written != MODEL_SIZE_BYTES) {
                throw IOException("下载不完整：已写入 $written / $MODEL_SIZE_BYTES 字节")
            }
            verifyAndPromote()
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(resumeFrom: Long): HttpURLConnection {
        val url = try {
            URL(MODEL_URL)
        } catch (e: MalformedURLException) {
            throw IOException("下载地址无效：$MODEL_URL")
        }
        val connection = url.openConnection() as? HttpURLConnection
            ?: throw IOException("只支持 http/https 下载地址")
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.useCaches = false
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "ClipTranslate")
        if (resumeFrom > 0) {
            connection.setRequestProperty("Range", "bytes=$resumeFrom-")
        }
        return connection
    }

    // --- 校验 ---------------------------------------------------------------

    /**
     * 流式算 `.part` 的 SHA256；不匹配就删掉并置 Error("校验失败")，
     * 匹配则 rename 成正式文件名（rename 失败时退化为复制）并置 Ready。
     */
    private fun verifyAndPromote() {
        publish(ModelState.Verifying)
        val actual = sha256Of(partFile)
        if (!actual.equals(MODEL_SHA256, ignoreCase = true)) {
            partFile.delete()
            publish(ModelState.Error("校验失败"))
            return
        }
        if (modelFile.exists()) modelFile.delete()
        if (!partFile.renameTo(modelFile)) {
            partFile.copyTo(modelFile, overwrite = true)
            partFile.delete()
        }
        publish(ModelState.Ready)
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(HASH_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    // --- 内部工具 -----------------------------------------------------------

    private fun publish(next: ModelState) {
        _state.value = next
        _hasPartial.value = partFile.exists() && partFile.length() > 0
    }

    private fun describeFailure(e: Exception): String = when (e) {
        is UnknownHostException -> "下载失败：无法解析下载地址"
        is SocketTimeoutException -> "下载失败：网络超时，可点「继续下载」重试"
        is IOException -> "下载失败：${e.message ?: "网络错误"}"
        else -> "下载失败：${e.message ?: e.javaClass.simpleName}"
    }

    companion object {
        const val MODEL_URL =
            "https://www.modelscope.cn/models/42ailab/TranslateGemma-4B-GGUF/resolve/master/translategemma-4b-it-Q4_K_M.gguf"
        const val MODEL_SHA256 = "7f7357c14abd9da4eb200b38b05da502cd6e10d7e1d403fbc9f78c19f3209b72"
        const val MODEL_SIZE_BYTES = 2489909312L

        private const val DIR_MODELS = "models"
        private const val FILE_NAME = "translategemma-4b-it-Q4_K_M.gguf"
        private const val HTTP_PARTIAL_CONTENT = 206
        private const val BUFFER_SIZE = 8 * 1024
        private const val PROGRESS_STEP_BYTES = 256L * 1024
        private const val HASH_BUFFER_SIZE = 256 * 1024
        private const val CONNECT_TIMEOUT_MS = 15000
        private const val READ_TIMEOUT_MS = 30000

        @Volatile private var instance: ModelManager? = null

        /** 进程内单例：设置页的下载进度和翻译页的模型状态必须看到同一份。 */
        fun get(context: Context): ModelManager = instance ?: synchronized(this) {
            instance ?: ModelManager(context.applicationContext).also { instance = it }
        }
    }
}
