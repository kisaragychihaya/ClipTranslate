package dev.cliptranslate.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cliptranslate.R
import dev.cliptranslate.data.Provider
import dev.cliptranslate.local.GpuBackend
import dev.cliptranslate.local.ModelManager
import dev.cliptranslate.local.ModelState
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.menu.OverlayDropdownMenu
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = viewModel(),
) {
    val providers by vm.providers.collectAsStateWithLifecycle()
    val editing by vm.editing.collectAsStateWithLifecycle()
    val isFetching by vm.isFetching.collectAsStateWithLifecycle()
    val fetchError by vm.fetchError.collectAsStateWithLifecycle()
    val fetchResult by vm.fetchResult.collectAsStateWithLifecycle()
    val modelState by vm.modelState.collectAsStateWithLifecycle()
    val hasPartialModel by vm.hasPartialModel.collectAsStateWithLifecycle()
    val localEnabled by vm.localEnabled.collectAsStateWithLifecycle()
    val gpuBackend by vm.gpuBackend.collectAsStateWithLifecycle()

    var pendingDelete by remember { mutableStateOf<Provider?>(null) }
    var pendingModelDelete by remember { mutableStateOf(false) }

    // 回到设置页时对一遍磁盘：下载中途进程被杀也能恢复到「未下载」而不是卡在「下载中」。
    LaunchedEffect(Unit) {
        vm.refreshModelState()
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "设置",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.startCreate() }) {
                Icon(
                    imageVector = MiuixIcons.Add,
                    contentDescription = "+",
                    tint = MiuixTheme.colorScheme.onPrimary,
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "local-model") {
                LocalModelCard(
                    state = modelState,
                    hasPartial = hasPartialModel,
                    enabled = localEnabled,
                    gpuBackend = gpuBackend,
                    onDownload = { vm.downloadModel() },
                    onCancel = { vm.cancelModelDownload() },
                    onDelete = { pendingModelDelete = true },
                    onToggleEnabled = { vm.setLocalEnabled(it) },
                    onSelectGpuBackend = { vm.setGpuBackend(it) },
                )
            }

            if (providers.isEmpty()) {
                item(key = "no-provider") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "还没有配置 Provider",
                                style = MiuixTheme.textStyles.title4,
                            )
                            Text(
                                text = "点击右下角 + 添加一个 OpenAI 兼容服务",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            } else {
                items(providers, key = { it.id }) { provider ->
                    ProviderCard(
                        provider = provider,
                        onClick = { vm.startEdit(provider) },
                        onRequestDelete = { pendingDelete = provider },
                    )
                }
            }
        }

        // Overlay 系列弹层/对话框都要放在 Scaffold 内部，才能被 Scaffold 的 popupHost 托管。
        pendingDelete?.let { target ->
            OverlayDialog(
                show = true,
                title = "删除 Provider",
                summary = "确定要删除「${target.name.ifBlank { "未命名 Provider" }}」吗？删除后需要重新配置。",
                onDismissRequest = { pendingDelete = null },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        text = "取消",
                        onClick = { pendingDelete = null },
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            vm.delete(target.id)
                            pendingDelete = null
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text("删除")
                    }
                }
            }
        }

        if (pendingModelDelete) {
            OverlayDialog(
                show = true,
                title = stringResource(R.string.local_model_delete_title),
                summary = stringResource(
                    R.string.local_model_delete_message,
                    formatBytes(ModelManager.MODEL_SIZE_BYTES),
                ),
                onDismissRequest = { pendingModelDelete = false },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        text = stringResource(R.string.local_model_cancel),
                        onClick = { pendingModelDelete = false },
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            vm.deleteModel()
                            pendingModelDelete = false
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text(stringResource(R.string.local_model_delete_confirm))
                    }
                }
            }
        }

        val editor = editing
        val fetched = fetchResult
        if (editor != null && fetched != null) {
            // 模型选择弹窗盖在编辑弹窗之上：确认后两层一起关闭，取消则回到编辑弹窗（草稿保留）。
            val existingIds = editor.models.map { it.id }.toSet()
            ModelPickerSheet(
                models = fetched,
                initiallySelected = fetched.map { it.id }.filter { it !in existingIds }.toSet(),
                onApply = { selectedIds ->
                    vm.applyFetchedModels(vm.editing.value ?: editor, selectedIds)
                },
                onDismiss = { vm.dismissModelPicker() },
            )
        } else if (editor != null) {
            ProviderEditorSheet(
                provider = editor,
                isFetching = isFetching,
                fetchError = fetchError,
                onFetch = { vm.fetchModels(vm.editing.value ?: editor) },
                onSave = { provider -> vm.save(provider) },
                onDismiss = { vm.dismissEditor() },
                onDraftChange = { provider -> vm.updateEditing(provider) },
            )
        }
    }
}

/**
 * 「本地模型（离线翻译）」卡片：状态行 + 下载/取消/删除按钮 + 启用开关 + 加速方式。
 * 放在 Provider 列表上方，因为它是内置的、不需要任何配置。
 */
