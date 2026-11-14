package dev.cliptranslate.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import dev.cliptranslate.ui.settings.SettingsScreen
import dev.cliptranslate.ui.settings.SettingsViewModel
import dev.cliptranslate.ui.translate.TranslateScreen

const val ROUTE_TRANSLATE = "translate"
const val ROUTE_SETTINGS = "settings"

/**
 * 返回主界面：优先正常出栈；出不动（进程被杀后导航栈被 savedState 恢复成只剩
 * 设置页的孤立栈，HyperOS 强杀场景实测如此）就跳回主页并清空整栈，
 * 保证返回键在任何恢复状态下都有效。
 */
private fun NavHostController.backToTranslate() {
    if (!popBackStack()) {
        navigate(ROUTE_TRANSLATE) {
            popUpTo(0) { inclusive = true }
        }
    }
}

@Composable
fun AppNav() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()

    // miuix 0.8.8 的 SuperDialog/SuperBottomSheet 内部用 NavigationBackHandler，
    // 依赖 LocalNavigationEventDispatcherOwner；navigation-compose 2.9.0 的 NavHost
    // 不提供它（真机点「更多」必崩 IllegalStateException），在根部显式提供。
    val dispatcherOwner = rememberNavigationEventDispatcherOwner()

    // 系统返回键兜底：在设置页时由这里处理，恢复出的孤立栈也能回到主页。
    BackHandler(enabled = backStackEntry?.destination?.route == ROUTE_SETTINGS) {
        navController.backToTranslate()
    }

    CompositionLocalProvider(
        LocalNavigationEventDispatcherOwner provides dispatcherOwner,
    ) {
        NavHost(
            navController = navController,
            startDestination = ROUTE_TRANSLATE,
        ) {
            composable(ROUTE_TRANSLATE) {
                TranslateScreen(
                    onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                )
            }
            composable(ROUTE_SETTINGS) {
                SettingsScreen(
                    onBack = { navController.backToTranslate() },
                    vm = viewModel<SettingsViewModel>(),
                )
            }
        }
    }
}
