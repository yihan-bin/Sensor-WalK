package com.example.sensorwalk.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.DateRange // *** 核心修复: 添加缺失的 History 图标 import ***
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import com.example.sensorwalk.ui.Destinations

@Composable
fun AppBottomNavBar(navController: NavController, currentRoute: String?) {
    NavigationBar {
        NavigationBarItem(
            selected = currentRoute == Destinations.DASHBOARD,
            onClick = { navController.navigate(Destinations.DASHBOARD) { launchSingleTop = true } },
            icon = { Icon(Icons.Default.Home, contentDescription = "主页") },
            label = { Text("主页") }
        )
        NavigationBarItem(
            selected = currentRoute == Destinations.HISTORY,
            onClick = { navController.navigate(Destinations.HISTORY) { launchSingleTop = true } },
            // *** 核心修复: 现在可以正确引用 Icons.Default.History ***
            icon = { Icon(Icons.Default.History, contentDescription = "历史") },
            label = { Text("历史") }
        )
        NavigationBarItem(
            selected = currentRoute == Destinations.SETTINGS,
            onClick = { navController.navigate(Destinations.SETTINGS) { launchSingleTop = true } },
            icon = { Icon(Icons.Default.Settings, contentDescription = "设置") },
            label = { Text("设置") }
        )
    }
}
