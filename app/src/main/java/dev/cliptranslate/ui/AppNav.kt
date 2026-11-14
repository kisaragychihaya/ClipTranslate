package dev.cliptranslate.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.cliptranslate.ui.settings.SettingsScreen
import dev.cliptranslate.ui.settings.SettingsViewModel
import dev.cliptranslate.ui.translate.TranslateScreen

const val ROUTE_TRANSLATE = "translate"
const val ROUTE_SETTINGS = "settings"

@Composable
fun AppNav() {
    val navController = rememberNavController()

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
                onBack = { navController.popBackStack() },
                vm = viewModel<SettingsViewModel>(),
            )
        }
    }
}
