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
 * 本地 GGUF 模型的下载 / 校验 / 删除，按模型 id（[LocalModelCatalog] 的 spec）各持一套状态。
 *
 * 存放位置：`filesDir/models/`，即应用私有内部存储 —— 卸载应用时由系统自动删除，
 * 不需要任何存储权限，也不会污染用户的相册/下载目录。
 * 下载先写同目录的 `.part` 临时文件，SHA256 校验通过后才 rename 成正式文件名；
 * 取消或失败都保留 `.part`，下次点「继续下载」用 Range 从断点续传（服务器实测支持 206）。
 *
 * 一个模型可能有两份文件：主文件（[LocalModelSpec.fileName]）与可选的视觉投影附件
 * （[LocalModelSpec.mmproj]）。两者共用同一套 [FileSlot] 逻辑 —— 各自一个 `.part`、各自的
 * 状态流与下载 job，互不影响；删模型时连带删掉附件，避免留下几百 MB 的孤儿文件。
 *
 * 允许多个模型同时下载：每个 [FileSlot] 有自己的 job 与 `.part`，互不影响。
 * 全进程只有一个实例（[get]），设置页与翻译页共享同一份进度 StateFlow。
 */
class ModelManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val modelsDir = File(appContext.filesDir, DIR_MODELS)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 每个目录模型一份会话。在构造时全部建好而不是首次访问才建：
     * [statesMap] 从进程启动的第一帧起就是完整的，翻译页不会把「还没人问过」误判成「未下载」。
     */
    private val sessions = mutableMapOf<String, Session>()

    /** 聚合状态。每次任意模型 publish 时整体换成一个新的不可变 Map 副本。 */
    private val _states = MutableStateFlow<Map<String, ModelState>>(emptyMap())
    val statesMap: StateFlow<Map<String, ModelState>> = _states.asStateFlow()

    init {
        LocalModelCatalog.models.forEach { spec -> sessionOf(spec.id) }
        refreshFromDisk()
    }

    // --- 公开 API（modelId 一律按目录归一化） --------------------------------

    /** 指定模型的下载/校验状态。 */
    fun stateOf(modelId: String): StateFlow<ModelState> = sessionOf(modelId).main.state

    /** 指定模型是否有可续传的 `.part` 残档 —— 决定按钮文案是「下载」还是「继续下载」。 */
    fun hasPartialOf(modelId: String): StateFlow<Boolean> = sessionOf(modelId).main.hasPartial

    /** 正式模型文件；大小与对应 spec 的 [LocalModelSpec.sizeBytes] 一致且校验通过后才会存在。 */
    fun modelFile(modelId: String): File = sessionOf(modelId).main.file

    /**
     * 视觉投影附件的状态；模型没有附件（[LocalModelSpec.mmproj] 为 null）时返回 null ——
     * 调用点据此判断「这个模型有没有图片翻译这项能力」，比让 UI 自己查目录更直接。
     */
    fun attachmentStateOf(modelId: String): StateFlow<ModelState>? =
        sessionOf(modelId).attachment?.state

    /** 视觉投影附件文件；没有附件的模型返回 null。未下载时文件不存在。 */
    fun attachmentFile(modelId: String): File? = sessionOf(modelId).attachment?.file

    /** 附件是否有可续传的 `.part` 残档；没有附件的模型返回 null。 */
    fun hasAttachmentPartialOf(modelId: String): StateFlow<Boolean>? =
        sessionOf(modelId).attachment?.hasPartial

    /**
     * 按磁盘现状推断所有模型的状态：正式文件大小对上才算 Ready；大小不符的坏文件直接删掉，
     * 退化为「未下载」。正在下载的模型不动，避免把 Downloading 覆盖掉。附件一并覆盖。
     */
    fun refreshFromDisk() {
        LocalModelCatalog.models.forEach { sessionOf(it.id).refreshFromDisk() }
    }

    /** 开始（或继续）下载；该模型已经在下载中或已经就绪则忽略。失败/取消都保留 `.part` 供续传。 */
    fun download(modelId: String) {
        sessionOf(modelId).main.download()
    }

    /** 取消下载：保留 `.part`，下次「继续下载」从断点续传。 */
    fun cancelDownload(modelId: String) {
        sessionOf(modelId).main.cancelDownload()
    }

    /** 删除正式文件与 `.part` 残档（连带视觉投影附件），回到「未下载」。 */
    fun deleteModel(modelId: String) {
        sessionOf(modelId).deleteModel()
    }

    /** 开始（或继续）下载视觉投影附件；没有附件的模型与下载中的状态都直接忽略。 */
    fun downloadAttachment(modelId: String) {
        sessionOf(modelId).attachment?.download()
    }

    /** 取消附件下载：保留 `.part` 供续传。 */
    fun cancelAttachmentDownload(modelId: String) {
        sessionOf(modelId).attachment?.cancelDownload()
    }

    /** 只删除视觉投影附件（主文件保留）；没有附件的模型忽略。 */
    fun deleteAttachment(modelId: String) {
        sessionOf(modelId).attachment?.delete()
    }

    // --- 会话 ---------------------------------------------------------------

    @Synchronized
    private fun sessionOf(modelId: String): Session {
        val spec = LocalModelCatalog.byId(modelId)
        return sessions.getOrPut(spec.id) { Session(spec) }
    }

    /**
     * 单个模型的两份文件槽（主文件 + 可选附件）。
     *
     * 主文件的状态既写进自己的 StateFlow，也同步刷新管理器的聚合副本（[publishState]）；
     * 附件不进聚合表 —— [statesMap] 的语义一直是「这个模型的模型文件好没好」，
     * 翻译页只依赖它判断能否翻译、能否加载。
     */
    private inner class Session(val spec: LocalModelSpec) {

        val main = FileSlot(
            fileName = spec.fileName,
            downloadUrl = spec.downloadUrl,
            sha256 = spec.sha256,
            sizeBytes = spec.sizeBytes,
            onPublished = { publishState(spec.id, it) },
        )

        /** 附件槽；模型没有视觉投影时为 null。 */
        val attachment: FileSlot? = spec.mmproj?.let { attachment ->
            FileSlot(
                fileName = attachment.fileName,
                downloadUrl = attachment.downloadUrl,
                sha256 = attachment.sha256,
                sizeBytes = attachment.sizeBytes,
                onPublished = { },
            )
        }

        init {
            // 会话一建出来就进聚合表，避免 statesMap 缺 key 被上层当成「未下载」。
            publishState(spec.id, main.state.value)
        }

        fun refreshFromDisk() {
            main.refreshFromDisk()
            attachment?.refreshFromDisk()
        }

        fun deleteModel() {
            main.delete()
            attachment?.delete()
        }
    }

    /**
     * 一个受管的磁盘文件：正式文件 + `.part` 残档 + 状态流 + 下载 job。
     *
     * 主模型文件与视觉投影附件只是文件名/SHA256/大小不同，行为必须逐字一致，
     * 所以两边共用这一个类；[onPublished] 让主文件把状态同步进聚合表，附件传空实现。
     * 所有状态都通过 [publish] 写，保证 StateFlow 与聚合表永远看到同一个值。
     */
    private inner class FileSlot(
        private val fileName: String,
        private val downloadUrl: String,
        private val sha256: String,
        private val sizeBytes: Long,
        private val onPublished: (ModelState) -> Unit,
    ) {

        val file = File(modelsDir, fileName)
        private val partFile = File(modelsDir, "$fileName.part")

        private val _state = MutableStateFlow<ModelState>(ModelState.NotDownloaded)
        val state: StateFlow<ModelState> = _state.asStateFlow()

        private val _hasPartial = MutableStateFlow(false)
        val hasPartial: StateFlow<Boolean> = _hasPartial.asStateFlow()

        @Volatile
        private var job: Job? = null

        fun refreshFromDisk() {
            if (job?.isActive == true) return
            when {
                !file.exists() -> publish(ModelState.NotDownloaded)
                file.length() == sizeBytes -> publish(ModelState.Ready)
                else -> {
                    file.delete()
                    publish(ModelState.NotDownloaded)
                }
            }
        }

        fun download() {
            if (job?.isActive == true) return
            refreshFromDisk()
            // 已经就绪就别再拉一遍；UI 也不会显示下载按钮，这里是防御。
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

        fun cancelDownload() {
            job?.cancel()
        }

        fun delete() {
            job?.cancel()
            job = null
            file.delete()
            partFile.delete()
            publish(ModelState.NotDownloaded)
        }

        private suspend fun downloadInternal() {
            modelsDir.mkdirs()

            var resumeFrom = if (partFile.exists()) partFile.length() else 0L
            if (resumeFrom > sizeBytes) {
                // 残档比目标还大，肯定是坏的，从头来过。
                partFile.delete()
                resumeFrom = 0L
            }
            if (resumeFrom == sizeBytes) {
                // 残档已经下载完整（上次校验前被打断），直接进校验。
                verifyAndPromote()
                return
            }

            publish(ModelState.Downloading(resumeFrom, sizeBytes))

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
                                publish(ModelState.Downloading(written, sizeBytes))
                            }
                        }
                    }
                }

                publish(ModelState.Downloading(written, sizeBytes))
                if (written != sizeBytes) {
                    throw IOException("下载不完整：已写入 $written / $sizeBytes 字节")
                }
                verifyAndPromote()
            } finally {
                connection.disconnect()
            }
        }

        private fun openConnection(resumeFrom: Long): HttpURLConnection {
            val url = try {
                URL(downloadUrl)
            } catch (e: MalformedURLException) {
                throw IOException("下载地址无效：$downloadUrl")
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

        /**
         * 流式算 `.part` 的 SHA256；不匹配就删掉并置 Error("校验失败")，
         * 匹配则 rename 成正式文件名（rename 失败时退化为复制）并置 Ready。
         */
        private fun verifyAndPromote() {
            publish(ModelState.Verifying)
            val actual = sha256Of(partFile)
            if (!actual.equals(sha256, ignoreCase = true)) {
                partFile.delete()
                publish(ModelState.Error("校验失败"))
                return
            }
            if (file.exists()) file.delete()
            if (!partFile.renameTo(file)) {
                partFile.copyTo(file, overwrite = true)
                partFile.delete()
            }
            publish(ModelState.Ready)
        }

        fun publish(next: ModelState) {
            _state.value = next
            _hasPartial.value = partFile.exists() && partFile.length() > 0
            onPublished(next)
        }
    }

    /** 把某个模型的状态并进聚合表；总是换新 Map，收集方不会漏掉这次变化。 */
    private fun publishState(modelId: String, state: ModelState) {
        _states.value = _states.value.toMutableMap().also { it[modelId] = state }
    }

    // --- 内部工具 -----------------------------------------------------------

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

    private fun describeFailure(e: Exception): String = when (e) {
        is UnknownHostException -> "下载失败：无法解析下载地址"
        is SocketTimeoutException -> "下载失败：网络超时，可点「继续下载」重试"
        is IOException -> "下载失败：${e.message ?: "网络错误"}"
        else -> "下载失败：${e.message ?: e.javaClass.simpleName}"
    }

    companion object {
        private const val DIR_MODELS = "models"
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
