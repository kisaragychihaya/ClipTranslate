package dev.cliptranslate.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.cliptranslate.data.Provider
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Provider 编辑底部弹窗：name / baseUrl / apiKey。
 * 字段值本地持有，「拉取模型」前会把草稿通过 [onDraftChange] 同步给上层，
 * 这样打开模型选择弹窗再返回时输入不会丢。
 */
@Composable
fun ProviderEditorSheet(
    provider: Provider,
    isFetching: Boolean,
    fetchError: String?,
    onFetch: () -> Unit,
    onSave: (Provider) -> Unit,
    onDismiss: () -> Unit,
    onDraftChange: (Provider) -> Unit = {},
) {
    var name by remember(provider.id) { mutableStateOf(provider.name) }
    var baseUrl by remember(provider.id) { mutableStateOf(provider.baseUrl) }
    var apiKey by remember(provider.id) { mutableStateOf(provider.apiKey) }
    var showKey by remember(provider.id) { mutableStateOf(false) }

    val trimmedName = name.trim()
    val trimmedUrl = baseUrl.trim()
    val trimmedKey = apiKey.trim()
    val urlLooksValid = trimmedUrl.contains("://")
    val canSave = trimmedName.isNotEmpty() && urlLooksValid
    val draft = provider.copy(name = trimmedName, baseUrl = trimmedUrl, apiKey = trimmedKey)

    OverlayBottomSheet(
        show = true,
        title = "Provider 配置",
        onDismissRequest = onDismiss,
        insideMargin = DpSize(0.dp, 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextField(
                value = name,
                onValueChange = {
                    name = it
                    onDraftChange(
                        provider.copy(
                            name = it.trim(),
                            baseUrl = trimmedUrl,
                            apiKey = trimmedKey,
                        )
                    )
                },
                label = "名称",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = if (name.isNotEmpty() && trimmedName.isEmpty()) {
                    "名称不能为空"
                } else {
                    "显示在 Provider 列表里的名字"
                },
                style = MiuixTheme.textStyles.footnote2,
                color = if (name.isNotEmpty() && trimmedName.isEmpty()) {
                    MiuixTheme.colorScheme.error
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            )

            TextField(
                value = baseUrl,
                onValueChange = {
                    baseUrl = it
                    onDraftChange(
                        provider.copy(
                            name = trimmedName,
                            baseUrl = it.trim(),
                            apiKey = trimmedKey,
                        )
                    )
                },
                label = "Base URL",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = if (baseUrl.isNotEmpty() && !urlLooksValid) {
                    "Base URL 需要包含 \"://\""
                } else {
                    "形如 https://api.openai.com/v1，缺少 /v1 时由客户端自动补"
                },
                style = MiuixTheme.textStyles.footnote2,
                color = if (baseUrl.isNotEmpty() && !urlLooksValid) {
                    MiuixTheme.colorScheme.error
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            )

            TextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    onDraftChange(
                        provider.copy(
                            name = trimmedName,
                            baseUrl = trimmedUrl,
                            apiKey = it.trim(),
                        )
                    )
                },
                label = "API Key",
                singleLine = true,
                visualTransformation = if (showKey) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    TextButton(
                        text = if (showKey) "隐藏" else "显示",
                        onClick = { showKey = !showKey },
                        textStyle = MiuixTheme.textStyles.footnote1,
                        insideMargin = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "仅作为 Authorization: Bearer 请求头，保存在本机",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )

            Button(
                onClick = {
                    onDraftChange(draft)
                    onFetch()
                },
                enabled = !isFetching && urlLooksValid,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(),
            ) {
                if (isFetching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("拉取中…")
                } else {
                    Text("拉取模型")
                }
            }

            val error = fetchError
            if (error != null) {
                Text(
                    text = error,
                    color = MiuixTheme.colorScheme.error,
                    style = MiuixTheme.textStyles.footnote2,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { onSave(draft) },
                    enabled = canSave,
                    modifier = Modifier.weight(2f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text("保存")
                }
            }
        }
    }
}
