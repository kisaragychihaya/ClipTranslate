package dev.cliptranslate.ui.about

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.cliptranslate.BuildConfig
import dev.cliptranslate.R
import dev.cliptranslate.local.DevSettings
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 长按 logo 解锁开发者模式所需的按住时长。 */
private const val DEV_MODE_HOLD_MS = 3_000L

private val LOGO_SIZE = 88.dp

/** 开源组件条目：名称 + 许可证名 + 源项目地址（点击用系统浏览器打开）。 */
private class OpenSourceComponent(
    val name: String,
    val license: String,
    val url: String,
)

/**
 * 组件清单的许可证名与链接都在 2026-11 逐一核对过：
 * llama.cpp 仓库根 LICENSE 是 MIT；miuix、AndroidX、Kotlin、kotlinx.coroutines 均为 Apache-2.0；
 * TranslateGemma-4B 的 ModelScope 模型页 License 字段为 `gemma`，模型卡徽标指向
 * Google 的 Gemma 使用条款（以模型页说明为准）。
 */
private val OPEN_SOURCE_COMPONENTS = listOf(
    OpenSourceComponent(
        name = "模译（ClipTranslate）",
        license = "Apache-2.0",
        url = "https://github.com/kisaragychihaya/ClipTranslate",
    ),
    OpenSourceComponent("llama.cpp", "MIT", "https://github.com/ggml-org/llama.cpp"),
    OpenSourceComponent("Miuix", "Apache-2.0", "https://github.com/compose-miuix-ui/miuix"),
    OpenSourceComponent("Jetpack Compose / AndroidX", "Apache-2.0", "https://developer.android.com/jetpack"),
    OpenSourceComponent("Kotlin", "Apache-2.0", "https://kotlinlang.org"),
    OpenSourceComponent("kotlinx-coroutines", "Apache-2.0", "https://github.com/Kotlin/kotlinx.coroutines"),
    OpenSourceComponent(
        name = "TranslateGemma-4B（GGUF）",
        license = "Gemma Terms of Use",
        url = "https://www.modelscope.cn/models/42ailab/TranslateGemma-4B-GGUF",
    ),
)

/**
 * 关于页：应用图标 + 名称 + 版本号 + 简介 + 开源组件清单。
 *
 * 隐藏入口：长按应用图标满 3 秒解锁开发者模式（中途抬手不到 3 秒则什么也不做），
 * 解锁后设置页才会出现「开发者选项」。用 pointerInput 手写计时而不是 CombinedClickable：
 * 后者只有长按阈值（几百毫秒），没法要求「按住满 3 秒」。
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val appName = stringResource(R.string.app_name)
    val density = LocalDensity.current
    val logo = remember(context, density) {
        loadAppIcon(context, with(density) { LOGO_SIZE.roundToPx() })
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "关于",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
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
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Image(
                    bitmap = logo,
                    contentDescription = appName,
                    modifier = Modifier
                        .size(LOGO_SIZE)
                        .clip(RoundedCornerShape(22.dp))
                        .pointerInput(Unit) { detectDevModeHold(context) },
                )
                Text(text = appName, style = MiuixTheme.textStyles.title2)
                Text(
                    text = "v${BuildConfig.VERSION_NAME}",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    text = "剪贴板或输入框里的文本一键翻译。支持 OpenAI 兼容在线接口，" +
                        "也能用本地离线的 TranslateGemma 模型（llama.cpp 推理，可选高通 NPU / GPU 加速）。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                )
            }

            HorizontalDivider()

            Text(text = "开源组件", style = MiuixTheme.textStyles.title4)

            Card(
                modifier = Modifier.fillMaxWidth(),
                insideMargin = PaddingValues(0.dp),
            ) {
                OPEN_SOURCE_COMPONENTS.forEachIndexed { index, component ->
                    if (index > 0) {
                        HorizontalDivider()
                    }
                    SuperArrow(
                        title = component.name,
                        summary = component.license,
                        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        onClick = { openInBrowser(context, component.url) },
                    )
                }
            }

            Text(
                text = "点击组件名可在浏览器中打开源项目",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/**
 * 关于页的隐藏开关：长按图标满 [DEV_MODE_HOLD_MS] 切换开发者模式。
 *
 * 未解锁 → 解锁并提示；已解锁 → 关闭并提示（调试日志设置保留，只是藏起入口）。
 * 从按下开始一直等到所有手指抬起；中途滑动也算按住（不按移动阈值取消，简单优先）。
 * 抬手早于 3 秒什么也不做。
 */
private suspend fun PointerInputScope.detectDevModeHold(context: Context) {
    awaitEachGesture {
        awaitFirstDown()
        val pressedAt = SystemClock.uptimeMillis()
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.none { it.pressed }) {
                break
            }
        }
        val heldMs = SystemClock.uptimeMillis() - pressedAt
        if (heldMs < DEV_MODE_HOLD_MS) return@awaitEachGesture
        // 直接读单例最新值，避免闭包拿到旧状态。
        if (DevSettings.devModeEnabled.value) {
            DevSettings.setDevModeEnabled(context, false)
            Toast.makeText(context, "已关闭开发者模式", Toast.LENGTH_SHORT).show()
        } else {
            DevSettings.setDevModeEnabled(context, true)
            Toast.makeText(context, "已开启开发者模式", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * 应用图标 → [ImageBitmap]。
 *
 * res 下只有自适应图标（xml，mipmap-anydpi-v26），`getApplicationIcon` 拿到的是
 * AdaptiveIconDrawable：它没有固有尺寸（intrinsicWidth 为 -1），toBitmap() 这类
 * 按固有尺寸取宽高的做法会直接抛异常，所以这里按目标像素自己 setBounds + draw。
 */
private fun loadAppIcon(context: Context, sizePx: Int): ImageBitmap {
    val drawable = context.packageManager.getApplicationIcon(context.packageName)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    drawable.setBounds(0, 0, sizePx, sizePx)
    drawable.draw(Canvas(bitmap))
    return bitmap.asImageBitmap()
}

/** 用系统浏览器打开链接；极简 ROM 没有浏览器时给个提示而不是崩。 */
private fun openInBrowser(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "没有可用的浏览器", Toast.LENGTH_SHORT).show()
    }
}
