package dev.cliptranslate.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * HyperOS / Miuix 配色：只覆盖品牌蓝（primary / primaryVariant）与错误色，
 * 其余沿用 Miuix 自带的 HyperOS 调色板；深浅色由 [isSystemInDarkTheme] 跟随系统。
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF3B6FD4),
    primaryVariant = Color(0xFF3B6FD4),
    onPrimary = Color(0xFFFFFFFF),
    onPrimaryVariant = Color(0xFFAECDFF),
    error = Color(0xFFE94634),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF277AF7),
    primaryVariant = Color(0xFF0073DD),
    onPrimary = Color(0xFFFFFFFF),
    onPrimaryVariant = Color(0xFF99C7F1),
)

/**
 * 全局主题。签名保持原样（[MainActivity] 不用改），内部从 Material3 换成 Miuix 的 `MiuixTheme`。
 */
@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MiuixTheme(
        colors = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
