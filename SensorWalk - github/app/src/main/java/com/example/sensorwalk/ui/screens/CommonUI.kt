// 文件: app/src/main/java/com/example/sensorwalk/ui/screens/CommonUI.kt
/**
 * 修改历史 (Modification History):
 * ------------------------------------------------------------------------------
 * YYYY-MM-DD - [你的名字/ID] - [简述上次修改，AI无需填写]
 * 2025-07-30 - Gemini-AI - [V1] 移除了底部导航栏中的“设置”按钮，以匹配新的导航结构。
 * 2025-07-30 - Gemini-AI - 同步导航栏UI。为匹配 `AppNavigation` 中的导航图变更（需求6），从 `AppBottomNavBar` Composable 中移除了 "设置" 对应的 `NavigationBarItem`。
 */
package com.example.sensorwalk.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.example.sensorwalk.ui.Destinations

@Composable
fun AppBottomNavBar(navController: NavController, currentRoute: String?) {
    NavigationBar {
        NavigationBarItem(
            selected = currentRoute == Destinations.DASHBOARD,
            onClick = {
                if (currentRoute != Destinations.DASHBOARD) {
                    navController.navigate(Destinations.DASHBOARD) {
                        popUpTo(navController.graph.startDestinationId)
                        launchSingleTop = true
                    }
                }
            },
            icon = { Icon(Icons.Default.Home, contentDescription = "主页") },
            label = { Text("主页") }
        )
        NavigationBarItem(
            selected = currentRoute == Destinations.HISTORY,
            onClick = {
                if (currentRoute != Destinations.HISTORY) {
                    navController.navigate(Destinations.HISTORY) {
                        popUpTo(navController.graph.startDestinationId)
                        launchSingleTop = true
                    }
                }
            },
            icon = { Icon(Icons.Default.History, contentDescription = "历史") },
            label = { Text("历史") }
        )
        // ★★★ 核心修改：移除 "设置" 导航项 ★★★
    }
}
