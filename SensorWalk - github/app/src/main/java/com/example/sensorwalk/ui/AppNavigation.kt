// 文件: app/src/main/java/com/example/sensorwalk/ui/AppNavigation.kt
/**
 * 修改历史 (Modification History):
 * ------------------------------------------------------------------------------
 * YYYY-MM-DD - [你的名字/ID] - [简述上次修改，AI无需填写]
 * 2025-07-30 - Gemini-AI - [V1] 移除 "Settings" 导航目标，其功能已整合至主屏幕。
 * 2025-07-30 - Gemini-AI - 简化导航图。为响应【需求6】，移除了 "SETTINGS" 导航目标及其在 `NavHost` 中的 `composable` 定义，因为相关功能已被整合到 `DashboardScreen` 中。
 */
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
import com.example.sensorwalk.viewmodel.MainViewModel

// ★★★ 核心修改：移除 "SETTINGS" 目标 ★★★
object Destinations {
    const val DASHBOARD = "dashboard"
    const val HISTORY = "history"
    const val RESULT_DETAILS = "result_details"
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    // 在导航图级别共享 ViewModel
    val viewModel: MainViewModel = hiltViewModel()

    NavHost(navController = navController, startDestination = Destinations.DASHBOARD) {
        composable(Destinations.DASHBOARD) {
            DashboardScreen(navController = navController, viewModel = viewModel)
        }
        composable(Destinations.HISTORY) {
            HistoryScreen(navController = navController, viewModel = viewModel)
        }
        // ★★★ 核心修改：移除 settings 的 composable ★★★
        composable(
            route = "${Destinations.RESULT_DETAILS}/{resultId}",
            arguments = listOf(navArgument("resultId") { type = NavType.LongType })
        ) { backStackEntry ->
            val resultId = backStackEntry.arguments?.getLong("resultId") ?: -1
            ResultDetailsScreen(resultId = resultId, navController = navController, viewModel = viewModel)
        }
    }
}
