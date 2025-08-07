// 文件: app/src/main/java/com/example/sensorwalk/MainActivity.kt
/**
 * 修改历史 (Modification History):
 * ------------------------------------------------------------------------------
 * YYYY-MM-DD - [你的名字/ID] - [简述上次修改，AI无需填写]
 * 2025-07-30 - Gemini-AI - 新增了处理文件导出请求的逻辑。`MainViewModel` 会发出一个创建文件的 `Intent`，`MainActivity` 会捕获这个请求，启动系统的文件保存对话框。当用户选择位置后，`onActivityResult` 会被调用，然后将 `MainViewModel` 中暂存的JSON数据写入用户选择的文件中。
 * 2025-07-30 - Gemini-AI - 实现文件导出交互。为响应 `MainViewModel` 的导出请求（需求 11.2 的隐含实现），新增 `createFileLauncher` 来处理 `ACTION_CREATE_DOCUMENT` Intent。通过 `LaunchedEffect` 监听 `viewModel.requestCreateFile` Flow，当收到请求时启动文件选择器。用户确认后，在回调中调用 `writeContentToUri` 方法，将 `ExportManager` 中暂存的数据写入用户指定的文件，并提供用户反馈。
 */

package com.example.sensorwalk

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.example.sensorwalk.ui.AppNavigation
import com.example.sensorwalk.ui.theme.SensorWalkTheme
import com.example.sensorwalk.viewmodel.ExportManager
import com.example.sensorwalk.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import java.io.FileOutputStream

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (!allGranted) {
                Toast.makeText(this, "部分核心权限未授予，功能可能受限。", Toast.LENGTH_LONG).show()
            }
        }

    // ★★★ 本次修改：处理文件创建的 Launcher ★★★
    private val createFileLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    writeContentToUri(uri)
                }
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
                    // ★★★ 本次修改：监听 ViewModel 的文件创建请求 ★★★
                    LaunchedEffect(Unit) {
                        viewModel.requestCreateFile.collectLatest { intent ->
                            createFileLauncher.launch(intent)
                        }
                    }
                    AppNavigation()
                }
            }
        }
    }

    private fun askForPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.WAKE_LOCK,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.HIGH_SAMPLING_RATE_SENSORS,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_MULTICAST_STATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissionsToRequest.add(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC)
        }

        requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
    }

    private fun writeContentToUri(uri: Uri) {
        val content = ExportManager.getContentToExport()
        if (content == null) {
            Toast.makeText(this, "导出内容为空，操作取消。", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            contentResolver.openFileDescriptor(uri, "w")?.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { fos ->
                    fos.write(content.toByteArray())
                }
            }
            Toast.makeText(this, "历史记录已成功导出！", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
