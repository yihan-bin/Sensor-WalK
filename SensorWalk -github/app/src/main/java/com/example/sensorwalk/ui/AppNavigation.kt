package com.example.sensorwalk.ui

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.sensorwalk.ui.screens.DashboardScreen
import com.example.sensorwalk.ui.screens.HistoryScreen
import com.example.sensorwalk.ui.screens.ResultDetailsScreen
import com.example.sensorwalk.ui.screens.SettingsScreen
import com.example.sensorwalk.viewmodel.MainViewModel

// *** FIX: Moved Destinations object to top-level for global access ***
object Destinations {
    const val DASHBOARD = "dashboard"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val RESULT_DETAILS = "result_details"
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    // Share ViewModel across the navigation graph
    val viewModel: MainViewModel = hiltViewModel()

    NavHost(navController = navController, startDestination = Destinations.DASHBOARD) {
        composable(Destinations.DASHBOARD) {
            DashboardScreen(navController = navController, viewModel = viewModel)
        }
        composable(Destinations.HISTORY) {
            HistoryScreen(navController = navController, viewModel = viewModel)
        }
        composable(Destinations.SETTINGS) {
            SettingsScreen(navController = navController, viewModel = viewModel)
        }
        composable(
            route = "${Destinations.RESULT_DETAILS}/{resultId}",
            arguments = listOf(navArgument("resultId") { type = NavType.LongType })
        ) { backStackEntry ->
            val resultId = backStackEntry.arguments?.getLong("resultId") ?: -1
            ResultDetailsScreen(resultId = resultId, navController = navController, viewModel = viewModel)
        }
    }
}