@Composable
private fun LocalModelCard(
    state: ModelState,
    hasPartial: Boolean,
    enabled: Boolean,
    gpuBackend: GpuBackend,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onSelectGpuBackend: (GpuBackend) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.local_model_title),
                style = MiuixTheme.textStyles.title4,
            )
            Text(
                text = stringResource(R.string.local_model_desc),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )

            HorizontalDivider()

            LocalModelStatus(state = state)

            // 校验中不提供按钮：算 SHA256 打断不了，给了按钮也是摆设。
            when (state) {
                is ModelState.Downloading -> {
                    Button(
                        onClick = onCancel,
                        colors = ButtonDefaults.buttonColors(),
                    ) {
                        Text(stringResource(R.string.local_model_cancel))
                    }
                }

                is ModelState.Ready -> {
                    Button(
                        onClick = onDelete,
                        colors = ButtonDefaults.buttonColors(),
                    ) {
                        Text(stringResource(R.string.local_model_delete))
                    }
                }

                is ModelState.Verifying -> Unit

                else -> {
                    Button(
                        onClick = onDownload,
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text(
                            stringResource(
                                if (hasPartial) {
                                    R.string.local_model_resume
                                } else {
                                    R.string.local_model_download
                                },
                            ),
                        )
                    }
                }
            }

            SwitchPreference(
                title = stringResource(R.string.local_model_enable),
                summary = stringResource(R.string.local_model_enable_desc),
                checked = enabled,
                onCheckedChange = onToggleEnabled,
                insideMargin = PaddingValues(0.dp),
            )

            HorizontalDivider()

            OverlayDropdownMenu(
                title = stringResource(R.string.local_model_gpu_backend),
                summary = gpuBackendLabel(gpuBackend),
                entry = DropdownEntry(
                    items = GpuBackend.entries.map { backend ->
                        DropdownItem(
                            text = gpuBackendLabel(backend),
                            selected = backend == gpuBackend,
                            onClick = { onSelectGpuBackend(backend) },
                        )
                    },
                ),
                insideMargin = PaddingValues(horizontal = 0.dp, vertical = 6.dp),
            )
            Text(
                text = stringResource(R.string.local_model_gpu_hint),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/** [GpuBackend] → 中文选项文案。 */
@Composable
private fun gpuBackendLabel(backend: GpuBackend): String = stringResource(
    when (backend) {
        GpuBackend.AUTO -> R.string.local_model_gpu_auto
        GpuBackend.CPU -> R.string.local_model_gpu_cpu
        GpuBackend.OPENCL -> R.string.local_model_gpu_opencl
        GpuBackend.NPU -> R.string.local_model_gpu_npu
    },
)

/** 状态行：未下载 / 下载中（进度条 + 百分比 + MB）/ 校验中 / 已就绪 · 2.32 GB / 错误信息。 */
@Composable
private fun LocalModelStatus(state: ModelState) {
    when (state) {
        is ModelState.NotDownloaded -> {
            Text(
                text = stringResource(R.string.local_model_status_not_downloaded),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }

        is ModelState.Downloading -> {
            val total = if (state.totalBytes > 0) state.totalBytes else ModelManager.MODEL_SIZE_BYTES
            val percent = ((state.downloadedBytes * 100) / total).toInt().coerceIn(0, 100)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.local_model_status_downloading),
                        style = MiuixTheme.textStyles.body2,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(
                            R.string.local_model_progress,
                            percent,
                            state.downloadedBytes / MEBIBYTE,
                            total / MEBIBYTE,
                        ),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
                LinearProgressIndicator(
                    progress = percent / 100f,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        is ModelState.Verifying -> {
            Text(
                text = stringResource(R.string.local_model_status_verifying),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }

        is ModelState.Ready -> {
            Text(
                text = stringResource(
                    R.string.local_model_status_ready,
                    formatBytes(ModelManager.MODEL_SIZE_BYTES),
                ),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.primary,
            )
        }

        is ModelState.Error -> {
            Text(
                text = state.message,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ProviderCard(
    provider: Provider,
    onClick: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(16.dp),
        pressFeedbackType = PressFeedbackType.Sink,
        onClick = onClick,
        onLongPress = onRequestDelete,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = provider.name.ifBlank { "未命名 Provider" },
                style = MiuixTheme.textStyles.title4,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = "删除",
                onClick = onRequestDelete,
                insideMargin = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        Text(
            text = provider.baseUrl,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = providerSummary(provider),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.primary,
        )
    }
}

/** 例："3 个模型 · 默认 cn:hy4free"；没有默认模型时只显示模型数。 */
private fun providerSummary(provider: Provider): String {
    val parts = mutableListOf("${provider.models.size} 个模型")
    val defaultModel = provider.defaultModelId
    if (!defaultModel.isNullOrBlank()) {
        parts.add("默认 $defaultModel")
    }
    return parts.joinToString(" · ")
}

private const val MEBIBYTE = 1024L * 1024L

/** 2 489 909 312 → "2.32 GB"；不足 1GB 时按 MB 显示，避免出现 "0.03 GB"。 */
private fun formatBytes(bytes: Long): String {
    val gigabytes = bytes / 1024.0 / 1024.0 / 1024.0
    return if (gigabytes >= 1.0) {
        String.format(Locale.US, "%.2f GB", gigabytes)
    } else {
        String.format(Locale.US, "%d MB", bytes / MEBIBYTE)
    }
}
