package com.example.sensorwalk

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.sensorwalk.ui.AppNavigation
import com.example.sensorwalk.ui.theme.SensorWalkTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            // 这里可以检查权限授予状态，如果用户拒绝，可以在此处理
            val allGranted = permissions.entries.all { it.value }
            if (!allGranted) {
                // TODO: 可以向用户显示一个对话框，解释为什么需要这些权限
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askForPermissions()
        setContent {
            SensorWalkTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation()
                }
            }
        }
    }

    private fun askForPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.HIGH_SAMPLING_RATE_SENSORS,
            // 网络权限
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_MULTICAST_STATE
        )

        // 导出文件需要存储权限 (仅在API < 30)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        // 使用 HIGH_SAMPLING_RATE_SENSORS 替代了 BODY_SENSORS
        // if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        //     permissionsToRequest.add(Manifest.permission.BODY_SENSORS)
        // }

        requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
    }
}
