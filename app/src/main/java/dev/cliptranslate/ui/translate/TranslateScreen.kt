package dev.cliptranslate.ui.translate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cliptranslate.R
import dev.cliptranslate.core.Languages
import dev.cliptranslate.data.LOCAL_PROVIDER_ID
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.extra.SuperDropdown
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val QUICK_LANGUAGE_COUNT = 8

@Composable
fun TranslateScreen(
    onOpenSettings: () -> Unit,
    vm: TranslateViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showAllLanguages by rememberSaveable { mutableStateOf(false) }

    // 首次进入页面：拉一次服务商配置，并自动使用剪贴板内容。
    LaunchedEffect(Unit) {
        vm.refreshProviders()
        vm.refreshClipboard(context)
    }

    // 每次回到前台（切回本应用）都重新读剪贴板；剪贴板有文本且与当前输入不同则自动填入。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vm.refreshProviders()
                vm.refreshClipboard(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = stringResource(R.string.app_name),
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(imageVector = MiuixIcons.Settings, contentDescription = "设置")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LanguageSection(
                targetCode = state.targetCode,
                onSelect = vm::selectTarget,
                onMore = { showAllLanguages = true },
            )

            ProviderSection(
                state = state,
                onSelectProvider = vm::selectProvider,
                onSelectModel = vm::selectModel,
                onOpenSettings = onOpenSettings,
            )

            TextField(
                value = state.input,
                onValueChange = vm::onInputChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
                minLines = 4,
                label = "在此输入要翻译的文本；复制后切回本应用会自动填入",
                useLabelAsPlaceholder = true,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(text = "粘贴", onClick = { vm.pasteFromClipboard(context) })
                TextButton(text = "清空", onClick = vm::clearInput)
            }

            Button(
                onClick = vm::translate,
                enabled = !state.isLoading && state.input.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                if (state.isLoading) {
                    // Miuix 的指示器用 size 参数自定尺寸（内部按它布局圆弧），
                    // 用 Modifier.size 强改会把圆弧画偏（「翻译中」圆圈错位）。
                    CircularProgressIndicator(
                        size = 18.dp,
                        strokeWidth = 2.dp,
                        colors = ProgressIndicatorDefaults.progressIndicatorColors(
                            foregroundColor = MiuixTheme.colorScheme.onPrimary,
                            backgroundColor = MiuixTheme.colorScheme.onPrimary,
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (state.isLoading) "翻译中…" else "翻译")
            }

            state.error?.let { message ->
                Text(
                    text = message,
                    color = MiuixTheme.colorScheme.error,
                    style = MiuixTheme.textStyles.body1,
                )
            }

            if (state.output.isNotBlank()) {
                ResultSection(
                    output = state.output,
                    onCopy = { copyToClipboard(context, state.output) },
                )
            }
        }

        // Super 系列弹层要放在 Scaffold 内部：popup 由 Scaffold 的 popupHost 托管。
        LanguageDialog(
            show = showAllLanguages,
            current = state.targetCode,
            onSelect = { code ->
                vm.selectTarget(code)
                showAllLanguages = false
            },
            onDismiss = { showAllLanguages = false },
        )
    }
}

@Composable
private fun LanguageSection(
    targetCode: String,
    onSelect: (String) -> Unit,
    onMore: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallTitle(text = "目标语言", insideMargin = PaddingValues(0.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Languages.SUPPORTED.take(QUICK_LANGUAGE_COUNT).forEach { (code, name) ->
                Button(
                    onClick = { onSelect(code) },
                    colors = if (code == targetCode) {
                        ButtonDefaults.buttonColorsPrimary()
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                    minWidth = 0.dp,
                    insideMargin = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(name)
                }
            }
            Button(
                onClick = onMore,
                colors = ButtonDefaults.buttonColors(),
                minWidth = 0.dp,
                insideMargin = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text("更多")
            }
        }
    }
}

@Composable
private fun ProviderSection(
    state: TranslateUiState,
    onSelectProvider: (String) -> Unit,
    onSelectModel: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val provider = state.currentProvider
    if (provider == null) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.errorContainer),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "未配置服务商，去设置添加",
                    style = MiuixTheme.textStyles.title4,
                    color = MiuixTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = "需要先添加一个 OpenAI 兼容服务商（baseUrl + apiKey）才能翻译。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onErrorContainer,
                )
                Button(
                    onClick = onOpenSettings,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text("去设置添加")
                }
            }
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.providers.size > 1) {
            SuperDropdown(
                items = state.providers.map { providerLabel(it.name, it.baseUrl) },
                selectedIndex = state.providers
                    .indexOfFirst { it.id == provider.id }
                    .coerceAtLeast(0),
                title = "服务商",
                summary = providerLabel(provider.name, provider.baseUrl),
                insideMargin = PaddingValues(horizontal = 0.dp, vertical = 6.dp),
                showValue = false,
                onSelectedIndexChange = { index ->
                    state.providers.getOrNull(index)?.id?.let(onSelectProvider)
                },
            )
        }
        SuperDropdown(
            items = provider.models.map { it.id },
            selectedIndex = provider.models.indexOfFirst { it.id == state.effectiveModelId }
                .coerceAtLeast(0),
            title = "模型",
            summary = state.effectiveModelId ?: "未选择模型",
            enabled = provider.models.isNotEmpty(),
            insideMargin = PaddingValues(horizontal = 0.dp, vertical = 6.dp),
            showValue = false,
            onSelectedIndexChange = { index ->
                provider.models.getOrNull(index)?.id?.let(onSelectModel)
            },
        )
        if (provider.models.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "该服务商还没有模型",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(text = "去设置拉取", onClick = onOpenSettings)
            }
        }
        if (provider.id == LOCAL_PROVIDER_ID && !state.localModelReady) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "本地模型还没下载，去设置里下载后即可离线翻译",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(text = "去下载", onClick = onOpenSettings)
            }
        }
    }
}

@Composable
private fun ResultSection(output: String, onCopy: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "译文",
                style = MiuixTheme.textStyles.title4,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onCopy) {
                Icon(imageVector = MiuixIcons.Copy, contentDescription = "复制译文")
            }
        }
        HorizontalDivider()
        SelectionContainer {
            Text(text = output, style = MiuixTheme.textStyles.paragraph)
        }
    }
}

@Composable
private fun LanguageDialog(
    show: Boolean,
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    SuperDialog(
        show = show,
        title = "选择目标语言",
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 400.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Languages.SUPPORTED.forEach { (code, name) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(code) }
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = name,
                        style = MiuixTheme.textStyles.body1,
                        color = if (code == current) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = code,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
        TextButton(
            text = "关闭",
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun providerLabel(name: String, baseUrl: String): String =
    name.ifBlank { baseUrl.ifBlank { "未命名服务商" } }

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("译文", text))
    Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
}
