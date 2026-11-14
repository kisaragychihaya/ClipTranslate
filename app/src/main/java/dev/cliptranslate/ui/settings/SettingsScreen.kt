package dev.cliptranslate.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cliptranslate.data.Provider

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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

    var pendingDelete by remember { mutableStateOf<Provider?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.startCreate() }) {
                Text(text = "+", style = MaterialTheme.typography.headlineSmall)
            }
        },
    ) { innerPadding ->
        if (providers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "还没有配置 Provider",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "点击右下角 + 添加一个 OpenAI 兼容服务",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(providers, key = { it.id }) { provider ->
                    ProviderCard(
                        provider = provider,
                        onClick = { vm.startEdit(provider) },
                        onRequestDelete = { pendingDelete = provider },
                    )
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除 Provider") },
            text = { Text("确定要删除「${target.name.ifBlank { "未命名 Provider" }}」吗？删除后需要重新配置。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.delete(target.id)
                        pendingDelete = null
                    },
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    val editor = editing
    val fetched = fetchResult
    if (editor != null && fetched != null) {
        // 模型选择弹窗盖在编辑弹窗之上：确认后两层一起关闭，取消则回到编辑弹窗（草稿保留）。
        val existingIds = editor.models.map { it.id }.toSet()
        ModelPickerSheet(
            models = fetched,
            initiallySelected = fetched.map { it.id }.filter { it !in existingIds }.toSet(),
            onApply = { selectedIds -> vm.applyFetchedModels(vm.editing.value ?: editor, selectedIds) },
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProviderCard(
    provider: Provider,
    onClick: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onRequestDelete),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = provider.name.ifBlank { "未命名 Provider" },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRequestDelete) { Text("删除") }
            }
            Text(
                text = provider.baseUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = providerSummary(provider),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
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
